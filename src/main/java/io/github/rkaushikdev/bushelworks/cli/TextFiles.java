package io.github.rkaushikdev.bushelworks.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The text files the commands read and write: rolls and maps the office keeps, the old system's
 * nightly file, and the exports. All of them are UTF-8.
 */
final class TextFiles {

    private TextFiles() {}

    /** A file the office wrote, refused if it is not UTF-8. */
    static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /**
     * A file the old system wrote, with anything that is not UTF-8 read as the replacement character,
     * so one mangled byte in a remark costs that remark rather than the night's file.
     */
    static String readLeniently(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /** Write {@code text} as UTF-8, its line ends exactly as they are. */
    static void write(Path path, String text) {
        try {
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
        }
    }
}
