package dev.k04mreverseforward;

import dev.krypt04mcg.api.KryptSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class KryptStreamsTest {
    /**
     * Verifies that full queue waits then preserves bytes.
     *
     * @throws Exception if the delegated operation cannot complete successfully
     */
    @Test void fullQueueWaitsThenPreservesBytes() throws Exception {
        KryptSocket socket = socket();
        socket.getOutputStream().write(new byte[KryptSocket.MAX_BUFFERED_BYTES]);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var write = workers.submit(() -> { KryptStreams.write(socket, new byte[] {42}); return null; });
            Thread.sleep(50);
            assertFalse(write.isDone());
            assertEquals(KryptSocket.MAX_BUFFERED_BYTES, socket.poll(KryptSocket.MAX_BUFFERED_BYTES).length);
            write.get(1, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] {42}, socket.poll(1));
        }
    }

    /**
     * Verifies that finish waits for queued bytes and both ends.
     *
     * @throws Exception if the delegated operation cannot complete successfully
     */
    @Test void finishWaitsForQueuedBytesAndBothEnds() throws Exception {
        KryptSocket socket = socket();
        socket.getOutputStream().write(new byte[] {42});
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var finish = workers.submit(() -> { KryptStreams.finish(socket); return null; });
            Thread.sleep(50);
            assertFalse(finish.isDone());
            assertArrayEquals(new byte[] {42}, socket.poll(1));
            assertTrue(socket.needsEnd());
            assertFalse(finish.isDone());
            socket.endSent();
            assertFalse(finish.isDone());
            socket.remoteEnd();
            finish.get(1, TimeUnit.SECONDS);
            assertFalse(socket.isFailed());
        }
    }

    /**
     * Verifies that cancellation unblocks paced writer.
     *
     * @throws Exception if the delegated operation cannot complete successfully
     */
    @Test void cancellationUnblocksPacedWriter() throws Exception {
        KryptSocket socket = socket();
        socket.getOutputStream().write(new byte[KryptSocket.MAX_BUFFERED_BYTES]);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var write = workers.submit(() -> { KryptStreams.write(socket, new byte[] {42}); return null; });
            socket.getInputStream().close();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> write.get(1, TimeUnit.SECONDS));
            assertInstanceOf(java.io.IOException.class, failure.getCause());
        }
    }

    /**
     * Provides the socket fixture operation used by the krypt streams test regression scenarios.
     *
     * @return the result described above
     */
    private static KryptSocket socket() {
        return new KryptSocket("Bob", "test:tunnel", UUID.randomUUID());
    }
}
