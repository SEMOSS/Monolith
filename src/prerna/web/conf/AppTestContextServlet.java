package prerna.web.conf;

import java.io.IOException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import prerna.testing.EngineTestContexts;

/** Fail-before-load handshake: verifies the deployed request filter is actually active. */
@WebServlet(name = "AppTestContextServlet", urlPatterns = "/app-test-context")
public class AppTestContextServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	@Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String token = EngineTestContexts.currentToken();
		if (token == null || !token.equals(request.getHeader(EngineTestContexts.HEADER))) {
			response.sendError(409, "Test database routing is not installed or active"); return;
		}
		response.setHeader("X-Semoss-App-Test-Active", token);
		response.setHeader("Cache-Control", "no-store");
		response.setContentType("application/json; charset=utf-8");
		response.getWriter().write("{\"active\":true}");
	}
}
