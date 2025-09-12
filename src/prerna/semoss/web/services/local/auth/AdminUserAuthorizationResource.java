package prerna.semoss.web.services.local.auth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;

import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("/auth/admin/user")
@PermitAll
@Tag(name = "auth", description = "Endpoints for managing authentication and authorization of users and applications, including user permissions, access control, and administrative actions.")
public class AdminUserAuthorizationResource extends AbstractAdminResource {

	private static final Logger classLogger = LogManager.getLogger(AdminUserAuthorizationResource.class);

	@GET
	@Path("/isAdminUser")
	@Produces("application/json")
	@Operation(summary = "Check if current user is admin", description = "Returns whether the authenticated user has admin privileges.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Admin status",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "boolean"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response isAdminUser(@Context HttpServletRequest request) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		boolean isAdmin = SecurityAdminUtils.userIsAdmin(user);
		return WebUtility.getResponse(isAdmin, 200);
	}

	@POST
	@Produces("application/json")
	@Path("/registerUser")
	@Operation(summary = "Register a user", description = "Registers a new user with optional admin/publisher/exporter flags and model usage restrictions.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "User registered",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "boolean"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "500", description = "Server error",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response registerUser(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		Map<String, String> errorRet = new HashMap<>();
		if (!SecurityAdminUtils.userIsAdmin(user)) {
			errorRet.put(Constants.ERROR_MESSAGE, "The user doesn't have the permissions to perform this action.");
			return WebUtility.getResponse(errorRet, 400);
		}

		boolean success = false;
		try {
			String newUserId = form.getFirst("userId");
			if (newUserId == null || newUserId.isEmpty()) {
				throw new IllegalArgumentException("The user id cannot be null or empty");
			}
			String type = WebUtility.inputSanitizer(form.getFirst("type"));
			String name = WebUtility.inputSQLSanitizer(form.getFirst("name"));
			String email = WebUtility.inputSQLSanitizer(form.getFirst("email"));
			String phone = WebUtility.inputSanitizer(request.getParameter("phone"));
			String phoneExtension = WebUtility.inputSanitizer(request.getParameter("phoneextension"));
			String countryCode = WebUtility.inputSanitizer(request.getParameter("countrycode"));
			Boolean newUserAdmin = Boolean.parseBoolean(form.getFirst("admin"));
			Boolean publisher = Boolean.parseBoolean(form.getFirst("publisher"));
			Boolean exporter = Boolean.parseBoolean(form.getFirst("exporter"));
			String password = WebUtility.inputSQLSanitizer(form.getFirst("password"));

			// model restrictions
			String modelUsageRestriction = WebUtility.inputSQLSanitizer(form.getFirst("modelUsageRestriction"));
			String modelUsageFrequency = WebUtility.inputSQLSanitizer(form.getFirst("modelUsageFrequency"));
			Integer modelMaxTokens = null;
			String modelMaxTokensStr = form.getFirst("modelMaxTokens");
			if (modelMaxTokensStr != null && !(modelMaxTokensStr = modelMaxTokensStr.trim()).isEmpty()) {
				try {
					modelMaxTokens = Integer.parseInt(modelMaxTokensStr);
				} catch (NumberFormatException e) {
					classLogger.error(Constants.STACKTRACE, e);
					throw new IllegalArgumentException("modelMaxTokens must be a valid Integer value");
				}
			}
			Double modelMaxResponseTime = null;
			String modelMaxResponseTimeStr = form.getFirst("modelMaxResponseTime");
			if (modelMaxResponseTimeStr != null
					&& !(modelMaxResponseTimeStr = modelMaxResponseTimeStr.trim()).isEmpty()) {
				try {
					modelMaxResponseTime = Double.parseDouble(modelMaxResponseTimeStr);
				} catch (NumberFormatException e) {
					classLogger.error(Constants.STACKTRACE, e);
					throw new IllegalArgumentException("modelMaxResponseTime must be a valid Number value");
				}
			}

			// validate email & password
			if (email != null && !email.isEmpty()) {
				try {
					AbstractSecurityUtils.validEmail(email, true);
				} catch (Exception e) {
					Map<String, String> errorMap = new HashMap<>();
					errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
					return WebUtility.getResponse(errorMap, 401);
				}
			}
			if (phone != null && !phone.isEmpty()) {
				try {
					phone = AbstractSecurityUtils.formatPhone(phone);
				} catch (Exception e) {
					Map<String, String> errorMap = new HashMap<>();
					errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
					return WebUtility.getResponse(errorMap, 401);
				}
			}
			// password is only defined if native type
			if (password != null && !password.isEmpty()) {
				try {
					AbstractSecurityUtils.validPassword(newUserId, AuthProvider.NATIVE, password);
				} catch (Exception e) {
					Map<String, String> errorMap = new HashMap<>();
					errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
					return WebUtility.getResponse(errorMap, 401);
				}
			}

			success = SecurityUpdateUtils.registerUser(newUserId, name, email, password, type, phone, phoneExtension,
					countryCode, newUserAdmin, publisher, exporter, modelUsageRestriction, modelUsageFrequency,
					modelMaxTokens, modelMaxResponseTime);
		} catch (IllegalArgumentException e) {
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}
		return WebUtility.getResponse(success, 200);
	}

