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

    public KryptSocket() {
        try { feed = new PipedOutputStream(input); }
        catch (IOException error) { throw new RuntimeException(error); }
    }
    public InputStream getInputStream() {
        return new FilterInputStream(input) {
            @Override public void close() { KryptSocket.this.close(); }
        };
    }
    public OutputStream getOutputStream() {
        return new FilterOutputStream(output) {
            @Override public void flush() { handshake.countDown(); }
        };
    }
    public String peer() { return "Bob"; }
    public boolean isClosed() { return closed; }
    public void close() {
        closed = true;
        try { feed.close(); input.close(); } catch (IOException ignored) {}
    }
}
