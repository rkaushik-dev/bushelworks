package io.github.rkaushikdev.bushelworks.api;

import java.util.Set;
import org.springdoc.webmvc.api.OpenApiResource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Keeps the API document to the one route it is published on.
 *
 * <p>The route table is a promise: the scale terminal, the office screen and the producer portal have
 * it compiled in. The document is served as JSON at its configured path, and the library that writes
 * it also serves the same document as YAML at that path with {@code .yaml} on the end, which no client
 * asked for. That second route is taken down once the routes are mounted, before the service answers a
 * request.
 */
final class OneDocument implements SmartInitializingSingleton {

    private final RequestMappingHandlerMapping routes;
    private final String path;

    /**
     * @param routes the service's routes
     * @param path where the document is published, {@code /openapi.json}
     */
    OneDocument(RequestMappingHandlerMapping routes, String path) {
        this.routes = routes;
        this.path = path;
    }

    @Override
    public void afterSingletonsInstantiated() {
        routes.getHandlerMethods().forEach((mapping, route) -> {
            if (OpenApiResource.class.isAssignableFrom(route.getBeanType())
                    && !mapping.getPatternValues().equals(Set.of(path))) {
                routes.unregisterMapping(mapping);
            }
        });
    }
}
