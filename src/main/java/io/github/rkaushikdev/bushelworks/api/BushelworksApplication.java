package io.github.rkaushikdev.bushelworks.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.bushelworks.Bushelworks;
import io.github.rkaushikdev.bushelworks.HouseLog;
import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.api.metrics.Metrics;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.services.houses.Houses;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.util.ArrayList;
import java.util.List;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The HTTP interface: the scale house terminal, the office screens and the grain accountant's
 * spreadsheet all talk to the house through it.
 *
 * <p>Every route is thin: read the request, call a service, write the answer. None of them can reach
 * a rule that is not in the domain.
 *
 * <p>There is one factory, {@link #run}, rather than a context that builds its own database, so the
 * tests can start one over a database of their own without touching the environment, and so two
 * houses can run in one process during a merger, which has already happened once. The settings and
 * the database are handed in and become beans; whoever opened the database closes it. Units of work
 * are per request ({@link Work}), and nothing caches a domain object between requests except the
 * house rule book, which nothing in a request can change.
 *
 * <p>Spring Boot's own data source, JPA and Flyway set up are switched off: with H2 on the class path
 * and no data source of its own, Boot would otherwise build an embedded database and migrate that.
 * Nothing migrates when the service starts. Boot's error page is switched off too: every failure is
 * answered by {@link Errors} in the service's own shape, and the page would be one more route on a
 * surface the clients have compiled in.
 */
@SpringBootApplication(
        exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            ErrorMvcAutoConfiguration.class
        })
public class BushelworksApplication {

    /** The API document's title. */
    public static final String TITLE = "bushelworks";

    /** The API document's description. */
    public static final String DESCRIPTION = "Scale house capture and settlement for country grain elevators."
            + " Every quantity is exact decimal and every rate is resolved as of the ticket date rather"
            + " than today.";

    /** Start the service over a database somebody else opened and will close. */
    public static ConfigurableApplicationContext run(Settings settings, Database database, String... args) {
        return run(settings, database, List.of(), args);
    }

    /**
     * Start the service with more configuration beside its own: routes a test adds, for instance.
     *
     * <p>The package's logging is pointed at standard error at the settings' level, and Spring Boot
     * is told to leave the logging alone, since it would otherwise reset it as it starts.
     *
     * @param args Spring Boot arguments, such as {@code --server.port=0}
     */
    public static ConfigurableApplicationContext run(
            Settings settings, Database database, List<Class<?>> extra, String... args) {
        System.setProperty(LoggingSystem.SYSTEM_PROPERTY, LoggingSystem.NONE);
        HouseLog.configure(settings.logLevel());
        List<Class<?>> sources = new ArrayList<>();
        sources.add(BushelworksApplication.class);
        sources.addAll(extra);
        return new SpringApplicationBuilder(sources.toArray(Class<?>[]::new))
                .initializers(context -> {
                    context.getBeanFactory().registerSingleton("settings", settings);
                    context.getBeanFactory().registerSingleton("database", database);
                })
                .run(args);
    }

    @Bean
    Work work(Database database) {
        return new Work(database);
    }

    /**
     * The house rule book, built once at start up and shared. It is immutable in practice: nothing
     * in a request files a schedule, and a schedule change is a deploy.
     */
    @Bean
    RuleBook rules(Settings settings) {
        return Houses.houseRulebook(settings);
    }

    @Bean
    Metrics metrics() {
        return new Metrics();
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer houseJson() {
        return Json::configure;
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title(TITLE).description(DESCRIPTION).version(Bushelworks.VERSION));
    }

    @Bean
    WireSchemas wireSchemas(ObjectMapper json) {
        return new WireSchemas(json);
    }

    @Bean
    OneDocument oneDocument(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping routes,
            SpringDocConfigProperties docs) {
        return new OneDocument(routes, docs.getApiDocs().getPath());
    }
}
