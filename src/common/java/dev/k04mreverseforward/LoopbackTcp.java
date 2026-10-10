package dev.k04mreverseforward;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/** Both ends of a route use the IPv4 address advertised by the commands. */
final class LoopbackTcp {
    /**
     * Prevents direct instantiation of this stateless utility.
     */
    private LoopbackTcp() {}

    /**
     * Constructs an IPv4 127.0.0.1 endpoint explicitly, independent of JVM IPv6 preference. Forwarding is
     * confined to this loopback address rather than accepting a peer-supplied remote hostname.
     *
     * @param port the port supplied to this operation
     * @return the result described above
     */
    static InetSocketAddress address(int port) {
        return new InetSocketAddress("127.0.0.1", port);
    }

    /**
     * Binds only the explicit IPv4 loopback endpoint with a bounded accept backlog and without enabling
     * SO_REUSEADDR. Avoiding address reuse prevents duplicate-listener behavior on Windows; failure closes
     * the newly allocated server socket.
     *
     * @param port the port supplied to this operation
     * @return the result described above
     * @throws IOException if input/output, stored-state validation or resource handling fails
     */
    static ServerSocket listen(int port) throws IOException {
        ServerSocket server = new ServerSocket();
        try {
            // SO_REUSEADDR can allow duplicate listeners on Windows.
            /*
             * Uses the explicitly constructed IPv4 loopback endpoint rather than accepting a remote hostname from
             * peer input. Port grammar and route authorization are checked separately; successful socket creation
             * is not permission to connect elsewhere.
             */
            server.bind(address(port), 32);
            return server;
        } catch (IOException | RuntimeException error) {
            try { server.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
    }
}
