package io.github.rkaushikdev.bushelworks.api;

import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.api.security.Gate;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** The service's additions to Spring MVC: the {@link Gate} in front of every route, and the caller it hands on. */
@Component
public class ApiWeb implements WebMvcConfigurer {

    private final Gate gate;

    public ApiWeb(Settings settings) {
        this.gate = new Gate(settings);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(gate);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(gate);
    }
}
