package prerna.semoss.web.services.local.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.security.PermitAll;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.PasswordRequirements;
import prerna.auth.User;
import prerna.auth.utils.SecurityNativeUserUtils;
import prerna.auth.utils.SecurityPasswordResetUtils;
import prerna.auth.utils.SecurityUserAccessKeyUtils;
import prerna.auth.utils.SecurityUserUtils;
import prerna.auth.utils.UserRegistrationEmailService;
import prerna.date.SemossDate;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.semoss.web.services.local.UserResource;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;
import prerna.web.services.util.WebUtility;

@Path("/auth/user")
@PermitAll
public class UserAuthorizationResource {

	private static final Logger classLogger = LogManager.getLogger(UserAuthorizationResource.class);

	private static final String RESET_PASSWORD = "/resetPassword/";

	/**
	 * Edit user properties. The user information is passed in the request body as a
	 * JSON object or as a form parameter.
	 * 
	 * @param request The HTTP request.
	 * @return A response indicating whether the user was edited successfully.
	 */
	@POST
	@Path("/editUser")
	@Produces("application/json")
	@Consumes({ "application/json", "application/x-www-form-urlencoded" })
	public Response editUser(@Context HttpServletRequest request) {
		User user = null;
		Map<String, String> errorMap = new HashMap<String, String>();

		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		Map<String, Object> userInfo = null;
		String contentType = request.getContentType();
		if (contentType != null && contentType.startsWith("application/json")) {
			try {
				StringBuilder jsonBuffer = new StringBuilder();
				String line;
				try (BufferedReader reader = request.getReader()) {
					while ((line = reader.readLine()) != null) {
						jsonBuffer.append(line);
					}
				}
				JSONObject jsonObj = new JSONObject(jsonBuffer.toString());
				userInfo = jsonObj.toMap();
			} catch (IOException | org.json.JSONException e) {
				classLogger.error(Constants.STACKTRACE, e);
				errorMap.put(Constants.ERROR_MESSAGE, "Error reading request body.");
				return WebUtility.getResponse(errorMap, 400);
			}
		} else {
			// for x-www-form-urlencoded, assume it's in a 'user' parameter as a JSON string
			String userJson = WebUtility.inputSQLSanitizer(request.getParameter("user"));
			try {
				JSONObject jsonObj = new JSONObject(userJson);
				userInfo = jsonObj.toMap();
			} catch (org.json.JSONException e) {
				classLogger.error(Constants.STACKTRACE, e);
				errorMap.put(Constants.ERROR_MESSAGE, "Error parsing user JSON from form data.");
				return WebUtility.getResponse(errorMap, 400);
			}
		}

		boolean update = SecurityUserUtils.editUser(user, userInfo);
		if (update) {
			AccessToken authToken = AccessToken.copyToken(user.getAccessToken(user.getLogins().get(0)));
			authToken.setName((String) userInfo.get("name"));
			authToken.setEmail((String) userInfo.get("newEmail"));
			UserResource.addAccessToken(authToken, request, false);
		}

		return WebUtility.getResponse(userInfo, 200);
	}

