package dev.k04mreverseforward;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class TunnelBridgeTest {
    /**
     * Verifies that half closed request receives complete large response.
     *
     * @throws Exception if the delegated operation cannot complete successfully
     */
    @Test void halfClosedRequestReceivesCompleteLargeResponse() throws Exception {
        byte[] request = new byte[200_000];
        byte[] response = new byte[300_000];
        new Random(1).nextBytes(request);
        new Random(2).nextBytes(response);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
             var entry = LoopbackTcp.listen(0); var target = LoopbackTcp.listen(0);
             var client = new Socket("127.0.0.1", entry.getLocalPort());
             var local = entry.accept();
             var forwarded = new Socket("127.0.0.1", target.getLocalPort());
             var service = target.accept();
             var leftInput = new PipedInputStream(65536); var rightOutput = new PipedOutputStream(leftInput);
             var rightInput = new PipedInputStream(65536); var leftOutput = new PipedOutputStream(rightInput)) {
            client.setSoTimeout(3000);
            service.setSoTimeout(3000);
            var left = workers.submit(() -> { TunnelBridge.run(local, leftInput, frame -> {
                leftOutput.write(frame); leftOutput.flush();
            }, workers); return null; });
            var right = workers.submit(() -> { TunnelBridge.run(forwarded, rightInput, frame -> {
                rightOutput.write(frame); rightOutput.flush();
            }, workers); return null; });
            var application = workers.submit(() -> {
                assertArrayEquals(request, service.getInputStream().readAllBytes());
                service.getOutputStream().write(response);
                service.shutdownOutput();
                return null;
            });
            client.getOutputStream().write(request);
            client.shutdownOutput();
            assertArrayEquals(response, client.getInputStream().readAllBytes());
            application.get(3, TimeUnit.SECONDS);
            left.get(3, TimeUnit.SECONDS);
            right.get(3, TimeUnit.SECONDS);
        }
    }

    /**
     * Verifies that malformed or truncated frames close the connection.
     *
     * @throws Exception if the delegated operation cannot complete successfully
     */
    @Test void malformedOrTruncatedFramesCloseTheConnection() throws Exception {
        for (byte[] frame : new byte[][] {
                ByteBuffer.allocate(4).putInt(-1).array(),
                ByteBuffer.allocate(4).putInt(TunnelBridge.MAX_FRAME + 1).array(),
                ByteBuffer.allocate(5).putInt(2).put((byte) 1).array(), new byte[0]}) {
            try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                 var server = LoopbackTcp.listen(0);
                 var client = new Socket("127.0.0.1", server.getLocalPort()); var tcp = server.accept()) {
                assertThrows(Exception.class, () -> TunnelBridge.run(tcp, new ByteArrayInputStream(frame), ignored -> {}, workers));
                assertTrue(tcp.isClosed());
            }
        }
    }
}
