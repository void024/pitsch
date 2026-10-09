package com.pitsch.backend.config;

import java.util.List;

import com.pitsch.backend.auth.AuthInterceptor;
import com.pitsch.backend.auth.AuthPrincipalResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** MVC wiring: authentication interceptor and principal resolver. CORS is a servlet filter, see {@link CorsConfig}. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AuthPrincipalResolver principalResolver;

    public WebConfig(AuthInterceptor authInterceptor, AuthPrincipalResolver principalResolver) {
        this.authInterceptor = authInterceptor;
        this.principalResolver = principalResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // API docs are only served when API_DOCS_ENABLED=true (springdoc is otherwise off) and are read-only metadata.
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**")
                .excludePathPatterns("/api/v1/openapi", "/api/v1/openapi/**", "/api/docs", "/api/docs/**", "/api/swagger-ui/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(principalResolver);
    }
}
