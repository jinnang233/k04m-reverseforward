package dev.k04mreverseforward;

import dev.krypt04mcg.api.Krypt04McgApi;
import dev.krypt04mcg.api.KryptSocket;
import net.minecraft.client.Minecraft;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.time.Duration;
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
import java.util.function.LongSupplier;

final class ForwardingManager {
    private static final long STREAM_MAGIC = 0x4B30344D5354524DL; // K04MSTRM
    private static final int STREAM_VERSION = 2;
    private static final int MAX_CONNECTIONS_PER_ROUTE = 8;
    private static final Duration INVITATION_TTL = Duration.ofMinutes(2);
    private static final int MAX_PENDING_INVITATIONS = 64;
    private static final int MAX_PENDING_INVITATIONS_PER_PEER = 4;

    private final Map<String, Mapping> mappings = new ConcurrentHashMap<>();
    private final Map<UUID, AllowedRoute> allowedRoutes = new ConcurrentHashMap<>();
    private final Map<UUID, Invitation> invitations = new ConcurrentHashMap<>();
    private final Map<UUID, Listener> listeners = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final LongSupplier clock;
    private RouteStore store;
    private boolean connected;

    ForwardingManager() { this(System::nanoTime); }

    ForwardingManager(LongSupplier clock) { this.clock = java.util.Objects.requireNonNull(clock); }

    void load(Path configDirectory) {
        store = new RouteStore(configDirectory.resolve("k04m-reverse-forward"));
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
            if (mappings.size() >= RouteStore.MAX_ROUTES) return fail("Too many stored routes");
            if (mappings.values().stream().anyMatch(mapping -> mapping.listenPort == listenPort)) {
                return fail("Listen port " + listenPort + " is already registered.");
            }
            Mapping mapping = new Mapping(UUID.randomUUID(), name, listenPort, targetPort, "", false, true);
            mappings.put(key(name), mapping);
            try { save(); }
            catch (IOException error) { mappings.remove(key(name), mapping); throw error; }
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
        mapping.pendingInvitation = invitationId;
        ControlPacket packet = new ControlPacket(ControlPacket.Type.INVITE, invitationId, mapping.routeId,
                mapping.name, mapping.listenPort, mapping.targetPort);
        try {
            save();
            sendControl(player, packet, "Invitation " + shortId(invitationId) + " sent to " + player + ".");
            return 1;
        } catch (RuntimeException | IOException error) {
            mapping.pendingInvitation = null;
            return fail("Could not send invitation: " + error.getMessage());
        }
    }

    int accept(String idText) {
        return accept(idText, null);
    }

    int accept(String idText, int targetPort) {
        return accept(idText, Integer.valueOf(targetPort));
    }

