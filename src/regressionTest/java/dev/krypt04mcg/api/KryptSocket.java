package dev.krypt04mcg.api;

import java.io.*;
import java.util.concurrent.CountDownLatch;

/** In-memory transport double. Real KryptSocket input.close() also aborts blocked reads. */
public class KryptSocket {
    public final PipedInputStream input = new PipedInputStream(65536);
    public final PipedOutputStream feed;
    public final ByteArrayOutputStream output = new ByteArrayOutputStream();
    public final CountDownLatch handshake = new CountDownLatch(1);
    public volatile boolean closed;
    private volatile boolean outputEnded;
    private final String peer;

    /**
     * Creates the krypt socket fixture with its supplied initial state.
     */
    public KryptSocket() { this("Bob"); }
    /**
     * Creates the krypt socket fixture with its supplied initial state.
     *
     * @param peer the peer identifier associated with this operation
     */
    public KryptSocket(String peer) {
        this.peer = peer;
        try { feed = new PipedOutputStream(input); }
        catch (IOException error) { throw new RuntimeException(error); }
    }
    /**
     * Provides the get input stream fixture operation used by the krypt socket regression scenarios.
     *
     * @return the result described above
     */
    public InputStream getInputStream() {
        return new FilterInputStream(input) {
            /**
             * Provides the read fixture operation used by the krypt socket regression scenarios.
             *
             * @return the result described above
             * @throws IOException if input/output, stored-state validation or resource handling fails
             */
            @Override public int read() throws IOException { requireWorker(); return in.read(); }
            /**
             * Provides the read fixture operation used by the krypt socket regression scenarios.
             *
             * @param b the b supplied to this operation
             * @param off the off supplied to this operation
             * @param len the len supplied to this operation
             * @return the result described above
             * @throws IOException if input/output, stored-state validation or resource handling fails
             */
            @Override public int read(byte[] b, int off, int len) throws IOException {
                requireWorker(); return in.read(b, off, len);
            }
            /**
             * Provides the close fixture operation used by the krypt socket regression scenarios.
             */
            @Override public void close() { KryptSocket.this.abort(); }
        };
    }
    /**
     * Provides the get output stream fixture operation used by the krypt socket regression scenarios.
     *
     * @return the result described above
     */
    public OutputStream getOutputStream() {
        return new FilterOutputStream(output) {
            /**
             * Provides the write fixture operation used by the krypt socket regression scenarios.
             *
             * @param b the b supplied to this operation
             * @throws IOException if input/output, stored-state validation or resource handling fails
             */
            @Override public void write(int b) throws IOException { requireWorker(); out.write(b); }
            /**
             * Provides the write fixture operation used by the krypt socket regression scenarios.
             *
             * @param b the b supplied to this operation
             * @param off the off supplied to this operation
             * @param len the len supplied to this operation
             * @throws IOException if input/output, stored-state validation or resource handling fails
             */
            @Override public void write(byte[] b, int off, int len) throws IOException {
                requireWorker(); out.write(b, off, len);
            }
            /**
             * Provides the flush fixture operation used by the krypt socket regression scenarios.
             *
             * @throws IOException if input/output, stored-state validation or resource handling fails
             */
            @Override public void flush() throws IOException { requireWorker(); handshake.countDown(); }
        };
    }
    /**
     * Provides the require worker fixture operation used by the krypt socket regression scenarios.
     *
     * @throws IOException if input/output, stored-state validation or resource handling fails
     */
    private static void requireWorker() throws IOException {
        if (net.minecraft.client.Minecraft.getInstance().isSameThread()) throw new IOException("Use an I/O worker");
    }
    /**
     * Provides the peer fixture operation used by the krypt socket regression scenarios.
     *
     * @return the result described above
     */
    public String peer() { return peer; }
    /**
     * Provides the is closed fixture operation used by the krypt socket regression scenarios.
     *
     * @return whether the condition or operation described above succeeds
     */
    public boolean isClosed() { return closed; }
    /**
     * Provides the writable bytes fixture operation used by the krypt socket regression scenarios.
     *
     * @return the result described above
     */
    public int writableBytes() { return 1024 * 1024; }
    /**
     * Provides the is failed fixture operation used by the krypt socket regression scenarios.
     *
     * @return whether the condition or operation described above succeeds
     */
    public boolean isFailed() { return closed; }
    /**
     * Provides the output ended fixture operation used by the krypt socket regression scenarios.
     *
     * @return whether the condition or operation described above succeeds
     */
    public boolean outputEnded() { return outputEnded; }
    /**
     * Provides the close fixture operation used by the krypt socket regression scenarios.
     */
    public void close() { outputEnded = true; }
    /**
     * Provides the abort fixture operation used by the krypt socket regression scenarios.
     */
    private void abort() {
        closed = true;
        try { feed.close(); input.close(); } catch (IOException ignored) {}
    }
}
