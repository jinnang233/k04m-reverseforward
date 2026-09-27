package dev.k04mreverseforward;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

class LoopbackTcpTest {
    @Test
    void listenerAcceptsIpv4Connections() throws Exception {
        try (ServerSocket server = LoopbackTcp.listen(0)) {
            assertEquals("127.0.0.1", server.getInetAddress().getHostAddress());
            server.setSoTimeout(2000);
            try (Socket client = new Socket()) {
                client.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()), 2000);
                try (Socket accepted = server.accept()) {
                    accepted.setSoTimeout(2000);
                    client.getOutputStream().write(42);
                    assertEquals(42, accepted.getInputStream().read());
                }
            }
        }
    }

    @Test
    void targetConnectsToIpv4OnlyService() throws Exception {
        try (ServerSocket service = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             Socket target = new Socket()) {
            service.setSoTimeout(2000);
            target.setSoTimeout(2000);
            target.connect(LoopbackTcp.address(service.getLocalPort()), 2000);
            try (Socket accepted = service.accept()) {
                accepted.getOutputStream().write(73);
                assertEquals(73, target.getInputStream().read());
            }
        }
    }

    @Test
    void occupiedPortFailsAndCanBeRetriedAfterRelease() throws Exception {
        int port;
        try (ServerSocket occupied = LoopbackTcp.listen(0)) {
            port = occupied.getLocalPort();
            assertThrows(IOException.class, () -> {
                try (ServerSocket unexpected = LoopbackTcp.listen(port)) {
                    fail("An occupied port must not report a successful listener");
                }
            });
        }
        try (ServerSocket retried = LoopbackTcp.listen(port)) {
            assertTrue(retried.isBound());
        }
    }
}
