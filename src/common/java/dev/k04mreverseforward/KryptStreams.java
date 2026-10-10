package dev.k04mreverseforward;

import dev.krypt04mcg.api.KryptSocket;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Worker-only pacing for the API's bounded, nonblocking output queue. */
final class KryptStreams {
    /**
     * Waits on a worker thread for enough capacity in the bounded nonblocking API output buffer, then
     * writes the supplied stream portion. Failure/timeout/interruption aborts rather than overwriting
     * queued bytes or busy-waiting on the client thread.
     *
     * @param socket the encrypted or TCP socket participating in the operation
     * @param bytes the bytes supplied to this operation
     * @throws Exception if the delegated operation cannot complete successfully
     */
    static void write(KryptSocket socket, byte[] bytes) throws Exception {
        await(socket, () -> socket.writableBytes() >= bytes.length);
        socket.getOutputStream().write(bytes);
    }

    /**
     * Queues local authenticated EOF and waits for both stream directions to close before cleanup. flush
     * is not a delivery barrier for this API; waiting avoids resetting a peer still consuming final queued
     * records.
     *
     * @param socket the encrypted or TCP socket participating in the operation
     * @throws Exception if the delegated operation cannot complete successfully
     */
    static void finish(KryptSocket socket) throws Exception {
        socket.close();
        // flush() is a no-op. Wait for both EOFs so cleanup cannot reset a peer
        // that is still consuming the last queued frames.
        await(socket, socket::isClosed);
    }

    /**
     * Waits at short worker-thread intervals for the supplied readiness condition under a fixed 60-second
     * monotonic deadline. Stream failure aborts the wait, and interruption restores interrupt status
     * before propagation.
     *
     * @param socket the encrypted or TCP socket participating in the operation
     * @param ready the ready supplied to this operation
     * @throws Exception if the delegated operation cannot complete successfully
     */
    private static void await(KryptSocket socket, BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!ready.getAsBoolean()) {
            if (socket.isFailed()) throw new IOException("Encrypted stream failed");
            if (System.nanoTime() >= deadline) throw new IOException("Encrypted stream write timed out");
            try { Thread.sleep(10); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }
        if (socket.isFailed()) throw new IOException("Encrypted stream failed");
    }

    /**
     * Prevents direct instantiation of this stateless utility.
     */
    private KryptStreams() {}
}
