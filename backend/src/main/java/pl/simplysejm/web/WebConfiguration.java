package pl.simplysejm.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
import pl.simplysejm.bootstrap.SejmProperties;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
    private final SejmProperties config;
    public WebConfiguration(SejmProperties config) { this.config = config; }
    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(config.corsOrigin())
                .allowedMethods("GET", "POST", "OPTIONS").allowedHeaders("Content-Type", "Accept").maxAge(3600);
    }
}
