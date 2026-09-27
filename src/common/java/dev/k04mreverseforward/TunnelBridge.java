package dev.k04mreverseforward;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;

/** Version 2: a four-byte length followed by data; length zero means directional EOF. */
final class TunnelBridge {
    static final int MAX_FRAME = 16 * 1024;

    @FunctionalInterface
    interface FrameWriter { void write(byte[] frame) throws Exception; }

    static void run(Socket tcp, InputStream encryptedInput, FrameWriter writer,
                    ExecutorService workers) throws Exception {
        var completion = new ExecutorCompletionService<Void>(workers);
        var sending = completion.submit(() -> {
            InputStream input = tcp.getInputStream();
            byte[] buffer = new byte[MAX_FRAME];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) continue;
                writer.write(ByteBuffer.allocate(4 + count).putInt(count).put(buffer, 0, count).array());
            }
            writer.write(new byte[4]);
            return null;
        });
        var receiving = completion.submit(() -> {
            DataInputStream input = new DataInputStream(encryptedInput);
            var output = tcp.getOutputStream();
            byte[] buffer = new byte[MAX_FRAME];
            while (true) {
                int length = input.readInt();
                if (length == 0) {
                    tcp.shutdownOutput();
                    return null;
                }
                if (length < 0 || length > MAX_FRAME) throw new IOException("Invalid tunnel frame length: " + length);
                input.readFully(buffer, 0, length);
                output.write(buffer, 0, length);
                output.flush();
            }
        });
        try {
            // A normal EOF only completes one direction. Any failure terminates both.
            completion.take().get();
            completion.take().get();
        } finally {
            sending.cancel(true);
            receiving.cancel(true);
            tcp.close();
        }
    }

    private TunnelBridge() {}
}
