package io.github.rkaushikdev.bushelworks.api;

import io.github.rkaushikdev.bushelworks.servicetest.TestService;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springdoc.webmvc.ui.SwaggerConfig;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;

/**
 * A test of the service's routes: the web layer of the application, every route, filter and the
 * error advice in it, over the suite's real database, driven through MockMvc with no server and no
 * mocks. The API document is served too, which a web slice would otherwise leave out.
 *
 * <p>Classes carrying this with the same properties share one application, started once. A class
 * that needs a house of its own sets it with {@code @TestPropertySource} (see {@link TestService});
 * one whose state in memory has to start fresh for every test, such as the counters or the rate
 * limit, adds {@code @DirtiesContext(classMode = BEFORE_EACH_TEST_METHOD)}: a
 * context left cached by another class with the same properties would carry that class's counts.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@WebMvcTest
@Import(TestService.class)
@ImportAutoConfiguration({
    SpringDocConfiguration.class,
    SpringDocConfigProperties.class,
    SpringDocWebMvcConfiguration.class,
    SwaggerConfig.class,
    SwaggerUiConfigProperties.class,
    SwaggerUiOAuthProperties.class
})
public @interface RouteTest {}
