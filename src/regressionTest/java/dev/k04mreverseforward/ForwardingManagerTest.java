package dev.k04mreverseforward;

import dev.krypt04mcg.api.Krypt04McgApi;
import dev.krypt04mcg.api.KryptSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ForwardingManagerTest {
    @TempDir Path directory;

    @Test void staleRepliesAreIgnoredAndAcceptancePreservesStop() throws Exception {
        ForwardingManager manager = manager();
        try {
            assertEquals(1, manager.register("web", 25570, 8080));
            manager.invite("web", "Bob");
            ControlPacket old = ControlPacket.decode(Krypt04McgApi.last);
            manager.invite("web", "Bob");
            ControlPacket current = ControlPacket.decode(Krypt04McgApi.last);
            manager.stop("web");
            manager.receiveControl("Bob", reply(old, ControlPacket.Type.ACCEPT, 9090).encode());
            assertFalse(state().accepted());
            assertEquals(8080, state().targetPort());
            manager.receiveControl("Bob", reply(old, ControlPacket.Type.REJECT, 8080).encode());
            manager.receiveControl("Bob", reply(current, ControlPacket.Type.ACCEPT, 9091).encode());
            assertTrue(state().accepted());
            assertFalse(state().enabled());
            assertEquals(9091, state().targetPort());
            manager.receiveControl("Bob", reply(old, ControlPacket.Type.REJECT, 8080).encode());
            manager.receiveControl("Bob", reply(current, ControlPacket.Type.ACCEPT, 9999).encode());
            assertTrue(state().accepted());
            assertEquals(9091, state().targetPort());
        } finally { shutdown(manager); }
    }

    @Test void overflowDoesNotPoisonLaterSaves() throws Exception {
        ForwardingManager manager = manager();
        try {
            for (int i = 0; i < RouteStore.MAX_ROUTES; i++) {
                assertEquals(1, manager.register("r" + i, 10000 + i, 8080));
            }
            assertEquals(0, manager.register("overflow", 11000, 8080));
            assertEquals(RouteStore.MAX_ROUTES, map(manager, "mappings").size());
            assertEquals(1, manager.stop("r0"));
            assertEquals(1, manager.remove("r1"));
            assertEquals(1, manager.register("replacement", 11000, 8080));
        } finally { shutdown(manager); }
    }

    @Test void rejectionAndRevocationInvalidatePendingAcceptance() throws Exception {
        ForwardingManager manager = manager();
        try {
            manager.register("web", 25570, 8080);
            for (ControlPacket.Type terminal : new ControlPacket.Type[] {
                    ControlPacket.Type.REJECT, ControlPacket.Type.REVOKE}) {
                manager.invite("web", "Bob");
                ControlPacket invitation = ControlPacket.decode(Krypt04McgApi.last);
                manager.receiveControl("Bob", reply(invitation, terminal, 8080).encode());
                manager.receiveControl("Bob", reply(invitation, ControlPacket.Type.ACCEPT, 9090).encode());
                assertFalse(state().accepted());
                assertEquals(8080, state().targetPort());
            }
        } finally { shutdown(manager); }
    }

    @Test void oldStreamProtocolIsRejected() throws Exception {
        ForwardingManager manager = manager();
        KryptSocket stream = new KryptSocket();
        try {
            DataOutputStream header = new DataOutputStream(stream.feed);
            header.writeLong(0x4B30344D5354524DL);
            header.writeByte(1);
            header.flush();
            manager.receiveSocket(stream);
            assertTrue(stream.handshake.await(3, TimeUnit.SECONDS));
            assertArrayEquals(new byte[] {1}, stream.output.toByteArray());
        } finally { stream.close(); shutdown(manager); }
    }

    @Test void failedRegistrationAndAcceptanceRollBack() throws Exception {
        ForwardingManager manager = manager();
        try {
            // A regular file blocks creation of the configuration directory.
            Files.writeString(directory.resolve("k04m-reverse-forward"), "blocked");
            assertEquals(0, manager.register("web", 25570, 8080));
            assertTrue(map(manager, "mappings").isEmpty());
            ControlPacket invitation = invite();
            manager.receiveControl("Bob", invitation.encode());
            assertEquals(0, manager.accept(invitation.invitationId().toString()));
            assertTrue(map(manager, "allowedRoutes").isEmpty());
            assertEquals(1, map(manager, "invitations").size());
            Files.delete(directory.resolve("k04m-reverse-forward"));
            assertEquals(1, manager.register("web", 25570, 8080));
            assertEquals(1, manager.accept(invitation.invitationId().toString()));
        } finally { shutdown(manager); }
    }

    @Test void localRevokeClosesActiveConnection() throws Exception { checkClose("local"); }
    @Test void remoteRevokeClosesActiveConnection() throws Exception { checkClose("remote"); }
    @Test void disconnectClosesActiveConnection() throws Exception { checkClose("disconnect"); }
    @Test void replacingAuthorizationClosesOldConnection() throws Exception { checkClose("replace"); }

    @Test void stopClosesOutgoingHandshake() throws Exception { checkOutgoingClose("stop"); }
    @Test void removeClosesOutgoingHandshake() throws Exception { checkOutgoingClose("remove"); }
    @Test void remoteRevokeClosesOutgoingHandshake() throws Exception { checkOutgoingClose("revoke"); }

    private void checkOutgoingClose(String action) throws Exception {
        ForwardingManager manager = manager();
        try {
            int port;
            try (var temporary = LoopbackTcp.listen(0)) { port = temporary.getLocalPort(); }
            manager.register("web", port, 8080);
            manager.invite("web", "Bob");
            ControlPacket invitation = ControlPacket.decode(Krypt04McgApi.last);
            manager.receiveControl("Bob", reply(invitation, ControlPacket.Type.ACCEPT, 8080).encode());
            var connected = ForwardingManager.class.getDeclaredField("connected");
            connected.setAccessible(true);
            connected.setBoolean(manager, true);
            Krypt04McgApi.lastSocket = null;
            assertEquals(1, manager.start("web"));
            try (var client = new java.net.Socket("127.0.0.1", port)) {
                client.setSoTimeout(3000);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (Krypt04McgApi.lastSocket == null && System.nanoTime() < deadline) Thread.sleep(5);
                KryptSocket stream = Krypt04McgApi.lastSocket;
                assertNotNull(stream);
                assertTrue(stream.handshake.await(3, TimeUnit.SECONDS));
                switch (action) {
                    case "stop" -> manager.stop("web");
                    case "remove" -> manager.remove("web");
                    case "revoke" -> manager.receiveControl("Bob", reply(invitation, ControlPacket.Type.REVOKE, 8080).encode());
                    default -> fail("Unknown action");
                }
                assertEquals(-1, client.getInputStream().read());
                assertTrue(stream.closed);
            }
        } finally { shutdown(manager); }
    }

    private void checkClose(String action) throws Exception {
        ForwardingManager manager = manager();
        KryptSocket stream = new KryptSocket();
        try (var server = LoopbackTcp.listen(0)) {
            server.setSoTimeout(3000);
            ControlPacket invitation = invite();
            manager.receiveControl("Bob", invitation.encode());
            assertEquals(1, manager.accept(invitation.invitationId().toString(), server.getLocalPort()));
            DataOutputStream header = new DataOutputStream(stream.feed);
            header.writeLong(0x4B30344D5354524DL);
            header.writeByte(2);
            header.writeLong(invitation.routeId().getMostSignificantBits());
            header.writeLong(invitation.routeId().getLeastSignificantBits());
            header.flush();
            manager.receiveSocket(stream);
            try (var service = server.accept()) {
                service.setSoTimeout(3000);
                assertTrue(stream.handshake.await(3, TimeUnit.SECONDS));
                header.writeInt(1);
                header.writeByte(42);
                header.flush();
                assertEquals(42, service.getInputStream().read());
                switch (action) {
                    case "local" -> manager.revoke(invitation.routeId().toString());
                    case "remote" -> manager.receiveControl("Bob", reply(invitation, ControlPacket.Type.REVOKE, 8080).encode());
                    case "disconnect" -> manager.disconnect();
                    case "replace" -> {
                        var replacement = new ControlPacket(ControlPacket.Type.INVITE, UUID.randomUUID(),
                                invitation.routeId(), "in", 25570, 9090);
                        manager.receiveControl("Bob", replacement.encode());
                        assertEquals(1, manager.accept(replacement.invitationId().toString()));
                    }
                    default -> fail("Unknown action");
                }
                assertEquals(-1, service.getInputStream().read());
                assertTrue(stream.closed);
            }
        } finally { stream.close(); shutdown(manager); }
    }

    @Test void revokedAuthorizationCannotAdmitLateWorker() throws Exception {
        ForwardingManager manager = manager();
        try {
            var invitation = invite();
            manager.receiveControl("Bob", invitation.encode());
            manager.accept(invitation.invitationId().toString());
            Object oldRoute = map(manager, "allowedRoutes").get(invitation.routeId());
            Object group = field(oldRoute, "connections");
            manager.revoke(invitation.routeId().toString());
            Class<?> connectionType = Class.forName(ForwardingManager.class.getName() + "$Connection");
            var constructor = connectionType.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object late = constructor.newInstance();
            var add = group.getClass().getDeclaredMethod("add", connectionType);
            add.setAccessible(true);
            assertEquals(false, add.invoke(group, late));
        } finally { shutdown(manager); }
    }

    private ForwardingManager manager() {
        var manager = new ForwardingManager();
        manager.load(directory);
        return manager;
    }
    private RouteStore.MappingData state() throws Exception {
        return new RouteStore(directory.resolve("k04m-reverse-forward")).load().mappings().getFirst();
    }
    private static ControlPacket invite() {
        return new ControlPacket(ControlPacket.Type.INVITE, UUID.randomUUID(), UUID.randomUUID(), "in", 25570, 8080);
    }
    private static ControlPacket reply(ControlPacket invitation, ControlPacket.Type type, int port) {
        return new ControlPacket(type, invitation.invitationId(), invitation.routeId(), invitation.name(), invitation.listenPort(), port);
    }
    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static Map<?, ?> map(Object target, String name) throws Exception { return (Map<?, ?>) field(target, name); }
    private static void shutdown(ForwardingManager manager) throws Exception {
        manager.disconnect();
        var workers = (ExecutorService) field(manager, "workers");
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
    }
}
