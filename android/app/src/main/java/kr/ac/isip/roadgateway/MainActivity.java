package kr.ac.isip.roadgateway;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements BleClient.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService uploads = Executors.newSingleThreadExecutor();
    private BleClient ble;
    private EventStore store;
    private LocationManager locations;
    private Location latestLocation, transferLocation;
    private TextView status, gps, history;
    private ImageView preview;
    private boolean running, visible, uploading, destroyed;
    private final int INK = Color.rgb(21, 61, 53);

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new EventStore(this);
        locations = getSystemService(LocationManager.class);
        ble = new BleClient(this, this);
        buildScreen();
        refreshHistory();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(INK);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }
    private void button(LinearLayout root, String text, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(text); button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(INK));
        button.setOnClickListener(listener); root.addView(button);
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(24), dp(28), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(240, 239, 231)); scroll.addView(root); setContentView(scroll);
        root.addView(label("ROAD GATEWAY / MVP", 12)); root.addView(label("도로 감지 게이트웨이", 28));
        root.addView(label("Pi 사진 수신 → 휴대폰 GPS 결합 → 서버 전송", 15));
        status = label("BLE 수신을 시작하세요.", 16); root.addView(status);
        gps = label("GPS 대기", 14); root.addView(gps);
        root.addView(label(ServerConfig.BASE_URL.trim().isEmpty()
            ? "서버 미설정 · 사진은 휴대폰에 보관됩니다."
            : "서버 전송 사용 · 수신한 사진을 자동 전송합니다.", 14));
        button(root, "BLE 수신 시작", view -> {
            running = true;
            ensurePermissions();
        });
        button(root, "수신 중지", view -> { running = false; stopSession(); log("수신을 중지했습니다."); });
        button(root, "미전송 사진 다시 올리기", view -> {
            if (ServerConfig.BASE_URL.trim().isEmpty()) log("서버가 설정되지 않아 사진을 휴대폰에 보관 중입니다.");
            else uploadPending();
        });
        preview = new ImageView(this); preview.setAdjustViewBounds(true); preview.setContentDescription("최근 감지 현장 사진"); root.addView(preview);
        root.addView(label("감지 기록", 22)); history = label("아직 수신한 기록이 없습니다.", 14); root.addView(history);
        root.addView(label("시연 중에는 앱을 화면에 열어 두세요. 사진은 업로드에 실패해도 휴대폰에 남습니다.", 13));
    }

    private void ensurePermissions() {
        List<String> missing = new ArrayList<>();
        for (String permission : permissions()) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 10);
        else startSession();
    }

    private String[] permissions() {
        return Build.VERSION.SDK_INT >= 31
            ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}
            : new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != 10) return;
        for (String permission : permissions()) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            running = false; log("블루투스와 정확한 위치 권한을 허용해야 수신할 수 있습니다."); return;
        }
        startSession();
    }

    @Override public void onStart() {
        super.onStart(); visible = true;
        main.postDelayed(retryUploads, 1000);
        if (running) ensurePermissions();
    }

    @Override public void onStop() {
        visible = false;
        main.removeCallbacks(retryUploads);
        stopSession();
        super.onStop();
    }

    @Override public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        ble.stop(); io.shutdown(); uploads.shutdown(); super.onDestroy();
    }

    @SuppressWarnings("MissingPermission")
    private void startSession() {
        if (!running || !visible) return;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            locations.removeUpdates(locationListener);
            if (locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locationListener);
            if (locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0, locationListener);
            gps.setText(R.string.gps_preparing);
            ble.start();
        } catch (SecurityException error) { running = false; log("위치/블루투스 권한을 확인해 주세요."); }
    }

    private void stopSession() {
        ble.stop();
        if (locations != null) locations.removeUpdates(locationListener);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location location) {
            latestLocation = new Location(location);
            gps.setText(String.format(java.util.Locale.US, "GPS %.6f, %.6f · ±%.0fm", location.getLatitude(), location.getLongitude(), location.getAccuracy()));
        }
        @Override public void onProviderDisabled(String provider) { gps.setText("위치 서비스를 켜 주세요."); }
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    };

    @Override public void log(String message) { runOnUiThread(() -> { if (!destroyed) status.setText(message); }); }

    @Override public void started() {
        // Snapshot the fix before a slow BLE image transfer moves the vehicle farther away.
        transferLocation = latestLocation != null
            && SystemClock.elapsedRealtimeNanos() - latestLocation.getElapsedRealtimeNanos() <= 30_000_000_000L
            ? new Location(latestLocation) : null;
    }

    @Override public void progress(int bytes) { status.setText(getString(R.string.receive_progress, bytes / 1024)); }

    @Override public void received(byte[] metadata, byte[] jpeg, Runnable committed) {
        Location fix = transferLocation == null ? null : new Location(transferLocation);
        io.execute(() -> {
            try {
                JSONObject event = store.save(metadata, jpeg, fix);
                committed.run();
                main.post(() -> {
                    if (destroyed) return;
                    preview.setImageBitmap(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length));
                    if (event.isNull("latitude")) log("사진 저장 완료 · 수신 당시 GPS가 없어 서버 전송 대기");
                    else if (ServerConfig.BASE_URL.trim().isEmpty()) log("사진 저장 완료 · 서버 미설정으로 전송 대기");
                    else log("사진 저장 완료 · 서버로 전송합니다.");
                    refreshHistory(); uploadPending();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (destroyed) return;
                    ble.stop(); running = false; log("사진 저장 실패: " + error.getMessage() + " · 수신을 다시 시작하세요.");
                });
            }
        });
    }

    private void uploadPending() {
        if (destroyed) return;
        if (uploading) return;
        String server = ServerConfig.BASE_URL.trim();
        String token = ServerConfig.API_TOKEN.trim();
        if (server.isEmpty()) return;
        uploading = true;
        uploads.execute(() -> {
            int count = 0;
            String failure = null;
            try {
                for (JSONObject event : store.all()) {
                    if (event.optBoolean("uploaded") || event.isNull("latitude")) continue;
                    try { Uploader.upload(server, token, event, store.image(event)); store.markUploaded(event); count++; }
                    catch (Exception error) {
                        failure = error.getMessage();
                        if (error instanceof Uploader.UploadException rejection && rejection.isEventError()) continue;
                        if (error instanceof java.nio.file.NoSuchFileException) continue;
                        break;
                    }
                }
            } catch (Exception error) { failure = error.getMessage(); }
            final int sent = count;
            final String error = failure;
            main.post(() -> {
                if (destroyed) return;
                uploading = false;
                if (error != null) log("업로드 대기: " + error + " · 사진은 휴대폰에 보관됩니다.");
                else if (sent > 0) log("서버 저장 완료 · " + sent + "건");
                refreshHistory();
            });
        });
    }

    private void refreshHistory() {
        if (destroyed) return;
        io.execute(() -> {
            try {
                List<JSONObject> events = store.all();
                StringBuilder text = new StringBuilder();
                for (int i = 0; i < Math.min(20, events.size()); i++) {
                    JSONObject event = events.get(i);
                    String state = event.optBoolean("uploaded") ? "서버 저장됨" : event.isNull("latitude") ? "수신 당시 GPS 없음" : "업로드 대기";
                    text.append(event.optString("type")).append(" · ").append(state).append('\n')
                        .append(event.optString("detectedAt")).append("\n\n");
                }
                main.post(() -> { if (!destroyed) history.setText(text.length() == 0 ? "아직 수신한 기록이 없습니다." : text.toString()); });
            } catch (Exception error) { log("기록 조회 실패: " + error.getMessage()); }
        });
    }

    private final Runnable retryUploads = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            uploadPending(); main.postDelayed(this, 30000);
        }
    };
}
