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
import prerna.auth.utils.SecurityGroupProjectUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("/auth/group/project")
@PermitAll
@Tag(name = "auth", description = "Endpoints for managing authentication and authorization of users and applications, including user permissions, access control, and administrative actions.")
public class GroupProjectAuthorizationResource {

	private static final Logger classLogger = LogManager.getLogger(GroupProjectAuthorizationResource.class);

	@Context
	protected ServletContext context;

	/**
	 * Get the group project permission level
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getGroupProjectPermission")
	@Operation(summary = "Get group permission for a project", description = "Returns the permission string for the specified group, type, and projectId.")
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
	public Response getGroupProjectPermission(@Context HttpServletRequest request,
			@QueryParam("groupId") String groupId, @QueryParam("type") String type,
			@QueryParam("projectId") String projectId) {

		projectId = WebUtility.inputSanitizer(projectId);
		type = WebUtility.inputSanitizer(type);
		groupId = WebUtility.inputSQLSanitizer(groupId);

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

			Integer permissionCode = SecurityGroupProjectUtils.getGroupProjectPermission(groupId, type, projectId);
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
	 * Add a group to a project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addGroupProjectPermission")
	@Operation(summary = "Add group permission to a project")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission added",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or invalid session",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response addGroupProjectPermission(@Context HttpServletRequest request,
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
			if (permission == null || (permission = permission.trim()).isEmpty()) {
				throw new IllegalArgumentException("The permission cannot be null or empty");
			}

			SecurityGroupProjectUtils.addProjectGroupPermission(user, groupId, type, projectId, permission, endDate);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to add groups to project " + projectId + " without having proper access");
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
		classLogger.info("User has added group " + groupId + " and type " + type + " to project " + projectId
				+ " with permission " + permission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Edit group permission for a project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editGroupProjectPermission")
	@Operation(summary = "Edit group permission for a project")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission updated",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or invalid session",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response editGroupProjectPermission(@Context HttpServletRequest request,
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
			if (newPermission == null || (newPermission = newPermission.trim()).isEmpty()) {
				throw new IllegalArgumentException("The permission cannot be null or empty");
			}
			SecurityGroupProjectUtils.editProjectGroupPermission(user, groupId, type, projectId, newPermission,
					endDate);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to edit group " + groupId + " and type " + type
					+ " permissions for project " + projectId + " without having proper access");
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
		classLogger.info("User has edited group " + groupId + " and type " + type + " permission to project "
				+ projectId + " with level " + newPermission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Remove group permission for a project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeGroupProjectPermission")
	@Operation(summary = "Remove group permission for a project")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission removed",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or invalid session",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response removeGroupProjectPermission(@Context HttpServletRequest request,
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

			SecurityGroupProjectUtils.removeProjectGroupPermission(user, groupId, type, projectId);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to remove group " + groupId + " and type " + type
					+ " from having access to project " + projectId + " without having proper access");
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
		classLogger.info("User has removed group " + groupId + " and type " + type + " from having access to project "
				+ projectId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

}
