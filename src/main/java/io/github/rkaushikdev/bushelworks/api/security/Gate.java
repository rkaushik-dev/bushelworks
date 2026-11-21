package io.github.rkaushikdev.bushelworks.api.security;

import io.github.rkaushikdev.bushelworks.Settings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The door every route that {@link Requires} a role goes through.
 *
 * <p>It runs as an interceptor, before a single argument of the route is read, so a caller who may
 * not be here is told so before anybody looks at what they sent: a 401 or a 403 beats a 422. It also
 * hands the caller to any route that asks for a {@link Caller}.
 */
public final class Gate implements HandlerInterceptor, HandlerMethodArgumentResolver {

    private static final String CALLER = Gate.class.getName() + ".caller";

    private final Settings settings;

    public Gate(Settings settings) {
        this.settings = settings;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod route) {
            Requires needs = requirement(route);
            if (needs != null) {
                Caller caller = Security.resolve(settings, request.getHeader(Security.HEADER));
                Security.require(caller, needs.value());
                request.setAttribute(CALLER, caller);
            }
        }
        return true;
    }

    /** What a route needs: its own requirement, or else its controller's. */
    static Requires requirement(HandlerMethod route) {
        Requires own = AnnotatedElementUtils.findMergedAnnotation(route.getMethod(), Requires.class);
        return own != null ? own : AnnotatedElementUtils.findMergedAnnotation(route.getBeanType(), Requires.class);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Caller.class;
    }

    @Override
    public Caller resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer container,
            NativeWebRequest request,
            WebDataBinderFactory binders) {
        if (request.getAttribute(CALLER, NativeWebRequest.SCOPE_REQUEST) instanceof Caller caller) {
            return caller;
        }
        throw new IllegalStateException(
                parameter.getExecutable().getName() + " asks for a caller and requires no role to check one");
    }
}
