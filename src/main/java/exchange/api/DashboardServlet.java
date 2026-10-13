package exchange.api;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;

/**
 * Serves the single-page dashboard from {@code src/main/resources/static/index.html}.
 *
 * <p>Mapped as the default servlet ("/"), so it only answers the site root and
 * {@code /index.html}; any other unmatched path returns a JSON 404. The page lives
 * outside {@code /api/v1/*}, so it is not behind the API key filter. The page itself
 * asks the viewer for the key and sends it with each API call.</p>
 */
public class DashboardServlet extends HttpServlet {

    private static final String RESOURCE = "/static/index.html";
    private volatile byte[] page;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getRequestURI();
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            JsonUtil.writeError(resp, 404, "Not found: " + path);
            return;
        }

        byte[] bytes = load();
        if (bytes == null) {
            JsonUtil.writeError(resp, 500, "Dashboard page is missing from this build");
            return;
        }

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/html; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setContentLength(bytes.length);
        resp.getOutputStream().write(bytes);
    }

    private byte[] load() throws IOException {
        byte[] cached = page;
        if (cached != null) return cached;
        try (InputStream in = DashboardServlet.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return null;
            cached = in.readAllBytes();
            page = cached;
            return cached;
        }
    }
}