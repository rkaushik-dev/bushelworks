package io.github.rkaushikdev.bushelworks;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.bushelworks.api.BushelworksApplication;
import io.github.rkaushikdev.bushelworks.cli.Main;
import io.github.rkaushikdev.bushelworks.cli.Shell;
import io.github.rkaushikdev.bushelworks.cli.Terminal;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * What the build says about itself.
 *
 * <p>Three things could disagree about the version: the constant, the build file that stamps the jar,
 * and the {@code /health} response. They are one thing, and this is what keeps them that way.
 */
class PackagingTest {

    /** The build file at the root of the project, which the build runs the tests from. */
    static final Path BUILD = Path.of("build.gradle");

    /** A top-level {@code name = 'value'} line of the build file. */
    static Optional<String> declared(String build, String name) {
        Matcher line = Pattern.compile("(?m)^\\s*" + Pattern.quote(name) + "\\s*=\\s*'([^']*)'\\s*$")
                .matcher(build);
        return line.find() ? Optional.of(line.group(1)) : Optional.empty();
    }

    static String buildFile() throws IOException {
        return Files.readString(BUILD, StandardCharsets.UTF_8);
    }

    @Test
    void theBuildCarriesAVersion() {
        assertThat(Bushelworks.VERSION).isNotEmpty();
        assertThat(Bushelworks.VERSION.chars().filter(letter -> letter == '.').count())
                .isEqualTo(2);
    }

    @Test
    void theVersionReadsBackAsNumbers() {
        Bushelworks.Version version = Bushelworks.versionTuple();
        assertThat(version).isGreaterThanOrEqualTo(new Bushelworks.Version(0, 1, 0));
        assertThat(version.toString()).isEqualTo(Bushelworks.VERSION);
    }

    /** The version the build stamps on the jar is the constant's. */
    @Test
    void theBuildFileDeclaresTheSameVersion() throws IOException {
        String build = buildFile();
        assertThat(declared(build, "group"))
                .as("the reader finds a declared line")
                .hasValue("io.github.rkaushikdev");
        assertThat(declared(build, "nothing")).isEmpty();
        assertThat(declared(build, "version")).hasValue(Bushelworks.VERSION);
    }

    /** The runnable jar starts the command line, through a console entry point it really has. */
    @Test
    void theRunnableJarStartsTheCommandLine() throws IOException, NoSuchMethodException {
        assertThat(declared(buildFile(), "mainClass")).hasValue(Main.class.getName());
        Method entry = Main.class.getMethod("main", String[].class);
        assertThat(Modifier.isStatic(entry.getModifiers())).isTrue();
        assertThat(entry.getReturnType()).isEqualTo(void.class);
    }

    @Test
    void healthReportsTheSameVersion() throws IOException, InterruptedException {
        String url = "jdbc:h2:mem:packaging-health";
        try (Database database = Database.open(url)) {
            database.migrations().upgrade();
            Settings house = Settings.defaults().withDatabaseUrl(url);
            try (ConfigurableApplicationContext app = BushelworksApplication.run(house, database, "--server.port=0");
                    HttpClient http = HttpClient.newHttpClient()) {
                int port = ((WebServerApplicationContext) app).getWebServer().getPort();
                HttpResponse<String> health = http.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(health.statusCode()).isEqualTo(200);
                assertThat(new ObjectMapper()
                                .readTree(health.body())
                                .get("version")
                                .asText())
                        .isEqualTo(Bushelworks.VERSION);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"--help", "-h"})
    void theCommandLineRunsAndPrintsHelp(String flag) {
        Shell.Result result = Shell.bare().run(flag);
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("Scale house capture and settlement");
    }

    @Test
    void theCommandLinePrintsItsVersion() {
        Shell.Result result = Shell.bare().run("--version");
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains(Bushelworks.VERSION);
    }

    @Test
    void theCommandLineListsTheCommands() {
        Shell.Result result = Shell.bare().run("--help");
        assertThat(result.out()).contains("settle", "tickets", "position", "cheques");
    }

    @Test
    void anUnknownCommandExitsNonZero() {
        Shell.Result result = Shell.bare().run("plough");
        assertThat(result.code()).isNotZero().isEqualTo(Main.MISUSED);
    }

    /** The console entry point hands its exit code to whatever started it, rather than exiting itself. */
    @Test
    void theEntryPointReturnsAnExitCode() {
        StringWriter out = new StringWriter();
        Terminal terminal = new Terminal(
                name -> null,
                new PrintWriter(out, true),
                new PrintWriter(new StringWriter(), true),
                Terminal.Databases.FRESH);
        assertThat(Main.run(terminal, "--version")).isEqualTo(Main.OK);
        assertThat(out.toString()).contains(Bushelworks.VERSION);
    }
}
