package io.github.rkaushikdev.bushelworks.api;

import io.github.rkaushikdev.bushelworks.BushelworksError;
import io.github.rkaushikdev.bushelworks.ConfigurationError;
import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.LookupFailure;
import io.github.rkaushikdev.bushelworks.ValidationError;
import io.github.rkaushikdev.bushelworks.api.schemas.Body;
import io.github.rkaushikdev.bushelworks.api.security.Forbidden;
import io.github.rkaushikdev.bushelworks.api.security.NotAuthorised;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * One error shape for every failure the API can return.
 *
 * <p>The touch screen in the scale house shows {@code detail} to whoever is standing at the window,
 * so it has to be a sentence rather than a stack frame. The spreadsheet keys on {@code code}, so the
 * code has to be stable even when the sentence is reworded.
 *
 * <p>The mapping from domain error to status is deliberate and short:
 *
 * <ul>
 *   <li>a validation error is a 422, because the request was understood and refused
 *   <li>a lookup failure is a 404
 *   <li>a conflict is a 409
 *   <li>a configuration error is a 500, because it is the house's fault and not the caller's
 * </ul>
 *
 * <p>A request that could not be read at all is a 422 too, with code {@code invalid_request} and a
 * {@code fields} list naming each refused field, so the terminal reads it like every other refusal.
 */
@RestControllerAdvice
public class Errors {

    /** The code every unreadable request is answered with. */
    public static final String INVALID_REQUEST = "invalid_request";

    /**
     * Which status each kind of error comes back as, tried in order. Ordered rather than looked up
     * because the classes overlap: a lookup failure is also a domain error, and the most specific
     * match has to win.
     */
    static final List<Map.Entry<Class<? extends BushelworksError>, Integer>> STATUS_FOR = List.of(
            Map.entry(NotAuthorised.class, 401),
            Map.entry(Forbidden.class, 403),
            Map.entry(LookupFailure.class, 404),
            Map.entry(ConflictError.class, 409),
            Map.entry(ValidationError.class, 422),
            Map.entry(ConfigurationError.class, 500));

    private static final List<String> PARTS = List.of("body", "query", "path", "header");

    /** The status this error should come back as; 400 for a domain error of no known kind. */
    public static int statusFor(BushelworksError error) {
        for (Map.Entry<Class<? extends BushelworksError>, Integer> kind : STATUS_FOR) {
            if (kind.getKey().isInstance(error)) {
                return kind.getValue();
            }
        }
        return 400;
    }

    /** The body a domain error is answered with: its code, its sentence, and the request it ended. */
    public static Map<String, Object> problem(BushelworksError error, Optional<String> requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", error.code());
        body.put("detail", error.getMessage());
        requestId.filter(id -> !id.isEmpty()).ifPresent(id -> body.put("request_id", id));
        return body;
    }

    /**
     * A location as a person reads it: {@code ["body", "splits", 0, "percent"]} is {@code
     * splits.0.percent}. The leading part of the request is dropped, since the caller knows which part
     * they sent; a location that is only that part reads as {@code body}.
     */
    public static String fieldPath(List<?> location) {
        List<String> parts = location.stream().map(String::valueOf).toList();
        if (!parts.isEmpty() && PARTS.contains(parts.get(0))) {
            parts = parts.subList(1, parts.size());
        }
        return parts.isEmpty() ? "body" : String.join(".", parts);
    }

    /** The sentence a refused request is summed up in: the field and why for one, a count for several. */
    public static String sentence(List<Refusal> refusals) {
        if (refusals.size() == 1) {
            return refusals.get(0).field() + ": " + refusals.get(0).message();
        }
        String named = refusals.stream().map(Refusal::field).collect(Collectors.joining(", "));
        return refusals.size() + " fields were refused: " + named;
    }

