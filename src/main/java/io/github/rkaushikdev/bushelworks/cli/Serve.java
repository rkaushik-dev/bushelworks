package io.github.rkaushikdev.bushelworks.cli;

import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.api.BushelworksApplication;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Run the HTTP service until it is stopped.
 *
 * <p>The one command that starts Spring. It hands the service the settings and the database it
 * opened, says where it is listening, and waits until the service closes, on a signal or an
 * interrupt. While it runs, the house log goes to the process's standard error at the settings'
 * level.
 */
@Command(name = "serve", description = "Run the HTTP service until it is stopped.")
final class Serve implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @Option(names = "--host", defaultValue = "127.0.0.1", paramLabel = "TEXT", description = "Address to listen on.")
    private String host;

    @Option(
            names = "--port",
            defaultValue = "8000",
            paramLabel = "INTEGER",
            description = "Port to listen on; 0 picks a free one.")
    private int port;

    @Override
    public Integer call() {
        Settings settings = main.settings();
        CountDownLatch closed = new CountDownLatch(1);
        try (ConfigurableApplicationContext service = BushelworksApplication.run(
                settings, main.database(), "--server.port=" + port, "--server.address=" + host)) {
            service.addApplicationListener(event -> {
                if (event instanceof ContextClosedEvent) {
                    closed.countDown();
                }
            });
            int listening = ((ServletWebServerApplicationContext) service)
                    .getWebServer()
                    .getPort();
            main.out().println("serving " + settings.houseName() + " on http://" + host + ":" + listening);
            main.out().flush();
            if (service.isActive()) {
                closed.await();
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
        return Main.OK;
    }
}
