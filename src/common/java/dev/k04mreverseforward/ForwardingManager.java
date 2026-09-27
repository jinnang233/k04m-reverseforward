package dev.k04mreverseforward;

import dev.krypt04mcg.api.DataTransfer;
import dev.krypt04mcg.api.Krypt04McgApi;
import dev.krypt04mcg.api.KryptSocket;
import net.minecraft.client.Minecraft;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class ForwardingManager {
    private static final long STREAM_MAGIC = 0x4B30344D5354524DL; // K04MSTRM
    private static final int STREAM_VERSION = 1;
    private static final int COPY_BUFFER = 16 * 1024;
    private static final int MAX_CONNECTIONS_PER_ROUTE = 8;
    private static final Duration INVITATION_TTL = Duration.ofMinutes(2);

    private final Map<String, Mapping> mappings = new ConcurrentHashMap<>();
    private final Map<UUID, AllowedRoute> allowedRoutes = new ConcurrentHashMap<>();
    private final Map<UUID, Invitation> invitations = new ConcurrentHashMap<>();
    private final Map<UUID, Listener> listeners = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private RouteStore store;
    private boolean connected;

    void load() {
        Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("k04m-reverse-forward");
        store = new RouteStore(directory);
        try {
            RouteStore.State state = store.load();
            for (RouteStore.MappingData data : state.mappings()) {
                mappings.put(key(data.name()), new Mapping(data.routeId(), data.name(), data.listenPort(),
                        data.targetPort(), data.peer(), data.accepted(), data.enabled()));
            }
            for (RouteStore.AllowedData data : state.allowed()) {
                allowedRoutes.put(data.routeId(), new AllowedRoute(data.routeId(), data.name(), data.peer(), data.targetPort()));
            }
        } catch (IOException error) {
            ReverseForward.message("Could not load routes: " + error.getMessage());
        }
    }

    int register(String name, int listenPort, int targetPort) {
        try {
            ControlPacket.validateName(name);
            ControlPacket.validatePort(listenPort);
            ControlPacket.validatePort(targetPort);
            if (mappings.containsKey(key(name))) return fail("A route named '" + name + "' already exists.");
            if (mappings.values().stream().anyMatch(mapping -> mapping.listenPort == listenPort)) {
                return fail("Listen port " + listenPort + " is already registered.");
            }
            Mapping mapping = new Mapping(UUID.randomUUID(), name, listenPort, targetPort, "", false, true);
            mappings.put(key(name), mapping);
            save();
            ReverseForward.message("Registered '" + name + "': 127.0.0.1:" + listenPort
                    + " -> invited peer's 127.0.0.1:" + targetPort + ". Now invite a player.");
            return 1;
        } catch (IllegalArgumentException | IOException error) {
            return fail(error.getMessage());
        }
    }

    int invite(String name, String player) {
        Mapping mapping = mappings.get(key(name));
        if (mapping == null) return fail("Unknown route '" + name + "'.");
        if (!validPlayer(player)) return fail("Invalid Minecraft player name.");
        if (self(player)) return fail("You cannot invite yourself.");
        stopListener(mapping.routeId);
        if (mapping.accepted && !mapping.peer.isEmpty() && !mapping.peer.equalsIgnoreCase(player)) {
            ControlPacket revoke = new ControlPacket(ControlPacket.Type.REVOKE, UUID.randomUUID(), mapping.routeId,
                    mapping.name, mapping.listenPort, mapping.targetPort);
            try { sendControl(mapping.peer, revoke, "Revoking the previous authorization for '" + mapping.name + "'."); }
            catch (RuntimeException error) { ReverseForward.message("Previous peer notification failed: " + error.getMessage()); }
        }
        mapping.peer = player;
        mapping.accepted = false;
        UUID invitationId = UUID.randomUUID();
        ControlPacket packet = new ControlPacket(ControlPacket.Type.INVITE, invitationId, mapping.routeId,
                mapping.name, mapping.listenPort, mapping.targetPort);
        try {
            save();
            sendControl(player, packet, "Invitation " + shortId(invitationId) + " sent to " + player + ".");
            return 1;
        } catch (RuntimeException | IOException error) {
            return fail("Could not send invitation: " + error.getMessage());
        }
    }

    int accept(String idText) {
        Invitation invitation = findInvitation(idText);
        if (invitation == null) return fail("Unknown or ambiguous invitation ID.");
        invitations.remove(invitation.invitationId);
        AllowedRoute route = new AllowedRoute(invitation.routeId, invitation.name, invitation.sender, invitation.targetPort);
        allowedRoutes.put(route.routeId, route);
        try {
            save();
            sendControl(invitation.sender, invitation.packet(ControlPacket.Type.ACCEPT),
                    "Accepted '" + invitation.name + "' from " + invitation.sender + ". Forwarded connections will reach 127.0.0.1:"
                            + invitation.targetPort + ".");
            return 1;
        } catch (RuntimeException | IOException error) {
            return fail("Acceptance was saved, but the response could not be sent: " + error.getMessage());
        }
    }

    int deny(String idText) {
        Invitation invitation = findInvitation(idText);
        if (invitation == null) return fail("Unknown or ambiguous invitation ID.");
        invitations.remove(invitation.invitationId);
        try {
            sendControl(invitation.sender, invitation.packet(ControlPacket.Type.REJECT),
                    "Denied invitation '" + invitation.name + "' from " + invitation.sender + ".");
            return 1;
        } catch (RuntimeException error) {
            return fail("Invitation was denied locally, but the response could not be sent: " + error.getMessage());
        }
    }

    int start(String name) {
        Mapping mapping = mappings.get(key(name));
        if (mapping == null) return fail("Unknown route '" + name + "'.");
        if (!mapping.accepted || mapping.peer.isEmpty()) return fail("The invited peer has not accepted this route.");
        mapping.enabled = true;
        try {
            save();
            if (connected) startListener(mapping);
            return 1;
        } catch (IOException error) {
            return fail("Could not start route: " + error.getMessage());
        }
    }

    int stop(String name) {
        Mapping mapping = mappings.get(key(name));
        if (mapping == null) return fail("Unknown route '" + name + "'.");
        mapping.enabled = false;
        stopListener(mapping.routeId);
        try {
            save();
            ReverseForward.message("Stopped route '" + name + "'.");
            return 1;
        } catch (IOException error) {
            return fail("Route stopped, but its state could not be saved: " + error.getMessage());
        }
    }

    int remove(String name) {
        Mapping mapping = mappings.remove(key(name));
        if (mapping == null) return fail("Unknown route '" + name + "'.");
        stopListener(mapping.routeId);
        if (!mapping.peer.isEmpty()) {
            ControlPacket packet = new ControlPacket(ControlPacket.Type.REVOKE, UUID.randomUUID(), mapping.routeId,
                    mapping.name, mapping.listenPort, mapping.targetPort);
            try { sendControl(mapping.peer, packet, "Removed route '" + name + "'."); }
            catch (RuntimeException error) { ReverseForward.message("Removed locally; peer notification failed: " + error.getMessage()); }
        }
        try { save(); } catch (IOException error) { return fail("Removed in memory, but save failed: " + error.getMessage()); }
        return 1;
    }

    int revoke(String routeText) {
        AllowedRoute route = findAllowed(routeText);
        if (route == null) return fail("Unknown or ambiguous allowed route ID.");
        allowedRoutes.remove(route.routeId);
        ControlPacket packet = new ControlPacket(ControlPacket.Type.REVOKE, UUID.randomUUID(), route.routeId,
                route.name, 1, route.targetPort);
        try { sendControl(route.peer, packet, "Revoked route '" + route.name + "' for " + route.peer + "."); }
        catch (RuntimeException error) { ReverseForward.message("Revoked locally; peer notification failed: " + error.getMessage()); }
        try { save(); } catch (IOException error) { return fail("Revoked in memory, but save failed: " + error.getMessage()); }
        return 1;
    }

    int list() {
        if (mappings.isEmpty() && allowedRoutes.isEmpty()) {
            ReverseForward.message("No configured routes.");
            return 1;
        }
        mappings.values().stream().sorted(Comparator.comparing(mapping -> mapping.name)).forEach(mapping ->
                ReverseForward.message("OUT " + mapping.name + " [" + shortId(mapping.routeId) + "] 127.0.0.1:"
                        + mapping.listenPort + " -> " + (mapping.peer.isEmpty() ? "<not invited>" : mapping.peer)
                        + ":127.0.0.1:" + mapping.targetPort + " (" + state(mapping) + ")"));
        allowedRoutes.values().stream().sorted(Comparator.comparing(route -> route.name)).forEach(route ->
                ReverseForward.message("IN  " + route.name + " [" + shortId(route.routeId) + "] from " + route.peer
                        + " -> 127.0.0.1:" + route.targetPort + " (authorized)"));
        return 1;
    }

    int listInvitations() {
        expireInvitations();
        if (invitations.isEmpty()) ReverseForward.message("No pending invitations.");
        invitations.values().forEach(invitation -> ReverseForward.message(shortId(invitation.invitationId) + ": "
                + invitation.sender + " requests '" + invitation.name + "' -> 127.0.0.1:" + invitation.targetPort
                + ". Accept with /k04mrf accept " + shortId(invitation.invitationId)));
        return 1;
    }

    void receiveControl(String sender, byte[] bytes) {
        try {
            ControlPacket packet = ControlPacket.decode(bytes);
            switch (packet.type()) {
                case INVITE -> receiveInvite(sender, packet);
                case ACCEPT -> receiveAccept(sender, packet);
                case REJECT -> receiveReject(sender, packet);
                case REVOKE -> receiveRevoke(sender, packet);
            }
        } catch (IOException | RuntimeException error) {
            ReverseForward.message("Rejected invalid control packet from " + sender + ": " + error.getMessage());
        }
    }

    void receiveSocket(KryptSocket socket) {
        workers.submit(() -> handleIncomingSocket(socket));
    }

    void tick() {
        boolean nowConnected = Minecraft.getInstance().player != null && Minecraft.getInstance().getConnection() != null;
        if (nowConnected && !connected) {
            connected = true;
            mappings.values().stream().filter(mapping -> mapping.accepted && mapping.enabled).forEach(this::startListener);
        } else if (!nowConnected && connected) {
            disconnect();
        }
        expireInvitations();
    }

    void disconnect() {
        connected = false;
        listeners.values().forEach(Listener::close);
        listeners.clear();
        invitations.clear();
    }

    private void receiveInvite(String sender, ControlPacket packet) {
        if (!validPlayer(sender)) return;
        AllowedRoute existing = allowedRoutes.get(packet.routeId());
        if (existing != null && !existing.peer.equalsIgnoreCase(sender)) {
            ReverseForward.message("Rejected invitation from " + sender + ": its route ID conflicts with an existing authorization.");
            return;
        }
        Invitation invitation = new Invitation(packet.invitationId(), packet.routeId(), packet.name(), sender,
                packet.listenPort(), packet.targetPort(), Instant.now());
        invitations.put(invitation.invitationId, invitation);
        ReverseForward.message(sender + " invites you to route '" + packet.name() + "' to your 127.0.0.1:"
                + packet.targetPort() + ". Accept: /k04mrf accept " + shortId(packet.invitationId())
                + "  Deny: /k04mrf deny " + shortId(packet.invitationId()));
    }

    private void receiveAccept(String sender, ControlPacket packet) throws IOException {
        Mapping mapping = mappingByRoute(packet.routeId());
        if (mapping == null || !mapping.peer.equalsIgnoreCase(sender) || !mapping.name.equals(packet.name())) return;
        mapping.accepted = true;
        mapping.enabled = true;
        save();
        startListener(mapping);
        ReverseForward.message(sender + " accepted route '" + mapping.name + "'. Listening on 127.0.0.1:" + mapping.listenPort + ".");
    }

    private void receiveReject(String sender, ControlPacket packet) throws IOException {
        Mapping mapping = mappingByRoute(packet.routeId());
        if (mapping == null || !mapping.peer.equalsIgnoreCase(sender)) return;
        mapping.accepted = false;
        save();
        stopListener(mapping.routeId);
        ReverseForward.message(sender + " denied route '" + mapping.name + "'.");
    }

    private void receiveRevoke(String sender, ControlPacket packet) throws IOException {
        Mapping mapping = mappingByRoute(packet.routeId());
        if (mapping != null && mapping.peer.equalsIgnoreCase(sender)) {
            mapping.accepted = false;
            stopListener(mapping.routeId);
            ReverseForward.message(sender + " revoked route '" + mapping.name + "'.");
        }
        AllowedRoute allowed = allowedRoutes.get(packet.routeId());
        if (allowed != null && allowed.peer.equalsIgnoreCase(sender)) {
            allowedRoutes.remove(packet.routeId());
            ReverseForward.message(sender + " removed route '" + allowed.name + "'.");
        }
        save();
    }

    private void startListener(Mapping mapping) {
        if (!connected || !mapping.enabled || !mapping.accepted || listeners.containsKey(mapping.routeId)) return;
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), mapping.listenPort), 32);
            Listener listener = new Listener(mapping, server);
            listeners.put(mapping.routeId, listener);
            workers.submit(() -> acceptLoop(listener));
            ReverseForward.message("Listening for '" + mapping.name + "' on 127.0.0.1:" + mapping.listenPort + ".");
        } catch (IOException error) {
            ReverseForward.message("Could not listen for '" + mapping.name + "' on port " + mapping.listenPort + ": " + error.getMessage());
        }
    }

    private void stopListener(UUID routeId) {
        Listener listener = listeners.remove(routeId);
        if (listener != null) listener.close();
    }

    private void acceptLoop(Listener listener) {
        try {
            while (!listener.closed.get()) {
                Socket local = listener.server.accept();
                local.setTcpNoDelay(true);
                if (!listener.slots.tryAcquire()) {
                    local.close();
                    ReverseForward.message("Route '" + listener.mapping.name + "' rejected a connection: limit reached.");
                    continue;
                }
                workers.submit(() -> {
                    try { openForward(listener.mapping, local); }
                    finally { listener.slots.release(); }
                });
            }
        } catch (SocketException closed) {
            if (!listener.closed.get()) ReverseForward.message("Listener for '" + listener.mapping.name + "' failed: " + closed.getMessage());
        } catch (IOException error) {
            ReverseForward.message("Listener for '" + listener.mapping.name + "' failed: " + error.getMessage());
        } finally {
            listeners.remove(listener.mapping.routeId, listener);
            listener.close();
        }
    }

    private void openForward(Mapping mapping, Socket local) {
        KryptSocket encrypted = null;
        try (local) {
            encrypted = clientCall(() -> Krypt04McgApi.connect(mapping.peer, ReverseForward.SOCKET_CHANNEL));
            KryptSocket stream = encrypted;
            clientRun(() -> {
                DataOutputStream output = new DataOutputStream(stream.getOutputStream());
                output.writeLong(STREAM_MAGIC);
                output.writeByte(STREAM_VERSION);
                output.writeLong(mapping.routeId.getMostSignificantBits());
                output.writeLong(mapping.routeId.getLeastSignificantBits());
                output.flush();
            });
            int response = stream.getInputStream().read();
            if (response != 0) throw new IOException(response < 0 ? "Peer closed before accepting" : "Peer rejected route (code " + response + ")");
            bridge(local, stream);
        } catch (Exception error) {
            ReverseForward.message("Connection on '" + mapping.name + "' closed: " + useful(error));
        } finally {
            closeEncrypted(encrypted);
        }
    }

    private void handleIncomingSocket(KryptSocket encrypted) {
        Socket target = null;
        AllowedRoute route = null;
        boolean slotAcquired = false;
        try {
            // Do not buffer this header: a BufferedInputStream could read application bytes
            // ahead and strand them when bridge() resumes from the KryptSocket input.
            DataInputStream input = new DataInputStream(encrypted.getInputStream());
            if (input.readLong() != STREAM_MAGIC || input.readUnsignedByte() != STREAM_VERSION) {
                reject(encrypted, 1);
                return;
            }
            UUID routeId = new UUID(input.readLong(), input.readLong());
            route = allowedRoutes.get(routeId);
            if (route == null || !route.peer.equalsIgnoreCase(encrypted.peer())) {
                reject(encrypted, 2);
                return;
            }
            if (!route.slots.tryAcquire()) {
                reject(encrypted, 4);
                return;
            }
            slotAcquired = true;
            target = new Socket();
            target.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), route.targetPort), 10_000);
            target.setTcpNoDelay(true);
            KryptSocket stream = encrypted;
            clientRun(() -> { stream.getOutputStream().write(0); stream.getOutputStream().flush(); });
            bridge(target, stream);
        } catch (Exception error) {
            try { reject(encrypted, 3); } catch (Exception ignored) {}
            ReverseForward.message("Incoming forwarded connection from " + encrypted.peer() + " failed: " + useful(error));
        } finally {
            if (target != null) try { target.close(); } catch (IOException ignored) {}
            if (slotAcquired) route.slots.release();
            closeEncrypted(encrypted);
        }
    }

    private void reject(KryptSocket socket, int code) throws Exception {
        clientRun(() -> { socket.getOutputStream().write(code); socket.getOutputStream().flush(); });
    }

    private void bridge(Socket tcp, KryptSocket encrypted) throws Exception {
        AtomicBoolean done = new AtomicBoolean();
        CompletableFuture<Void> tcpToEncrypted = CompletableFuture.runAsync(() -> {
            try (InputStream input = new BufferedInputStream(tcp.getInputStream())) {
                byte[] buffer = new byte[COPY_BUFFER];
                int count;
                while (!done.get() && (count = input.read(buffer)) >= 0) {
                    if (count == 0) continue;
                    byte[] chunk = java.util.Arrays.copyOf(buffer, count);
                    clientRun(() -> { encrypted.getOutputStream().write(chunk); encrypted.getOutputStream().flush(); });
                }
            } catch (Exception error) {
                if (!done.get()) throw new java.util.concurrent.CompletionException(error);
            }
        }, workers);
        CompletableFuture<Void> encryptedToTcp = CompletableFuture.runAsync(() -> {
            try (InputStream input = new BufferedInputStream(encrypted.getInputStream());
                 OutputStream output = new BufferedOutputStream(tcp.getOutputStream())) {
                byte[] buffer = new byte[COPY_BUFFER];
                int count;
                while (!done.get() && (count = input.read(buffer)) >= 0) {
                    if (count == 0) continue;
                    output.write(buffer, 0, count);
                    output.flush();
                }
            } catch (IOException error) {
                if (!done.get()) throw new java.util.concurrent.CompletionException(error);
            }
        }, workers);
        try {
            CompletableFuture.anyOf(tcpToEncrypted, encryptedToTcp).join();
        } finally {
            done.set(true);
            try { tcp.close(); } catch (IOException ignored) {}
            closeEncrypted(encrypted);
        }
    }

    private void sendControl(String player, ControlPacket packet, String queuedMessage) {
        DataTransfer transfer = Krypt04McgApi.send(player, ReverseForward.CONTROL_CHANNEL, packet.encode());
        ReverseForward.message(queuedMessage + " Transfer " + shortId(transfer.transferId()) + " queued.");
        transfer.whenComplete(result -> {
            if (!"DELIVERED".equals(result.status().name())) {
                ReverseForward.message("Control transfer " + shortId(result.transferId()) + " finished with " + result.status() + ".");
            }
        });
    }

    private void save() throws IOException {
        List<RouteStore.MappingData> mappingData = mappings.values().stream().map(mapping ->
                new RouteStore.MappingData(mapping.routeId, mapping.name, mapping.listenPort, mapping.targetPort,
                        mapping.peer, mapping.accepted, mapping.enabled)).toList();
        List<RouteStore.AllowedData> allowedData = allowedRoutes.values().stream().map(route ->
                new RouteStore.AllowedData(route.routeId, route.name, route.peer, route.targetPort)).toList();
        store.save(mappingData, allowedData);
    }

    private void expireInvitations() {
        Instant cutoff = Instant.now().minus(INVITATION_TTL);
        invitations.values().removeIf(invitation -> invitation.receivedAt.isBefore(cutoff));
    }

    private Invitation findInvitation(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        List<Invitation> matches = invitations.values().stream()
                .filter(invitation -> invitation.invitationId.toString().toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private AllowedRoute findAllowed(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        List<AllowedRoute> matches = allowedRoutes.values().stream()
                .filter(route -> route.routeId.toString().toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private Mapping mappingByRoute(UUID routeId) {
        return mappings.values().stream().filter(mapping -> mapping.routeId.equals(routeId)).findFirst().orElse(null);
    }

    private static String state(Mapping mapping) {
        if (!mapping.accepted) return mapping.peer.isEmpty() ? "unassigned" : "pending";
        return mapping.enabled ? "enabled" : "stopped";
    }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    private static String shortId(UUID id) { return id.toString().substring(0, 8); }
    private static boolean validPlayer(String player) { return player != null && player.matches("[A-Za-z0-9_]{1,16}"); }
    private static boolean self(String player) {
        Minecraft client = Minecraft.getInstance();
        return client.getUser() != null && client.getUser().getName().equalsIgnoreCase(player);
    }
    private static int fail(String text) { ReverseForward.message(text); return 0; }
    private static String useful(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static void clientRun(IoTask task) throws Exception {
        clientCall(() -> { task.run(); return null; });
    }

    private static <T> T clientCall(ThrowingSupplier<T> task) throws Exception {
        Minecraft client = Minecraft.getInstance();
        if (client.isSameThread()) return task.get();
        CompletableFuture<T> future = new CompletableFuture<>();
        client.execute(() -> {
            try { future.complete(task.get()); }
            catch (Throwable error) { future.completeExceptionally(error); }
        });
        return future.get(30, TimeUnit.SECONDS);
    }

    private static void closeEncrypted(KryptSocket socket) {
        if (socket == null || socket.isClosed()) return;
        Minecraft.getInstance().execute(() -> {
            try { socket.close(); } catch (RuntimeException ignored) {}
        });
    }

    @FunctionalInterface private interface IoTask { void run() throws Exception; }
    @FunctionalInterface private interface ThrowingSupplier<T> { T get() throws Exception; }

    private static final class Mapping {
        final UUID routeId;
        final String name;
        final int listenPort;
        final int targetPort;
        volatile String peer;
        volatile boolean accepted;
        volatile boolean enabled;
        Mapping(UUID routeId, String name, int listenPort, int targetPort, String peer, boolean accepted, boolean enabled) {
            this.routeId = routeId; this.name = name; this.listenPort = listenPort; this.targetPort = targetPort;
            this.peer = peer; this.accepted = accepted; this.enabled = enabled;
        }
    }

    private static final class AllowedRoute {
        final UUID routeId;
        final String name;
        final String peer;
        final int targetPort;
        final Semaphore slots = new Semaphore(MAX_CONNECTIONS_PER_ROUTE);
        AllowedRoute(UUID routeId, String name, String peer, int targetPort) {
            this.routeId = routeId; this.name = name; this.peer = peer; this.targetPort = targetPort;
        }
    }

    private record Invitation(UUID invitationId, UUID routeId, String name, String sender,
                              int listenPort, int targetPort, Instant receivedAt) {
        ControlPacket packet(ControlPacket.Type type) {
            return new ControlPacket(type, invitationId, routeId, name, listenPort, targetPort);
        }
    }

    private static final class Listener {
        final Mapping mapping;
        final ServerSocket server;
        final Semaphore slots = new Semaphore(MAX_CONNECTIONS_PER_ROUTE);
        final AtomicBoolean closed = new AtomicBoolean();
        Listener(Mapping mapping, ServerSocket server) { this.mapping = mapping; this.server = server; }
        void close() {
            if (closed.compareAndSet(false, true)) try { server.close(); } catch (IOException ignored) {}
        }
    }
}