	/**
	 * Set user as publisher
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("/setUserPublisher")
	@Operation(summary = "Set user publisher flag", description = "Sets or unsets a user's publisher status.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Publisher flag updated",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response setUserPublisher(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		String userId = WebUtility.inputSQLSanitizer(form.getFirst("userId"));
		boolean isPublisher = Boolean.parseBoolean(form.getFirst("isPublisher"));

		try {
			adminUtils.setUserPublisher(userId, isPublisher);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Set user as locked/unlocked
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("/setUserLocked")
	@Operation(summary = "Set user lock status", description = "Locks or unlocks a user's account.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Lock status updated",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response setUserLocked(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		String userId = WebUtility.inputSQLSanitizer(form.getFirst("userId"));
		String type = WebUtility.inputSQLSanitizer(form.getFirst("type"));
		boolean isLocked = Boolean.parseBoolean(form.getFirst("isLocked"));

		try {
			adminUtils.setUserLock(userId, type, isLocked);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Edit user properties
	 * 
	 * @param request
	 * @param form
	 * @return true if the edition was performed
	 */
	@POST
	@Path("/editUser")
	@Produces("application/json")
	@Operation(summary = "Edit user", description = "Edits user properties including admin flag; prevents removing the last admin.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "User updated",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "boolean"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response editUser(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		Gson gson = new Gson();
		Map<String, Object> userInfo = gson.fromJson(form.getFirst("user"), Map.class);

		Boolean adminChange = null;
		if (userInfo.containsKey("admin")) {
			if (userInfo.get("admin") instanceof Number) {
				adminChange = ((Number) userInfo.get("admin")).intValue() == 1;
				classLogger.info(
						"User has edited user " + userInfo.get("id") + " to admin level " + userInfo.get("admin"));
			} else {
				adminChange = Boolean.parseBoolean(userInfo.get("admin") + "");
				classLogger.info(
						"User has edited user " + userInfo.get("id") + " to admin level " + userInfo.get("admin"));
			}
		}

		if (adminChange != null && !adminChange) {
			// if you are making this user not an admin
			// need to make sure they are not the last admin for the instance
			synchronized (AdminUserAuthorizationResource.class) {
				int numAdmins = adminUtils.getNumAdmins();
				if (numAdmins == 1) {
					Object[] adminUser = adminUtils.getAdminUserIdAndType();
					String thisUserId = userInfo.get("id") + "";
					String thisUserType = userInfo.get("type") + "";
					if (thisUserId.equals(adminUser[0]) && thisUserType.equals(adminUser[1])) {
						Map<String, String> errorMap = new HashMap<String, String>();
						errorMap.put(Constants.ERROR_MESSAGE,
								"You cannot remove the last admin from having admin level permissions. Please assign a new admin before removing admin access.");
						return WebUtility.getResponse(errorMap, 400);
					}
				}
			}
		}

		boolean ret = false;
		try {
			ret = adminUtils.editUser(userInfo);
		} catch (IllegalArgumentException e) {
			Map<String, String> retMap = new Hashtable<>();
			retMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(retMap, 400);
		}
		if (!ret) {
			Map<String, String> retMap = new Hashtable<>();
			retMap.put(Constants.ERROR_MESSAGE, "Unknown error occurred with updating user. Please try again.");
			return WebUtility.getResponse(retMap, 400);
		}
		return WebUtility.getResponse(ret, 200);
	}

	@POST
	@Produces("application/json")
	@Path("/deleteUser")
	@Operation(summary = "Delete user", description = "Deletes a user; prevents deleting the last admin.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "User deleted",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "boolean"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response deleteUser(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		String userIdToDelete = WebUtility.inputSQLSanitizer(form.getFirst("userId"));
		String userTypeToDelete = WebUtility.inputSQLSanitizer(form.getFirst("type"));

		boolean isDeletedUserAdmin = adminUtils.userIsAdmin(userIdToDelete, userTypeToDelete);
		if (isDeletedUserAdmin) {
			// need to make sure there are other admins and we are not deleting the last
			// admin
			if (!adminUtils.otherAdminsExist(userIdToDelete, userTypeToDelete)) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE,
						"You cannot delete this user as it is the last admin. Please assign a new admin before deleting this user.");
				return WebUtility.getResponse(errorMap, 400);
			}
		}

		// log the operation
		classLogger.info("User has deleted user " + userIdToDelete + " with provider " + userTypeToDelete);

		boolean success = adminUtils.deleteUser(userIdToDelete, userTypeToDelete);
		return WebUtility.getResponse(success, 200);
	}

	@GET
	@Path("/getAllDbUsers")
	@Produces("application/json")
	@Operation(summary = "List users (deprecated)", description = "Deprecated: use /getAllUsers instead.", deprecated = true)
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Users retrieved",
			content = @Content(mediaType = "application/json",
				array = @ArraySchema(schema = @Schema(type = "object"))
			)
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	@Deprecated
	/**
	 * PLEASE USE
	 * {@link AdminUserAuthorizationResource#getAllUsers(HttpServletRequest)}
	 * 
	 * @param request
	 * @return
	 */
	public Response getAllDbUsers(@Context HttpServletRequest request) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, Object>> ret = adminUtils.getAllUsers(null, -1, -1);
		return WebUtility.getResponse(ret, 200);
	}

	@GET
	@Path("/getAllUsers")
	@Produces("application/json")
	@Operation(summary = "List users", description = "Returns all users filtered by an optional search term with pagination.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Users retrieved",
			content = @Content(mediaType = "application/json",
				array = @ArraySchema(schema = @Schema(type = "object"))
			)
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response searchTerm(@Context HttpServletRequest request, @QueryParam("filterWord") String searchTerm,
			@QueryParam("limit") long limit, @QueryParam("offset") long offset) {
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, Object>> ret = adminUtils.getAllUsers(searchTerm, limit, offset);
		return WebUtility.getResponse(ret, 200);
	}

	@GET
	@Path("/getNumUsers")
	@Produces("application/json")
	@Operation(summary = "Get number of users", description = "Returns the total number of users.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Count returned",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "integer", format = "int64"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response getNumUsers(@Context HttpServletRequest request) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		Long ret = adminUtils.getNumUsers();
		return WebUtility.getResponse(ret, 200);
	}

}
