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
    private static final long MAGIC = 0x4B30344D53544F52L;
    // K04MSTOR
    private static final int VERSION = 1;
    static final int MAX_ROUTES = 256;
    private final Path file;

    /**
     * Creates a route store with the supplied dependencies and initial state.
     *
     * @param configDirectory the directory containing the persisted configuration
     */
    RouteStore(Path configDirectory) {
        this.file = configDirectory.toAbsolutePath().normalize().resolve("routes.dat");
    }

    /**
     * Loads the bounded versioned routes.dat authorization snapshot after rejecting linked/special paths
     * and enforcing private permissions. Names, peers, ports, counts and trailing/truncated data are
     * validated before authorization is reconstructed.
     *
     * @return the result described above
     * @throws IOException if input/output, stored-state validation or resource handling fails
     */
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

    /**
     * Validates bounded route lists and writes their authorization snapshot through private temporary-file
     * replacement. Atomic replacement is used when supported; link/path and owner-permission checks are
     * performed around the filesystem operations.
     *
     * @param mappings the mappings supplied to this operation
     * @param allowed the allowed supplied to this operation
     * @throws IOException if input/output, stored-state validation or resource handling fails
     */
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
                /*
                 * Replaces the destination through the filesystem move API. Atomic replacement depends on filesystem
                 * support; the surrounding catch path determines whether fallback is allowed. Link/permission
                 * preflight checks are separate and do not eliminate every concurrent path race.
                 */
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                /*
                 * Replaces the destination through the filesystem move API. Atomic replacement depends on filesystem
                 * support; the surrounding catch path determines whether fallback is allowed. Link/permission
                 * preflight checks are separate and do not eliminate every concurrent path race.
                 */
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Rejects symlink or special-file attributes in the normalized destination path and its parents
     * without following links. These path preflight checks do not eliminate concurrent path-replacement
     * races.
     *
     * @param path the filesystem path used by this operation
     * @throws IOException if input/output, stored-state validation or resource handling fails
     */
    private static void rejectLinks(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) {
            try {
                /*
                 * Reads path attributes without following the final symbolic link so the caller can reject linked or
                 * special components. Ancestors are checked by the surrounding loop; this is preflight inspection, not
                 * an atomic directory-handle capability.
                 */
                var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther())
                    throw new IOException("Linked or special route storage path is not allowed: " + current);
            } catch (NoSuchFileException missing) {
                // New storage is allowed only below parents already checked above.
            }
        }
    }

    /**
     * Enforces owner-only POSIX permissions or an owner-only ACL and rejects filesystems where neither is
     * available. Stored forwarding authorization must not be writable by unrelated local accounts.
     *
     * @param path the filesystem path used by this operation
     * @param directory the directory supplied to this operation
     * @throws IOException if input/output, stored-state validation or resource handling fails
     */
    private static void restrictToOwner(Path path, boolean directory) throws IOException {
        rejectLinks(path);
        /*
         * Checks the filesystem permission interface before applying owner-only access. POSIX permissions or
         * ACL support must be present; the surrounding implementation rejects unsupported protection instead
         * of assuming private defaults.
         */
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            var permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            if (directory) permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
            return;
        }
        /*
         * Checks the filesystem permission interface before applying owner-only access. POSIX permissions or
         * ACL support must be present; the surrounding implementation rejects unsupported protection instead
         * of assuming private defaults.
         */
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            var ownerOnly = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
            acl.setAcl(List.of(ownerOnly));
            return;
        }
        throw new IOException("Owner-only route storage permissions are unavailable: " + path);
    }

    /**
     * Returns the recorded count for the persisted reverse-forward routes.
     *
     * @param count the required number of frame components
     * @return the result described above
     * @throws IOException if input/output, stored-state validation or resource handling fails
     */
    private static int boundedCount(int count) throws IOException {
        if (count < 0 || count > MAX_ROUTES) throw new IOException("Invalid route count: " + count);
        return count;
    }

    /**
     * Checks the input required by the persisted reverse-forward routes and rejects invalid state instead
     * of continuing.
     *
     * @param name the name supplied to this operation
     * @param listenPort the listen port supplied to this operation
     * @param targetPort the target port supplied to this operation
     * @param peer the peer identifier associated with this operation
     * @throws IOException if input/output, stored-state validation or resource handling fails
     */
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

    /**
     * Writes uuid to the output used by the persisted reverse-forward routes.
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
     * Reads uuid from the input used by the persisted reverse-forward routes.
     *
     * @param input the input bytes or stream consumed by the operation
     * @return the result described above
     * @throws IOException if input/output, stored-state validation or resource handling fails
     */
    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    record MappingData(UUID routeId, String name, int listenPort, int targetPort,
                       String peer, boolean accepted, boolean enabled) {}
    record AllowedData(UUID routeId, String name, String peer, int targetPort) {}
    record State(List<MappingData> mappings, List<AllowedData> allowed) {}
}
