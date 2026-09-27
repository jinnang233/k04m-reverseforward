package dev.k04mreverseforward;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/** Both ends of a route use the IPv4 address advertised by the commands. */
final class LoopbackTcp {
    private LoopbackTcp() {}

    static InetSocketAddress address(int port) {
        return new InetSocketAddress("127.0.0.1", port);
    }

    static ServerSocket listen(int port) throws IOException {
        ServerSocket server = new ServerSocket();
        try {
            // SO_REUSEADDR can allow duplicate listeners on Windows.
            server.bind(address(port), 32);
            return server;
        } catch (IOException | RuntimeException error) {
            try { server.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
    }
}
