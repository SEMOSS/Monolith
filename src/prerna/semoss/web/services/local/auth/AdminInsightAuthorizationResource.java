package prerna.semoss.web.services.local.auth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

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
import java.lang.reflect.Type;

import prerna.auth.AccessToken;
import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.engine.impl.InsightAdministrator;
import prerna.project.api.IProject;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/auth/admin/insight")
@PermitAll
@Tag(name = "auth", description = "Endpoints for managing authentication and authorization of users and applications, including user permissions, access control, and administrative actions.")
public class AdminInsightAuthorizationResource extends AbstractAdminResource {

	private static final Logger classLogger = LogManager.getLogger(AdminInsightAuthorizationResource.class);

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
	@Operation(summary = "List insights", description = "Returns insights for the current user filtered by project and/or search term, with pagination.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Insights retrieved",
			content = @Content(mediaType = "application/json",
				array = @ArraySchema(schema = @Schema(type = "object"))
			)
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response getInsights(@Context HttpServletRequest request, @QueryParam("projectId") String projectId,
			@QueryParam("searchTerm") String searchTerm, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {
		projectId = WebUtility.inputSQLSanitizer(projectId);
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);

		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to see all the insights when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		List<String> projectFilter = null;
		if (projectId != null && !(projectId = projectId.trim()).isEmpty()) {
			projectFilter = new ArrayList<>();
			projectFilter.add(projectId);
		}

		List<Map<String, Object>> ret = adminUtils.getAllUserInsights(user, projectFilter, searchTerm, limit, offset);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Get the insights for the project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getProjectInsights")
	@Operation(summary = "List project insights", description = "Returns insights belonging to the specified project.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Insights retrieved",
			content = @Content(mediaType = "application/json",
				array = @ArraySchema(schema = @Schema(type = "object"))
			)
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response getProjectInsights(@Context HttpServletRequest request, @QueryParam("projectId") String projectId,
			@QueryParam("searchTerm") String searchTerm) {
		projectId = WebUtility.inputSQLSanitizer(projectId);
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);

		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to see all the insight for project " + projectId + " when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, Object>> ret = adminUtils.getProjectInsights(projectId);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Get the project users and their permissions
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("getAllProjectInsightUsers")
	@Operation(summary = "List project insight users", description = "Returns all users and their permissions for project insights.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Users retrieved",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
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
	public Response getAllProjectInsightUsers(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String userId = WebUtility.inputSQLSanitizer(form.getFirst("userId"));

		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to see all the user insight access for project " + projectId
					+ " when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		return WebUtility.getResponse(adminUtils.getAllUserInsightAccess(projectId, userId), 200);
	}

	/**
	 * Get the user insight permissions for a given insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("deleteProjectInsights")
	@Operation(summary = "Delete project insights", description = "Deletes one or more insights from a project.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Insights deleted",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
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
	public Response deleteProjectInsights(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;

		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
	Gson gson = new Gson();
	Type insightIdsType = new TypeToken<List<String>>(){}.getType();
	List<String> insightIds = gson.fromJson(form.getFirst("insightId"), insightIdsType);
		insightIds = WebUtility.inputSQLSanitizer(insightIds);

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to delete insight from projectId " + projectId + " when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.deleteProjectInsights(projectId, insightIds);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Get the user insight permissions for a given insight
	 * 
	 * @param request
	 * @param projectId
	 * @param insightId
	 * @param userId
	 * @param permission
	 * @param limit
	 * @param offset
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getInsightUsers")
	@Operation(summary = "List insight users", description = "Returns users who have access to a given insight, with total count and pagination.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Users retrieved",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response getInsightUsers(@Context HttpServletRequest request, @QueryParam("projectId") String projectId,
			@QueryParam("insightId") String insightId, @QueryParam("userId") String userId,
			@QueryParam("permission") String permission, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {
		projectId = WebUtility.inputSQLSanitizer(projectId);
		userId = WebUtility.inputSQLSanitizer(userId);
		insightId = WebUtility.inputSQLSanitizer(insightId);
		permission = WebUtility.inputSQLSanitizer(permission);

		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to get all users who have access to insight " + insightId + " in project "
					+ projectId + " when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		Map<String, Object> ret = new HashMap<String, Object>();
		List<Map<String, Object>> members = adminUtils.getInsightUsers(projectId, insightId, userId, permission, limit,
				offset);
		long totalMembers = SecurityAdminUtils.getInsightUsersCount(projectId, insightId, userId, permission);
		ret.put("totalMembers", totalMembers);
		ret.put("members", members);

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
	@Operation(summary = "Add insight user permission", description = "Adds a user to an insight with a specific permission level.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission added",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
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
	public Response addInsightUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;

		String newUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		String permission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to add user " + newUserId + " to insight " + insightId + " in project "
					+ projectId + " when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.addInsightUser(newUserId, projectId, insightId, permission, user, endDate);
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
	 * Give permission to user for all insights in a project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("/grantAllProjectInsights")
	@Operation(summary = "Grant all project insights", description = "Grants a user access to all insights in a project.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Access granted",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
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
	public Response grantAllProjectInsights(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String userId = WebUtility.inputSQLSanitizer(form.getFirst("userId"));
		String permission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to pull the projects that user " + userId + " has access to when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.grantAllProjectInsights(projectId, userId, permission, user);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has granted all projects to " + userId + "with permission " + permission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Give permission to user for all insights in an project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("/grantNewUsersInsightAccess")
	@Operation(summary = "Grant new users insight access", description = "Grants access to new users for an insight with a given permission.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Access granted",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
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
	public Response grantNewUsersInsightAccess(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		String permission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to grant new users insight access when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.grantNewUsersInsightAccess(projectId, insightId, permission, user, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has granted new users with permission " + permission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Add all users to an insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addAllUsers")
	@Operation(summary = "Add all users to an insight", description = "Adds all users to the given insight with a permission level.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Users added",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
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
	public Response addAllUsers(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		String permission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to add all users to insight " + insightId + " in project " + projectId
					+ " when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.addAllInsightUsers(projectId, insightId, permission, user, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has added all users to project " + projectId + " with permission " + permission);

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
	@Operation(summary = "Edit insight user permission", description = "Edits a user's permission for a given insight.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission updated",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
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
	public Response editInsightUserPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;

		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		String newPermission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to edit user " + existingUserId + " permissions for insight " + insightId
					+ " in project " + projectId + " when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.editInsightUserPermission(existingUserId, projectId, insightId, newPermission, user, endDate);
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
	 * Edit user permission for insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editInsightUserPermissions")
	@Operation(summary = "Edit multiple insight user permissions", description = "Edits multiple user permissions for an insight in bulk.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permissions updated",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
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
	public Response editInsightUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			classLogger.warn(
					"User is trying to edit user access permissions for insight " + insightId + " when not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, String>> requests = new Gson().fromJson(form.getFirst("userpermissions"), List.class);
		try {
			SecurityAdminUtils.editInsightUserPermissions(projectId, insightId, requests, user, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has edited user access permissions to insight " + insightId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * update all user's permission level to new permission level for an insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("updateInsightUserPermissions")
	@Operation(summary = "Update all insight user permissions", description = "Updates all users' permissions for an insight to a new level.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permissions updated",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
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
	public Response updateAppUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String newPermission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			classLogger.warn("User is trying to edit user permissions for project " + projectId + " when not an admin");
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.updateInsightUserPermissions(projectId, insightId, newPermission, user, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has added all users to insight " + insightId + " with level " + newPermission);

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
	@Operation(summary = "Remove insight user permission", description = "Removes a user's access to a given insight.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission removed",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
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
	public Response removeInsightUserPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;

		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to remove user " + existingUserId + " from having access to insight "
					+ insightId + " in project " + projectId + " when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.removeInsightUser(existingUserId, projectId, insightId);
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
	 * Remove user permission for an insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setInsightGlobal")
	@Operation(summary = "Set insight visibility", description = "Sets an insight to public or private within a project.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Visibility updated",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
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
	public Response setInsightGlobal(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;

		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		boolean isGlobal = Boolean.parseBoolean(form.getFirst("isPublic"));
		String logPublic = isGlobal ? " public " : " private";

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to set the insight " + insightId + " in project " + projectId + logPublic
					+ " when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.setInsightGlobalWithinProject(projectId, insightId, isGlobal);
			IProject project = Utility.getProject(projectId);
			InsightAdministrator admin = new InsightAdministrator(project.getInsightDatabase());
			admin.updateInsightGlobal(insightId, isGlobal);
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
	 * Get the users with no access to a given insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getInsightUsersNoCredentials")
	@Operation(summary = "List users without insight access", description = "Returns users who do not have access to a given insight.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Users retrieved",
			content = @Content(mediaType = "application/json",
				array = @ArraySchema(schema = @Schema(type = "object"))
			)
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response getInsightUsersNoCredentials(@Context HttpServletRequest request,
			@QueryParam("projectId") String projectId, @QueryParam("insightId") String insightId,
			@QueryParam("searchTerm") String searchTerm, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {
		projectId = WebUtility.inputSQLSanitizer(projectId);
		insightId = WebUtility.inputSQLSanitizer(insightId);
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);

		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to get all users who have access to insight " + insightId + " in project "
					+ projectId + " when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		List<Map<String, Object>> ret = adminUtils.getInsightUsersNoCredentials(projectId, insightId, searchTerm, limit,
				offset);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Add user permissions in bulk to an insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addInsightUserPermissions")
	@Operation(summary = "Add multiple insight user permissions", description = "Adds user permissions to an insight in bulk.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permissions added",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
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
	public Response addInsightUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to add user permission to insight " + insightId + " when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		// adding user permissions in bulk
	Type permissionType = new TypeToken<List<Map<String, String>>>(){}.getType();
	List<Map<String, String>> permission = new Gson().fromJson(form.getFirst("userpermissions"), permissionType);
		try {
			adminUtils.addInsightUserPermissions(projectId, insightId, permission, user, endDate);
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
	 * Remove user permissions for an insight, in bulk
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeInsightUserPermissions")
	@Operation(summary = "Remove multiple insight user permissions", description = "Removes user permissions from an insight in bulk.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permissions removed",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
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
	public Response removeInsightUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to remove usersfrom having access to insight " + insightId + " when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		Gson gson = new Gson();
	Type idsType = new TypeToken<List<String>>(){}.getType();
	List<String> ids = gson.fromJson(form.getFirst("ids"), idsType);
		try {
			adminUtils.removeInsightUsers(ids, projectId, insightId);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has removed users from having access to insight " + insightId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Admin approval of user access requests
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("approveInsightUserAccessRequest")
	@Operation(summary = "Approve insight user access requests", description = "Approves user access requests for an insight and grants permissions.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Requests approved",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
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
	public Response approveInsightUserAccessRequest(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to approve user request for permission to insight " + insightId
					+ " when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		// adding user permissions and updating user access requests in bulk
	Type approveRequestsType = new TypeToken<List<Map<String, Object>>>(){}.getType();
	List<Map<String, Object>> requests = new Gson().fromJson(form.getFirst("requests"), approveRequestsType);
		try {
			AccessToken token = user.getAccessToken(user.getPrimaryLogin());
			String userId = token.getId();
			String userType = token.getProvider().toString();
			adminUtils.approveInsightUserAccessRequests(userId, userType, projectId, insightId, requests, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has approved user access requests and added user permissions to project " + projectId
				+ " insigth " + insightId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Admin deny of user access requests
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("denyInsightUserAccessRequest")
	@Operation(summary = "Deny insight user access requests", description = "Denies user access requests for an insight.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Requests denied",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
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
	public Response denyInsightUserAccessRequest(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;

		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSQLSanitizer(form.getFirst("insightId"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to deny user request for permission to insight " + insightId
					+ " when not an admin");
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		// updating user access requests in bulk
	Type requestIdsType = new TypeToken<List<String>>(){}.getType();
	List<String> requestids = new Gson().fromJson(form.getFirst("requestids"), requestIdsType);
		try {
			AccessToken token = user.getAccessToken(user.getPrimaryLogin());
			String userId = token.getId();
			String userType = token.getProvider().toString();
			adminUtils.denyInsightUserAccessRequests(userId, userType, projectId, insightId, requestids);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has denied user access requests to insight " + insightId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

}
