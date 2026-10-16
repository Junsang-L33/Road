package kr.ac.isip.roadgateway;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs on an emulator with real Android file APIs, BitmapFactory and Activity scheduling. */
public final class GatewayRegressionInstrumentation extends Instrumentation {
    private File fixture;
    private int passed;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            fixture = new File(getTargetContext().getCacheDir(), "regression-" + UUID.randomUUID());
            check(fixture.mkdirs(), "Could not create fixture");
            Context isolated = new ContextWrapper(getTargetContext()) {
                @Override public File getFilesDir() { return fixture; }
            };
            EventStore store = new EventStore(isolated);
            byte[] photo = jpeg(Color.GRAY);
            Location fix = new Location("gps");
            fix.setLatitude(35.8562); fix.setLongitude(128.6103); fix.setAccuracy(8); fix.setTime(System.currentTimeMillis());

            byte[] metadata = metadata("missing-photo");
            JSONObject saved = store.save(metadata, photo, fix);
            store.markUploaded(saved);
            check(store.image(saved).delete(), "Could not remove photo fixture");
            JSONObject restored = store.save(metadata, photo, null);
            check(Arrays.equals(Files.readAllBytes(store.image(restored).toPath()), photo), "Retry did not restore the missing JPEG");
            check(restored.getBoolean("uploaded") && restored.getDouble("latitude") == fix.getLatitude(), "Retry changed original GPS/upload state");
            passed++;

            Files.write(store.image(restored).toPath(), new byte[]{1, 2, 3});
            store.save(metadata, photo, null);
            check(Arrays.equals(Files.readAllBytes(store.image(restored).toPath()), photo), "Retry did not repair a damaged JPEG");
            passed++;

            try {
                store.save(metadata, jpeg(Color.RED), fix);
                throw new AssertionError("Different photo reused an existing event ID");
            } catch (IllegalArgumentException expected) {}
            check(Arrays.equals(Files.readAllBytes(store.image(restored).toPath()), photo), "Conflicting retry overwrote the saved photo");
            passed++;

            Files.write(new File(fixture, "events/broken.json").toPath(), "{".getBytes(StandardCharsets.UTF_8));
            check(store.all().size() == 1, "A damaged marker blocked healthy records");
            passed++;

            verifyBlockedUploadDoesNotBlockSave(store, photo);
            passed++;
            result.putInt("passed", passed);
            result.putInt("failed", 0);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putInt("passed", passed);
            result.putInt("failed", 1);
            result.putString("failure", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        } finally { if (fixture != null) remove(fixture); }
    }

    private void verifyBlockedUploadDoesNotBlockSave(EventStore store, byte[] photo) throws Exception {
        Intent intent = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity = (MainActivity) startActivitySync(intent);
        CountDownLatch unblock = new CountDownLatch(1);
        CountDownLatch uploadBlocked = new CountDownLatch(1);
        CountDownLatch committed = new CountDownLatch(1);
        try {
            runOnMainSync(() -> check(!hasTextInput(activity.getWindow().getDecorView()), "Server settings must not be entered by the app user"));
            passed++;
            Field storeField = MainActivity.class.getDeclaredField("store"); storeField.setAccessible(true);
            runOnMainSync(() -> { try { storeField.set(activity, store); } catch (Exception error) { throw new RuntimeException(error); } });
            Field uploadsField = MainActivity.class.getDeclaredField("uploads"); uploadsField.setAccessible(true);
            ExecutorService uploads = (ExecutorService) uploadsField.get(activity);
            uploads.submit(() -> {
                uploadBlocked.countDown();
                try { unblock.await(10, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            check(uploadBlocked.await(2, TimeUnit.SECONDS), "Upload task did not start");
            byte[] event = metadata("during-blocked-upload");
            runOnMainSync(() -> activity.received(event, photo, committed::countDown));
            check(committed.await(3, TimeUnit.SECONDS), "Blocked upload also blocked photo commit");
        } finally {
            unblock.countDown();
            runOnMainSync(activity::finish);
            waitForIdleSync();
        }
    }

    private byte[] metadata(String id) throws Exception {
        return new JSONObject().put("eventId", "REGRESSION-" + id).put("type", "POTHOLE")
            .put("confidence", 0.87).put("detectedAt", "2026-10-15T00:00:00Z")
            .toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] jpeg(int color) {
        Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, bytes); bitmap.recycle();
        return bytes.toByteArray();
    }

    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private boolean hasTextInput(View view) {
        if (view instanceof EditText) return true;
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++)
                if (hasTextInput(group.getChildAt(index))) return true;
        }
        return false;
    }

    private void remove(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        file.delete();
    }
}
