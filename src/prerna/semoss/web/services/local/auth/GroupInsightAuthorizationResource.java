package prerna.semoss.web.services.local.auth;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

import java.util.HashMap;
import java.util.Map;

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

import prerna.auth.AccessPermissionEnum;
import prerna.auth.User;
import prerna.auth.utils.SecurityGroupInsightsUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("/auth/group/insight")
@PermitAll
@Tag(name = "auth", description = "Endpoints for managing authentication and authorization of users and applications, including user permissions, access control, and administrative actions.")
public class GroupInsightAuthorizationResource {

	private static final Logger classLogger = LogManager.getLogger(GroupInsightAuthorizationResource.class);

	@Context
	protected ServletContext context;

	/**
	 * Get the group insight permission level
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getGroupInsightPermission")
	@Operation(summary = "Get group permission for an insight", description = "Returns the permission string for the specified group, type, projectId, and insightId.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission fetched",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "400", description = "Invalid input",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or invalid session",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "500", description = "Server error",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response getGroupInsightPermission(@Context HttpServletRequest request,
			@QueryParam("groupId") String groupId, @QueryParam("type") String type,
			@QueryParam("projectId") String projectId, @QueryParam("insightId") String insightId) {

		projectId = WebUtility.inputSanitizer(projectId);
		type = WebUtility.inputSanitizer(type);
		insightId = WebUtility.inputSanitizer(insightId);
		groupId = WebUtility.inputSanitizer(groupId);

		Map<String, String> errorMap = new HashMap<String, String>();
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn("Invalid user session trying to access authorization resources");
			classLogger.error(Constants.STACKTRACE, e);
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			if (groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id cannot be null or empty");
			}
			if (type == null || (type = type.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group type cannot be null or empty");
			}
			if (projectId == null || (projectId = projectId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The projectId cannot be null or empty");
			}
			if (insightId == null || (insightId = insightId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The insightId cannot be null or empty");
			}

			Integer permissionCode = SecurityGroupInsightsUtils.getGroupInsightPermission(groupId, type, projectId,
					insightId);
			String permission = permissionCode == null ? null
					: AccessPermissionEnum.getPermissionValueById(permissionCode);

			Map<String, String> ret = new HashMap<String, String>();
			ret.put("permission", permission);
			return WebUtility.getResponse(ret, 200);
		} catch (IllegalArgumentException e) {
			classLogger.error(Constants.STACKTRACE, e);
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			errorMap.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorMap, 500);
		}
	}

	/**
	 * Add a group to an insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addGroupInsightPermission")
	@Operation(summary = "Add group permission to an insight")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission added",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or invalid session",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response addGroupInsightPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
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

		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = WebUtility.inputSanitizer(form.getFirst("type"));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));

		try {
			if (groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id cannot be null or empty");
			}
			if (type == null || (type = type.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group type cannot be null or empty");
			}
			if (projectId == null || (projectId = projectId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The projectId cannot be null or empty");
			}
			if (insightId == null || (insightId = insightId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The insightId cannot be null or empty");
			}
			if (permission == null || (permission = permission.trim()).isEmpty()) {
				throw new IllegalArgumentException("The permission cannot be null or empty");
			}

			SecurityGroupInsightsUtils.addInsightGroupPermission(user, groupId, type, projectId, insightId, permission,
					endDate);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to add groups to insight " + insightId + " under project " + projectId
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
		classLogger.info("User has added group " + groupId + " and type " + type + " to insight " + insightId
				+ " under project " + projectId + " with permission " + permission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Edit group permission for an insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editGroupInsightPermission")
	@Operation(summary = "Edit group permission for an insight")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission updated",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or invalid session",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response editGroupInsightPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
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

		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = WebUtility.inputSanitizer(form.getFirst("type"));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		String newPermission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));
		try {
			if (groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id cannot be null or empty");
			}
			if (type == null || (type = type.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group type cannot be null or empty");
			}
			if (projectId == null || (projectId = projectId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The projectId cannot be null or empty");
			}
			if (insightId == null || (insightId = insightId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The insightId cannot be null or empty");
			}
			if (newPermission == null || (newPermission = newPermission.trim()).isEmpty()) {
				throw new IllegalArgumentException("The permission cannot be null or empty");
			}
			SecurityGroupInsightsUtils.editInsightGroupPermission(user, groupId, type, projectId, insightId,
					newPermission, endDate);
		} catch (IllegalAccessException e) {
			classLogger
					.warn("User is trying to edit group " + groupId + " and type " + type + " permissions to insight "
							+ insightId + " under project " + projectId + " without having proper access");
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
		classLogger.info("User has edited group " + groupId + " and type " + type + " permission to insight "
				+ insightId + " under project " + projectId + " with level " + newPermission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Remove group permission for an insight
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeGroupInsightPermission")
	@Operation(summary = "Remove group permission for an insight")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission removed",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or invalid session",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response removeGroupInsightPermission(@Context HttpServletRequest request,
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

		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = WebUtility.inputSanitizer(form.getFirst("type"));
		String projectId = WebUtility.inputSanitizer(form.getFirst("projectId"));
		String insightId = WebUtility.inputSanitizer(form.getFirst("insightId"));
		try {
			if (groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id cannot be null or empty");
			}
			if (type == null || (type = type.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group type cannot be null or empty");
			}
			if (projectId == null || (projectId = projectId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The projectId cannot be null or empty");
			}
			if (insightId == null || (insightId = insightId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The insightId cannot be null or empty");
			}

			SecurityGroupInsightsUtils.removeInsightGroupPermission(user, groupId, type, projectId, insightId);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to remove group " + groupId + " and type " + type
					+ " from having access to insight " + insightId + " under project " + projectId
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
		classLogger.info("User has removed group " + groupId + " and type " + type + " from having access to insight "
				+ insightId + " under project " + projectId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

}
