package exchange.api;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Set;

/**
 * Simple Bearer-token authentication filter.
 *
 * <p>Reads the expected API key from the {@code API_KEY} environment variable.
 * If the variable is not set, the filter passes all requests through (development mode).
 *
 * <p>Endpoints listed in {@link #OPEN_PATHS} bypass authentication entirely
 * so that load-balancer health probes work without credentials.
 */
public class ApiAuthFilter implements Filter {

    /** Paths that do not require authentication. */
    private static final Set<String> OPEN_PATHS = Set.of(
            "/api/v1/health",
            "/api/v1/ready"
    );

    private String expectedKey;

    @Override
    public void init(FilterConfig filterConfig) {
        expectedKey = System.getenv("API_KEY");
        if (expectedKey == null || expectedKey.isBlank()) {
            System.out.println("[ApiAuthFilter] API_KEY not set — running in open (no-auth) mode");
            expectedKey = null;
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  req  = (HttpServletRequest)  request;
        HttpServletResponse resp = (HttpServletResponse) response;

        String path = req.getRequestURI();

        // Exempt open paths
        if (OPEN_PATHS.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        // If no key is configured, allow everything
        if (expectedKey == null) {
            chain.doFilter(request, response);
            return;
        }

        // Validate Authorization: Bearer <key>
        String authHeader = req.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String providedKey = authHeader.substring(7).strip();
            if (expectedKey.equals(providedKey)) {
                chain.doFilter(request, response);
                return;
            }
        }

        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType("application/json; charset=UTF-8");
        resp.getWriter().write("{\"error\":\"Unauthorized\"}");
    }

    @Override
    public void destroy() {}
}
