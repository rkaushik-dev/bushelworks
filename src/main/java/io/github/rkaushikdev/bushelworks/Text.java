package io.github.rkaushikdev.bushelworks;

import java.util.ArrayList;
import java.util.List;

/**
 * Lines and white space in the text files the house is handed: the old system's nightly ticket file,
 * the membership roll the accounting package exports, the crosswalk of old party codes to new.
 *
 * <p>Those files come off three different machines, so a line may end in any of the ways text can
 * end one, and a code may be padded with any kind of space, the no-break one a spreadsheet likes
 * included.
 */
public final class Text {

    private Text() {}

    /**
     * The lines of {@code text}, without their endings. A line ends at a line feed, a carriage
     * return, the pair of them, or any of the other breaks text can carry: a vertical tab, a form
     * feed, the file, group and record separators, a next line, a line separator or a paragraph
     * separator. A break at the very end starts no further line.
     */
    public static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (index < text.length()) {
            char letter = text.charAt(index);
            if (isLineBreak(letter)) {
                lines.add(text.substring(start, index));
                if (letter == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
                start = index + 1;
            }
            index++;
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    /** {@code text} without white space at either end, no-break and other Unicode spaces included. */
    public static String trimmed(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isLineBreak(char letter) {
        return switch (letter) {
            case '\n', '\r', '\u000B', '\f', '\u001C', '\u001D', '\u001E', '\u0085', ' ', ' ' -> true;
            default -> false;
        };
    }

    private static boolean isSpace(char letter) {
        return Character.isWhitespace(letter) || Character.isSpaceChar(letter) || letter == '\u0085';
    }
}
