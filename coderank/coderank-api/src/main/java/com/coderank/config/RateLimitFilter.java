package com.coderank.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket rate limiter that guards the code-submission endpoint.
 *
 * Only POST /submissions is rate-limited because that is the only endpoint
 * that triggers expensive work (Docker container creation). Read-only
 * endpoints are cheap and already require authentication, so they do not
 * need throttling.
 *
 * Buckets are keyed per-user (by principal) or per-IP (for guests) so that
 * one abusive client cannot starve others.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitConfig config;
    // In-memory map is acceptable for a single-node deployment; for a
    // multi-node setup this should move to Redis-backed Bucket4j.
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitConfig config) {
        this.config = config;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        // Skip non-submission requests -- only code execution is expensive
        // enough to warrant rate limiting.
        if (!request.getRequestURI().startsWith("/api/v1/submissions") || !"POST".equals(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = resolveKey(request);
        // computeIfAbsent is safe here because ConcurrentHashMap guarantees
        // at-most-one bucket creation per key under contention.
        Bucket bucket = buckets.computeIfAbsent(key, k -> createBucket(isAuthenticated()));

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.getWriter().write("{\"error\":\"Rate limit exceeded\"}");
        }
    }

    private boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // Spring Security sets principal to "anonymousUser" for unauthenticated
        // requests that pass through the filter chain -- treat those as guests.
        return auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal());
    }

    /**
     * Authenticated users are keyed by principal name so their limit follows
     * them across IPs (e.g. mobile vs desktop). Guests fall back to IP,
     * which is the only stable identifier we have for anonymous traffic.
     */
    private String resolveKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return "user:" + auth.getName();
        }
        return "guest:" + request.getRemoteAddr();
    }

    private Bucket createBucket(boolean authenticated) {
        int rpm = authenticated ? config.getUserRequestsPerMinute() : config.getGuestRequestsPerMinute();
        // Greedy refill restores all tokens at once when the window resets,
        // giving users their full burst capacity each minute.
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(rpm)
                        .refillGreedy(rpm, Duration.ofMinutes(1))
                        .build())
                .build();
    }
}
