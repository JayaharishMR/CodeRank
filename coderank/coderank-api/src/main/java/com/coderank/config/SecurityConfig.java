package com.coderank.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * HTTP security configuration for the CodeRank API.
 *
 * The API is stateless (JWT-based), so sessions and CSRF protection are
 * unnecessary. Guest users can submit code without signing in to lower the
 * barrier to entry, but retrieving past submissions requires authentication
 * so that user history stays private.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(Customizer.withDefaults())
            // CSRF is disabled because the API is stateless and uses JWT bearer
            // tokens -- there are no cookies for an attacker to exploit.
            .csrf(csrf -> csrf.disable())
            // No server-side session; every request must carry its own JWT.
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/health").permitAll()
                .requestMatchers("/api/v1/languages").permitAll()
                // WebSocket handshake must be unauthenticated; the WS layer
                // handles its own auth once the connection upgrades.
                .requestMatchers("/ws/**").permitAll()
                // Problem listing and detail endpoints are public so users can
                // browse problems without signing in.
                .requestMatchers(HttpMethod.GET, "/api/v1/problems/**").permitAll()
                // POST submissions is open to guests so anonymous users can
                // try the code runner without creating an account first.
                .requestMatchers(HttpMethod.POST, "/api/v1/submissions").permitAll()
                // GET submissions is open so guests can view their own submission results
                // (identified by submission ID, not user ID).
                .requestMatchers(HttpMethod.GET, "/api/v1/submissions/**").permitAll()
                .requestMatchers("/api/v1/submissions/*/feedback").permitAll()
                .anyRequest().authenticated()
            )
            // Validate incoming JWTs via the standard OAuth2 resource-server
            // flow (issuer, audience, signature checked automatically).
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:5173"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
