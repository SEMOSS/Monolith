package prerna.web.conf;

import java.io.IOException;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import prerna.auth.User;
import prerna.testing.EngineTestContexts;
import prerna.util.Constants;

/** Owns routing only for this HTTP request; never changes the authenticated browser session's engines. */
@WebFilter(filterName = "AppTestEngineContextFilter", urlPatterns = "/*", asyncSupported = true)
public class AppTestEngineContextFilter implements Filter {
	@Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
		HttpServletRequest http = (HttpServletRequest) request;
		HttpServletResponse out = (HttpServletResponse) response;
		String token = http.getHeader(EngineTestContexts.HEADER);
		if (token == null) {
			if (http.getParameter(EngineTestContexts.PARAMETER) != null) {
				out.sendError(409, "Test database routing is not active. Open recording from the Testing panel.");
				return;
			}
			chain.doFilter(request, response); return;
		}
		HttpSession session = http.getSession(false);
		User user = session == null ? null : (User) session.getAttribute(Constants.SESSION_USER);
		EngineTestContexts.Scope scope;
		try {
			scope = EngineTestContexts.enterRequest(token, user, session == null ? null : session.getId());
		} catch (IllegalArgumentException e) {
			out.sendError(403, "App test context is invalid, expired or unauthorized. Production fallback is disabled."); return;
		}
		try (scope) {
			out.setHeader("X-Semoss-App-Test-Active", token);
			String base = http.getContextPath() + "/";
			if (http.getRequestURI().startsWith(base + "public_home/")) {
				String expected = base + "public_home/" + EngineTestContexts.validate(token, user, session.getId()).projectId() + "/portals/";
				if (!http.getRequestURI().startsWith(expected)) { out.sendError(403, "Test navigation left its app"); return; }
				// Keep test documents from bypassing HTTP routing through workers or websocket transports.
				String origin = http.getScheme() + "://" + http.getServerName()
						+ ((http.getServerPort() == 80 && http.getScheme().equals("http")) || (http.getServerPort() == 443 && http.getScheme().equals("https")) ? "" : ":" + http.getServerPort());
				out.setHeader("Content-Security-Policy", "worker-src 'none'; connect-src " + origin + base + "; form-action " + origin + base + ";");
				out.setHeader("Referrer-Policy", "same-origin");
			}
			out.setHeader("Cache-Control", "private, no-store");
			chain.doFilter(request, response);
		}
	}
}
