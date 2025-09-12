package prerna.semoss.web.services.local.auth;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
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

import prerna.auth.AccessPermissionEnum;
import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("/auth/insight")
@PermitAll
@Tag(name = "auth", description = "Endpoints for managing authentication and authorization of users and applications, including user permissions, access control, and administrative actions.")
public class InsightAuthorizationResource {

	private static final Logger classLogger = LogManager.getLogger(InsightAuthorizationResource.class);

	/**
	 * Get the insights of user
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getInsights")
	@Operation(summary = "List insights available to the user", description = "Search and list insights optionally filtered by project, search term, and pagination.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Insights fetched",
			content = @Content(array = @ArraySchema(schema = @Schema(implementation = Object.class)))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or invalid session",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response getInsights(@Context HttpServletRequest request, 
			@QueryParam("projectId") String projectId, 
			@QueryParam("searchTerm") String searchTerm, 
			@QueryParam("limit") String limit,
			@QueryParam("offset") String offset) {

		projectId = WebUtility.inputSanitizer(projectId);
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);
		offset = WebUtility.inputSanitizer(offset);
		limit = WebUtility.inputSanitizer(limit);

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn("Invalid user session trying to access authorization resources");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		List<String> projectFilter = null;
		if (projectId != null && !(projectId = projectId.trim()).isEmpty()) {
			projectFilter = new ArrayList<>();
			projectFilter.add(projectId);
		}

		List<Map<String, Object>> ret = SecurityInsightUtils.searchUserInsights(user, projectFilter, searchTerm, false,
				null, null, limit, offset);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Get the insights the user can edit in the project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getProjectInsights")
	@Operation(summary = "List project insights the user can edit")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Insights fetched",
			content = @Content(array = @ArraySchema(schema = @Schema(implementation = Object.class)))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or invalid session",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response getProjectInsights(@Context HttpServletRequest request, @QueryParam("projectId") String projectId,
			@QueryParam("searchTerm") String searchTerm) {

		projectId = WebUtility.inputSanitizer(projectId);
		searchTerm = WebUtility.inputSanitizer(searchTerm);

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn("Invalid user session trying to access authorization resources");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, Object>> ret = SecurityInsightUtils.getUserEditableInsights(user, projectId);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Get the user insight permissions for a given insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getUserInsightPermission")
	@Operation(summary = "Get current user's permission for an insight")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission fetched",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or no access",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response getUserInsightPermission(@Context HttpServletRequest request, 
			@QueryParam("projectId") String projectId, @QueryParam("insightId") String insightId) {

		projectId = WebUtility.inputSanitizer(projectId);
		insightId = WebUtility.inputSanitizer(insightId);

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn("Invalid user session trying to access authorization resources");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String permission = SecurityInsightUtils.getActualUserInsightPermission(user, projectId, insightId);
		if (permission == null) {
			classLogger.warn("User is trying to pull permission details for insight " + insightId + " in project "
					+ projectId + " without having proper access");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User does not have access to this insight");
			return WebUtility.getResponse(errorMap, 401);
		}

		Map<String, String> ret = new HashMap<String, String>();
		ret.put("permission", permission);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Get the user insight permissions for a given insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getInsightUsers")
	@Operation(summary = "List insight users and permissions")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Users fetched",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or no access",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response getInsightUsers(@Context HttpServletRequest request, 
			@QueryParam("projectId") String projectId, 
			@QueryParam("insightId") String insightId, 
			@QueryParam("userId") String userId,
			@QueryParam("permission") String permission, 
			@QueryParam("limit") long limit, 
			@QueryParam("offset") long offset) {

		projectId = WebUtility.inputSanitizer(projectId);
		userId = WebUtility.inputSQLSanitizer(userId);
		insightId = WebUtility.inputSanitizer(insightId);
		permission = WebUtility.inputSanitizer(permission);

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn("Invalid user session trying to access authorization resources");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		Map<String, Object> ret = new HashMap<String, Object>();
		try {
			List<Map<String, Object>> members = SecurityInsightUtils.getInsightUsers(user, projectId, insightId, userId,
					permission, limit, offset);
			long totalMembers = SecurityInsightUtils.getInsightUsersCount(user, projectId, insightId, userId,
					permission);
			ret.put("totalMembers", totalMembers);
			ret.put("members", members);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to pull permission details for insight " + insightId + " in project "
					+ projectId + " without having proper access");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Add a user to an insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addInsightUserPermission")
	@Operation(summary = "Grant insight access to a user")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission added",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or invalid session",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response addInsightUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn("Invalid user session trying to access authorization resources");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String newUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		// add the person with read only access if they do not have access to the app
		if (SecurityProjectUtils.getUserProjectPermission(newUserId, projectId) == null) {
			try {
				SecurityProjectUtils.addProjectUser(user, newUserId, projectId,
						AccessPermissionEnum.READ_ONLY.getPermission(), endDate);
			} catch (IllegalAccessException e) {
				classLogger.warn("User is trying to add user " + newUserId + " to insight " + insightId + " in project "
						+ projectId + " without having proper access");
				classLogger.error(Constants.STACKTRACE, e);
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
				return WebUtility.getResponse(errorMap, 400);
			} catch (Exception e) {
				classLogger.error(Constants.STACKTRACE, e);
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
				return WebUtility.getResponse(errorMap, 400);
			}
		}

		try {
			SecurityInsightUtils.addInsightUser(user, newUserId, projectId, insightId, permission, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has added user " + newUserId + " to insight " + insightId + " in project " + projectId
				+ " with permission " + permission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Edit user permission for an insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editInsightUserPermission")
	@Operation(summary = "Edit a user's insight permission")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission updated",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or invalid session",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response editInsightUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn("Invalid user session trying to access authorization resources");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		String newPermission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		try {
			SecurityInsightUtils.editInsightUserPermission(user, existingUserId, projectId, insightId, newPermission,
					endDate);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to edit user " + existingUserId + " permissions for insight " + insightId
					+ " in project " + projectId + " without having proper access");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has edited user " + existingUserId + " permission to insight " + insightId
				+ " in project " + projectId + " with level " + newPermission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Edit user permission for insight, in bulk
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editInsightUserPermissions")
	@Operation(summary = "Edit multiple users' insight permissions")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permissions updated",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or invalid session",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response editInsightUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn("Invalid user session trying to access authorization resources");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		String endDate = null; // form.getFirst("endDate");

		if (AbstractSecurityUtils.adminOnlyInsightAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger
					.warn("User is trying to edit user permissions for insight " + insightId + " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		Type listMapStr = new TypeToken<List<Map<String, String>>>(){}.getType();
		List<Map<String, String>> requests = new Gson().fromJson(form.getFirst("userpermissions"), listMapStr);
		try {
			SecurityInsightUtils.editInsightUserPermissions(user, projectId, insightId, requests, endDate);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to edit user permissions for insight " + insightId
					+ " without having proper access");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has edited user permission to insight " + insightId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Remove user permission for an insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeInsightUserPermission")
	@Operation(summary = "Remove a user's insight access")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission removed",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or invalid session",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response removeInsightUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn("Invalid user session trying to access authorization resources");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));

		try {
			SecurityInsightUtils.removeInsightUser(user, existingUserId, projectId, insightId);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to remove user " + existingUserId + " from having access to insight "
					+ insightId + " in project " + projectId + " without having proper access");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has removed user " + existingUserId + " from having access to insight " + insightId
				+ " in project " + projectId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Set the insight global
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setInsightGlobal")
	@Operation(summary = "Set insight global visibility (public/private)")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Flag updated",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or forbidden",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response setInsightGlobal(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn("Invalid user session trying to access authorization resources");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		boolean isPublic = Boolean.parseBoolean(form.getFirst("isPublic"));
		if (isPublic && AbstractSecurityUtils.adminOnlyInsightSetPublic()) {
			if (!SecurityAdminUtils.userIsAdmin(user)) {
				classLogger.warn("User is trying to set the insight " + insightId + " in project " + projectId
						+ "  public is not an admin");
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, "Only an admin can set an insight as public");
				return WebUtility.getResponse(errorMap, 401);
			}
		}
		String logPublic = isPublic ? " public " : " private";

		try {
			SecurityInsightUtils.setInsightGlobalWithinProject(user, projectId, insightId, isPublic);

			/*
			 * BELOW COMMENTED OUT IS INVALID LOGIC WE DO NOT WANT TO MAKE IT HIDDEN IN
			 * INSIGHTS DB THAT WILL RESULT IN IT NOT BEING LOADED TO SECURITY AT ALL
			 */

