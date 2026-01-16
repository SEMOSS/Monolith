package prerna.web.conf;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.AccessToken;
import prerna.auth.User;
import prerna.auth.mcp.MCPTokenStore;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

/**
 * Filter to handle MCP OAuth Bearer token authentication.
 * Similar to OpenAIFilter but validates MCP OAuth tokens issued via /auth/mcp/token endpoint.
 *
 * When ChatGPT/Claude sends requests with Bearer tokens, this filter:
 * 1. Extracts the Bearer token from Authorization header
 * 2. Validates it against MCPTokenStore
 * 3. Sets the authenticated user in the session
 * 4. Allows the request to continue to MCPResource
 */
public class MCPOAuthFilter implements Filter {

	private static final Logger classLogger = LogManager.getLogger(MCPOAuthFilter.class);

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
			throws IOException, ServletException {
		HttpServletRequest request = (HttpServletRequest) arg0;
		javax.servlet.http.HttpServletResponse response = (javax.servlet.http.HttpServletResponse) arg1;
		HttpSession session = request.getSession(false);
		User user = null;

		// Check if user is already authenticated in session
		if (session != null) {
			user = (User) session.getAttribute(Constants.SESSION_USER);
		}

		// If user already authenticated, continue
		if (user != null) {
			arg2.doFilter(arg0, arg1);
			return;
		}

		// Check for Bearer token in Authorization header
		String authValue = request.getHeader("Authorization");
		if (authValue == null) {
			authValue = request.getHeader("authorization");
		}

		// No Authorization header - return 401 with WWW-Authenticate challenge
		if (authValue == null) {
			send401Challenge(request, response);
			return;
		}

		// Validate Bearer token format
		if (authValue.startsWith("Bearer") || authValue.startsWith("bearer")) {
			String bearerToken = authValue.substring("Bearer".length()).trim();

			if (bearerToken != null && !bearerToken.isEmpty()) {
				// Try to verify JWT token first
				user = verifyJWTAndGetUser(request, bearerToken);

				// If not a JWT or verification failed, try legacy token store
				if (user == null) {
					user = MCPTokenStore.getInstance().validateToken(bearerToken);
				}

				if (user == null) {
					classLogger.warn("MCP OAuth filter - invalid or expired Bearer token");
					// Invalid token - return 401 with challenge
					send401Challenge(request, response);
					return;
				} else {
					classLogger.info("MCP OAuth filter - user authenticated: " +
						WebUtility.inputSanitizer(user.getPrimaryLogin().toString()));

					// Get the user's primary login token
					AccessToken token = user.getPrimaryLoginToken();

					if (token != null) {
						// Create session and set user
						session = request.getSession(true);
						session.setAttribute(Constants.SESSION_USER, user);
						session.setAttribute(Constants.SESSION_USER_ID_LOG, token.getId());
						WebUtility.loggingContextLoginEvent(session);

						classLogger.info("MCP OAuth filter - user logged in via Bearer token");
					} else {
						classLogger.warn("MCP OAuth filter - user has no primary login token");
						send401Challenge(request, response);
						return;
					}
				}
			} else {
				// Empty bearer token - return 401
				send401Challenge(request, response);
				return;
			}
		} else {
			// Authorization header present but not Bearer - return 401
			send401Challenge(request, response);
			return;
		}

		// Continue the filter chain
		HttpServletRequestWrapper requestWrapper = new HttpServletRequestWrapper(request);
		arg2.doFilter(requestWrapper, arg1);
	}

	/**
	 * Send 401 Unauthorized response with WWW-Authenticate header
	 * per RFC 9728 and OpenAI MCP documentation
	 */
	private void send401Challenge(HttpServletRequest request, javax.servlet.http.HttpServletResponse response)
			throws IOException {
		String baseUrl = getBaseUrl(request);
		String resourceMetadataUrl = baseUrl + "/api/auth/.well-known/oauth-protected-resource";

		// Set WWW-Authenticate header per RFC 9728
		String wwwAuthenticate = String.format(
			"Bearer realm=\"%s\", resource_metadata=\"%s\"",
			baseUrl + "/api/mcp",
			resourceMetadataUrl
		);

		response.setHeader("WWW-Authenticate", wwwAuthenticate);
		response.setStatus(401);
		response.setContentType("application/json");
		response.getWriter().write("{\"error\":\"invalid_token\",\"error_description\":\"Valid Bearer token required\"}");

		classLogger.info("MCP OAuth filter - sent 401 challenge with resource_metadata: " + resourceMetadataUrl);
	}

	/**
	 * Get base URL for the application
	 */
	private String getBaseUrl(HttpServletRequest request) {
		String scheme = request.getScheme();
		String serverName = request.getServerName();
		int serverPort = request.getServerPort();
		String contextPath = request.getContextPath();

		StringBuilder url = new StringBuilder();
		url.append(scheme).append("://").append(serverName);

		if ((scheme.equals("http") && serverPort != 80) ||
			(scheme.equals("https") && serverPort != 443)) {
			url.append(":").append(serverPort);
		}

		url.append(contextPath);
		return url.toString();
	}

	/**
	 * Verify JWT token and extract user
	 */
	private User verifyJWTAndGetUser(HttpServletRequest request, String token) {
		try {
			// Check if token looks like a JWT (has 3 parts separated by dots)
			if (token.split("\\.").length != 3) {
				return null; // Not a JWT, try legacy token store
			}

			String baseUrl = getBaseUrl(request);
			String expectedIssuer = baseUrl + "/api/auth";
			// We don't strictly validate audience - let it be flexible
			String expectedAudience = null;

			// Verify JWT signature and claims
			java.util.Map<String, Object> claims = prerna.auth.mcp.MCPJWTHelper.verifyJWT(
				token, expectedIssuer, expectedAudience);

			if (claims == null) {
				classLogger.warn("JWT verification failed");
				return null;
			}

			classLogger.info("JWT signature verified successfully");

			// After verifying JWT, get user from token store
			// (User was stored when JWT was issued)
			User user = MCPTokenStore.getInstance().validateToken(token);
			if (user == null) {
				classLogger.warn("JWT verified but user not found in token store");
				return null;
			}

			classLogger.info("JWT verified - authenticated user: " +
				WebUtility.inputSanitizer(user.getPrimaryLogin().toString()));
			return user;

		} catch (Exception e) {
			classLogger.error("Error verifying JWT", e);
			return null;
		}
	}

	@Override
	public void destroy() {
		// cleanup if needed
	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		// initialization if needed
	}

}