    /** The body a refused request is answered with, in the same shape as every other refusal. */
    public static Map<String, Object> validationProblem(List<Refusal> refusals, Optional<String> requestId) {
        List<Map<String, String>> fields = new ArrayList<>();
        for (Refusal refusal : refusals) {
            Map<String, String> field = new LinkedHashMap<>();
            field.put("field", refusal.field());
            field.put("detail", refusal.message());
            fields.add(field);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", INVALID_REQUEST);
        body.put("detail", sentence(refusals));
        body.put("fields", fields);
        requestId.filter(id -> !id.isEmpty()).ifPresent(id -> body.put("request_id", id));
        return body;
    }

    @ExceptionHandler(BushelworksError.class)
    public ResponseEntity<Map<String, Object>> domain(BushelworksError error, HttpServletRequest request) {
        return ResponseEntity.status(statusFor(error)).body(problem(error, RequestIdFilter.of(request)));
    }

    @ExceptionHandler(RequestRefused.class)
    public ResponseEntity<Map<String, Object>> refused(RequestRefused refused, HttpServletRequest request) {
        return ResponseEntity.unprocessableEntity()
                .body(validationProblem(refused.refusals(), RequestIdFilter.of(request)));
    }

    /**
     * A body that could not be read: a shape that refused its fields, no body at all, or text that
     * is not JSON.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(
            HttpMessageNotReadableException failure, HttpServletRequest request) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RequestRefused refused) {
                return refused(refused, request);
            }
        }
        String why = failure.getMessage() != null && failure.getMessage().startsWith("Required request body")
                ? Body.FIELD_REQUIRED
                : "JSON decode error";
        return refused(RequestRefused.of(new Refusal(List.of("body"), why)), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> missing(
            MissingServletRequestParameterException failure, HttpServletRequest request) {
        return refused(
                RequestRefused.of(Refusal.of("query", failure.getParameterName(), Body.FIELD_REQUIRED)), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> mismatched(
            MethodArgumentTypeMismatchException failure, HttpServletRequest request) {
        Refusal refusal =
                Refusal.of(partOf(failure.getParameter()), failure.getName(), expected(failure.getRequiredType()));
        return refused(RequestRefused.of(refusal), request);
    }

    /** A query or path parameter outside the bounds its route declares. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> outOfBounds(
            HandlerMethodValidationException failure, HttpServletRequest request) {
        List<Refusal> refusals = new ArrayList<>();
        failure.getParameterValidationResults().forEach(result -> {
            MethodParameter parameter = result.getMethodParameter();
            result.getResolvableErrors()
                    .forEach(error ->
                            refusals.add(Refusal.of(partOf(parameter), nameOf(parameter), error.getDefaultMessage())));
        });
        return refused(new RequestRefused(refusals), request);
    }

    /** A path nothing is served at. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException failure) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "Not Found"));
    }

    /** A path served, but not for the method asked. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> notAllowed(HttpRequestMethodNotSupportedException failure) {
        ResponseEntity.BodyBuilder answer = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (failure.getSupportedHttpMethods() != null) {
            answer.header(
                    HttpHeaders.ALLOW,
                    failure.getSupportedHttpMethods().stream()
                            .map(Object::toString)
                            .collect(Collectors.joining(", ")));
        }
        return answer.body(Map.of("detail", "Method Not Allowed"));
    }

    /** What a parameter of a type was expected to look like, in the words a caller is shown. */
    static String expected(Class<?> type) {
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return "Input should be a valid integer, unable to parse string as an integer";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "Input should be a valid boolean, unable to interpret input";
        }
        if (type == BigDecimal.class) {
            return "Input should be a valid decimal";
        }
        if (type == LocalDate.class) {
            return Body.NOT_A_DATE;
        }
        return "Input should be valid";
    }

    private static String partOf(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(PathVariable.class) ? "path" : "query";
    }

    /** The name a parameter is bound by, which is the one the caller sent. */
    private static String nameOf(MethodParameter parameter) {
        RequestParam query = parameter.getParameterAnnotation(RequestParam.class);
        if (query != null) {
            return named(query.name(), query.value(), parameter);
        }
        PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
        if (path != null) {
            return named(path.name(), path.value(), parameter);
        }
        return parameter.getParameterName();
    }

    private static String named(String name, String value, MethodParameter parameter) {
        if (!name.isEmpty()) {
            return name;
        }
        return value.isEmpty() ? parameter.getParameterName() : value;
    }
}