			// also update in the app itself
			// so it is properly synchronized with the security db
//			ClusterUtil.reactorPullInsightsDB(appId);
//			IEngine app = Utility.getEngine(appId);
//			InsightAdministrator admin = new InsightAdministrator(app.getInsightDatabase());
//			admin.updateInsightGlobal(insightId, !isPublic);
//			ClusterUtil.reactorPushInsightDB(appId);

		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to set the insight " + insightId + " in project " + projectId + logPublic
					+ " without having proper access");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has set the insight " + insightId + " in project " + projectId + logPublic);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Set the insight as favorited by the user
	 * 
	 * @param request
	 * @param form
	 * @param appId
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setInsightFavorite")
	@Operation(summary = "Set insight favorite flag for the current user")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Favorite set",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or forbidden",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response setInsightFavorite(@Context HttpServletRequest request, MultivaluedMap<String, String> form, String appId) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn("Invalid user session trying to access authorization resources");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		boolean isFavorite = Boolean.parseBoolean(form.getFirst("isFavorite"));
		String logFavorited = isFavorite ? " favorited " : " not favorited";

		try {
			SecurityInsightUtils.setInsightFavorite(user, projectId, insightId, isFavorite);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to set the insight " + insightId + " in project " + projectId
					+ logFavorited + " without having proper access");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has set the insight " + insightId + " in project " + appId + logFavorited);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Get the users with no access to a given insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getInsightUsersNoCredentials")
	@Operation(summary = "List users without access to an insight")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Users fetched",
			content = @Content(array = @ArraySchema(schema = @Schema(implementation = Object.class)))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or no access",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response getInsightUsersNoCredentials(@Context HttpServletRequest request, 
			@QueryParam("projectId") String projectId, 
			@QueryParam("insightId") String insightId,
			@QueryParam("searchTerm") String searchTerm,
			@QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {

		projectId = WebUtility.inputSanitizer(projectId);
		insightId = WebUtility.inputSanitizer(insightId);
		searchTerm = WebUtility.inputSanitizer(searchTerm);

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn("User  user does not have access to provided insight");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, Object>> ret = null;
		try {
			ret = SecurityInsightUtils.getInsightUsersNoCredentials(user, projectId, insightId, searchTerm, limit,
					offset);
		} catch (IllegalAccessException e) {
			classLogger.warn("User  is trying to pull users without access to insight " + insightId + " in project "
					+ projectId + " without having proper access");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Add user permissions in bulk to insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addInsightUserPermissions")
	@Operation(summary = "Grant insight access to multiple users")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permissions added",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or forbidden",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response addInsightUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn("Invalid user session trying to access authorization resources");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		String endDate = null; // form.getFirst("endDate");

		if (AbstractSecurityUtils.adminOnlyInsightAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to add user permissions to insight " + insightId + " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		// adding user permissions in bulk
		Type listMapStr = new TypeToken<List<Map<String, String>>>(){}.getType();
		List<Map<String, String>> permission = new Gson().fromJson(form.getFirst("userpermissions"), listMapStr);
		try {
			SecurityInsightUtils.addInsightUserPermissions(user, projectId, insightId, permission, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has added user permissions to insight " + insightId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Remove user permissions for insight, in bulk
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeInsightUserPermissions")
	@Operation(summary = "Remove multiple users' insight access")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permissions removed",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or forbidden",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response removeInsightUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn("Invalid user session trying to access authorization resources");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		List<String> ids = new Gson().fromJson(form.getFirst("ids"), List.class);
		ids = WebUtility.inputSanitizer(ids);
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));

		if (AbstractSecurityUtils.adminOnlyProjectAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to remove users from having access to insight " + insightId
					+ " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			SecurityInsightUtils.removeInsightUsers(user, ids, projectId, insightId);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to remove users from having access to insight " + insightId
					+ " without having proper access");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has removed users from having access to project " + projectId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * approval of user access requests
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("approveInsightUserAccessRequest")
	@Operation(summary = "Approve user access requests for an insight")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Requests approved",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or forbidden",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response approveInsightUserAccessRequest(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn("Invalid user session trying to access authorization resources");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		String endDate = null; // form.getFirst("endDate");

		if (AbstractSecurityUtils.adminOnlyInsightAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to approve user access to insight " + insightId + " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		// adding user permissions and updating user access requests in bulk
		Type listMapStr = new TypeToken<List<Map<String, String>>>(){}.getType();
		List<Map<String, String>> requests = new Gson().fromJson(form.getFirst("requests"), listMapStr);
		try {
			SecurityInsightUtils.approveInsightUserAccessRequests(user, projectId, insightId, requests, endDate);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to grant user access to insight " + insightId + " without having proper access");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has approved user access and added user permissions to insight " + insightId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * deny of user access requests
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("denyInsightUserAccessRequest")
	@Operation(summary = "Deny user access requests for an insight")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Requests denied",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or forbidden",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response denyInsightUserAccessRequest(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn("Invalid user session trying to access authorization resources");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));

		if (AbstractSecurityUtils.adminOnlyInsightAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn("User is trying to deny user access to insight " + insightId + " but is not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		// updating user access requests in bulk
		Type listString = new TypeToken<List<String>>(){}.getType();
		List<String> requestIds = new Gson().fromJson(form.getFirst("requestIds"), listString);
		requestIds = WebUtility.inputSanitizer(requestIds);
		try {
			SecurityInsightUtils.denyInsightUserAccessRequests(user, projectId, insightId, requestIds);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has denied user access requests to project " + projectId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
}