	/**
	 * Delete a user.
	 * 
	 * @param request The HTTP request.
	 * @return A response indicating whether the user was deleted successfully.
	 */
	@POST
	@Produces("application/json")
	@Path("/deleteUser")
	@Consumes({ "application/json", "application/x-www-form-urlencoded" })
	public Response deleteUser(@Context HttpServletRequest request) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		// need to actually build out this logic...
		return null;
	}

	/**
	 * Create a new user access key. The token name and description are passed in
	 * the request body as a JSON object or as form parameters.
	 * 
	 * @param request The HTTP request.
	 * @return A response containing the new access key details.
	 */
	@POST
	@Produces("application/json")
	@Path("createUserAccessKey")
	@Consumes({ "application/json", "application/x-www-form-urlencoded" })
	public Response createUserAccessKey(@Context HttpServletRequest request) {
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		if (user == null) {
			Map<String, String> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "No active session. Please login");
			return WebUtility.getResponse(ret, 401);
		}
		AccessToken token = user.getPrimaryLoginToken();
		boolean accessKeysAllowed = SocialPropertiesUtil.getInstance().accessKeysAllowed(token.getProvider());
		if (!accessKeysAllowed) {
			Map<String, String> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE,
					"Creating access keys is not allowed. Please reach out to an administrator if you require this functionality");
			return WebUtility.getResponse(ret, 401);
		}

		String tokenName = null;
		String tokenDescription = null;

		String contentType = request.getContentType();
		if (contentType != null && contentType.startsWith("application/json")) {
			try {
				StringBuilder jsonBuffer = new StringBuilder();
				String line;
				try (BufferedReader reader = request.getReader()) {
					while ((line = reader.readLine()) != null) {
						jsonBuffer.append(line);
					}
				}
				JSONObject root = new JSONObject(jsonBuffer.toString());
				tokenName = root.has("tokenName") ? root.getString("tokenName") : null;
				tokenDescription = root.has("tokenDescription") ? root.getString("tokenDescription") : null;
			} catch (IOException | org.json.JSONException e) {
				classLogger.error(Constants.STACKTRACE, e);
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, "Error parsing JSON request body.");
				return WebUtility.getResponse(errorMap, 400);
			}
		} else {
			tokenName = request.getParameter("tokenName");
			tokenDescription = request.getParameter("tokenDescription");
		}

		tokenName = WebUtility.inputSQLSanitizer(tokenName);
		if (tokenName != null) {
			if (tokenName.length() > 255) {
				Map<String, String> ret = new HashMap<>();
				ret.put(Constants.ERROR_MESSAGE, "Token name must be less than 255 characters long");
				return WebUtility.getResponse(ret, 400);
			}
		}
		tokenDescription = WebUtility.inputSQLSanitizer(tokenDescription);
		if (tokenDescription != null) {
			if (tokenDescription.length() > 500) {
				Map<String, String> ret = new HashMap<>();
				ret.put(Constants.ERROR_MESSAGE, "Token description must be less than 500 characters long");
				return WebUtility.getResponse(ret, 400);
			}
		}

		Map<String, String> oneTimeDetails;
		try {
			oneTimeDetails = SecurityUserAccessKeyUtils.createUserAccessToken(token, tokenName, tokenDescription);
			classLogger.info("User created new access and secret key for login");
			return WebUtility.getResponse(oneTimeDetails, 200);
		} catch (SQLException e) {
			classLogger.error("Error trying to create user access and secret key for login", e);
			Map<String, String> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(ret, 400);
		}
	}

	/**
	 * Delete a user access key. The access key is passed in the request body as a
	 * JSON object or as a form parameter.
	 * 
	 * @param request The HTTP request.
	 * @return A response indicating whether the access key was deleted
	 *         successfully.
	 */
	@POST
	@Produces("application/json")
	@Path("deleteUserAccessKey")
	@Consumes({ "application/json", "application/x-www-form-urlencoded" })
	public Response deleteUserAccessKey(@Context HttpServletRequest request) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		Map<String, Object> retMap = new HashMap<>();

		AccessToken token = user.getPrimaryLoginToken();

		String accessKey = null;
		String contentType = request.getContentType();
		if (contentType != null && contentType.startsWith("application/json")) {
			try {
				StringBuilder jsonBuffer = new StringBuilder();
				String line;
				try (BufferedReader reader = request.getReader()) {
					while ((line = reader.readLine()) != null) {
						jsonBuffer.append(line);
					}
				}
				JSONObject root = new JSONObject(jsonBuffer.toString());
				accessKey = root.getString("accessKey");
			} catch (IOException | org.json.JSONException e) {
				classLogger.error(Constants.STACKTRACE, e);
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, "Error parsing JSON request body.");
				return WebUtility.getResponse(errorMap, 400);
			}
		} else {
			accessKey = request.getParameter("accessKey");
		}

		accessKey = WebUtility.inputSQLSanitizer(accessKey);
		if (accessKey == null || accessKey.isEmpty()) {
			retMap.put(Constants.ERROR_MESSAGE, "accessKey parameter is not defined");
			return WebUtility.getResponse(retMap, 400);
		}
		try {
			boolean success = SecurityUserAccessKeyUtils.deleteUserAccessToken(token, accessKey);
			retMap.put("success", success);
			if (success) {
				classLogger.info("User has deleted access key {}", accessKey);
				return WebUtility.getResponse(retMap, 200);
			} else {
				classLogger.info("User could not delete access key {}", accessKey);
				return WebUtility.getResponse(retMap, 400);
			}
		} catch (Exception e) {
			classLogger.error("Error trying to delete access key {}", accessKey, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
	}

	/**
	 * Get the user access keys for the currently logged-in user.
	 * 
	 * @param request The HTTP request.
	 * @return A response containing a list of access keys.
	 */
	@GET
	@Path("/getUserAccessKeys")
	@Produces("application/json")
	public Response getUserAccessKeys(@Context HttpServletRequest request) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, Object>> results = SecurityUserAccessKeyUtils
				.getUserAccessKeyInfo(user.getPrimaryLoginToken());
		return WebUtility.getResponse(results, 200);
	}

	/**
	 * Set up a password reset request for a user. This will send an email to the
	 * user with a password reset link.
	 * 
	 * @param context The servlet context.
	 * @param request The HTTP request.
	 * @return A response indicating whether the password reset email was sent
	 *         successfully.
	 */
	@POST
	@Produces("application/json")
	@Path("/setupResetPassword")
	@Consumes({ "application/json", "application/x-www-form-urlencoded" })
	public Response setupResetPassword(@Context ServletContext context, @Context HttpServletRequest request) {
		// do we allow users to change their password?
		try {
			if (!PasswordRequirements.getInstance().isAllowUserChangePassword()) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, "Only the administrator is allowed to change the user password");
				return WebUtility.getResponse(errorMap, 401);
			}
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		String email = null;
		String type = null;
		String resetEmailUrl = null;
		String sender = null;

		String contentType = request.getContentType();
		if (contentType != null && contentType.startsWith("application/json")) {
			try {
				StringBuilder jsonBuffer = new StringBuilder();
				String line;
				try (BufferedReader reader = request.getReader()) {
					while ((line = reader.readLine()) != null) {
						jsonBuffer.append(line);
					}
				}
				JSONObject root = new JSONObject(jsonBuffer.toString());
				email = root.has("email") ? root.getString("email") : null;
				type = root.has("type") ? root.getString("type") : null;
				resetEmailUrl = root.has("url") ? root.getString("url") : null;
				sender = root.has("sender") ? root.getString("sender") : null;
			} catch (IOException | org.json.JSONException e) {
				classLogger.error(Constants.STACKTRACE, e);
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, "Error parsing JSON request body.");
				return WebUtility.getResponse(errorMap, 400);
			}
		} else {
			email = request.getParameter("email");
			type = request.getParameter("type");
			resetEmailUrl = request.getParameter("url");
			sender = request.getParameter("sender");
		}

		email = WebUtility.inputSQLSanitizer(email);
		type = WebUtility.inputSQLSanitizer(type);
		resetEmailUrl = WebUtility.inputSQLSanitizer(resetEmailUrl);
		sender = WebUtility.inputSQLSanitizer(sender);

		String uniqueToken = null;
		try {
			uniqueToken = SecurityPasswordResetUtils.allowUserResetPassword(email, type);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		if (resetEmailUrl == null || (resetEmailUrl = resetEmailUrl.trim()).isEmpty()) {
			String fullUrl = WebUtility.cleanHttpResponse(request.getRequestURL().toString());
			String contextPath = request.getContextPath();
			resetEmailUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length()) + RESET_PASSWORD
					+ "index.html?token=" + uniqueToken;
		} else {
			resetEmailUrl += "?token=" + uniqueToken;
		}

		if (!UserRegistrationEmailService.getInstance().sendPasswordResetRequestEmail(email, resetEmailUrl, sender)) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error occurred sending email to " + email);
			SecurityPasswordResetUtils.deleteToken(uniqueToken);
			return WebUtility.getResponse(errorMap, 500);
		}

		classLogger.info("User has requested a password reset for email = " + email);

		Map<String, Object> retMap = new HashMap<>();
		retMap.put("success", true);
		retMap.put("message", "Email has been sent to: " + email);
		return WebUtility.getResponse(retMap, 200);
	}

	/**
	 * Reset the user's password using a reset token.
	 * 
	 * @param context The servlet context.
	 * @param request The HTTP request.
	 * @return A response indicating whether the password was reset successfully.
	 */
	@POST
	@Produces("application/json")
	@Path("/resetPassword")
	@Consumes({ "application/json", "application/x-www-form-urlencoded" })
	public Response resetPassword(@Context ServletContext context, @Context HttpServletRequest request) {
		// do we allow users to change their password?
		try {
			if (!PasswordRequirements.getInstance().isAllowUserChangePassword()) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, "Only the administrator is allowed to change the user password");
				return WebUtility.getResponse(errorMap, 401);
			}
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		String token = null;
		String password = null;
		String sender = null;

		String contentType = request.getContentType();
		if (contentType != null && contentType.startsWith("application/json")) {
			try {
				StringBuilder jsonBuffer = new StringBuilder();
				String line;
				try (BufferedReader reader = request.getReader()) {
					while ((line = reader.readLine()) != null) {
						jsonBuffer.append(line);
					}
				}
				JSONObject root = new JSONObject(jsonBuffer.toString());
				token = root.has("token") ? root.getString("token") : null;
				password = root.has("password") ? root.getString("password") : null;
				sender = root.has("sender") ? root.getString("sender") : null;
			} catch (IOException | org.json.JSONException e) {
				classLogger.error(Constants.STACKTRACE, e);
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, "Error parsing JSON request body.");
				return WebUtility.getResponse(errorMap, 400);
			}
		} else {
			token = request.getParameter("token");
			password = request.getParameter("password");
			sender = request.getParameter("sender");
		}

		token = WebUtility.inputSQLSanitizer(token);
		password = WebUtility.inputSQLSanitizer(password);
		sender = WebUtility.inputSQLSanitizer(sender);

		Map<String, Object> resetDetails = null;
		try {
			resetDetails = SecurityPasswordResetUtils.userResetPassword(token, password);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		String userId = (String) resetDetails.get("userId");
		String email = (String) resetDetails.get("email");
		SemossDate dateAdded = (SemossDate) resetDetails.get("dateAdded");

		classLogger.info("User has changed password for user id = " + userId + " for reset request on " + dateAdded
				+ " with email " + email);

		UserRegistrationEmailService.getInstance().sendPasswordResetSuccessEmail(email, sender);

		Map<String, Object> retMap = new HashMap<>();
		retMap.put("success", true);
		retMap.put("userId", userId);
		retMap.put("message", "Email has been sent to: " + email);
		return WebUtility.getResponse(retMap, 200);
	}

	/**
	 * Set user metadata
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Path("setUserMetadata")
	@Produces("application/json")
	public Response setUserMetadata(@Context HttpServletRequest request) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		String metaKey = null;
		Object metaValue = null;

		String contentType = request.getContentType();
		if (contentType != null && contentType.startsWith("application/json")) {
			try {
				StringBuilder jsonBuffer = new StringBuilder();
				String line;
				try (BufferedReader reader = request.getReader()) {
					while ((line = reader.readLine()) != null) {
						jsonBuffer.append(line);
					}
				}
				JSONObject root = new JSONObject(jsonBuffer.toString());
				metaKey = root.has("metaKey") ? root.getString("metaKey") : null;
				boolean arrayValue = root.optJSONArray("metaValue") != null;
				if (arrayValue) {
					metaValue = root.getJSONArray("metaValue").toList();
				} else {
					metaValue = root.has("metaValue") ? root.getString("metaValue") : null;
				}
			} catch (IOException | org.json.JSONException e) {
				classLogger.error(Constants.STACKTRACE, e);
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, "Error parsing JSON request body.");
				return WebUtility.getResponse(errorMap, 400);
			}
		} else {
			Map<String, String[]> paramMap = request.getParameterMap();
			metaKey = paramMap.get("metaKey") != null ? paramMap.get("metaKey")[0] : null;
			metaValue = paramMap.get("metaValue") != null ? Arrays.asList(paramMap.get("metaValue")) : null;
		}

		if (metaKey == null) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Parameter metaKey cannot be null");
			return WebUtility.getResponse(errorMap, 400);
		}
		if (metaValue == null) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Parameter metaValue cannot be null");
			return WebUtility.getResponse(errorMap, 400);
		}

		try {
			SecurityUserUtils.updateUserMetadata(user, metaKey, metaValue);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}

		Map<String, String> successMap = new HashMap<>();
		successMap.put("userMetadata", "Metadata " + metaKey + " updated to " + metaValue);
		return WebUtility.getResponse(successMap, 200);
	}

	/**
	 * Change the user's password. The current and new passwords are passed in the
	 * request body as a JSON object or as form parameters.
	 * 
	 * @param context The servlet context.
	 * @param request The HTTP request.
	 * @return A response indicating whether the password was changed successfully.
	 */
	@POST
	@Produces("application/json")
	@Consumes({ "application/json", "application/x-www-form-urlencoded" })
	@Path("/changePassword")
	public Response changePassword(@Context ServletContext context, @Context HttpServletRequest request) {
		User user = null;
		String userId = null;
		try {
			user = ResourceUtility.getUser(request);
			AuthProvider authProvider = user.getPrimaryLogin();
			if (authProvider != AuthProvider.NATIVE) {
				throw new IllegalArgumentException("Can only change password for NATIVE logins");
			}
			userId = user.getPrimaryLoginToken().getId();

			String currentPassword = null;
			String newPassword = null;

			String contentType = request.getContentType();
			if (contentType != null && contentType.startsWith("application/json")) {
				// Handle JSON content
				StringBuilder jsonBuffer = new StringBuilder();
				String line;
				try (BufferedReader reader = request.getReader()) {
					while ((line = reader.readLine()) != null) {
						jsonBuffer.append(line);
					}
					JSONObject root = new JSONObject(jsonBuffer.toString());

					if (root == null || !root.has("currentPassword") || !root.has("newPassword")) {
						Map<String, Object> retMap = new HashMap<>();
						retMap.put("success", false);
						retMap.put("userId", userId);
						retMap.put("message",
								"Missing required fields. Must have 'currentPassword' and 'newPassword' ");
						return WebUtility.getResponse(retMap, 400);
					}
					currentPassword = root.getString("currentPassword");
					newPassword = root.getString("newPassword");
				} catch (IOException | org.json.JSONException e) {
					classLogger.error(Constants.STACKTRACE, e);
					Map<String, String> errorMap = new HashMap<String, String>();
					errorMap.put(Constants.ERROR_MESSAGE, "Error parsing JSON request body.");
					return WebUtility.getResponse(errorMap, 400);
				}
			} else {
				// Handle form data
				currentPassword = request.getParameter("currentPassword");
				newPassword = request.getParameter("newPassword");
			}

			currentPassword = WebUtility.inputSQLSanitizer(currentPassword);

			if (SecurityNativeUserUtils.isCurrentPassword(userId, authProvider, currentPassword)) {
				newPassword = WebUtility.inputSQLSanitizer(newPassword);
				if (PasswordRequirements.getInstance().validatePassword(newPassword)) {
					SecurityNativeUserUtils.performResetPassword(userId, newPassword);

					classLogger.info("User has changed their password");

					Map<String, Object> retMap = new HashMap<>();
					retMap.put("success", true);
					retMap.put("userId", userId);
					retMap.put("message", "User has changed the password for user id = " + userId);
					classLogger.info("User has changed the password for user id = " + userId);
					return WebUtility.getResponse(retMap, 200);
				} else {
					classLogger.warn("User entered invalid new password");

					Map<String, Object> retMap = new HashMap<>();
					retMap.put("success", false);
					retMap.put("userId", userId);
					retMap.put("message", "The new password is not valid");
					return WebUtility.getResponse(retMap, 401);
				}
			} else {
				classLogger.warn("User entered the wrong password");

				Map<String, Object> retMap = new HashMap<>();
				retMap.put("success", false);
				retMap.put("userId", userId);
				retMap.put("message", "The current password for the user is wrong. Try again.");
				return WebUtility.getResponse(retMap, 401);
			}
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
	}

}
