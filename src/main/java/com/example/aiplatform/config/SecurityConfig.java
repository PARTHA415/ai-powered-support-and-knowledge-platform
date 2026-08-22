package com.example.aiplatform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * API-level (endpoint) authorization rules - the outermost layer of the
 * three-layer authorization model this phase builds:
 *   1. API level (here): can this role call this endpoint at all.
 *   2. Tool level (SupportTools, via CurrentUser): can this specific caller
 *      access this specific resource - ownership, not just role.
 *   3. Agent level: the agent workflow calls the same tools as (2), so it
 *      inherits the same enforcement automatically, at the same point.
 *
 * HTTP Basic auth, stateless sessions: this is a JSON API with no browser
 * login form or cookie-based session, so CSRF protection (which defends
 * against a browser being tricked into submitting an authenticated form)
 * doesn't apply here and is disabled deliberately, not by oversight.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // Aggregate operational data (request counts, latency histograms,
                        // liveness) with no per-customer content - same permitAll tier as
                        // the API docs above, and the standard way a scraper like Prometheus
                        // reaches this endpoint without needing app credentials configured
                        // in prometheus.yml. Nothing beyond health+prometheus is exposed
                        // (see management.endpoints.web.exposure.include) - actuator's other
                        // endpoints (env, beans, configprops...) stay unavailable entirely.
                        .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                        // Uploading knowledge-base content is a staff action, not a customer one.
                        .requestMatchers(HttpMethod.POST, "/api/documents").hasAnyRole("SUPPORT_AGENT", "ADMIN")
                        // Raw embedding generation is an internal/debug tool from Phase 4, not customer-facing.
                        .requestMatchers(HttpMethod.POST, "/api/embeddings").hasRole("ADMIN")
                        // Running the eval dataset makes real LLM calls (cost/latency) and is an
                        // internal quality-check tool (Phase 15), same tier as raw embeddings.
                        .requestMatchers(HttpMethod.POST, "/api/eval/run").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
