package prerna.web.conf;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.Gson;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import prerna.reactor.apptest.RegressionPixelAccess;

/** Loopback-only HTTP boundary for cookie-free H2 regression playback. */
public final class RegressionPixelServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Pattern PATH = Pattern.compile(
            "/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/(ready|api/engine/runPixel)");
    private static final Set<String> PARAMETERS = Set.of("expression", "insightId", "tz");
    private static final Gson JSON = new Gson();

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (!"127.0.0.1".equals(request.getRemoteAddr()) || request.getQueryString() != null
                || request.getHeader("Cookie") != null || request.getHeader("Authorization") != null) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        String path = request.getPathInfo();
        var route = PATH.matcher(path == null ? "" : path);
        if (!route.matches()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        try {
            String runId = route.group(1);
            var session = RegressionPixelAccess.authenticate(runId, request.getHeader(RegressionPixelAccess.HEADER));
            if ("ready".equals(route.group(2)) && "GET".equals(request.getMethod())) {
                response.setHeader("X-Semoss-Regression-Ready", runId);
                response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            } else if ("api/engine/runPixel".equals(route.group(2)) && "POST".equals(request.getMethod())) {
                if (!"application/x-www-form-urlencoded".equalsIgnoreCase(request.getContentType())) {
                    response.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
                    return;
                }
                byte[] body = request.getInputStream().readNBytes(RegressionPixelAccess.MAX_REQUEST_BYTES + 1);
                if (body.length > RegressionPixelAccess.MAX_REQUEST_BYTES) {
                    response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                    return;
                }
                Map<String, String> form = form(new String(body, StandardCharsets.UTF_8));
                Map<String, Object> result = session.execute(form.get("expression"), form.get("insightId"));
                response.setContentType("application/json;charset=UTF-8");
                JSON.toJson(result, response.getWriter());
            } else {
                response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            }
        } catch (SecurityException e) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        } catch (IllegalArgumentException | IllegalStateException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        }
    }

    static Map<String, String> form(String body) {
        Map<String, String> result = new HashMap<>();
        for (String field : body.split("&", -1)) {
            int equals = field.indexOf('=');
            if (equals < 1) {
                throw new IllegalArgumentException("Invalid SDK form");
            }
            String key = URLDecoder.decode(field.substring(0, equals), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(field.substring(equals + 1), StandardCharsets.UTF_8);
            if (!PARAMETERS.contains(key) || result.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Unexpected or duplicate SDK form parameter");
            }
        }
        return result;
    }
}
