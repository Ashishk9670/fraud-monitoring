package com.example.fraud.security;

import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.server.WebSocketGraphQlInterceptor;
import org.springframework.graphql.server.WebSocketSessionInfo;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.RequestMatcher;
import reactor.core.publisher.Mono;

/**
 * Stateless API-key security. REST ({@code /api/**}), GraphQL over HTTP and Prometheus scraping need a key; the
 * dashboard shell, health probes and the WebSocket handshake are open (the WebSocket itself authenticates in
 * {@code connection_init}, because browsers cannot set headers on a WebSocket).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    static final String DEV_KEY = "dev-local-key";

    @Bean
    ApiKeys apiKeys(SecurityProperties props) {
        if (props.apiKeys().isEmpty()) {
            throw new IllegalStateException("fraud.security.api-keys (FRAUD_API_KEYS) must define at least one key");
        }
        if (props.apiKeys().contains(DEV_KEY)) {
            log.warn("Using the default development API key; set FRAUD_API_KEYS in any shared environment");
        }
        return new ApiKeys(props.apiKeys());
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeys keys) throws Exception {
        RequestMatcher webSocketUpgrade = req -> HttpMethod.GET.matches(req.getMethod())
                && "websocket".equalsIgnoreCase(req.getHeader("Upgrade"));
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(new ApiKeyAuthFilter(keys), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/", "/index.html", "/favicon.ico", "/error", "/graphiql", "/graphiql/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(webSocketUpgrade).permitAll()
                        .anyRequest().hasRole("API"));
        return http.build();
    }

    /** Authenticates GraphQL-over-WebSocket in {@code connection_init} ({@code {"apiKey": "..."}}) or by header. */
    @Bean
    WebSocketGraphQlInterceptor webSocketAuth(ApiKeys keys) {
        return new WebSocketGraphQlInterceptor() {
            @Override
            public Mono<Object> handleConnectionInitialization(
                    WebSocketSessionInfo info, Map<String, Object> payload) {
                Object fromPayload = payload == null ? null : payload.get("apiKey");
                String fromHeader = info.getHeaders().getFirst(ApiKeys.HEADER);
                if (keys.isValid(fromPayload instanceof String s ? s : null) || keys.isValid(fromHeader)) {
                    return Mono.empty();
                }
                return Mono.error(new SecurityException("Unauthorized"));
            }
        };
    }

    @Bean
    Instrumentation graphQlDepthLimit(SecurityProperties props) {
        return new MaxQueryDepthInstrumentation(props.maxGraphQlDepth());
    }
}
