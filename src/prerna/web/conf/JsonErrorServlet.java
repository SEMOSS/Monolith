package prerna.web.conf;

import java.io.IOException;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.gson.Gson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Servlet that handles Tomcat error pages and returns JSON responses
 * instead of the default HTML error pages.
 */
public class JsonErrorServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Gson GSON = new Gson();

	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
		// Tomcat sets these request attributes when forwarding to error pages
		Integer statusCode = (Integer) request.getAttribute("javax.servlet.error.status_code");
		String message = (String) request.getAttribute("javax.servlet.error.message");
		String requestUri = (String) request.getAttribute("javax.servlet.error.request_uri");

		if (statusCode == null) {
			statusCode = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
		}
		if (message == null || message.isEmpty()) {
			message = getDefaultMessage(statusCode);
		}

		Map<String, Object> errorBody = new LinkedHashMap<>();
		errorBody.put("status", statusCode);
		errorBody.put("error", message);
		if (requestUri != null) {
			errorBody.put("request_uri", requestUri);
		}

		response.setStatus(statusCode);
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write(GSON.toJson(errorBody));
	}

	private static String getDefaultMessage(int statusCode) {
		switch (statusCode) {
			case 400: return "Bad Request";
			case 401: return "Unauthorized";
			case 403: return "Forbidden";
			case 404: return "Not Found";
			case 405: return "Method Not Allowed";
			case 500: return "Internal Server Error";
			case 502: return "Bad Gateway";
			case 503: return "Service Unavailable";
			default:  return "Error";
		}
	}
}
