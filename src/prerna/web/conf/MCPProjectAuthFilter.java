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
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.AccessToken;
import prerna.auth.User;
import prerna.auth.mcp.MCPTokenStore;
import prerna.util.Constants;
import prerna.util.MCP.MCPUrlUtility;

/**
 * Filter to handle MCP JWT Bearer token authentication for project-specific endpoints.
 * Validates JWT tokens with project-specific audience claims for /api/mcp/{project_id} endpoints.
 *
 * This filter:
 * 1. Extracts project ID from URL path
 * 2. Validates JWT Bearer token with project-specific audience
 * 3. Sets authenticated user in session and request attributes
 * 4. Allows the request to continue to StandardMCPResource
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

		// Extract project ID from URL
		String projectId = extractProjectId(uri);

		// Skip OAuth discovery endpoints (they don't need JWT authentication)
		if (uri.contains("/.well-known/")) {
			classLogger.info("MCPProjectAuthFilter - OAuth discovery endpoint, skipping auth");
			chain.doFilter(request, response);
			return;
		}

		// Only authenticate if project ID is present
		if (projectId == null) {
			classLogger.info("MCPProjectAuthFilter - no project ID in URL, skipping auth");
			chain.doFilter(request, response);
			return;
		}

		classLogger.info("MCPProjectAuthFilter - extracted project ID: " + projectId);

		// Check if user already authenticated in session
		HttpSession session = httpRequest.getSession(false);
		User user = null;
		if (session != null) {
			user = (User) session.getAttribute(Constants.SESSION_USER);
		}

		if (user != null) {
			classLogger.info("MCPProjectAuthFilter - user already authenticated: " + user.getPrimaryLogin());
			// Store project ID in request attribute for resource to use
			httpRequest.setAttribute("mcp_project_id", projectId);
			chain.doFilter(request, response);
			return;
		}

		// Extract Bearer token
		String authHeader = httpRequest.getHeader("Authorization");
		if (authHeader == null || !authHeader.startsWith("Bearer ")) {
			classLogger.warn("MCPProjectAuthFilter - no Bearer token, sending 401");
			send401(httpRequest, httpResponse, "invalid_token", "Bearer token required");
			return;
		}

		String token = authHeader.substring(7).trim();
		classLogger.info("MCPProjectAuthFilter - validating JWT with project-specific audience");

		// Validate JWT with project-specific audience
		user = verifyJWTWithProjectAudience(httpRequest, token, projectId);

		if (user == null) {
			classLogger.warn("MCPProjectAuthFilter - JWT validation failed, sending 401");
			send401(httpRequest, httpResponse, "invalid_token", "Invalid or expired Bearer token");
			return;
		}

		classLogger.info("MCPProjectAuthFilter - JWT valid, user: " + user.getPrimaryLogin());

		// Get user's primary login token
		AccessToken loginToken = user.getPrimaryLoginToken();
		if (loginToken == null) {
			classLogger.warn("MCPProjectAuthFilter - user has no primary login token");
			send401(httpRequest, httpResponse, "invalid_token", "User authentication failed");
			return;
		}

		// Create session and store user
		session = httpRequest.getSession(true);
		session.setAttribute(Constants.SESSION_USER, user);
		session.setAttribute(Constants.SESSION_USER_ID_LOG, loginToken.getId());

		// Store project ID in request attribute
		httpRequest.setAttribute("mcp_project_id", projectId);
		httpRequest.setAttribute("mcp_authenticated_user", user);

		classLogger.info("MCPProjectAuthFilter - SUCCESS! User authenticated for project " + projectId);

		// Continue filter chain
		chain.doFilter(request, response);
	}

	/**
	 * Extract project ID (UUID) from URL path
	 * Example: /api/mcp/28b0df8e-029a-4a9f-91bb-ddb338c8ce52/... -> 28b0df8e-029a-4a9f-91bb-ddb338c8ce52
	 */
	private String extractProjectId(String uri) {
		Matcher matcher = PROJECT_ID_PATTERN.matcher(uri);
		if (matcher.find()) {
			return matcher.group(1);
		}
		return null;
	}

	/**
	 * Verify JWT with base audience claim
	 * Accepts tokens with audience = baseUrl/api/mcp (not project-specific)
	 * This allows one token to access multiple projects based on user permissions
	 */
	private User verifyJWTWithProjectAudience(HttpServletRequest request, String token, String projectId) {
		try {
			// Check JWT format
			if (token.split("\\.").length != 3) {
				classLogger.warn("Token is not JWT format");
				return null;
			}

			// Build expected issuer and base audience
			String baseUrl = MCPUrlUtility.getExternalBaseUrl(request);
			String expectedIssuer = baseUrl + "/api/mcp";
			String expectedAudience = baseUrl + "/api/mcp";  // Accept base audience (not project-specific)

			classLogger.info("MCPProjectAuthFilter - expected issuer: " + expectedIssuer);
			classLogger.info("MCPProjectAuthFilter - expected audience: " + expectedAudience);

			// Verify JWT signature and claims
			java.util.Map<String, Object> claims = prerna.auth.mcp.MCPJWTHelper.verifyJWT(
				token, expectedIssuer, expectedAudience);

			if (claims == null) {
				classLogger.warn("JWT verification failed");
				return null;
			}

			classLogger.info("JWT signature and claims verified");

			// Get user from token store
			User user = MCPTokenStore.getInstance().validateToken(token);
			if (user == null) {
				classLogger.warn("JWT verified but user not found in token store");
				return null;
			}

			return user;

		} catch (Exception e) {
			classLogger.error("Error verifying JWT", e);
			return null;
		}
	}

	/**
	 * Send 401 Unauthorized response with WWW-Authenticate header per RFC 6750
	 */
	private void send401(HttpServletRequest request, HttpServletResponse response,
			String error, String errorDescription) throws IOException {

		String baseUrl = MCPUrlUtility.getExternalBaseUrl(request);
		String resourceMetadataUrl = baseUrl + "/api/mcp/.well-known/oauth-protected-resource";

		// Build WWW-Authenticate header per RFC 6750
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
		classLogger.info(">>>>> MCPProjectAuthFilter INITIALIZED - url-pattern: /api/mcp/*");
	}

	@Override
	public void destroy() {
		classLogger.info("MCPProjectAuthFilter DESTROYED");
	}
}
