package kr.ac.isip.roadgateway;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Android-independent assembler so the Pi/phone wire format can be tested on a desktop. */
public final class TransferAssembler {
    public static final int WINDOW = 16;
    private static final int MAX_META = 8192;
    private static final int MAX_IMAGE = 2 * 1024 * 1024;
    private final ByteArrayOutputStream stream = new ByteArrayOutputStream();
    private Integer transferId;
    private int nextSequence = 0;
    private int expectedLength = -1;
    private int metadataLength;
    private boolean completed = false;

    public record Receipt(int transferId, int sequence, boolean acknowledge, boolean complete, boolean started) {}

    public void reset() {
        transferId = null;
        stream.reset();
        nextSequence = 0;
        expectedLength = -1;
        completed = false;
    }

    public Receipt accept(byte[] packet) {
        if (packet.length <= 8) throw new IllegalArgumentException("Empty BLE packet");
        ByteBuffer header = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        int incomingId = header.getInt();
        int sequence = header.getInt();
        if (sequence < 0) throw new IllegalArgumentException("Invalid sequence");
        boolean started = false;
        if (transferId == null || transferId != incomingId) {
            if (sequence != 0) return null;
            reset();
            transferId = incomingId;
            started = true;
        }
        if (sequence != nextSequence || completed) {
            return nextSequence == 0 ? null : new Receipt(incomingId, nextSequence - 1, true, completed, false);
        }
        stream.write(packet, 8, packet.length - 8);
        nextSequence++;
        if (expectedLength == -1 && stream.size() >= 8) {
            ByteBuffer sizes = ByteBuffer.wrap(stream.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
            metadataLength = sizes.getInt();
            int imageLength = sizes.getInt();
            if (metadataLength <= 0 || metadataLength > MAX_META || imageLength <= 0 || imageLength > MAX_IMAGE)
                throw new IllegalArgumentException("Invalid event size");
            expectedLength = 8 + metadataLength + imageLength;
        }
        if (stream.size() > MAX_META + MAX_IMAGE + 8 || (expectedLength != -1 && stream.size() > expectedLength))
            throw new IllegalArgumentException("Event overflow");
        completed = expectedLength != -1 && stream.size() == expectedLength;
        return new Receipt(incomingId, sequence, completed || nextSequence % WINDOW == 0, completed, started);
    }

    public byte[] metadata() {
        if (!completed) throw new IllegalStateException("Incomplete event");
        return Arrays.copyOfRange(stream.toByteArray(), 8, 8 + metadataLength);
    }

    public byte[] image() {
        if (!completed) throw new IllegalStateException("Incomplete event");
        return Arrays.copyOfRange(stream.toByteArray(), 8 + metadataLength, expectedLength);
    }

    public int size() { return stream.size(); }
    public boolean isComplete() { return completed; }
}
