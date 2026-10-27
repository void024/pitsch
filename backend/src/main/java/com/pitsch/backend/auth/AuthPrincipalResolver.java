package com.pitsch.backend.auth;

import com.pitsch.backend.common.ApiException;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Injects the {@link AuthPrincipal} built by AuthInterceptor into controller methods. */
@Component
public class AuthPrincipalResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AuthPrincipal.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object principal = webRequest.getAttribute(AuthPrincipal.REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (principal instanceof AuthPrincipal p) {
            return p;
        }
        throw ApiException.unauthorized("Please sign in again.");
    }
}
