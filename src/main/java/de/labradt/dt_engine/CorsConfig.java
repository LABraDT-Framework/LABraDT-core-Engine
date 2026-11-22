package de.labradt.dt_engine;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Opens every endpoint under this application to requests from any
 * origin. Needed because the Gateway, the Service Manager and the
 * monitor dashboard (served from this same application, but loaded and
 * used as if it were a separate frontend) all call this engine's REST
 * API from a different port than whichever one happens to be serving a
 * given browser tab, which the browser's own same origin policy would
 * otherwise block.
 *
 * This is intentionally permissive for a thesis demo running entirely
 * on localhost. A deployment reachable from outside a trusted network
 * would want to replace allowedOriginPatterns("*") with an explicit list
 * of the Gateway's and dashboard's real origins.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}