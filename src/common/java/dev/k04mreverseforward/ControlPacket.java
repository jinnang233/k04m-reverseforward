package dev.k04mreverseforward;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

record ControlPacket(Type type, UUID invitationId, UUID routeId, String name,
                     int listenPort, int targetPort) {
    private static final long MAGIC = 0x4B30344D4354524CL; // K04MCTRL
    private static final int VERSION = 1;
    static final int MAX_PACKET = 4096;
    private static final int MAX_NAME_BYTES = 48;

    enum Type { INVITE, ACCEPT, REJECT, REVOKE }

    ControlPacket {
        if (type == null || invitationId == null || routeId == null) {
            throw new IllegalArgumentException("Missing control packet identity");
        }
        validateName(name);
        validatePort(listenPort);
        validatePort(targetPort);
    }

    byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeLong(MAGIC);
                output.writeByte(VERSION);
                output.writeByte(type.ordinal());
                writeUuid(output, invitationId);
                writeUuid(output, routeId);
                byte[] encodedName = name.getBytes(StandardCharsets.UTF_8);
                output.writeByte(encodedName.length);
                output.write(encodedName);
                output.writeShort(listenPort);
                output.writeShort(targetPort);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static ControlPacket decode(byte[] packet) throws IOException {
        if (packet == null || packet.length > MAX_PACKET) throw new IOException("Invalid control packet size");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
            if (input.readLong() != MAGIC) throw new IOException("Invalid control packet magic");
            if (input.readUnsignedByte() != VERSION) throw new IOException("Unsupported control packet version");
            int typeId = input.readUnsignedByte();
            if (typeId >= Type.values().length) throw new IOException("Invalid control packet type");
            UUID invitation = readUuid(input);
            UUID route = readUuid(input);
            int nameLength = input.readUnsignedByte();
            if (nameLength == 0 || nameLength > MAX_NAME_BYTES || nameLength > input.available() - 4) {
                throw new IOException("Invalid route name length");
            }
            String name = new String(input.readNBytes(nameLength), StandardCharsets.UTF_8);
            int listenPort = input.readUnsignedShort();
            int targetPort = input.readUnsignedShort();
            if (input.available() != 0) throw new IOException("Trailing control packet data");
            try {
                return new ControlPacket(Type.values()[typeId], invitation, route, name, listenPort, targetPort);
            } catch (IllegalArgumentException invalid) {
                throw new IOException(invalid.getMessage(), invalid);
            }
        }
    }

    static void validateName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Route name must contain 1-32 letters, digits, '_' or '-'");
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
            throw new IllegalArgumentException("Route name is too long");
        }
    }

    static void validatePort(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535");
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }
}
