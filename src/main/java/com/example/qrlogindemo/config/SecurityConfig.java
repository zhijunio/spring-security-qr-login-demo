package com.example.qrlogindemo.config;

import com.example.qrlogindemo.auth.MobileAppTokenService;
import com.example.qrlogindemo.auth.MobileBearerTokenFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService users(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(User.withUsername("demo")
                .password(encoder.encode("changeit"))
                .roles("USER")
                .build());
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    @Order(1)
    SecurityFilterChain mobileApi(HttpSecurity http, MobileAppTokenService tokens, UserDetailsService users)
            throws Exception {
        return http.securityMatcher("/api/mobile/**")
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET, "/api/mobile/csrf")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/mobile/login")
                        .authenticated()
                        .requestMatchers("/api/mobile/me")
                        .authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/mobile/logout")
                        .authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/mobile/qr-sessions/*/scan")
                        .authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/mobile/qr-sessions/*/approve")
                        .authenticated()
                        .anyRequest()
                        .denyAll())
                .httpBasic(Customizer.withDefaults())
                .addFilterAfter(new MobileBearerTokenFilter(tokens, users), BasicAuthenticationFilter.class)
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                        .sessionFixation(fixation -> fixation.none()))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain browser(HttpSecurity http, SecurityContextRepository contexts) throws Exception {
        return http.securityContext(context -> context.securityContextRepository(contexts))
                .csrf(csrf -> csrf.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/",
                                "/index.html",
                                "/styles.css",
                                "/app.js",
                                "/mobile.html",
                                "/mobile.js",
                                "/api/csrf",
                                "/api/qr-sessions",
                                "/api/qr-sessions/*",
                                "/api/qr-sessions/*/qr")
                        .permitAll()
                        .requestMatchers("/api/me", "/api/logout")
                        .authenticated()
                        .anyRequest()
                        .denyAll())
                .exceptionHandling(
                        ex -> ex.authenticationEntryPoint((request, response, cause) -> response.sendError(401)))
                .build();
    }
}
