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

import prerna.auth.AccessToken;
import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.engine.api.IEngine;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("/auth/admin/database")
@PermitAll
@Deprecated
@Tag(name = "auth", description = "Endpoints for managing authentication and authorization of users and applications, including user permissions, access control, and administrative actions.")
public class AdminDatabaseAuthorizationResource2 extends AbstractAdminResource {

	private static final Logger classLogger = LogManager.getLogger(AdminDatabaseAuthorizationResource2.class);

	@Context
	protected ServletContext context;
	
	/**
	 * Get the apps the user has access to
	 * @param request
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getDatabases")
	@Operation(
		summary = "Get databases (legacy)",
		description = "Returns engine settings for database-type engines. Deprecated: use /auth/admin/engine/getEngines with engineTypes.",
		deprecated = true,
		parameters = {
			@Parameter(name = "databaseId", in = ParameterIn.QUERY, description = "Filter by databaseId (repeatable)")
		},
		responses = {
			@ApiResponse(responseCode = "200", description = "Databases retrieved",
				content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = java.lang.Object.class)))),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response getDatabases(@Context HttpServletRequest request, @QueryParam("databaseId") List<String> databaseId) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/getEngines with PARAM engineTypes");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/getEngines with PARAM engineTypes");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/getEngines with PARAM engineTypes");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/getEngines with PARAM engineTypes");

		databaseId=WebUtility.inputSanitizer(databaseId);

		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to get all databases when not an admin"));
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
	@Path("/getAllUserDatabases")
	@Produces("application/json")
	@Operation(
		summary = "Get all user databases (legacy)",
		description = "Lists engines a user has access to. Deprecated: use /auth/admin/engine/getAllUserEngines with engineTypes.",
		deprecated = true,
		responses = {
			@ApiResponse(responseCode = "200", description = "User databases retrieved",
				content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = java.lang.Object.class)))),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response getAllUserDatabases(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/getAllUserEngines with PARAM engineTypes");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/getAllUserEngines with PARAM engineTypes");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/getAllUserEngines with PARAM engineTypes");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/getAllUserEngines with PARAM engineTypes");

		SecurityAdminUtils adminUtils = null;
		User user = null;
		String userId = WebUtility.inputSQLSanitizer(form.getFirst("userId"));
		List<String> engineTypes = null;
		if (form.getFirst("engineTypes") != null) {
			Type listOfString = new TypeToken<List<String>>(){}.getType();
			engineTypes = new Gson().fromJson(form.getFirst("engineTypes"), listOfString);
			engineTypes = WebUtility.inputSanitizer(engineTypes);
		}

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to pull the databases that user " + userId + " has access to when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		return WebUtility.getResponse(adminUtils.getAllUserEngines(userId, engineTypes), 200);
	}
	
	@POST
	@Path("/grantAllDatabases")
	@Produces("application/json")
	@Operation(
		summary = "Grant all databases (legacy)",
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
	public Response grantAllDatabases(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/grantAllEngines with PARAM engineTypes");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/grantAllEngines with PARAM engineTypes");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/grantAllEngines with PARAM engineTypes");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/grantAllEngines with PARAM engineTypes");

		SecurityAdminUtils adminUtils = null;
		User user = null;
		String userId = WebUtility.inputSQLSanitizer(form.getFirst("userId"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		boolean isAddNew = Boolean.parseBoolean(form.getFirst("isAddNew") + "");

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to grant all the databases to user " + userId + " when not an admin"));
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
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user),
				"has granted all databases to " + userId + "with permission " + permission));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	
	@POST
	@Path("/grantNewUsersDatabaseAccess")
	@Produces("application/json")
	@Operation(
		summary = "Grant new users database access (legacy)",
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
	public Response grantNewUsersDatabaseAccess(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/grantNewUsersEngineAccess with PARAM engineTypes");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/grantNewUsersEngineAccess with PARAM engineTypes");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/grantNewUsersEngineAccess with PARAM engineTypes");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/grantNewUsersEngineAccess with PARAM engineTypes");
		
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to grant database to new users when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.grantNewUsersEngineAccess(databaseId, permission, user, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user),
				"has granted database " + databaseId + "to new users with permission " + permission));

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Get the database users and their permissions
	 * @param request
	 * @param databaseId
	 * @param userId
	 * @param permission
	 * @param limit
	 * @param offset
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getDatabaseUsers")
	@Operation(
		summary = "Get database users (legacy)",
		description = "Gets users and permissions for a database. Deprecated: use /auth/admin/engine/getEngineUsers.",
		deprecated = true,
		parameters = {
			@Parameter(name = "databaseId", in = ParameterIn.QUERY, description = "Database identifier"),
			@Parameter(name = "userId", in = ParameterIn.QUERY, description = "User identifier to filter"),
			@Parameter(name = "searchTerm", in = ParameterIn.QUERY, description = "Search term"),
			@Parameter(name = "permission", in = ParameterIn.QUERY, description = "Permission filter"),
			@Parameter(name = "limit", in = ParameterIn.QUERY, description = "Limit"),
			@Parameter(name = "offset", in = ParameterIn.QUERY, description = "Offset")
		},
		responses = {
			@ApiResponse(responseCode = "200", description = "Users retrieved",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = java.lang.Object.class))),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response getDatabaseUsers(@Context HttpServletRequest request, 
			@QueryParam("databaseId") String databaseId,  @QueryParam("userId") String userId, @QueryParam("searchTerm") String searchTerm,  @QueryParam("permission") String permission, @QueryParam("limit") long limit, @QueryParam("offset") long offset) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/getEngineUsers with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/getEngineUsers with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/getEngineUsers with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/getEngineUsers with PARAM engineId");

		databaseId=WebUtility.inputSanitizer(databaseId);
	    userId=WebUtility.inputSQLSanitizer(userId);
	    searchTerm=WebUtility.inputSQLSanitizer(searchTerm);
	    permission=WebUtility.inputSanitizer(permission);
	    
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to pull all the users who use database " + databaseId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		Map<String, Object> ret = new HashMap<String, Object>();
		String searchParam = searchTerm != null ? searchTerm : userId;
		List<Map<String, Object>> members = adminUtils.getEngineUsers(databaseId, searchParam, permission, limit, offset);
		long totalMembers = SecurityAdminUtils.getEngineUsersCount(databaseId, searchParam, permission);
		ret.put("totalMembers", totalMembers);
		ret.put("members", members);

		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Add a user to a database
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addDatabaseUserPermission")
	@Operation(
		summary = "Add database user permission (legacy)",
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
	public Response addDatabaseUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Map<String, Object> ret = new HashMap<String, Object>();

		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/addEngineUserPermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/addEngineUserPermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/addEngineUserPermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/addEngineUserPermission with PARAM engineId");

		SecurityAdminUtils adminUtils = null;
		User user = null;
		String newUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to add user " + newUserId + " to database " + databaseId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			ret.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(ret, 401);
		}
		
	    try {
			adminUtils.addEngineUser(newUserId, databaseId, permission, user, null, null, null, 0, 0.0);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has added user " + newUserId + " to database " + databaseId + " with permission " + permission));
		
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Add user permissions in bulk to a database
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addDatabaseUserPermissions")
	@Operation(
		summary = "Add database user permissions in bulk (legacy)",
		description = "Adds user permissions in bulk. Deprecated: use /auth/admin/engine/addEngineUserPermissions.",
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
	public Response addDatabaseUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/addEngineUserPermissions with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/addEngineUserPermissions with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/addEngineUserPermissions with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/addEngineUserPermissions with PARAM engineId");

		SecurityAdminUtils adminUtils = null;
		User user = null;
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to add user permission to database " + databaseId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		// adding user permissions in bulk
	Type listOfMapStringObject = new TypeToken<List<Map<String, Object>>>(){}.getType();
	List<Map<String, Object>> permission = new Gson().fromJson(form.getFirst("userpermissions"), listOfMapStringObject);
		try {
			adminUtils.addEngineUserPermissions(databaseId, permission, user);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has added user permissions to database " + databaseId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Add all users to a database
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
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/addAllUsers with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/addAllUsers with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/addAllUsers with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/addAllUsers with PARAM engineId");

		SecurityAdminUtils adminUtils = null;
		User user = null;
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to add all users to database " + databaseId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			adminUtils.addAllEngineUsers(databaseId, permission, user, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has added all users to database " + databaseId + " with permission " + permission));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Edit user permission for a database
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editDatabaseUserPermission")
	@Operation(
		summary = "Edit database user permission (legacy)",
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
	public Response editDatabaseUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Map<String, Object> ret = new HashMap<String, Object>();

		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/editEngineUserPermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/editEngineUserPermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/editEngineUserPermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/editEngineUserPermission with PARAM engineId");

		SecurityAdminUtils adminUtils = null;
		User user = null;

		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));
		String newPermission = WebUtility.inputSanitizer(form.getFirst("permission"));

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit user " + existingUserId + " permissions for database " + databaseId + " when not an admin"));
			ret.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(ret, 401);
		}
				
		try {
			adminUtils.editEngineUserPermission(existingUserId, databaseId, newPermission, user, null, null, null, 0, 0.0);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			ret.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(ret, 400);
		}
		
		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has edited user " + existingUserId + " permission to database " + databaseId + " with level " + newPermission));
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Edit user permission for a database
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editDatabaseUserPermissions")
	@Operation(
		summary = "Edit database user permissions in bulk (legacy)",
		description = "Edits multiple user permissions. Deprecated: use /auth/admin/engine/editEngineUserPermissions.",
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
	public Response editDatabaseUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/editEngineUserPermissions with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/editEngineUserPermissions with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/editEngineUserPermissions with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/editEngineUserPermissions with PARAM engineId");

		User user = null;
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));
		try {
			user = ResourceUtility.getUser(request);
			performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit user access permissions for database " + databaseId + " when not an admin"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
	Type listOfMapStringObject = new TypeToken<List<Map<String, Object>>>(){}.getType();
	List<Map<String, Object>> requests = new Gson().fromJson(form.getFirst("userpermissions"), listOfMapStringObject);
		try {
			SecurityAdminUtils.editEngineUserPermissions(databaseId, requests, user);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has edited user access permissions to database " + databaseId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * update all user's permission level to new permission level for a database
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("updateDatabaseUserPermissions")
	@Operation(
		summary = "Update database user permissions (legacy)",
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
	public Response updateDatabaseUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/updateEngineUserPermissions with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/updateEngineUserPermissions with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/updateEngineUserPermissions with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/updateEngineUserPermissions with PARAM engineId");

		SecurityAdminUtils adminUtils = null;
		User user = null;
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));
		String newPermission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit user permissions for database " + databaseId + " when not an admin"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			adminUtils.updateEngineUserPermissions(databaseId, newPermission, user, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has edited user permissions to database " + databaseId + " with level " + newPermission));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Remove user permission for a database
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeDatabaseUserPermission")
	@Operation(
		summary = "Remove database user permission (legacy)",
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
	public Response removeDatabaseUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/removeEngineUserPermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/removeEngineUserPermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/removeEngineUserPermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/removeEngineUserPermission with PARAM engineId");

		SecurityAdminUtils adminUtils = null;
		User user = null;
		
		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));
		
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to remove user " + existingUserId + " from having access to database " + databaseId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			adminUtils.removeEngineUser(existingUserId, databaseId);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has removed user " + existingUserId + " from having access to database " + databaseId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Remove user permissions for a database, in bulk
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeDatabaseUserPermissions")
	@Operation(
		summary = "Remove database user permissions in bulk (legacy)",
		description = "Removes multiple users' permissions. Deprecated: use /auth/admin/engine/removeEngineUserPermissions.",
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
	public Response removeDatabaseUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/removeEngineUserPermissions with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/removeEngineUserPermissions with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/removeEngineUserPermissions with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/removeEngineUserPermissions with PARAM engineId");

		SecurityAdminUtils adminUtils = null;
		User user = null;
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to remove usersfrom having access to database " + databaseId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
	Gson gson = new Gson();
	Type listOfString = new TypeToken<List<String>>(){}.getType();
	List<String> ids = gson.fromJson(form.getFirst("ids"), listOfString);
		ids =  WebUtility.inputSanitizer(ids);
		try {
			adminUtils.removeEngineUsers(ids, databaseId);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has removed users from having access to database " + databaseId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("setDatabaseGlobal")
	@Operation(
		summary = "Set database global (legacy)",
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
	public Response setDatabaseGlobal(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/setEngineGlobal with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/setEngineGlobal with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/setEngineGlobal with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/setEngineGlobal with PARAM engineId");

		SecurityAdminUtils adminUtils = null;
		User user = null;
		
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));
		boolean isPublic = Boolean.parseBoolean(form.getFirst("public"));
		String logPublic = isPublic ? " public " : " private";

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to set the database " + databaseId + logPublic + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.setEngineGlobal(databaseId, isPublic);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE,e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has set the database " + databaseId + logPublic));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	} 
	
	/**
	 * Set the database as being discoverable for the entire semoss instance
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setDatabaseDiscoverable")
	@Operation(
		summary = "Set database discoverable (legacy)",
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
	public Response setDatabaseDiscoverable(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/setEngineDiscoverable with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/setEngineDiscoverable with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/setEngineDiscoverable with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/setEngineDiscoverable with PARAM engineId");

		SecurityAdminUtils adminUtils = null;
		User user = null;
		
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));
		boolean isDiscoverable = Boolean.parseBoolean(form.getFirst("discoverable"));
		String logDiscoverable = isDiscoverable ? " discoverable " : " not discoverable";

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to set the database " + databaseId + logDiscoverable + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			adminUtils.setEngineDiscoverable(databaseId, isDiscoverable);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has set the database " + databaseId + logDiscoverable));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Get users with no access to a given database
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getDatabaseUsersNoCredentials")
	@Operation(
		summary = "Get database users without credentials (legacy)",
		description = "Lists users without engine credentials. Deprecated: use /auth/admin/engine/getEngineUsersNoCredentials.",
		deprecated = true,
		parameters = {
			@Parameter(name = "databaseId", in = ParameterIn.QUERY, description = "Database identifier"),
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
	public Response getDatabaseUsersNoCredentials(@Context HttpServletRequest request, 
			@QueryParam("databaseId") String databaseId, 
			@QueryParam("searchTerm") String searchTerm,
			@QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/getEngineUsersNoCredentials with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/getEngineUsersNoCredentials with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/getEngineUsersNoCredentials with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/getEngineUsersNoCredentials with PARAM engineId");

		databaseId=WebUtility.inputSanitizer(databaseId);
		searchTerm=WebUtility.inputSanitizer(searchTerm);
	    
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), " is trying to get all users when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		List<Map<String, Object>> ret = adminUtils.getEngineUsersNoCredentials(databaseId, searchTerm, limit, offset);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Admin approval of user access requests
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("approveDatabaseUserAccessRequest")
	@Operation(
		summary = "Approve database user access request (legacy)",
		description = "Approves user access requests and adds permissions. Deprecated: use /auth/admin/engine/approveEngineUserAccessRequest.",
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
	public Response approveDatabaseUserAccessRequest(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/approveEngineUserAccessRequest with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/approveEngineUserAccessRequest with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/approveEngineUserAccessRequest with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/approveEngineUserAccessRequest with PARAM engineId");

		SecurityAdminUtils adminUtils = null;

		User user = null;
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to approve user request for permission to database " + databaseId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		// adding user permissions and updating user access requests in bulk
	Type listOfMapStringObject = new TypeToken<List<Map<String, Object>>>(){}.getType();
	List<Map<String, Object>> requests = new Gson().fromJson(form.getFirst("requests"), listOfMapStringObject);
		try {
			AccessToken token = user.getAccessToken(user.getPrimaryLogin());
			String userId = token.getId();
			String userType = token.getProvider().toString();
			adminUtils.approveEngineUserAccessRequests(userId, userType, databaseId, requests, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has approved user access requests and added user permissions to database " + databaseId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Admin deny of user access requests
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("denyDatabaseUserAccessRequest")
	@Operation(
		summary = "Deny database user access request (legacy)",
		description = "Denies user access requests. Deprecated: use /auth/admin/engine/denyEngineUserAccessRequest.",
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
	public Response denyDatabaseUserAccessRequest(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/denyEngineUserAccessRequest with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/denyEngineUserAccessRequest with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/denyEngineUserAccessRequest with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/admin/engine/denyEngineUserAccessRequest with PARAM engineId");

		SecurityAdminUtils adminUtils = null;

		User user = null;
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to deny user request for permission to database " + databaseId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		// updating user access requests in bulk
	Type listOfString = new TypeToken<List<String>>(){}.getType();
	List<String> requestIds = new Gson().fromJson(form.getFirst("requestIds"), listOfString);
		try {
			AccessToken token = user.getAccessToken(user.getPrimaryLogin());
			String userId = token.getId();
			String userType = token.getProvider().toString();
			adminUtils.denyEngineUserAccessRequests(userId, userType, databaseId, requestIds);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has denied user access requests to database " + databaseId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	}
