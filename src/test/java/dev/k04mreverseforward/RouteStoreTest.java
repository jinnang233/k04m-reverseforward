package dev.k04mreverseforward;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RouteStoreTest {
    @TempDir
    Path temporaryDirectory;

    /**
     * Verifies that fixed temporary symlink cannot overwrite another file.
     *
     * @throws Exception if the delegated operation cannot complete successfully
     */
    @Test void fixedTemporarySymlinkCannotOverwriteAnotherFile() throws Exception {
        Path victim = temporaryDirectory.resolve("unrelated.txt");
        Files.writeString(victim, "keep me");
        Path config = Files.createDirectory(temporaryDirectory.resolve("config"));
        createLink(config.resolve("routes.dat.tmp"), victim);
        new RouteStore(config).save(List.of(), List.of());
        assertEquals("keep me", Files.readString(victim));
        assertTrue(Files.isSymbolicLink(config.resolve("routes.dat.tmp")));
    }

    /**
     * Verifies that linked authorization files cannot be loaded or replaced.
     *
     * @throws Exception if the delegated operation cannot complete successfully
     */
    @Test void linkedAuthorizationFilesCannotBeLoadedOrReplaced() throws Exception {
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        new RouteStore(outside).save(List.of(), List.of());
        byte[] original = Files.readAllBytes(outside.resolve("routes.dat"));
        Path config = Files.createDirectory(temporaryDirectory.resolve("config"));
        createLink(config.resolve("routes.dat"), outside.resolve("routes.dat"));
        var store = new RouteStore(config);
        assertThrows(IOException.class, store::load);
        assertThrows(IOException.class, () -> store.save(List.of(), List.of()));
        assertArrayEquals(original, Files.readAllBytes(outside.resolve("routes.dat")));
    }

    /**
     * Verifies that linked parent and dangling authorization file are rejected.
     *
     * @throws Exception if the delegated operation cannot complete successfully
     */
    @Test void linkedParentAndDanglingAuthorizationFileAreRejected() throws Exception {
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        Path linkedDirectory = temporaryDirectory.resolve("linked");
        createLink(linkedDirectory, outside);
        var linkedStore = new RouteStore(linkedDirectory);
        assertThrows(IOException.class, linkedStore::load);
        assertThrows(IOException.class, () -> linkedStore.save(List.of(), List.of()));
        assertFalse(Files.exists(outside.resolve("routes.dat")));
        Path config = Files.createDirectory(temporaryDirectory.resolve("config"));
        createLink(config.resolve("routes.dat"), outside.resolve("missing"));
        assertThrows(IOException.class, new RouteStore(config)::load);
    }

    /**
     * Verifies that authorization directory and file are owner only on save and load.
     *
     * @throws Exception if the delegated operation cannot complete successfully
     */
    @Test void authorizationDirectoryAndFileAreOwnerOnlyOnSaveAndLoad() throws Exception {
        assumeTrue(Files.getFileStore(temporaryDirectory).supportsFileAttributeView("posix"));
        Path config = Files.createDirectory(temporaryDirectory.resolve("config"));
        Files.setPosixFilePermissions(config, java.util.EnumSet.allOf(PosixFilePermission.class));
        var store = new RouteStore(config);
        store.save(List.of(), List.of());
        Set<PosixFilePermission> directoryPermissions = Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
        Set<PosixFilePermission> filePermissions = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertEquals(directoryPermissions, Files.getPosixFilePermissions(config));
        assertEquals(filePermissions, Files.getPosixFilePermissions(config.resolve("routes.dat")));
        Files.setPosixFilePermissions(config, java.util.EnumSet.allOf(PosixFilePermission.class));
        Files.setPosixFilePermissions(config.resolve("routes.dat"), java.util.EnumSet.allOf(PosixFilePermission.class));
        store.load();
        assertEquals(directoryPermissions, Files.getPosixFilePermissions(config));
        assertEquals(filePermissions, Files.getPosixFilePermissions(config.resolve("routes.dat")));
    }

    /**
     * Provides the create link fixture operation used by the route store test regression scenarios.
     *
     * @param link the link supplied to this operation
     * @param target the target supplied to this operation
     * @throws Exception if the delegated operation cannot complete successfully
     */
    private static void createLink(Path link, Path target) throws Exception {
        try { Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException | IOException unavailable) {
            assumeTrue(false, "Symbolic links unavailable: " + unavailable.getMessage());
        }
    }

    /**
     * Verifies that persists mappings and authorizations.
     *
     * @throws Exception if the delegated operation cannot complete successfully
     */
    @Test
    void persistsMappingsAndAuthorizations() throws Exception {
        UUID outgoingId = UUID.randomUUID();
        UUID incomingId = UUID.randomUUID();
        List<RouteStore.MappingData> mappings = List.of(new RouteStore.MappingData(
                outgoingId, "web", 25570, 8080, "Bob", true, false));
        List<RouteStore.AllowedData> allowed = List.of(new RouteStore.AllowedData(
                incomingId, "database", "Alice", 5432));

        RouteStore store = new RouteStore(temporaryDirectory);
        store.save(mappings, allowed);
        RouteStore.State loaded = store.load();

        assertEquals(mappings, loaded.mappings());
        assertEquals(allowed, loaded.allowed());
    }
}
