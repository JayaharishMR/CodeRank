package com.coderank.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Externalized rate-limit thresholds, bound from {@code application.yml}
 * under the {@code coderank.rate-limit} prefix.
 *
 * Keeping these values in config (not hard-coded) allows ops to tune limits
 * per environment without redeploying. Guests get a tighter cap because
 * unauthenticated traffic is the primary abuse vector.
 */
@Configuration
@ConfigurationProperties(prefix = "coderank.rate-limit")
public class RateLimitConfig {
    private int guestRequestsPerMinute = 5;
    private int userRequestsPerMinute = 20;

    public int getGuestRequestsPerMinute() { return guestRequestsPerMinute; }
    public void setGuestRequestsPerMinute(int v) { this.guestRequestsPerMinute = v; }
    public int getUserRequestsPerMinute() { return userRequestsPerMinute; }
    public void setUserRequestsPerMinute(int v) { this.userRequestsPerMinute = v; }
}
