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
    private static final long MAGIC = 0x4B30344D4354524CL;
    // K04MCTRL
    private static final int VERSION = 1;
    static final int MAX_PACKET = 4096;
    private static final int MAX_NAME_BYTES = 48;

    enum Type { INVITE, ACCEPT, REJECT, REVOKE }

    /**
     * Creates a control packet with the supplied dependencies and initial state.
     *
     * @param type the type supplied to this operation
     * @param invitationId the invitation id supplied to this operation
     * @param routeId the route id supplied to this operation
     * @param name the name supplied to this operation
     * @param listenPort the listen port supplied to this operation
     * @param targetPort the target port supplied to this operation
     */
    ControlPacket {
        if (type == null || invitationId == null || routeId == null) {
            throw new IllegalArgumentException("Missing control packet identity");
        }
        validateName(name);
        validatePort(listenPort);
        validatePort(targetPort);
    }

    /**
     * Serializes validated route/invitation UUIDs, action, ASCII-constrained route name and port values
     * into the fixed versioned frame. Encoding is not encryption and does not grant forwarding
     * authorization.
     *
     * @return the resulting array produced by this operation
     */
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

    /**
     * Parses the bounded versioned reverse-forward control frame and rejects bad magic, enum IDs,
     * route-name/port values, truncation and trailing data. The frame is not cryptographically
     * authenticated here; its enclosing encrypted stream supplies peer context.
     *
     * @param packet the packet being serialized, authenticated or processed
     * @return the result described above
     * @throws IOException if input/output, stored-state validation or resource handling fails
     */
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

    /**
     * Restricts route names to the documented ASCII identifier grammar and encoded size bound, avoiding
     * arbitrary path/display data being treated as a route key.
     *
     * @param name the name supplied to this operation
     */
    static void validateName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Route name must contain 1-32 letters, digits, '_' or '-'");
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
            throw new IllegalArgumentException("Route name is too long");
        }
    }

    /**
     * Requires a nonzero TCP port within 1..65535. A valid numerical port is not an authorization to
     * connect to it.
     *
     * @param port the port supplied to this operation
     */
    static void validatePort(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535");
    }

    /**
     * Writes uuid to the output used by the reverse-forward control frame.
     *
     * @param output the destination buffer or stream for produced data
     * @param value the value supplied to this operation
     * @throws IOException if input/output, stored-state validation or resource handling fails
     */
    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    /**
     * Reads uuid from the input used by the reverse-forward control frame.
     *
     * @param input the input bytes or stream consumed by the operation
     * @return the result described above
     * @throws IOException if input/output, stored-state validation or resource handling fails
     */
    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }
}
