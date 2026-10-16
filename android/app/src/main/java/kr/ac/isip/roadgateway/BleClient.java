package kr.ac.isip.roadgateway;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.UUID;

@SuppressLint("MissingPermission")
final class BleClient {
    private static final UUID SERVICE = UUID.fromString("7d53a100-5f67-4fba-9fb5-8e5d73f91801");
    private static final UUID DATA = UUID.fromString("7d53a101-5f67-4fba-9fb5-8e5d73f91801");
    private static final UUID CONTROL = UUID.fromString("7d53a102-5f67-4fba-9fb5-8e5d73f91801");
    private static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    interface Listener {
        void log(String message);
        void started();
        void progress(int bytes);
        void received(byte[] metadata, byte[] jpeg, Runnable committed);
    }
    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TransferAssembler assembler = new TransferAssembler();
    private final ArrayDeque<byte[]> writes = new ArrayDeque<>();
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic data, control;
    private boolean active, scanning, writing, configured, ready;
    private int scanGeneration;
    private int mtu = 23;
    private Integer savingId, committedId;
    private long lastPacketAt, writeStartedAt;

    BleClient(Context context, Listener listener) { this.context = context; this.listener = listener; }

    void start() {
        if (active) return;
        active = true;
        scan();
        handler.post(watchdog);
    }

    void stop() {
        active = false;
        handler.removeCallbacksAndMessages(null);
        close();
    }

    private void close() {
        if (scanning && scanner != null) scanner.stopScan(scanCallback);
        scanning = false;
        scanGeneration++;
        BluetoothGatt old = gatt;
        gatt = null;
        if (old != null) { old.disconnect(); old.close(); }
        data = control = null;
        configured = writing = ready = false;
        lastPacketAt = writeStartedAt = 0;
        writes.clear();
        assembler.reset();
        savingId = committedId = null;
        mtu = 23;
    }

    private void reconnect(String reason) {
        listener.log(reason);
        close();
        if (active) handler.postDelayed(this::scan, 3000);
    }

