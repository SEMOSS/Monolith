package prerna.web.conf;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.io.connector.IAccessTokenFiller;
import prerna.semoss.web.services.local.UserResource;
import prerna.util.Constants;
import prerna.util.MCP.MCPUrlUtility;
import prerna.util.SocialPropertiesUtil;
import prerna.web.services.util.WebUtility;

/**
 * Filter to handle MCP OAuth Bearer token authentication for project-specific endpoints.
 * Validates OAuth provider tokens (GitHub, Google, etc.) for /api/mcp/{project_id} endpoints.
 *
 * This filter uses the same authentication approach as UserAccessKeyFilter:
 * 1. Extracts Bearer token from Authorization header
 * 2. Gets provider from Bearer-Provider header (or defaults to configured provider)
 * 3. Validates token with the OAuth provider's API
 * 4. Creates user session for the authenticated user
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

		// Skip OAuth discovery endpoints (they don't need authentication)
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
			httpRequest.setAttribute("mcp_project_id", projectId);
			chain.doFilter(request, response);
			return;
		}

		// Extract Bearer token
		String authHeader = httpRequest.getHeader("Authorization");
		if (authHeader == null) {
			authHeader = httpRequest.getHeader("authorization");
		}
		if (authHeader == null || (!authHeader.startsWith("Bearer ") && !authHeader.startsWith("bearer "))) {
			classLogger.warn("MCPProjectAuthFilter - no Bearer token, sending 401");
			send401(httpRequest, httpResponse, "invalid_token", "Bearer token required");
			return;
		}

		String bearerToken = authHeader.substring("Bearer ".length()).trim();
		classLogger.info("MCPProjectAuthFilter - validating OAuth provider token");

		// Get the OAuth provider from header
		String provider = WebUtility.inputSanitizer(httpRequest.getHeader("Bearer-Provider"));
		if (provider == null || provider.isEmpty()) {
			// Try to guess the provider from available logins
			provider = guessOAuthProvider();
			if (provider == null) {
				classLogger.warn("MCPProjectAuthFilter - no Bearer-Provider header and cannot determine provider");
				send401(httpRequest, httpResponse, "invalid_request", "Bearer-Provider header required");
				return;
			}
			classLogger.info("MCPProjectAuthFilter - guessed provider: " + provider);
		}

		// Validate the OAuth token with the provider
		user = validateOAuthToken(bearerToken, provider, httpRequest);

		if (user == null) {
			classLogger.warn("MCPProjectAuthFilter - OAuth token validation failed");
			send401(httpRequest, httpResponse, "invalid_token", "Invalid or expired Bearer token");
			return;
		}

		classLogger.info("MCPProjectAuthFilter - OAuth token valid, user: " + user.getPrimaryLogin());

		// Store project ID in request attribute
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
	 * Try to guess the OAuth provider from available logins
	 */
	private String guessOAuthProvider() {
		try {
			List<Map<String, Object>> loginsMap = SocialPropertiesUtil.getInstance().getAvailableProviders();
			Set<String> allowedLogins = new HashSet<>();
			for (Map<String, Object> loginInfo : loginsMap) {
				if ((boolean) loginInfo.get("isOauth")) {
					allowedLogins.add((String) loginInfo.get("label"));
				}
			}
			if (allowedLogins.size() == 1) {
				return allowedLogins.iterator().next();
			}
		} catch (Exception e) {
			classLogger.error("Error guessing OAuth provider", e);
		}
		return null;
	}

	/**
	 * Validate OAuth token with the provider's API
	 * Uses the same approach as UserAccessKeyFilter
	 */
	private User validateOAuthToken(String bearerToken, String provider, HttpServletRequest request) {
		try {
			SocialPropertiesUtil socialData = SocialPropertiesUtil.getInstance();

			// Check if provider is allowed
			Map<String, Boolean> loginsMap = socialData.getLoginsAllowed();
			Boolean providerLogin = loginsMap.get(provider.toLowerCase());
			if (providerLogin == null || !providerLogin) {
				classLogger.warn("User is attempting to login using bearer token for provider '" + provider
						+ "' but provider either does not exist or login is not allowed");
				return null;
			}

			// Get the auth provider enum
			AuthProvider thisProvider = AuthProvider.valueOf(provider.toUpperCase());
			String tokenFillerClass = thisProvider.getTokenFillerClass();
			if (tokenFillerClass == null) {
				classLogger.warn(
						"Attempting to login using access token but this functionality is not implemented for auth provider "
								+ thisProvider.getLabel());
				return null;
			}

			// Create token filler and validate token with provider's API
			IAccessTokenFiller thisTokenFiller = (IAccessTokenFiller) Class.forName(tokenFillerClass).newInstance();

			String prefix = thisProvider.getLabel().toLowerCase() + "_";
			String userInfoURL = socialData.getProperty(prefix + "userinfo_url");
			String beanProps = socialData.getProperty(prefix + "beanProps");
			String[] beanPropsArr = null;
			if (beanProps != null) {
				beanPropsArr = beanProps.split(",", -1);
			}
			String jsonPattern = socialData.getProperty(prefix + "jsonPattern");
			boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));
			boolean sanitizeResponse = Boolean.parseBoolean(socialData.getProperty(prefix + "sanitizeUserResponse"));

			// Create access token and fill it by calling provider's API
			AccessToken accessToken = new AccessToken();
			accessToken.setProvider(thisProvider);
			accessToken.setAccess_token(bearerToken);

			// This calls the provider's API (e.g., GitHub's /user endpoint) to validate
			thisTokenFiller.fillAccessToken(accessToken, userInfoURL, jsonPattern, beanPropsArr, null, sanitizeResponse);

			// Check if we got valid user info
			if (accessToken.getId() == null || accessToken.getId().isEmpty()) {
				classLogger.warn("OAuth token validation failed - no user ID returned from provider");
				return null;
			}

			classLogger.info("OAuth token validated successfully for user: " + accessToken.getId());

			// Add the access token and create user session (same as normal login)
			UserResource.addAccessToken(accessToken, request, autoAdd);

			// Return the user from session
			HttpSession session = request.getSession(false);
			if (session != null) {
				return (User) session.getAttribute(Constants.SESSION_USER);
			}

			return null;

		} catch (Exception e) {
			classLogger.error("Error validating OAuth token", e);
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
		classLogger.info(">>>>> MCPProjectAuthFilter INITIALIZED - OAuth token validation for /api/mcp/*");
	}

	@Override
	public void destroy() {
		classLogger.info("MCPProjectAuthFilter DESTROYED");
	}
}
