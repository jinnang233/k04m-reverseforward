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
    private static final long STREAM_MAGIC = 0x4B30344D5354524DL;
    // K04MSTRM
    private static final int STREAM_VERSION = 2;
    private static final int MAX_CONNECTIONS_PER_ROUTE = 8;
    private static final Duration INVITATION_TTL = Duration.ofMinutes(2);
    private static final int MAX_PENDING_INVITATIONS = 64;
    private static final int MAX_PENDING_INVITATIONS_PER_PEER = 4;
    private static final long REQUEST_TTL_NANOS = Duration.ofSeconds(30).toNanos();
    private static final int MAX_PENDING_READS = 64;
    private static final int MAX_PENDING_READS_PER_PEER = 16;

    private final Map<String, Mapping> mappings = new ConcurrentHashMap<>();
    private final Map<UUID, AllowedRoute> allowedRoutes = new ConcurrentHashMap<>();
    private final Map<UUID, Invitation> invitations = new ConcurrentHashMap<>();
    private final Map<UUID, Listener> listeners = new ConcurrentHashMap<>();
    private final Map<Connection, PendingRead> pendingReads = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final LongSupplier clock;
    private RouteStore store;
    private boolean connected;

    /**
     * Creates a forwarding manager with the supplied dependencies and initial state.
     */
    ForwardingManager() { this(System::nanoTime); }

    /**
     * Creates a forwarding manager with the supplied dependencies and initial state.
     *
     * @param clock the time source used for deadline or expiry checks
     */
    ForwardingManager(LongSupplier clock) { this.clock = java.util.Objects.requireNonNull(clock); }

    /**
     * Performs the load operation for the reverse-forward authorization state.
     *
     * @param configDirectory the directory containing the persisted configuration
     */
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

    /**
     * Registers the supported callbacks and channels for the reverse-forward authorization state.
     *
     * @param name the name supplied to this operation
     * @param listenPort the listen port supplied to this operation
     * @param targetPort the target port supplied to this operation
     * @return the result described above
     */
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

    /**
     * Performs the invite operation for the reverse-forward authorization state.
     *
     * @param name the name supplied to this operation
     * @param player the player supplied to this operation
     * @return the result described above
     */
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

    /**
     * Performs the accept operation for the reverse-forward authorization state.
     *
     * @param idText the id text supplied to this operation
     * @return the result described above
     */
    int accept(String idText) {
        return accept(idText, null);
    }

    /**
     * Performs the accept operation for the reverse-forward authorization state.
     *
     * @param idText the id text supplied to this operation
     * @param targetPort the target port supplied to this operation
     * @return the result described above
     */
    int accept(String idText, int targetPort) {
        return accept(idText, Integer.valueOf(targetPort));
    }

    /**
     * Performs the accept operation for the reverse-forward authorization state.
     *
     * @param idText the id text supplied to this operation
     * @param targetPortOverride the target port override supplied to this operation
     * @return the result described above
     */
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

    /**
     * Performs the deny operation for the reverse-forward authorization state.
     *
     * @param idText the id text supplied to this operation
     * @return the result described above
     */
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

    /**
     * Performs the start operation for the reverse-forward authorization state.
     *
     * @param name the name supplied to this operation
     * @return the result described above
     */
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

    /**
     * Performs the stop operation for the reverse-forward authorization state.
     *
     * @param name the name supplied to this operation
     * @return the result described above
     */
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

    /**
     * Removes the selected entry in the reverse-forward authorization state.
     *
     * @param name the name supplied to this operation
     * @return the result described above
     */
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

    /**
     * Performs the revoke operation for the reverse-forward authorization state.
     *
     * @param routeText the route text supplied to this operation
     * @return the result described above
     */
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

    /**
     * Performs the list operation for the reverse-forward authorization state.
     *
     * @return the result described above
     */
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

    /**
     * Performs the list invitations operation for the reverse-forward authorization state.
     *
     * @return the result described above
     */
    int listInvitations() {
        expireInvitations();
        if (invitations.isEmpty()) ReverseForward.message("No pending invitations.");
        invitations.values().forEach(invitation -> ReverseForward.message(shortId(invitation.invitationId) + ": "
                + invitation.sender + " requests '" + invitation.name + "' -> proposed 127.0.0.1:" + invitation.targetPort
                + ". Accept with /k04mrf accept " + shortId(invitation.invitationId)
                + " [targetPort]"));
        return 1;
    }

    /**
     * Performs the receive control socket operation for the reverse-forward authorization state.
     *
     * @param socket the encrypted or TCP socket participating in the operation
     */
    void receiveControlSocket(KryptSocket socket) {
        PendingRead pending = admit(socket);
        if (pending == null) return;
        socket.close();
        // Control streams carry one packet, terminated by authenticated EOF.
        submit(pending, () -> {
            try {
                byte[] bytes = socket.getInputStream().readNBytes(ControlPacket.MAX_PACKET + 1);
                ControlPacket.decode(bytes);
                // Reject oversized/truncated streams before dispatch.
                clientCall(() -> {
                    if (pending.complete(clock.getAsLong())) receiveControl(socket.peer(), bytes);
                    return null;
                });
            } catch (Exception error) {
                if (!pending.connection.closed())
                    ReverseForward.message("Rejected control stream from " + socket.peer() + ": " + useful(error));
            } finally {
                pending.connection.close();
                pendingReads.remove(pending.connection, pending);
            }
        });
    }

    /**
     * Performs the receive control operation for the reverse-forward authorization state.
     *
     * @param sender the sender or source associated with this operation
     * @param bytes the bytes supplied to this operation
     */
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

    /**
     * Performs the receive socket operation for the reverse-forward authorization state.
     *
     * @param socket the encrypted or TCP socket participating in the operation
     */
    void receiveSocket(KryptSocket socket) {
        PendingRead pending = admit(socket);
        if (pending != null) submit(pending, () -> handleIncomingSocket(socket, pending));
    }

    /**
     * Admits a bounded pending control/header read per authenticated KryptSocket peer with a fixed
     * monotonic lifetime. New reads are rejected at capacity and cannot evict active requests; admission
     * is not permission to connect to a local TCP target.
     *
     * @param socket the encrypted or TCP socket participating in the operation
     * @return the result described above
     */
    private synchronized PendingRead admit(KryptSocket socket) {
        if (pendingReads.size() >= MAX_PENDING_READS
                || pendingReads.values().stream().filter(p -> p.peer.equalsIgnoreCase(socket.peer()))
                        .count() >= MAX_PENDING_READS_PER_PEER) {
            closeEncrypted(socket);
            return null;
        }
        var connection = new Connection();
        connection.attach(socket);
        var pending = new PendingRead(connection, socket.peer(), clock.getAsLong());
        pendingReads.put(connection, pending);
        return pending;
    }

    /**
     * Performs the submit operation for the reverse-forward authorization state.
     *
     * @param pending the pending supplied to this operation
     * @param task the task supplied to this operation
     */
    private void submit(PendingRead pending, Runnable task) {
        try { workers.submit(task); }
        catch (RuntimeException rejected) {
            pending.connection.close();
            pendingReads.remove(pending.connection, pending);
        }
    }

    /**
     * Processes the next scheduled work and lifecycle checks for the reverse-forward authorization state.
     */
    void tick() {
        long now = clock.getAsLong();
        pendingReads.forEach((connection, pending) -> {
            if (pending.expire(now)) pendingReads.remove(connection, pending);
        });
        boolean nowConnected = Minecraft.getInstance().player != null && Minecraft.getInstance().getConnection() != null;
        if (nowConnected && !connected) {
            connected = true;
            mappings.values().stream().filter(mapping -> mapping.accepted && mapping.enabled).forEach(this::startListener);
        } else if (!nowConnected && connected) {
            disconnect();
        }
        expireInvitations();
    }

    /**
     * Closes pending reads, listeners and active tunnel connections and clears connection-bound invitation
     * state. Late worker/client callbacks must not resurrect an authorization after cleanup.
     */
    void disconnect() {
        connected = false;
        pendingReads.values().forEach(pending -> pending.connection.close());
        pendingReads.clear();
        listeners.values().forEach(Listener::close);
        listeners.clear();
        allowedRoutes.replaceAll((id, route) -> {
            route.connections.close();
            return new AllowedRoute(route.routeId, route.name, route.peer, route.targetPort);
        });
        mappings.values().forEach(mapping -> mapping.pendingInvitation = null);
        invitations.clear();
    }

    /**
     * Admits an authenticated-peer invitation under fixed global/per-peer limits and an immutable
     * first-admission deadline. Retransmissions cannot replace the original sender/target or extend the
     * offer lifetime; user acceptance is still required.
     *
     * @param sender the sender or source associated with this operation
     * @param packet the packet being serialized, authenticated or processed
     */
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

    /**
     * Accepts only a response that matches the pending invitation, route and peer, then persists mapping
     * authorization before the implemented listener lifecycle continues.
     *
     * @param sender the sender or source associated with this operation
     * @param packet the packet being serialized, authenticated or processed
     * @throws IOException if input/output, stored-state validation or resource handling fails
     */
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

    /**
     * Performs the receive reject operation for the reverse-forward authorization state.
     *
     * @param sender the sender or source associated with this operation
     * @param packet the packet being serialized, authenticated or processed
     * @throws IOException if input/output, stored-state validation or resource handling fails
     */
    private void receiveReject(String sender, ControlPacket packet) throws IOException {
        Mapping mapping = mappingByRoute(packet.routeId());
        if (!matchesPending(mapping, sender, packet)) return;
        mapping.pendingInvitation = null;
        mapping.accepted = false;
        save();
        stopListener(mapping.routeId);
        ReverseForward.message(sender + " denied route '" + mapping.name + "'.");
    }

    /**
     * Applies revocation only to matching active authorization and closes associated listeners/connections
     * as implemented. Unrelated or repeated requests must not cause unnecessary persistent writes or alter
     * another route.
     *
     * @param sender the sender or source associated with this operation
     * @param packet the packet being serialized, authenticated or processed
     * @throws IOException if input/output, stored-state validation or resource handling fails
     */
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

    /**
     * Checks peer, route and invitation identity against the exact pending mapping rather than accepting a
     * same-name route or an unrelated control response.
     *
     * @param mapping the mapping supplied to this operation
     * @param sender the sender or source associated with this operation
     * @param packet the packet being serialized, authenticated or processed
     * @return whether the condition or operation described above succeeds
     */
    private boolean matchesPending(Mapping mapping, String sender, ControlPacket packet) {
        return mapping != null && mapping.peer.equalsIgnoreCase(sender)
                && mapping.name.equals(packet.name()) && mapping.listenPort == packet.listenPort()
                && packet.invitationId().equals(mapping.pendingInvitation);
    }

    /**
     * Returns the recorded false for the reverse-forward authorization state.
     *
     * @param mapping the mapping supplied to this operation
     * @return whether the condition or operation described above succeeds
     */
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

    /**
     * Performs the stop listener operation for the reverse-forward authorization state.
     *
     * @param routeId the route id supplied to this operation
     */
    private void stopListener(UUID routeId) {
        Listener listener = listeners.remove(routeId);
        if (listener != null) listener.close();
    }

    /**
     * Performs the accept loop operation for the reverse-forward authorization state.
     *
     * @param listener the listener supplied to this operation
     */
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

    /**
     * Opens an encrypted API stream for the accepted mapping, exchanges the tunnel authorization
     * header/result and bridges the connected loopback socket. Errors and disconnects close both
     * resources; stream creation alone does not authorize a different route or port.
     *
     * @param listener the listener supplied to this operation
     * @param local the local supplied to this operation
     */
    private void openForward(Listener listener, Socket local) {
        Mapping mapping = listener.mapping;
        Connection connection = new Connection();
        connection.attach(local);
        try (local) {
            if (!listener.connections.add(connection)) return;
            /*
             * Delegates session/stream cryptography to the core API. Route and invitation authorization still
             * belong to this extension; an encrypted stream handle must match the approved peer and route before
             * it can expose a local TCP endpoint.
             */
            KryptSocket stream = clientCall(() -> {
                if (connection.closed()) throw new IOException("Route closed");
                /*
                 * Delegates session/stream cryptography to the core API. Route and invitation authorization still
                 * belong to this extension; an encrypted stream handle must match the approved peer and route before
                 * it can expose a local TCP endpoint.
                 */
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

    /**
     * Reads the unbuffered versioned tunnel header without consuming application bytes ahead, checks route
     * UUID and authenticated stream peer, and acquires a per-route connection slot. Pending
     * expiry/completion is resolved before connecting only to the authorized IPv4 loopback target.
     * Ownership is tracked through setup so disconnect/revocation can close the connection without an
     * untracked gap.
     *
     * @param encrypted the encrypted supplied to this operation
     * @param pending the pending supplied to this operation
     */
    private void handleIncomingSocket(KryptSocket encrypted, PendingRead pending) {
        Socket target = null;
        AllowedRoute route = null;
        boolean slotAcquired = false;
        Connection connection = pending.connection;
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
            if (pending.expire(clock.getAsLong())) return;
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
            if (!pending.complete(clock.getAsLong())) return;
            // Transfer ownership only after the authorization group tracks this connection.
            // disconnect() can therefore close it throughout setup, without a tracking gap.
            pendingReads.remove(connection, pending);
            if (connection.closed()) return;
            target = new Socket();
            connection.attach(target);
            /*
             * Uses the explicitly constructed IPv4 loopback endpoint rather than accepting a remote hostname from
             * peer input. Port grammar and route authorization are checked separately; successful socket creation
             * is not permission to connect elsewhere.
             */
            target.connect(LoopbackTcp.address(route.targetPort), 10_000);
            target.setTcpNoDelay(true);
            KryptSocket stream = encrypted;
            stream.getOutputStream().write(0);
            stream.getOutputStream().flush();
            bridge(target, stream);
        } catch (Exception error) {
            if (!connection.closed()) {
                try { reject(encrypted, 3); } catch (Exception ignored) {}
                ReverseForward.message("Incoming forwarded connection from " + encrypted.peer() + " failed: " + useful(error));
            }
        } finally {
            connection.close();
            pendingReads.remove(connection, pending);
            if (group != null) group.remove(connection);
            if (slotAcquired) route.slots.release();
            closeEncrypted(encrypted);
        }
    }

    /**
     * Performs the reject operation for the reverse-forward authorization state.
     *
     * @param socket the encrypted or TCP socket participating in the operation
     * @param code the code supplied to this operation
     * @throws Exception if the delegated operation cannot complete successfully
     */
    private void reject(KryptSocket socket, int code) throws Exception {
        socket.getOutputStream().write(code);
        socket.getOutputStream().flush();
        KryptStreams.finish(socket);
    }

    /**
     * Bridges an authorized loopback TCP connection with an encrypted API stream through the tunnel copy
     * workers. TCP half-close and authenticated stream EOF are coordinated by TunnelBridge; this layer
     * relies on the core API for cryptographic protection.
     *
     * @param tcp the tcp supplied to this operation
     * @param encrypted the encrypted supplied to this operation
     * @throws Exception if the delegated operation cannot complete successfully
     */
    private void bridge(Socket tcp, KryptSocket encrypted) throws Exception {
        try {
            TunnelBridge.run(tcp, encrypted.getInputStream(),
                    frame -> KryptStreams.write(encrypted, frame), workers);
            KryptStreams.finish(encrypted);
        } finally {
            closeEncrypted(encrypted);
        }
    }
    /**
     * Submits control through the reverse-forward authorization state path. Local submission does not by
     * itself acknowledge remote receipt.
     *
     * @param player the player supplied to this operation
     * @param packet the packet being serialized, authenticated or processed
     * @param queuedMessage the queued message supplied to this operation
     */
    private void sendControl(String player, ControlPacket packet, String queuedMessage) {
        Krypt04McgApi.send(player, ReverseForward.CONTROL_CHANNEL, packet.encode());
        ReverseForward.message(queuedMessage + " Queued locally; no delivery receipt is available.");
    }

    /**
     * Performs the save operation for the reverse-forward authorization state.
     *
     * @throws IOException if input/output, stored-state validation or resource handling fails
     */
    private void save() throws IOException {
        List<RouteStore.MappingData> mappingData = mappings.values().stream().map(mapping ->
                new RouteStore.MappingData(mapping.routeId, mapping.name, mapping.listenPort, mapping.targetPort,
                        mapping.peer, mapping.accepted, mapping.enabled)).toList();
        List<RouteStore.AllowedData> allowedData = allowedRoutes.values().stream().map(route ->
                new RouteStore.AllowedData(route.routeId, route.name, route.peer, route.targetPort)).toList();
        store.save(mappingData, allowedData);
    }

    /**
     * Drops invitations after their fixed first-admission lifetime without allowing retransmissions to
     * keep authorization offers alive.
     */
    private void expireInvitations() {
        long now = clock.getAsLong();
        invitations.values().removeIf(invitation -> now - invitation.receivedAt >= INVITATION_TTL.toNanos());
    }

    /**
     * Looks up invitation in the reverse-forward authorization state without creating a replacement.
     *
     * @param text the text supplied to this operation
     * @return the result described above
     */
    private Invitation findInvitation(String text) {
        expireInvitations();
        String normalized = text.toLowerCase(Locale.ROOT);
        List<Invitation> matches = invitations.values().stream()
                .filter(invitation -> invitation.invitationId.toString().toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    /**
     * Looks up allowed in the reverse-forward authorization state without creating a replacement.
     *
     * @param text the text supplied to this operation
     * @return the result described above
     */
    private AllowedRoute findAllowed(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        List<AllowedRoute> matches = allowedRoutes.values().stream()
                .filter(route -> route.routeId.toString().toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    /**
     * Performs the mapping by route operation for the reverse-forward authorization state.
     *
     * @param routeId the route id supplied to this operation
     * @return the result described above
     */
    private Mapping mappingByRoute(UUID routeId) {
        return mappings.values().stream().filter(mapping -> mapping.routeId.equals(routeId)).findFirst().orElse(null);
    }

    /**
     * Performs the state operation for the reverse-forward authorization state.
     *
     * @param mapping the mapping supplied to this operation
     * @return the result described above
     */
    private String state(Mapping mapping) {
        if (!mapping.accepted) return mapping.peer.isEmpty() ? "unassigned" : "pending";
        if (!mapping.enabled) return "stopped";
        if (!connected) return "waiting for server connection";
        return listeners.containsKey(mapping.routeId) ? "listening" : "not listening; retry /k04mrf start " + mapping.name;
    }

    /**
     * Performs the key operation for the reverse-forward authorization state.
     *
     * @param name the name supplied to this operation
     * @return the result described above
     */
    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    /**
     * Performs the short id operation for the reverse-forward authorization state.
     *
     * @param id the id supplied to this operation
     * @return the result described above
     */
    private static String shortId(UUID id) { return id.toString().substring(0, 8); }
    /**
     * Performs the valid player operation for the reverse-forward authorization state.
     *
     * @param player the player supplied to this operation
     * @return whether the condition or operation described above succeeds
     */
    private static boolean validPlayer(String player) { return player != null && player.matches("[A-Za-z0-9_]{1,16}"); }
    /**
     * Performs the self operation for the reverse-forward authorization state.
     *
     * @param player the player supplied to this operation
     * @return whether the condition or operation described above succeeds
     */
    private static boolean self(String player) {
        Minecraft client = Minecraft.getInstance();
        return client.getUser() != null && client.getUser().getName().equalsIgnoreCase(player);
    }
    /**
     * Performs the fail operation for the reverse-forward authorization state.
     *
     * @param text the text supplied to this operation
     * @return the result described above
     */
    private static int fail(String text) { ReverseForward.message(text); return 0; }
    /**
     * Performs the useful operation for the reverse-forward authorization state.
     *
     * @param error the error supplied to this operation
     * @return the result described above
     */
    private static String useful(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    /**
     * Runs the supplied operation on the Minecraft client thread and waits for its result from a worker.
     * This preserves thread confinement of core API/control state; callers must avoid blocking the client
     * thread on themselves.
     *
     * @param task the task supplied to this operation
     * @return the result described above
     * @throws Exception if the delegated operation cannot complete successfully
     */
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

    /**
     * Performs the close encrypted operation for the reverse-forward authorization state.
     *
     * @param socket the encrypted or TCP socket participating in the operation
     */
    private static void closeEncrypted(KryptSocket socket) {
        if (socket == null || socket.isClosed()) return;
        // Input close cancels the stream and wakes blocked readers.
        try { socket.getInputStream().close(); } catch (IOException | RuntimeException ignored) {}
        try { socket.close(); } catch (RuntimeException ignored) {}
    }

    @FunctionalInterface private interface ThrowingSupplier<T> {
        /**
         * Performs the get operation for the reverse-forward authorization state.
         *
         * @return the result described above
         * @throws Exception if the delegated operation cannot complete successfully
         */
        T get() throws Exception; }

    private static final class PendingRead {
        final Connection connection;
        final String peer;
        final long started;
        private boolean complete;

        /**
         * Creates a pending read with the supplied dependencies and initial state.
         *
         * @param connection the connection supplied to this operation
         * @param peer the peer identifier associated with this operation
         * @param started the started supplied to this operation
         */
        PendingRead(Connection connection, String peer, long started) {
            this.connection = connection; this.peer = peer; this.started = started;
        }

        /**
         * Returns the complete value used by the reverse-forward authorization state.
         *
         * @param now the now supplied to this operation
         * @return whether the condition or operation described above succeeds
         */
        synchronized boolean complete(long now) {
            if (complete || connection.closed() || now - started >= REQUEST_TTL_NANOS) {
                connection.close();
                return false;
            }
            complete = true;
            return true;
        }

        /**
         * Expires state whose deadline has elapsed in the reverse-forward authorization state.
         *
         * @param now the now supplied to this operation
         * @return whether the condition or operation described above succeeds
         */
        synchronized boolean expire(long now) {
            if (complete || now - started < REQUEST_TTL_NANOS) return false;
            connection.close();
            return true;
        }
    }

    // Closing a generation also rejects workers that were admitted before revocation
    // but have not attached their sockets yet.
    private static final class ConnectionGroup {
        private final java.util.Set<Connection> active = new java.util.HashSet<>();
        private boolean closed;
        /**
         * Returns the recorded false for the reverse-forward authorization state.
         *
         * @param connection the connection supplied to this operation
         * @return whether the condition or operation described above succeeds
         */
        synchronized boolean add(Connection connection) {
            if (closed) { connection.close(); return false; }
            active.add(connection);
            return true;
        }
        /**
         * Removes the selected entry in the reverse-forward authorization state.
         *
         * @param connection the connection supplied to this operation
         */
        synchronized void remove(Connection connection) { active.remove(connection); }
        /**
         * Closes retained resources in the reverse-forward authorization state.
         */
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
        /**
         * Returns the recorded closed for the reverse-forward authorization state.
         *
         * @return whether the condition or operation described above succeeds
         */
        synchronized boolean closed() { return closed; }
        /**
         * Performs the attach operation for the reverse-forward authorization state.
         *
         * @param socket the encrypted or TCP socket participating in the operation
         */
        synchronized void attach(Socket socket) {
            tcp = socket;
            if (closed) closeTcp();
        }
        /**
         * Performs the attach operation for the reverse-forward authorization state.
         *
         * @param socket the encrypted or TCP socket participating in the operation
         */
        synchronized void attach(KryptSocket socket) {
            encrypted = socket;
            if (closed) closeEncrypted(socket);
        }
        /**
         * Closes retained resources in the reverse-forward authorization state.
         */
        synchronized void close() {
            closed = true;
            closeTcp();
            closeEncrypted(encrypted);
        }
        /**
         * Performs the close tcp operation for the reverse-forward authorization state.
         */
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
        /**
         * Creates a mapping with the supplied dependencies and initial state.
         *
         * @param routeId the route id supplied to this operation
         * @param name the name supplied to this operation
         * @param listenPort the listen port supplied to this operation
         * @param targetPort the target port supplied to this operation
         * @param peer the peer identifier associated with this operation
         * @param accepted the accepted supplied to this operation
         * @param enabled the enabled supplied to this operation
         */
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
        /**
         * Creates a allowed route with the supplied dependencies and initial state.
         *
         * @param routeId the route id supplied to this operation
         * @param name the name supplied to this operation
         * @param peer the peer identifier associated with this operation
         * @param targetPort the target port supplied to this operation
         */
        AllowedRoute(UUID routeId, String name, String peer, int targetPort) {
            this.routeId = routeId; this.name = name; this.peer = peer; this.targetPort = targetPort;
        }
    }

    private record Invitation(UUID invitationId, UUID routeId, String name, String sender,
                              int listenPort, int targetPort, long receivedAt) {
        /**
         * Performs the packet operation for the reverse-forward authorization state.
         *
         * @param type the type supplied to this operation
         * @return the result described above
         */
        ControlPacket packet(ControlPacket.Type type) {
            return new ControlPacket(type, invitationId, routeId, name, listenPort, targetPort);
        }
        /**
         * Performs the packet operation for the reverse-forward authorization state.
         *
         * @param type the type supplied to this operation
         * @param selectedTargetPort the selected target port supplied to this operation
         * @return the result described above
         */
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
        /**
         * Creates a listener with the supplied dependencies and initial state.
         *
         * @param mapping the mapping supplied to this operation
         * @param server the server supplied to this operation
         */
        Listener(Mapping mapping, ServerSocket server) { this.mapping = mapping; this.server = server; }
        /**
         * Closes retained resources in the reverse-forward authorization state.
         */
        void close() {
            connections.close();
            if (closed.compareAndSet(false, true)) try { server.close(); } catch (IOException ignored) {}
        }
    }
}
