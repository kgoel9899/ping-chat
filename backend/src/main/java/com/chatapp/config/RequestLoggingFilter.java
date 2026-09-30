package com.chatapp.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Skip logging for static file requests
        String uri = request.getRequestURI();
        if (uri.startsWith("/css/") || uri.startsWith("/js/") || uri.equals("/index.html") || uri.equals("/")) {
            filterChain.doFilter(request, response);
            return;
        }

        long start = System.currentTimeMillis();
        String method = request.getMethod();
        String auth = request.getHeader("Authorization");
        String user = auth != null ? "(auth)" : "(anon)";

        log.info(">>> {} {} {} from {}", method, uri, user, request.getRemoteAddr());

        filterChain.doFilter(request, response);

        long duration = System.currentTimeMillis() - start;
        int status = response.getStatus();
        log.info("<<< {} {} {} {}ms", method, uri, status, duration);
    }
}
