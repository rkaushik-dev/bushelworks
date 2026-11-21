package io.github.rkaushikdev.bushelworks.api.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The role a route needs. On a controller it covers every route in it; on a route it decides for
 * that route alone. A route with neither is open to anybody, which is what health checks and the API
 * document want.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Requires {

    /** The least senior role that may call. */
    Role value();
}
