package dev.k04mreverseforward;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RouteStoreTest {
    @TempDir
    Path temporaryDirectory;

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