    private void scan() {
        if (!active || scanning || gatt != null) return;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            listener.log("블루투스를 켜 주세요.");
            handler.postDelayed(this::scan, 5000);
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { handler.postDelayed(this::scan, 3000); return; }
        listener.log("RoadPi 장치 검색 중");
        scanning = true;
        int generation = ++scanGeneration;
        scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build()),
            new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
        handler.postDelayed(() -> {
            if (scanning && scanGeneration == generation) { scanner.stopScan(scanCallback); scanning = false; if (active) handler.postDelayed(this::scan, 3000); }
        }, 12000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            handler.post(() -> {
                if (!active || !scanning || gatt != null) return;
                scanner.stopScan(this);
                scanning = false;
                listener.log("RoadPi 연결 중: " + result.getDevice().getAddress());
                gatt = result.getDevice().connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
                BluetoothGatt connecting = gatt;
                handler.postDelayed(() -> { if (gatt == connecting && !ready) reconnect("연결 시간이 초과되어 재검색합니다."); }, 15000);
            });
        }
        @Override public void onScanFailed(int error) {
            handler.post(() -> { scanning = false; if (active) reconnect("BLE 검색 오류 " + error); });
        }
    };

    private void subscribe() {
        if (gatt == null || configured) return;
        BluetoothGattService service = gatt.getService(SERVICE);
        if (service == null) { reconnect("RoadPi 서비스를 찾지 못했습니다."); return; }
        data = service.getCharacteristic(DATA);
        control = service.getCharacteristic(CONTROL);
        if (data == null || control == null || !gatt.setCharacteristicNotification(data, true)) {
            reconnect("사진 수신 특성 설정 실패"); return;
        }
        BluetoothGattDescriptor descriptor = data.getDescriptor(CCC);
        if (descriptor == null) { reconnect("Notify descriptor가 없습니다."); return; }
        configured = true;
        boolean success;
        if (Build.VERSION.SDK_INT >= 33) success = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS;
        else { descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); success = gatt.writeDescriptor(descriptor); }
        if (!success) reconnect("Notify 활성화 요청 실패");
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt connection, int status, int state) {
            handler.post(() -> {
                if (gatt != connection) return;
                if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                    connection.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    if (!connection.discoverServices()) reconnect("서비스 검색 시작 실패");
                } else if (state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) reconnect("BLE 연결 끊김; 재연결합니다.");
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt connection, int status) {
            handler.post(() -> {
                if (gatt != connection) return;
                if (status != BluetoothGatt.GATT_SUCCESS) { reconnect("서비스 검색 실패"); return; }
                if (!connection.requestMtu(185)) subscribe();
                else handler.postDelayed(() -> { if (gatt == connection && !configured) reconnect("MTU 협상 시간 초과"); }, 10000);
            });
        }
        @Override public void onMtuChanged(BluetoothGatt connection, int value, int status) {
            handler.post(() -> { if (gatt == connection) { mtu = status == BluetoothGatt.GATT_SUCCESS ? value : 23; subscribe(); } });
        }
        @Override public void onDescriptorWrite(BluetoothGatt connection, BluetoothGattDescriptor descriptor, int status) {
            handler.post(() -> {
                if (gatt != connection) return;
                if (status != BluetoothGatt.GATT_SUCCESS) { reconnect("Notify 활성화 실패"); return; }
                ready = true;
                queue(ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN).put((byte)1).putShort((short)mtu).array());
                lastPacketAt = 0;
                listener.log("BLE 연결 완료 · 사진 대기 중");
            });
        }
        @Override public void onCharacteristicWrite(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, int status) {
            handler.post(() -> {
                if (gatt != connection) return;
                writing = false;
                writeStartedAt = 0;
                if (status != BluetoothGatt.GATT_SUCCESS) { reconnect("수신 확인 전송 실패"); return; }
                pump();
            });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, byte[] value) {
            byte[] copy = value.clone();
            handler.post(() -> { if (gatt == connection && DATA.equals(characteristic.getUuid())) packet(copy); });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                byte[] copy = characteristic.getValue().clone();
                handler.post(() -> { if (gatt == connection && DATA.equals(characteristic.getUuid())) packet(copy); });
            }
        }
    };

    private void packet(byte[] bytes) {
        try {
            lastPacketAt = android.os.SystemClock.elapsedRealtime();
            TransferAssembler.Receipt receipt = assembler.accept(bytes);
            if (receipt == null) return;
            if (receipt.started()) listener.started();
            if (receipt.acknowledge()) queue(ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).put((byte)2).putInt(receipt.transferId()).putInt(receipt.sequence()).array());
            if (receipt.sequence() % TransferAssembler.WINDOW == 0) listener.progress(assembler.size());
            if (!receipt.complete()) return;
            int id = receipt.transferId();
            if (committedId != null && committedId == id) { complete(id); lastPacketAt = 0; return; }
            if (savingId != null && savingId == id) return;
            savingId = id;
            BluetoothGatt connection = gatt;
            listener.received(assembler.metadata(), assembler.image(), () -> handler.post(() -> {
                if (gatt != connection || !active) return;
                committedId = id;
                savingId = null;
                complete(id);
                lastPacketAt = 0;
            }));
        } catch (Exception error) { reconnect("사진 수신 오류: " + error.getMessage()); }
    }

    private void complete(int id) { queue(ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put((byte)3).putInt(id).array()); }

    private void queue(byte[] value) {
        writes.add(value);
        if (writes.size() > 64) { reconnect("BLE 응답 지연; 재연결합니다."); return; }
        pump();
    }

    private void pump() {
        if (writing || writes.isEmpty() || gatt == null || control == null) return;
        byte[] value = writes.removeFirst();
        writing = true;
        writeStartedAt = android.os.SystemClock.elapsedRealtime();
        boolean success;
        if (Build.VERSION.SDK_INT >= 33) success = gatt.writeCharacteristic(control, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS;
        else { control.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); control.setValue(value); success = gatt.writeCharacteristic(control); }
        if (!success) reconnect("BLE 명령 전송을 시작하지 못했습니다.");
    }

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (!assembler.isComplete() && lastPacketAt != 0 && android.os.SystemClock.elapsedRealtime() - lastPacketAt > 30000) reconnect("사진 수신 시간이 초과되었습니다.");
            if (writing && android.os.SystemClock.elapsedRealtime() - writeStartedAt > 10000) reconnect("BLE 명령 응답 시간 초과");
            handler.postDelayed(this, 5000);
        }
    };
}
