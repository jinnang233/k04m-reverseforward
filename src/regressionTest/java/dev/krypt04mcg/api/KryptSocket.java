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

    public KryptSocket() { this("Bob"); }
    public KryptSocket(String peer) {
        this.peer = peer;
        try { feed = new PipedOutputStream(input); }
        catch (IOException error) { throw new RuntimeException(error); }
    }
    public InputStream getInputStream() {
        return new FilterInputStream(input) {
            @Override public int read() throws IOException { requireWorker(); return in.read(); }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                requireWorker(); return in.read(b, off, len);
            }
            @Override public void close() { KryptSocket.this.abort(); }
        };
    }
    public OutputStream getOutputStream() {
        return new FilterOutputStream(output) {
            @Override public void write(int b) throws IOException { requireWorker(); out.write(b); }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                requireWorker(); out.write(b, off, len);
            }
            @Override public void flush() throws IOException { requireWorker(); handshake.countDown(); }
        };
    }
    private static void requireWorker() throws IOException {
        if (net.minecraft.client.Minecraft.getInstance().isSameThread()) throw new IOException("Use an I/O worker");
    }
    public String peer() { return peer; }
    public boolean isClosed() { return closed; }
    public int writableBytes() { return 1024 * 1024; }
    public boolean isFailed() { return closed; }
    public boolean outputEnded() { return outputEnded; }
    public void close() { outputEnded = true; }
    private void abort() {
        closed = true;
        try { feed.close(); input.close(); } catch (IOException ignored) {}
    }
}
