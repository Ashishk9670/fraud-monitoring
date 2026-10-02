package com.example.fraud.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates a request when it carries a valid API key; otherwise leaves it anonymous (the chain then rejects). */
public class ApiKeyAuthFilter extends OncePerRequestFilter {
    private final ApiKeys keys;

    public ApiKeyAuthFilter(ApiKeys keys) {
        this.keys = keys;
    }

    /** SSE / async GraphQL responses are re-dispatched; the stateless context must be rebuilt for those too. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (keys.isValid(ApiKeys.extract(req))) {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    "api-client", null, List.of(new SimpleGrantedAuthority("ROLE_API"))));
        }
        chain.doFilter(req, res);
    }
}
