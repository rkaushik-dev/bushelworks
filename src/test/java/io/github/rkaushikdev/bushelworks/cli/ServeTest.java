package io.github.rkaushikdev.bushelworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.bushelworks.HouseLog;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** {@code bushelworks serve} answers over HTTP until it is stopped, and then the command returns. */
class ServeTest {

    private static final Pattern LISTENING =
            Pattern.compile("serving BUSHELWORKS ELEVATOR on http://127\\.0\\.0\\.1:(\\d+)");

    @Test
    void theServiceAnswersUntilItIsStoppedAndThenTheCommandReturns() throws Exception {
        TestDatabase.fresh();
        StringWriter out = new StringWriter();
        Terminal terminal = new Terminal(
                Map.of(Shell.DATABASE_VARIABLE, TestDatabase.URL)::get,
                new PrintWriter(out, true),
                new PrintWriter(new StringWriter(), true),
                Terminal.Databases.FRESH);
        AtomicInteger code = new AtomicInteger(-1);
        Thread serving = new Thread(() -> code.set(Main.run(terminal, "serve", "--port", "0")));
        serving.start();
        try {
            int port = portOnceListening(out);
            try (HttpClient http = HttpClient.newHttpClient()) {
                HttpResponse<String> health = http.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(health.statusCode()).isEqualTo(200);
                assertThat(health.body()).contains("\"status\":\"ok\"", "\"version\":\"0.8.0\"");
            }
        } finally {
            serving.interrupt();
            serving.join(Duration.ofSeconds(60));
            HouseLog.configure("INFO", new ByteArrayOutputStream());
        }
        assertThat(serving.isAlive()).isFalse();
        assertThat(code.get()).isEqualTo(Main.OK);
    }

    private static int portOnceListening(StringWriter out) throws InterruptedException {
        Instant giveUp = Instant.now().plusSeconds(120);
        while (Instant.now().isBefore(giveUp)) {
            Matcher listening = LISTENING.matcher(out.toString());
            if (listening.find()) {
                return Integer.parseInt(listening.group(1));
            }
            Thread.sleep(100);
        }
        throw new AssertionError("serve never said where it was listening: " + out);
    }
}
