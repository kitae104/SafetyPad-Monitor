package kr.safetypad.monitor.config;

import java.time.Clock;
import kr.safetypad.monitor.admin.AdminUserDetailsService;
import kr.safetypad.monitor.device.DeviceKeyService;
import kr.safetypad.monitor.security.AdminSessionRevalidationFilter;
import kr.safetypad.monitor.security.DeviceApiKeyFilter;
import kr.safetypad.monitor.security.FixedWindowRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Separate security boundaries (a device key never opens the admin API and vice versa):
 *  1. /api/v1/device-events : stateless, X-API-Key only, no cookies/CSRF.
 *  2. /api/v1/admin/**      : server session (httpOnly cookie) + CSRF double-submit token on every mutation.
 *  3. everything else       : denied, except health.
 */
@Configuration
public class SecurityConfig {

    @Bean
    FixedWindowRateLimiter ingestRateLimiter(Clock clock) {
        return new FixedWindowRateLimiter(clock);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    AuthenticationManager adminAuthenticationManager(AdminUserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    @Order(1)
    SecurityFilterChain deviceChain(HttpSecurity http, DeviceKeyService keys, IngestProperties props,
                                    FixedWindowRateLimiter limiter) throws Exception {
        http.securityMatcher("/api/v1/device-events")
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(c -> c.disable())
            .anonymous(a -> a.disable())
            .addFilterBefore(new DeviceApiKeyFilter(keys, props, limiter), AuthorizationFilter.class)
            .authorizeHttpRequests(a -> a.anyRequest().hasRole("DEVICE"));
        return http.build();
    }

    /**
     * Prometheus scrape endpoint: its own Basic-auth identity, unrelated to admin accounts and device keys.
     * With METRICS_USERNAME/PASSWORD unset the endpoint is denied outright.
     */
    @Bean
    @Order(0)
    SecurityFilterChain metricsChain(HttpSecurity http, PasswordEncoder encoder,
                                     @Value("${safetypad.metrics.username:}") String user,
                                     @Value("${safetypad.metrics.password:}") String password) throws Exception {
        http.securityMatcher("/actuator/prometheus")
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        if (user.isBlank() || password.length() < 16) {
            http.authorizeHttpRequests(a -> a.anyRequest().denyAll());
        } else {
            DaoAuthenticationProvider provider = new DaoAuthenticationProvider(new InMemoryUserDetailsManager(
                    User.withUsername(user).password(encoder.encode(password)).roles("METRICS").build()));
            provider.setPasswordEncoder(encoder);
            // Write the 401 ourselves: sendError() would be re-dispatched to /error, which the deny-by-default chain turns into 403.
            AuthenticationEntryPoint entry = (req, res, ex) -> {
                res.setHeader("WWW-Authenticate", "Basic realm=\"safetypad-metrics\", charset=\"UTF-8\"");
                DeviceApiKeyFilter.error(res, 401, "UNAUTHENTICATED", "Metrics credentials required");
            };
            http.authenticationManager(new ProviderManager(provider))
                .httpBasic(b -> b.authenticationEntryPoint(entry))
                .exceptionHandling(e -> e.authenticationEntryPoint(entry))
                .authorizeHttpRequests(a -> a.anyRequest().hasRole("METRICS"));
        }
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain adminChain(HttpSecurity http, JdbcClient jdbc) throws Exception {
        http.securityMatcher("/api/v1/admin/**")
            .addFilterBefore(new AdminSessionRevalidationFilter(jdbc), AuthorizationFilter.class)
            .csrf(c -> c
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .sessionManagement(s -> s
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(f -> f.changeSessionId()))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable())
            .requestCache(c -> c.disable())
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/v1/admin/csrf", "/api/v1/admin/login").permitAll()
                .anyRequest().hasRole("ADMIN"))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) ->
                        DeviceApiKeyFilter.error(res, 401, "UNAUTHENTICATED", "Login required"))
                .accessDeniedHandler((req, res, ex) ->
                        DeviceApiKeyFilter.error(res, 403, "FORBIDDEN", "Forbidden or invalid CSRF token")));
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain defaultChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .anyRequest().denyAll());
        return http.build();
    }
}
