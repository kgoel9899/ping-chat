package com.chatapp.imageservice.config;

import com.chatapp.imageservice.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// @EnableWebSecurity - Activates Spring Security for this application — without it, no security is applied at all.
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) /* Disables CSRF protection. Safe here because this is a stateless REST API using JWT — CSRF attacks only apply to session-cookie-based auth, not token-based auth. */
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) /* For stateless microservices */
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
            // Inserts your custom JwtAuthFilter into the filter chain before Spring's default username/password filter.
            // Request comes in
            // → JwtAuthFilter runs first
            // → extracts + validates JWT
            // → sets authenticated user in SecurityContext
            // → Spring's auth checks pass
            // → request reaches controller

        return http.build();
    }
}
