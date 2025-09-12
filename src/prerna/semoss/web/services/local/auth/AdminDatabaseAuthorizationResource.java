package prerna.semoss.web.services.local.auth;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Type;

import javax.annotation.security.PermitAll;
import javax.servlet.ServletContext;
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

import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.engine.api.IEngine;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("/auth/admin/app")
@PermitAll
@Deprecated
@Tag(name = "auth", description = "Endpoints for managing authentication and authorization of users and applications, including user permissions, access control, and administrative actions.")
public class AdminDatabaseAuthorizationResource extends AbstractAdminResource {

	private static final Logger classLogger = LogManager.getLogger(AdminDatabaseAuthorizationResource.class);

	@Context
	protected ServletContext context;

	/**
	 * Get the apps the user has access to
	 * 
	 * @param request
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getApps")
	@Operation(
		summary = "Get apps (legacy)",
		description = "Returns engine settings for database-type engines. Deprecated: use /auth/admin/engine/getEngines with engineTypes.",
		deprecated = true,
		parameters = {
			@Parameter(name = "databaseId", in = ParameterIn.QUERY, description = "Filter by databaseId (repeatable)")
		},
		responses = {
			@ApiResponse(responseCode = "200", description = "Apps retrieved",
				content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = java.lang.Object.class)))),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response getUserApps(@Context HttpServletRequest request,
			@QueryParam("databaseId") List<String> databaseId) {
		classLogger.warn(
				"CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/getEngines WITH PARAM engineTypes");

		databaseId = WebUtility.inputSanitizer(databaseId);
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to get all databases when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		List<String> eTypes = new ArrayList<>();
		eTypes.add(IEngine.CATALOG_TYPE.DATABASE.toString());
		return WebUtility.getResponse(adminUtils.getAllEngineSettings(databaseId, eTypes, null, null, null, null), 200);
	}

	@POST
	@Path("/getAllUserApps")
	@Produces("application/json")
	@Operation(
		summary = "Get all user apps (legacy)",
		description = "Lists engines a user has access to. Deprecated: use /auth/admin/engine/getAllUserEngines with engineTypes.",
		deprecated = true,
	responses = {
			@ApiResponse(responseCode = "200", description = "User apps retrieved",
				content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = java.lang.Object.class)))),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response getAllUserApps(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn(
				"CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/getAllUserEngines with PARAM engineTypes");

		SecurityAdminUtils adminUtils = null;
		User user = null;
		String userId = WebUtility.inputSQLSanitizer(form.getFirst("userId"));
		List<String> engineTypes = null;
		if (WebUtility.inputSanitizer(form.getFirst("engineTypes")) != null) {
			engineTypes = new Gson().fromJson(form.getFirst("engineTypes"), List.class);
			engineTypes = WebUtility.inputSanitizer(engineTypes);
		}
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to pull the databases that user " + userId + " has access to when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		return WebUtility.getResponse(adminUtils.getAllUserEngines(userId, engineTypes), 200);
	}

	@POST
	@Path("/grantAllApps")
	@Produces("application/json")
	@Operation(
		summary = "Grant all apps (legacy)",
		description = "Grants a user permissions to all database-type engines. Deprecated: use /auth/admin/engine/grantAllEngines.",
		deprecated = true,
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = LegacyAdminSuccessResponse.class))),
			@ApiResponse(responseCode = "400", description = "Bad request",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response grantAllApps(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn(
				"CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/grantAllEngines with PARAM engineTypes");

		SecurityAdminUtils adminUtils = null;
		User user = null;
		String userId = WebUtility.inputSQLSanitizer(form.getFirst("userId"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		boolean isAddNew = Boolean.parseBoolean(form.getFirst("isAddNew") + "");

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to grant all the databases to user " + userId + " when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.grantAllEngines(userId, permission, isAddNew, null, user);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has granted all databases to " + userId + "with permission " + permission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	@POST
	@Path("/grantNewUsersAppAccess")
	@Produces("application/json")
	@Operation(
		summary = "Grant new users app access (legacy)",
		description = "Grants all new users access to a database. Deprecated: use /auth/admin/engine/grantNewUsersEngineAccess.",
		deprecated = true,
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = LegacyAdminSuccessResponse.class))),
			@ApiResponse(responseCode = "400", description = "Bad request",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response grantNewUsersAppAccess(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn(
				"CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/grantNewUsersEngineAccess with PARAM engineId");

		SecurityAdminUtils adminUtils = null;
		User user = null;
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String appId = WebUtility.inputSanitizer(form.getFirst("appId"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to grant database to new users when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.grantNewUsersEngineAccess(appId, permission, user, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has granted database " + appId + "to new users with permission " + permission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Get the database users and their permissions
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getAppUsers")
	@Operation(
		summary = "Get app users (legacy)",
		description = "Gets users and permissions for a database. Deprecated: use /auth/admin/engine/getEngineUsers.",
		deprecated = true,
		parameters = {
			@Parameter(name = "appId", in = ParameterIn.QUERY, description = "Database identifier"),
			@Parameter(name = "searchTerm", in = ParameterIn.QUERY, description = "Search term"),
			@Parameter(name = "limit", in = ParameterIn.QUERY, description = "Limit"),
			@Parameter(name = "offset", in = ParameterIn.QUERY, description = "Offset")
		},
		responses = {
			@ApiResponse(responseCode = "200", description = "Users retrieved",
				content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = java.lang.Object.class)))),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response getAppUsers(@Context HttpServletRequest request, @QueryParam("appId") String appId,
			@QueryParam("searchTerm") String searchTerm, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {
		classLogger.warn(
				"CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/getEngineUsers with PARAM engineId");

		appId = WebUtility.inputSanitizer(appId);
		searchTerm = WebUtility.inputSanitizer(searchTerm);

		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to pull all the users who use database " + appId + " when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		return WebUtility.getResponse(adminUtils.getEngineUsers(appId, searchTerm, null, limit, offset), 200);
	}

	/**
	 * Add a user to a database
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addAppUserPermission")
	@Operation(
		summary = "Add app user permission (legacy)",
		description = "Adds a user permission for a database. Deprecated: use /auth/admin/engine/addEngineUserPermission.",
		deprecated = true,
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = LegacyAdminSuccessResponse.class))),
			@ApiResponse(responseCode = "400", description = "Bad request",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response addAppUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Map<String, Object> ret = new HashMap<String, Object>();
		classLogger.warn(
				"CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/addEngineUserPermission with PARAM engineId");

		SecurityAdminUtils adminUtils = null;
		User user = null;
		String newUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String appId = WebUtility.inputSQLSanitizer(form.getFirst("appId"));
		String permission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger
					.warn("User is trying to add user " + newUserId + " to database " + appId + " when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			ret.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(ret, 401);
		}

		try {
			adminUtils.addEngineUser(newUserId, appId, permission, user, null, null, null, 0, 0.0);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger
				.info("User has added user " + newUserId + " to database " + appId + " with permission " + permission);
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Add all users to a database
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addAllUsers")
	@Operation(
		summary = "Add all users (legacy)",
		description = "Adds all users to a database. Deprecated: use /auth/admin/engine/addAllUsers.",
		deprecated = true,
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = LegacyAdminSuccessResponse.class))),
			@ApiResponse(responseCode = "400", description = "Bad request",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response addAllUsers(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn(
				"CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/addAllUsers with PARAM engineId");
		classLogger.warn(
				"CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/addAllUsers with PARAM engineId");
		classLogger.warn(
				"CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/addAllUsers with PARAM engineId");
		classLogger.warn(
				"CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/addAllUsers with PARAM engineId");

		SecurityAdminUtils adminUtils = null;
		User user = null;
		String appId = WebUtility.inputSanitizer(form.getFirst("appId"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to add all users to database " + appId + " when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.addAllEngineUsers(appId, permission, user, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has added all users to database " + appId + " with permission " + permission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Edit user permission for a database
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editAppUserPermission")
	@Operation(
		summary = "Edit app user permission (legacy)",
		description = "Edits a user's permission for a database. Deprecated: use /auth/admin/engine/editEngineUserPermission.",
		deprecated = true,
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = LegacyAdminSuccessResponse.class))),
			@ApiResponse(responseCode = "400", description = "Bad request",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response editAppUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Map<String, Object> ret = new HashMap<String, Object>();
		classLogger.warn(
				"CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/editEngineUserPermission with PARAM engineId");

		SecurityAdminUtils adminUtils = null;
		User user = null;

		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String appId = WebUtility.inputSQLSanitizer(form.getFirst("appId"));
		String newPermission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			classLogger.warn("User is trying to edit user " + existingUserId + " permissions for database " + appId
					+ " when not an admin");
			ret.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(ret, 401);
		}

		try {
			adminUtils.editEngineUserPermission(existingUserId, appId, newPermission, user, null, null, null, 0, 0.0);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			ret.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(ret, 400);
		}

		// log the operation
		classLogger.info("User has edited user " + existingUserId + " permission to database " + appId + " with level "
				+ newPermission);
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * update all user's permission level to new permission level for a database
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("updateAppUserPermissions")
	@Operation(
		summary = "Update app user permissions (legacy)",
		description = "Updates all users' permissions for a database. Deprecated: use /auth/admin/engine/updateEngineUserPermissions.",
		deprecated = true,
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = LegacyAdminSuccessResponse.class))),
			@ApiResponse(responseCode = "400", description = "Bad request",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response updateAppUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn(
				"CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/updateEngineUserPermissions with PARAM engineId");

		SecurityAdminUtils adminUtils = null;
		User user = null;
		String appId = WebUtility.inputSanitizer(form.getFirst("appId"));
		String newPermission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			classLogger.warn("User is trying to edit user permissions for database " + appId + " when not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.updateEngineUserPermissions(appId, newPermission, user, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has edited user permissions to database " + appId + " with level " + newPermission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Remove user permission for a database
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeAppUserPermission")
	@Operation(
		summary = "Remove app user permission (legacy)",
		description = "Removes a user's permission for a database. Deprecated: use /auth/admin/engine/removeEngineUserPermission.",
		deprecated = true,
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = LegacyAdminSuccessResponse.class))),
			@ApiResponse(responseCode = "400", description = "Bad request",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response removeAppUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn(
				"CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/removeEngineUserPermission with PARAM engineId");

		SecurityAdminUtils adminUtils = null;
		User user = null;

		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String appId = WebUtility.inputSQLSanitizer(form.getFirst("appId"));

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to remove user " + existingUserId + " from having access to database "
					+ appId + " when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.removeEngineUser(existingUserId, appId);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has removed user " + existingUserId + " from having access to database " + appId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	@POST
	@Produces("application/json")
	@Path("setAppGlobal")
	@Operation(
		summary = "Set app global (legacy)",
		description = "Sets the database public/private. Deprecated: use /auth/admin/engine/setEngineGlobal.",
		deprecated = true,
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = LegacyAdminSuccessResponse.class))),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "500", description = "Internal server error",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response setAppGlobal(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn(
				"CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/setEngineGlobal with PARAM engineId");

		SecurityAdminUtils adminUtils = null;
		User user = null;

		String appId = WebUtility.inputSanitizer(form.getFirst("appId"));
		boolean isPublic = Boolean.parseBoolean(form.getFirst("public"));
		String logPublic = isPublic ? " public " : " private";

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to set the database " + appId + logPublic + " when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.setEngineGlobal(appId, isPublic);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}

		// log the operation
		classLogger.info("User has set the database " + appId + logPublic);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Set the database as being discoverable for the entire semoss instance
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setAppDiscoverable")
	@Operation(
		summary = "Set app discoverable (legacy)",
		description = "Sets the database discoverability. Deprecated: use /auth/admin/engine/setEngineDiscoverable.",
		deprecated = true,
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = LegacyAdminSuccessResponse.class))),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "500", description = "Internal server error",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response setAppDiscoverable(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn(
				"CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/setEngineDiscoverable with PARAM engineId");

		SecurityAdminUtils adminUtils = null;
		User user = null;

		String appId = WebUtility.inputSanitizer(form.getFirst("appId"));
		boolean isDiscoverable = Boolean.parseBoolean(form.getFirst("discoverable"));
		String logDiscoverable = isDiscoverable ? " discoverable " : " not discoverable";

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to set the database " + appId + logDiscoverable + " when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.setEngineDiscoverable(appId, isDiscoverable);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}

		// log the operation
		classLogger.info("User has set the database " + appId + logDiscoverable);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Get users with no access to a given database
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getAppUsersNoCredentials")
	@Operation(
		summary = "Get app users without credentials (legacy)",
		description = "Lists users without engine credentials. Deprecated: use /auth/admin/engine/getEngineUsersNoCredentials.",
		deprecated = true,
		parameters = {
			@Parameter(name = "appId", in = ParameterIn.QUERY, description = "Database identifier"),
			@Parameter(name = "searchTerm", in = ParameterIn.QUERY, description = "Search term"),
			@Parameter(name = "limit", in = ParameterIn.QUERY, description = "Limit"),
			@Parameter(name = "offset", in = ParameterIn.QUERY, description = "Offset")
		},
		responses = {
			@ApiResponse(responseCode = "200", description = "Users retrieved",
				content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = java.lang.Object.class)))),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response getAppUsersNoCredentials(@Context HttpServletRequest request, @QueryParam("appId") String appId,
			@QueryParam("searchTerm") String searchTerm, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {
		classLogger.warn(
				"CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/getEngineUsersNoCredentials with PARAM engineId");

		appId = WebUtility.inputSanitizer(appId);
		searchTerm = WebUtility.inputSanitizer(searchTerm);

		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to get all users when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		List<Map<String, Object>> ret = adminUtils.getEngineUsersNoCredentials(appId,
				WebUtility.inputSanitizer(searchTerm), limit, offset);
		return WebUtility.getResponse(ret, 200);
	}

}
