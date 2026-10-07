package com.aashir.payment.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class ServiceAuthenticationFilter extends OncePerRequestFilter {

    @Value("${service.security.secret}")
    private String serviceSecret;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if(request.getRequestURI().startsWith("/internal")){

            String providedSecret = request.getHeader("X-Service-Secret");

            if(!serviceSecret.equals(providedSecret)){
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getWriter().write("Unauthorized service");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
