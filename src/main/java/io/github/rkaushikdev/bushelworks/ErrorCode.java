package io.github.rkaushikdev.bushelworks;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The code an error class reports, when it is not the one its name gives.
 *
 * <p>Deliberately not inherited: a subclass that names no code of its own gets one from its own
 * class name, so a new error is never reported under its parent's code.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ErrorCode {

    /** The code, in lower snake case. */
    String value();
}
