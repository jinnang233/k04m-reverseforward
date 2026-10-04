package dev.k04mreverseforward;

import dev.krypt04mcg.api.KryptSocket;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Worker-only pacing for the API's bounded, nonblocking output queue. */
final class KryptStreams {
    static void write(KryptSocket socket, byte[] bytes) throws Exception {
        await(socket, () -> socket.writableBytes() >= bytes.length);
        socket.getOutputStream().write(bytes);
    }

    static void finish(KryptSocket socket) throws Exception {
        socket.close();
        // flush() is a no-op. Wait for both EOFs so cleanup cannot reset a peer
        // that is still consuming the last queued frames.
        await(socket, socket::isClosed);
    }

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

    private KryptStreams() {}
}
