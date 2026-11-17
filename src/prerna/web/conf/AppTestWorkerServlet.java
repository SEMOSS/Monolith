package prerna.web.conf;

import java.io.IOException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import prerna.testing.AppTestBrowserBridge;

/** App-independent worker; its per-client context is inert for ordinary browsing. */
@WebServlet(name = "AppTestWorkerServlet", urlPatterns = "/app-test-worker.js")
public class AppTestWorkerServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	@Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		response.setContentType("application/javascript; charset=utf-8");
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("Service-Worker-Allowed", request.getContextPath() + "/");
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.getWriter().write(AppTestBrowserBridge.script());
	}
}
