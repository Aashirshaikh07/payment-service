package com.aashir.payment.config;

import com.aashir.payment.security.ServiceAuthenticationFilter;
import com.aashir.payment.service.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, ServiceAuthenticationFilter serviceAuthenticationFilter, JwtAuthenticationFilter jwtAuthenticationFilter
    ) throws Exception {
        return http
                .csrf(csrf ->csrf.disable())
                .addFilterBefore(
                        serviceAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                )
                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                )
                .authorizeHttpRequests(auth->auth
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().authenticated()
                )
                .build();
    }
}
