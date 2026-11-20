package kr.safetypad.monitor.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Dev only: serves the contract-first OpenAPI files and Swagger UI. In any other profile these paths are denied. */
@Configuration
@Profile("dev")
public class DevDocsConfig implements WebMvcConfigurer {
    private final String contractsDir;

    public DevDocsConfig(@Value("${safetypad.docs.contracts-dir:../contracts}") String contractsDir) {
        this.contractsDir = contractsDir.endsWith("/") ? contractsDir : contractsDir + "/";
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/api-docs/**").addResourceLocations("file:" + contractsDir);
    }

    @Bean
    @Order(0)
    SecurityFilterChain devDocsChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/swagger.html", "/api-docs/**")
            .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }
}
