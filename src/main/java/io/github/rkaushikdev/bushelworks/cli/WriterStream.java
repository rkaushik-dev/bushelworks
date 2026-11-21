package io.github.rkaushikdev.bushelworks.cli;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * A byte stream that writes into a text writer, so the house log can share the terminal's error
 * stream.
 *
 * <p>Bytes are held until a flush and then written as UTF-8 text. The log flushes after every line,
 * so a line never reaches the writer in pieces. Closing flushes and leaves the writer open.
 */
final class WriterStream extends OutputStream {

    private final PrintWriter target;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    WriterStream(PrintWriter target) {
        this.target = target;
    }

    @Override
    public synchronized void write(int value) {
        pending.write(value);
    }

    @Override
    public synchronized void write(byte[] data, int offset, int length) {
        pending.write(data, offset, length);
    }

    @Override
    public synchronized void flush() {
        if (pending.size() > 0) {
            target.write(pending.toString(StandardCharsets.UTF_8));
            pending.reset();
        }
        target.flush();
    }

    @Override
    public void close() {
        flush();
    }
}
