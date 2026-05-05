package com.secret_message.secret_message_app.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Resolves the client IP for downstream filters and the rate limiter.
 *
 * <p>When {@code server.forward-headers-strategy=NATIVE} is set in
 * {@code application.properties} (configured separately), Tomcat's RemoteIpValve
 * has already substituted {@code request.getRemoteAddr()} with the value from
 * a trusted X-Forwarded-For header. This filter validates the resulting IP
 * and stashes it as a request attribute so {@link RateLimitFilter} can key
 * its bucket on it.
 *
 * <p>Behavior by profile:
 * <ul>
 *   <li><b>prod</b>: rejects with HTTP 400 if the IP cannot be resolved
 *       (null, empty, or 0.0.0.0). Catches misconfigured deployments where
 *       the reverse proxy is bypassed.</li>
 *   <li><b>any other profile</b>: falls back to 127.0.0.1 so local development
 *       and the integration test suite work without a proxy in front.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class ClientIpFilter extends OncePerRequestFilter {

    public static final String CLIENT_IP_ATTRIBUTE = "secret_message.clientIp";

    private static final String PATH_PREFIX = "/api/";

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!request.getRequestURI().startsWith(PATH_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String ip = request.getRemoteAddr();

        if (ip == null || ip.isEmpty() || "0.0.0.0".equals(ip)) {
            if (isProductionProfile()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"client IP not resolvable\"}");
                return;
            }
            ip = "127.0.0.1";
        }

        request.setAttribute(CLIENT_IP_ATTRIBUTE, ip);
        chain.doFilter(request, response);
    }

    private boolean isProductionProfile() {
        return activeProfile != null
                && (activeProfile.equalsIgnoreCase("prod")
                || activeProfile.equalsIgnoreCase("production"));
    }
}