    private int accept(String idText, Integer targetPortOverride) {
        Invitation invitation = findInvitation(idText);
        if (invitation == null) return fail("Unknown or ambiguous invitation ID.");
        int targetPort = targetPortOverride == null ? invitation.targetPort : targetPortOverride;
        try {
            ControlPacket.validatePort(targetPort);
        } catch (IllegalArgumentException error) {
            return fail(error.getMessage());
        }
        AllowedRoute route = new AllowedRoute(invitation.routeId, invitation.name, invitation.sender, targetPort);
        AllowedRoute previous = allowedRoutes.get(route.routeId);
        if (previous == null && allowedRoutes.size() >= RouteStore.MAX_ROUTES) return fail("Too many stored routes");
        allowedRoutes.put(route.routeId, route);
        try {
            save();
        } catch (IOException error) {
            if (previous == null) allowedRoutes.remove(route.routeId, route);
            else allowedRoutes.put(route.routeId, previous);
            route.connections.close();
            return fail("Could not save acceptance: " + error.getMessage());
        }
        invitations.remove(invitation.invitationId);
        if (previous != null) previous.connections.close();
        try {
            sendControl(invitation.sender, invitation.packet(ControlPacket.Type.ACCEPT, targetPort),
                    "Accepted '" + invitation.name + "' from " + invitation.sender + ". Forwarded connections will reach 127.0.0.1:"
                            + targetPort + ".");
            return 1;
        } catch (RuntimeException error) {
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
            if (connected) return startListener(mapping) ? 1 : 0;
            ReverseForward.message("Route '" + name + "' will listen after joining a server.");
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
        route.connections.close();
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
                + invitation.sender + " requests '" + invitation.name + "' -> proposed 127.0.0.1:" + invitation.targetPort
                + ". Accept with /k04mrf accept " + shortId(invitation.invitationId)
                + " [targetPort]"));
        return 1;
    }

    void receiveControlSocket(KryptSocket socket) {
        socket.close(); // Control streams carry one packet, terminated by authenticated EOF.
        workers.submit(() -> {
            try {
                byte[] bytes = socket.getInputStream().readNBytes(ControlPacket.MAX_PACKET + 1);
                ControlPacket.decode(bytes); // Reject oversized/truncated streams before dispatch.
                clientCall(() -> { receiveControl(socket.peer(), bytes); return null; });
            } catch (Exception error) {
                ReverseForward.message("Rejected control stream from " + socket.peer() + ": " + useful(error));
            } finally {
                closeEncrypted(socket);
            }
        });
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
        allowedRoutes.replaceAll((id, route) -> {
            route.connections.close();
            return new AllowedRoute(route.routeId, route.name, route.peer, route.targetPort);
        });
        mappings.values().forEach(mapping -> mapping.pendingInvitation = null);
        invitations.clear();
    }

    private void receiveInvite(String sender, ControlPacket packet) {
        if (!validPlayer(sender)) return;
        expireInvitations();
        // An acceptance token must keep the sender and port originally shown to the user.
        // Retransmissions neither replace the offer nor refresh its deadline.
        if (invitations.containsKey(packet.invitationId())) return;
        AllowedRoute existing = allowedRoutes.get(packet.routeId());
        if (existing != null && !existing.peer.equalsIgnoreCase(sender)) {
            ReverseForward.message("Rejected invitation from " + sender + ": its route ID conflicts with an existing authorization.");
            return;
        }
        if (invitations.size() >= MAX_PENDING_INVITATIONS
                || invitations.values().stream().filter(invitation -> invitation.sender.equalsIgnoreCase(sender))
                        .count() >= MAX_PENDING_INVITATIONS_PER_PEER) return;
        Invitation invitation = new Invitation(packet.invitationId(), packet.routeId(), packet.name(), sender,
                packet.listenPort(), packet.targetPort(), clock.getAsLong());
        invitations.put(invitation.invitationId, invitation);
        ReverseForward.message(sender + " invites you to route '" + packet.name() + "' to your 127.0.0.1:"
                + packet.targetPort() + " (proposed). Accept or choose another local port: /k04mrf accept "
                + shortId(packet.invitationId()) + " [targetPort]"
                + "  Deny: /k04mrf deny " + shortId(packet.invitationId()));
    }

    private void receiveAccept(String sender, ControlPacket packet) throws IOException {
        Mapping mapping = mappingByRoute(packet.routeId());
        if (!matchesPending(mapping, sender, packet)) return;
        mapping.pendingInvitation = null;
        mapping.targetPort = packet.targetPort();
        mapping.accepted = true;
        save();
        ReverseForward.message(sender + " accepted route '" + mapping.name + "' with target 127.0.0.1:"
                + mapping.targetPort + ".");
        if (mapping.enabled) {
            if (connected) startListener(mapping);
            else ReverseForward.message("Route '" + mapping.name + "' will listen after joining a server.");
        }
    }

    private void receiveReject(String sender, ControlPacket packet) throws IOException {
        Mapping mapping = mappingByRoute(packet.routeId());
        if (!matchesPending(mapping, sender, packet)) return;
        mapping.pendingInvitation = null;
        mapping.accepted = false;
        save();
        stopListener(mapping.routeId);
        ReverseForward.message(sender + " denied route '" + mapping.name + "'.");
    }

    private void receiveRevoke(String sender, ControlPacket packet) throws IOException {
        boolean changed = false;
        Mapping mapping = mappingByRoute(packet.routeId());
        if (mapping != null && mapping.peer.equalsIgnoreCase(sender)
                && (mapping.accepted || mapping.pendingInvitation != null)) {
            mapping.accepted = false;
            mapping.pendingInvitation = null;
            stopListener(mapping.routeId);
            changed = true;
            ReverseForward.message(sender + " revoked route '" + mapping.name + "'.");
        }
        AllowedRoute allowed = allowedRoutes.get(packet.routeId());
        if (allowed != null && allowed.peer.equalsIgnoreCase(sender)) {
            allowedRoutes.remove(packet.routeId());
            allowed.connections.close();
            changed = true;
            ReverseForward.message(sender + " removed route '" + allowed.name + "'.");
        }
        // Unrelated or repeated remote packets must not trigger synchronous disk writes.
        if (changed) save();
    }

    private boolean matchesPending(Mapping mapping, String sender, ControlPacket packet) {
        return mapping != null && mapping.peer.equalsIgnoreCase(sender)
                && mapping.name.equals(packet.name()) && mapping.listenPort == packet.listenPort()
                && packet.invitationId().equals(mapping.pendingInvitation);
    }

    private boolean startListener(Mapping mapping) {
        if (!connected || !mapping.enabled || !mapping.accepted) return false;
        if (listeners.containsKey(mapping.routeId)) return true;
        try {
            ServerSocket server = LoopbackTcp.listen(mapping.listenPort);
            Listener listener = new Listener(mapping, server);
            listeners.put(mapping.routeId, listener);
            workers.submit(() -> acceptLoop(listener));
            ReverseForward.message("Listening for '" + mapping.name + "' on 127.0.0.1:" + mapping.listenPort + ".");
            return true;
        } catch (IOException error) {
            ReverseForward.message("Could not listen for '" + mapping.name + "' on 127.0.0.1:" + mapping.listenPort
                    + ": " + error.getMessage() + ". Free the port, then retry /k04mrf start " + mapping.name + ".");
            return false;
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
                    try { openForward(listener, local); }
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

    private void openForward(Listener listener, Socket local) {
        Mapping mapping = listener.mapping;
        Connection connection = new Connection();
        connection.attach(local);
        try (local) {
            if (!listener.connections.add(connection)) return;
            KryptSocket stream = clientCall(() -> {
                if (connection.closed()) throw new IOException("Route closed");
                KryptSocket opened = Krypt04McgApi.connect(mapping.peer, ReverseForward.SOCKET_CHANNEL);
                connection.attach(opened);
                return opened;
            });
            DataOutputStream output = new DataOutputStream(stream.getOutputStream());
            output.writeLong(STREAM_MAGIC);
            output.writeByte(STREAM_VERSION);
            output.writeLong(mapping.routeId.getMostSignificantBits());
            output.writeLong(mapping.routeId.getLeastSignificantBits());
            output.flush();
            int response = stream.getInputStream().read();
            if (response != 0) throw new IOException(response < 0 ? "Peer closed before accepting" : "Peer rejected route (code " + response + ")");
            bridge(local, stream);
        } catch (Exception error) {
            ReverseForward.message("Connection on '" + mapping.name + "' closed: " + useful(error));
        } finally {
            connection.close();
            listener.connections.remove(connection);
        }
    }

    private void handleIncomingSocket(KryptSocket encrypted) {
        Socket target = null;
        AllowedRoute route = null;
        boolean slotAcquired = false;
        Connection connection = new Connection();
        connection.attach(encrypted);
        ConnectionGroup group = null;
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
            group = route.connections;
            if (!group.add(connection)) return;
            target = new Socket();
            connection.attach(target);
            target.connect(LoopbackTcp.address(route.targetPort), 10_000);
            target.setTcpNoDelay(true);
            KryptSocket stream = encrypted;
            stream.getOutputStream().write(0);
            stream.getOutputStream().flush();
            bridge(target, stream);
        } catch (Exception error) {
            try { reject(encrypted, 3); } catch (Exception ignored) {}
            ReverseForward.message("Incoming forwarded connection from " + encrypted.peer() + " failed: " + useful(error));
        } finally {
            connection.close();
            if (group != null) group.remove(connection);
            if (slotAcquired) route.slots.release();
            closeEncrypted(encrypted);
        }
    }

    private void reject(KryptSocket socket, int code) throws Exception {
        socket.getOutputStream().write(code);
        socket.getOutputStream().flush();
        KryptStreams.finish(socket);
    }

    private void bridge(Socket tcp, KryptSocket encrypted) throws Exception {
        try {
            TunnelBridge.run(tcp, encrypted.getInputStream(),
                    frame -> KryptStreams.write(encrypted, frame), workers);
            KryptStreams.finish(encrypted);
        } finally {
            closeEncrypted(encrypted);
        }
    }
    private void sendControl(String player, ControlPacket packet, String queuedMessage) {
        Krypt04McgApi.send(player, ReverseForward.CONTROL_CHANNEL, packet.encode());
        ReverseForward.message(queuedMessage + " Queued locally; no delivery receipt is available.");
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
        long now = clock.getAsLong();
        invitations.values().removeIf(invitation -> now - invitation.receivedAt >= INVITATION_TTL.toNanos());
    }

    private Invitation findInvitation(String text) {
        expireInvitations();
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

    private String state(Mapping mapping) {
        if (!mapping.accepted) return mapping.peer.isEmpty() ? "unassigned" : "pending";
        if (!mapping.enabled) return "stopped";
        if (!connected) return "waiting for server connection";
        return listeners.containsKey(mapping.routeId) ? "listening" : "not listening; retry /k04mrf start " + mapping.name;
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
        // Input close cancels the stream and wakes blocked readers.
        try { socket.getInputStream().close(); } catch (IOException | RuntimeException ignored) {}
        try { socket.close(); } catch (RuntimeException ignored) {}
    }

    @FunctionalInterface private interface ThrowingSupplier<T> { T get() throws Exception; }

    // Closing a generation also rejects workers that were admitted before revocation
    // but have not attached their sockets yet.
    private static final class ConnectionGroup {
        private final java.util.Set<Connection> active = new java.util.HashSet<>();
        private boolean closed;
        synchronized boolean add(Connection connection) {
            if (closed) { connection.close(); return false; }
            active.add(connection);
            return true;
        }
        synchronized void remove(Connection connection) { active.remove(connection); }
        synchronized void close() {
            closed = true;
            active.forEach(Connection::close);
            active.clear();
        }
    }

    private static final class Connection {
        private Socket tcp;
        private KryptSocket encrypted;
        private boolean closed;
        synchronized boolean closed() { return closed; }
        synchronized void attach(Socket socket) {
            tcp = socket;
            if (closed) closeTcp();
        }
        synchronized void attach(KryptSocket socket) {
            encrypted = socket;
            if (closed) closeEncrypted(socket);
        }
        synchronized void close() {
            closed = true;
            closeTcp();
            closeEncrypted(encrypted);
        }
        private void closeTcp() {
            if (tcp != null) try { tcp.close(); } catch (IOException ignored) {}
        }
    }

    private static final class Mapping {
        final UUID routeId;
        final String name;
        final int listenPort;
        volatile int targetPort;
        volatile String peer;
        volatile boolean accepted;
        volatile boolean enabled;
        UUID pendingInvitation;
        Mapping(UUID routeId, String name, int listenPort, int targetPort, String peer, boolean accepted, boolean enabled) {
            this.routeId = routeId; this.name = name; this.listenPort = listenPort; this.targetPort = targetPort;
            this.peer = peer; this.accepted = accepted; this.enabled = enabled;
        }
    }

    private static final class AllowedRoute {
        final ConnectionGroup connections = new ConnectionGroup();
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
                              int listenPort, int targetPort, long receivedAt) {
        ControlPacket packet(ControlPacket.Type type) {
            return new ControlPacket(type, invitationId, routeId, name, listenPort, targetPort);
        }
        ControlPacket packet(ControlPacket.Type type, int selectedTargetPort) {
            return new ControlPacket(type, invitationId, routeId, name, listenPort, selectedTargetPort);
        }
    }

    private static final class Listener {
        final ConnectionGroup connections = new ConnectionGroup();
        final Mapping mapping;
        final ServerSocket server;
        final Semaphore slots = new Semaphore(MAX_CONNECTIONS_PER_ROUTE);
        final AtomicBoolean closed = new AtomicBoolean();
        Listener(Mapping mapping, ServerSocket server) { this.mapping = mapping; this.server = server; }
        void close() {
            connections.close();
            if (closed.compareAndSet(false, true)) try { server.close(); } catch (IOException ignored) {}
        }
    }
}
