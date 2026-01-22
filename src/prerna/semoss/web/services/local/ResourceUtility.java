package prerna.semoss.web.services.local;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.core.Context;

import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

public class ResourceUtility {

	protected static List<String> allowAccessWithoutUsers = new ArrayList<>();
	static {
		allowAccessWithoutUsers.add("config");
		allowAccessWithoutUsers.add("config/fetchCsrf");
	}

	public static List<String> allowAccessWithoutLogin = new ArrayList<>();
	static {
		// allow these for successful dropping of
		// sessions when browser is closed/refreshed
		// these do their own session checks
		allowAccessWithoutLogin.add("session/active");
		allowAccessWithoutLogin.add("session/cleanSession");
		allowAccessWithoutLogin.add("session/cancelCleanSession");
		allowAccessWithoutLogin.add("session/invalidateSession");

		allowAccessWithoutLogin.add("config");
		allowAccessWithoutLogin.add("config/fetchCsrf");
		allowAccessWithoutLogin.add("auth/logins");
		allowAccessWithoutLogin.add("auth/loginsAllowed");
		allowAccessWithoutLogin.add("auth/login");
		allowAccessWithoutLogin.add("auth/loginLDAP");
		allowAccessWithoutLogin.add("auth/changeADPassword");
		allowAccessWithoutLogin.add("auth/loginLinOTP");
		allowAccessWithoutLogin.add("auth/createUser");
		allowAccessWithoutLogin.add("auth/whoami");
		allowAccessWithoutLogin.add("auth/user/setupResetPassword");
		allowAccessWithoutLogin.add("auth/user/resetPassword");
		for (AuthProvider v : AuthProvider.values()) {
			allowAccessWithoutLogin.add("auth/userinfo/" + v.toString().toLowerCase());
			allowAccessWithoutLogin.add("auth/login/" + v.toString().toLowerCase());
		}
		// legacy ms login
		allowAccessWithoutLogin.add("auth/userinfo/ms");
		allowAccessWithoutLogin.add("auth/login/ms");

		// MCP OAuth token endpoints and JWKS (at /api/auth)
		allowAccessWithoutLogin.add("auth/.well-known/jwks.json");  // Public key for JWT verification
		allowAccessWithoutLogin.add("auth/oauth/register");  // Dynamic client registration
		allowAccessWithoutLogin.add("auth/mcp/authorize");  // OAuth authorization endpoint
		allowAccessWithoutLogin.add("auth/mcp/callback");  // OAuth callback
		allowAccessWithoutLogin.add("auth/mcp/token");  // Token exchange endpoint

		// MCP server endpoints (at /api/mcp) - OAuth metadata and JSON-RPC
		allowAccessWithoutLogin.add("ext/mcp");  // Legacy MCP endpoint
		allowAccessWithoutLogin.add("mcp");  // Standard MCP endpoint for ChatGPT (JSON-RPC + JWT auth)
		allowAccessWithoutLogin.add("mcp/.well-known/oauth-authorization-server");  // OAuth discovery
		allowAccessWithoutLogin.add("mcp/.well-known/openid-configuration");  // OAuth discovery (OpenID alias)
		allowAccessWithoutLogin.add("mcp/.well-known/oauth-protected-resource");  // Protected resource metadata (RFC 9728)
	}

	/**
	 * Get the user
	 * 
	 * @param request
	 * @return
	 * @throws IOException
	 */
	public static User getUser(@Context HttpServletRequest request) throws IllegalAccessException {
		HttpSession session = request.getSession(false);
		if (session == null) {
			throw new IllegalAccessException("User session is invalid");
		}

		User user = (User) session.getAttribute(Constants.SESSION_USER);
		if (user == null) {
			throw new IllegalAccessException("User session is invalid");
		}

		return user;
	}

	public static String getClientIp(@Context HttpServletRequest request) {
		String remoteAddr = "";
		if (request != null) {
			remoteAddr = WebUtility.inputSanitizer(request.getHeader("X-FORWARDED-FOR"));
			if (remoteAddr == null || "".equals(remoteAddr)) {
				remoteAddr = request.getRemoteAddr();
			}
		}

		return WebUtility.inputSanitizer(remoteAddr);
	}

	/**
	 * Need to ignore some URLs
	 * 
	 * @param fullUrl
	 * @return
	 */
	public static boolean allowAccessWithoutUsers(String fullUrl) {
		for (String ignore : allowAccessWithoutUsers) {
			if (fullUrl.endsWith(ignore)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Need to ignore some URLs
	 *
	 * @param fullUrl
	 * @return
	 */
	public static boolean allowAccessWithoutLogin(String fullUrl) {
		for (String ignore : allowAccessWithoutLogin) {
			if (fullUrl.endsWith(ignore)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Need to ignore some URLs
	 * 
	 * @param fullUrl
	 * @return
	 */
	public static boolean endsWithMatch(Collection<String> ignoreForFE, String fullUrl) {
		for (String ignore : ignoreForFE) {
			if (fullUrl.endsWith(ignore)) {
				return true;
			}
		}
		return false;
	}
}
