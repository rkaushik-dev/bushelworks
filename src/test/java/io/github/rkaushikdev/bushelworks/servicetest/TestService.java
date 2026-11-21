package io.github.rkaushikdev.bushelworks.servicetest;

import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * What the service is handed when a test slice starts it, in place of what {@code
 * BushelworksApplication.run} registers: the house's settings and the suite's database.
 *
 * <p>The settings are the defaults over the suite's database, with anything a test class sets as a
 * {@code house.BUSHELWORKS_...} property read the way the environment is read, so a class wanting a
 * locked or a rate limited house says so in one line:
 *
 * <pre>{@code
 * @TestPropertySource(properties = "house.BUSHELWORKS_RATE_LIMIT_PER_MINUTE=3")
 * }</pre>
 *
 * <p>This lives outside the application's package on purpose. A test that starts the whole
 * application scans that package for components without the test filters a slice has, and would
 * otherwise mount these beans beside the ones it was handed.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestService {

    /** The prefix a test's house settings carry. */
    public static final String PREFIX = "house.";

    @Bean
    Settings settings(Environment environment) {
        return Settings.load(name -> environment.getProperty(PREFIX + name)).withDatabaseUrl(TestDatabase.URL);
    }

    /** The suite's database. The suite keeps it open; the context must not close it. */
    @Bean(destroyMethod = "")
    Database database() {
        return TestDatabase.fresh();
    }
}
