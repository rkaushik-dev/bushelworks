package io.github.rkaushikdev.bushelworks;

import static io.github.rkaushikdev.bushelworks.Quoting.quoted;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class QuotingTest {

    /** Text as handed in, and as a refusal quotes it. */
    static Stream<Arguments> quotings() {
        return Stream.of(
                Arguments.of("plain", "'plain'"),
                Arguments.of("", "''"),
                Arguments.of("a'b", "\"a'b\""),
                Arguments.of("a\"b", "'a\"b'"),
                Arguments.of("'\"", "'\\'\"'"),
                Arguments.of("\\", "'\\\\'"),
                Arguments.of("a\"b\\c", "'a\"b\\\\c'"),
                Arguments.of("\t\n\r", "'\\t\\n\\r'"),
                Arguments.of("\u0000\u001b\u007f", "'\\x00\\x1b\\x7f'"),
                Arguments.of("\u0085\u00a0", "'\\x85\\xa0'"),
                Arguments.of("\u200b\u2028\ue000", "'\\u200b\\u2028\\ue000'"),
                Arguments.of(new String(Character.toChars(0xe0001)), "'\\U000e0001'"),
                Arguments.of("é ü", "'é ü'"),
                Arguments.of(
                        new String(Character.toChars(0x1f600)), "'" + new String(Character.toChars(0x1f600)) + "'"));
    }

    @ParameterizedTest
    @MethodSource("quotings")
    void textIsQuotedWithItsUnprintablesSpelledOut(String text, String expected) {
        assertThat(quoted(text)).isEqualTo(expected);
    }
}
