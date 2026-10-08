package dev.k04mreverseforward;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

final class RouteStore {
    private static final long MAGIC = 0x4B30344D53544F52L; // K04MSTOR
    private static final int VERSION = 1;
    static final int MAX_ROUTES = 256;
    private final Path file;

    RouteStore(Path configDirectory) {
        this.file = configDirectory.toAbsolutePath().normalize().resolve("routes.dat");
    }

    State load() throws IOException {
        rejectLinks(file);
        if (!Files.exists(file)) return new State(List.of(), List.of());
        restrictToOwner(file.getParent(), true);
        restrictToOwner(file, false);
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readLong() != MAGIC || input.readUnsignedByte() != VERSION) {
                throw new IOException("Unsupported routes.dat format");
            }
            int mappingCount = boundedCount(input.readInt());
            List<MappingData> mappings = new ArrayList<>(mappingCount);
            for (int i = 0; i < mappingCount; i++) {
                MappingData data = new MappingData(readUuid(input), input.readUTF(), input.readUnsignedShort(),
                        input.readUnsignedShort(), input.readUTF(), input.readBoolean(), input.readBoolean());
                validate(data.name(), data.listenPort(), data.targetPort(), data.peer());
                mappings.add(data);
            }
            int allowedCount = boundedCount(input.readInt());
            List<AllowedData> allowed = new ArrayList<>(allowedCount);
            for (int i = 0; i < allowedCount; i++) {
                AllowedData data = new AllowedData(readUuid(input), input.readUTF(), input.readUTF(),
                        input.readUnsignedShort());
                validate(data.name(), 1, data.targetPort(), data.peer());
                allowed.add(data);
            }
            if (input.read() != -1) throw new IOException("Trailing routes.dat data");
            return new State(List.copyOf(mappings), List.copyOf(allowed));
        } catch (EOFException truncated) {
            throw new IOException("routes.dat is truncated", truncated);
        }
    }

    void save(List<MappingData> mappings, List<AllowedData> allowed) throws IOException {
        if (mappings.size() > MAX_ROUTES || allowed.size() > MAX_ROUTES) {
            throw new IOException("Too many stored routes");
        }
        var bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeLong(MAGIC);
            output.writeByte(VERSION);
            output.writeInt(mappings.size());
            for (MappingData data : mappings) {
                validate(data.name(), data.listenPort(), data.targetPort(), data.peer());
                writeUuid(output, data.routeId());
                output.writeUTF(data.name());
                output.writeShort(data.listenPort());
                output.writeShort(data.targetPort());
                output.writeUTF(data.peer());
                output.writeBoolean(data.accepted());
                output.writeBoolean(data.enabled());
            }
            output.writeInt(allowed.size());
            for (AllowedData data : allowed) {
                validate(data.name(), 1, data.targetPort(), data.peer());
                writeUuid(output, data.routeId());
                output.writeUTF(data.name());
                output.writeUTF(data.peer());
                output.writeShort(data.targetPort());
            }
        }
        rejectLinks(file);
        Files.createDirectories(file.getParent());
        restrictToOwner(file.getParent(), true);
        Path temporary = Files.createTempFile(file.getParent(), "routes-", ".tmp");
        try {
            restrictToOwner(temporary, false);
            Files.write(temporary, bytes.toByteArray(), StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void rejectLinks(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) {
            try {
                var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther())
                    throw new IOException("Linked or special route storage path is not allowed: " + current);
            } catch (NoSuchFileException missing) {
                // New storage is allowed only below parents already checked above.
            }
        }
    }

    private static void restrictToOwner(Path path, boolean directory) throws IOException {
        rejectLinks(path);
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            var permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            if (directory) permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
            return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            var ownerOnly = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
            acl.setAcl(List.of(ownerOnly));
            return;
        }
        throw new IOException("Owner-only route storage permissions are unavailable: " + path);
    }

    private static int boundedCount(int count) throws IOException {
        if (count < 0 || count > MAX_ROUTES) throw new IOException("Invalid route count: " + count);
        return count;
    }

    private static void validate(String name, int listenPort, int targetPort, String peer) throws IOException {
        try {
            ControlPacket.validateName(name);
            ControlPacket.validatePort(listenPort);
            ControlPacket.validatePort(targetPort);
            if (!peer.isEmpty() && !peer.matches("[A-Za-z0-9_]{1,16}")) {
                throw new IllegalArgumentException("Invalid player name");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid stored route: " + invalid.getMessage(), invalid);
        }
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    record MappingData(UUID routeId, String name, int listenPort, int targetPort,
                       String peer, boolean accepted, boolean enabled) {}
    record AllowedData(UUID routeId, String name, String peer, int targetPort) {}
    record State(List<MappingData> mappings, List<AllowedData> allowed) {}
}
