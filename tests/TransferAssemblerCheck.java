package kr.ac.isip.roadgateway;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

public final class TransferAssemblerCheck {
    public static void main(String[] args) throws Exception {
        byte[] fixtures = Files.readAllBytes(Path.of(args[0]));
        ByteBuffer input = ByteBuffer.wrap(fixtures).order(ByteOrder.LITTLE_ENDIAN);
        TransferAssembler assembler = new TransferAssembler();
        TransferAssembler.Receipt receipt = null;
        while (input.hasRemaining()) {
            int length = Short.toUnsignedInt(input.getShort());
            byte[] packet = new byte[length];
            input.get(packet);
            receipt = assembler.accept(packet);
            if (receipt != null && receipt.sequence() == 1) {
                // Duplicate packets must not add bytes twice.
                int size = assembler.size();
                assembler.accept(packet);
                if (assembler.size() != size) throw new AssertionError("Duplicate packet changed data");
                byte[] gap = packet.clone();
                ByteBuffer.wrap(gap).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 999);
                assembler.accept(gap);
                if (assembler.size() != size) throw new AssertionError("Out-of-order packet changed data");
            }
        }
        if (receipt == null || !receipt.complete()) throw new AssertionError("Transfer incomplete");
        System.out.println(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(assembler.image())));
        assembler.reset();
        byte[] invalid = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(0).putInt(Integer.MAX_VALUE).putInt(1).array();
        try { assembler.accept(invalid); throw new AssertionError("Accepted bad size"); }
        catch (IllegalArgumentException expected) {}
    }
}
