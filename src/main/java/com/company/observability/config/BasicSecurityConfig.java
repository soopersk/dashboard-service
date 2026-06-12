package com.company.observability.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class BasicSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/health",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .httpBasic(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(
            @Value("${observability.security.basic.username:admin}") String username,
            @Value("${observability.security.basic.password:admin}") String password,
            @Value("${observability.security.basic.role:USER}") String role,
            @Value("${observability.security.admin.username:ops}") String adminUsername,
            @Value("${observability.security.admin.password:ops}") String adminPassword) {

        UserDetails appUser = User.withUsername(username)
                .password("{noop}" + password)
                .roles(role)
                .build();

        UserDetails adminUser = User.withUsername(adminUsername)
                .password("{noop}" + adminPassword)
                .roles("ADMIN")
                .build();

        return new InMemoryUserDetailsManager(appUser, adminUser);
    }
}
