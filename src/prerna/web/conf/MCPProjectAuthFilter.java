package prerna.web.conf;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.util.Constants;
import prerna.util.MCP.MCPUrlUtility;

/**
 * Filter to handle MCP authentication for project-specific endpoints.
 *
 * This filter runs AFTER UserAccessKeyFilter, which handles the actual
 * Bearer token validation with the OAuth provider. This filter:
 * 1. Checks if user was authenticated by UserAccessKeyFilter
 * 2. Sends OAuth-compliant 401 responses if not authenticated
 * 3. Extracts project ID from URL and sets it in request attributes
 *
 * The 401 response includes WWW-Authenticate header per RFC 6750,
 * which MCP clients (like ChatGPT) need for OAuth discovery.
 */
public class MCPProjectAuthFilter implements Filter {

	private static final Logger classLogger = LogManager.getLogger(MCPProjectAuthFilter.class);
	private static final Pattern PROJECT_ID_PATTERN = Pattern.compile("/api/mcp/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {

		HttpServletRequest httpRequest = (HttpServletRequest) request;
		HttpServletResponse httpResponse = (HttpServletResponse) response;

		String uri = httpRequest.getRequestURI();
		String method = httpRequest.getMethod();
		classLogger.info(">>>>> MCPProjectAuthFilter - " + method + " " + uri);

		// Skip OAuth discovery endpoints (they don't need authentication)
		if (uri.contains("/.well-known/")) {
			classLogger.info("MCPProjectAuthFilter - OAuth discovery endpoint, skipping auth");
			chain.doFilter(request, response);
			return;
		}

		// Extract project ID from URL
		String projectId = extractProjectId(uri);

		// Only require authentication if project ID is present
		if (projectId == null) {
			classLogger.info("MCPProjectAuthFilter - no project ID in URL, skipping auth");
			chain.doFilter(request, response);
			return;
		}

		classLogger.info("MCPProjectAuthFilter - extracted project ID: " + projectId);

		// Check if user was authenticated by UserAccessKeyFilter
		HttpSession session = httpRequest.getSession(false);
		User user = null;
		if (session != null) {
			user = (User) session.getAttribute(Constants.SESSION_USER);
		}

		if (user == null) {
			// No authenticated user - send OAuth-compliant 401
			classLogger.warn("MCPProjectAuthFilter - no authenticated user, sending 401");
			send401(httpRequest, httpResponse, "invalid_token", "Bearer token required");
			return;
		}

		classLogger.info("MCPProjectAuthFilter - user authenticated: " + user.getPrimaryLogin());

		// Store project ID in request attributes for downstream use
		httpRequest.setAttribute("mcp_project_id", projectId);
		httpRequest.setAttribute("mcp_authenticated_user", user);

		classLogger.info("MCPProjectAuthFilter - SUCCESS! User authenticated for project " + projectId);

		// Wrap the request to allow subsequent reading and continue filter chain
		HttpServletRequestWrapper requestWrapper = new HttpServletRequestWrapper(httpRequest);
		chain.doFilter(requestWrapper, response);
	}

	/**
	 * Extract project ID (UUID) from URL path
	 */
	private String extractProjectId(String uri) {
		Matcher matcher = PROJECT_ID_PATTERN.matcher(uri);
		if (matcher.find()) {
			return matcher.group(1);
		}
		return null;
	}

	/**
	 * Send 401 Unauthorized response with WWW-Authenticate header per RFC 6750.
	 * MCP clients need this header for OAuth discovery.
	 */
	private void send401(HttpServletRequest request, HttpServletResponse response,
			String error, String errorDescription) throws IOException {

		String baseUrl = MCPUrlUtility.getExternalBaseUrl(request);
		String resourceMetadataUrl = baseUrl + "/api/mcp/.well-known/oauth-protected-resource";

		String wwwAuthenticate = String.format(
			"Bearer resource_metadata=\"%s\", error=\"%s\", error_description=\"%s\"",
			resourceMetadataUrl, error, errorDescription
		);

		response.setHeader("WWW-Authenticate", wwwAuthenticate);
		response.setStatus(401);
		response.setContentType("application/json");
		response.getWriter().write(
			"{\"error\":\"" + error + "\",\"error_description\":\"" + errorDescription + "\"}"
		);

		classLogger.info("Sent 401 with WWW-Authenticate: " + wwwAuthenticate);
	}

	@Override
	public void init(FilterConfig config) throws ServletException {
		classLogger.info(">>>>> MCPProjectAuthFilter INITIALIZED - MCP auth check for /api/mcp/*");
	}

	@Override
	public void destroy() {
		classLogger.info("MCPProjectAuthFilter DESTROYED");
	}
}
