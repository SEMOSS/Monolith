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
import prerna.auth.utils.SecurityGroupEngineUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("/auth/group/engine")
@PermitAll
@Tag(name = "auth", description = "Endpoints for managing authentication and authorization of users and applications, including user permissions, access control, and administrative actions.")
public class GroupEngineAuthorizationResource {

	private static final Logger classLogger = LogManager.getLogger(GroupEngineAuthorizationResource.class);

	@Context
	protected ServletContext context;
	
	/**
	 * Get the group app permission level
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getGroupAppPermission")
	@Operation(summary = "Get group permission for an app/engine", description = "Returns the permission string for the specified group, type, and app/engine id.")
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
	public Response getGroupAppPermission(@Context HttpServletRequest request, @QueryParam("groupId") String groupId, 
			@QueryParam("type") String type, @QueryParam("appId") String appId) {
		
		type=WebUtility.inputSanitizer(type);
		appId=WebUtility.inputSanitizer(appId);
		groupId=WebUtility.inputSQLSanitizer(groupId);

	    
		Map<String, String> errorMap = new HashMap<String, String>();
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "invalid user session trying to access authorization resources"));
			classLogger.error(Constants.STACKTRACE, e);
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			if(groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id cannot be null or empty");
			}
			if(type == null || (type = type.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group type cannot be null or empty");
			}
			if(appId == null || (appId = appId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The appId cannot be null or empty");
			}
			
			Integer permissionCode = SecurityGroupEngineUtils.getGroupDatabasePermission(groupId, type, appId);
			String permission = permissionCode == null ? null : AccessPermissionEnum.getPermissionValueById(permissionCode);
			
			Map<String, String> ret = new HashMap<String, String>();
			ret.put("permission", permission);
			return WebUtility.getResponse(ret, 200);
		} catch (IllegalArgumentException e){
			classLogger.error(Constants.STACKTRACE, e);
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE, e);
			errorMap.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorMap, 500);
		}
	}
	
	/**
	 * Add a group to an app
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addGroupEnginePermission")
	@Operation(summary = "Add group permission to an engine")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission added",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or invalid session",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response addGroupEnginePermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "invalid user session trying to access authorization resources"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = WebUtility.inputSanitizer(form.getFirst("type"));
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));
		
		try {
			if(groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id cannot be null or empty");
			}
			if(type == null || (type = type.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group type cannot be null or empty");
			}
			if(engineId == null || (engineId = engineId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The engineId cannot be null or empty");
			}
			if(permission == null || (permission = permission.trim()).isEmpty()) {
				throw new IllegalArgumentException("The permission cannot be null or empty");
			}

			SecurityGroupEngineUtils.addEngineGroupPermission(user, groupId, type, engineId, permission, endDate);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to add groups to engine " + engineId + " without having proper access"));
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
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has added group " + groupId + " and type " + type + " to engine " + engineId + " with permission " + permission));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Edit group permission for an app
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editGroupAppPermission")
	@Operation(summary = "Edit group permission for an app")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission updated",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or invalid session",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response editGroupAppPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "invalid user session trying to access authorization resources"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = WebUtility.inputSanitizer(form.getFirst("type"));
		String appId = WebUtility.inputSanitizer(form.getFirst("appId"));
		String newPermission =WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));
		try {
			if(groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id cannot be null or empty");
			}
			if(type == null || (type = type.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group type cannot be null or empty");
			}
			if(appId == null || (appId = appId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The appId cannot be null or empty");
			}
			if(newPermission == null || (newPermission = newPermission.trim()).isEmpty()) {
				throw new IllegalArgumentException("The permission cannot be null or empty");
			}
			SecurityGroupEngineUtils.editDatabaseGroupPermission(user, groupId, type, appId, newPermission, endDate);
		} catch(IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit group " + groupId + " and type " + type + " permissions for app " + appId + " without having proper access"));
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
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has edited group " + groupId + " and type " + type + " permission to app " + appId + " with level " + newPermission));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Remove group permission for an app
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeGroupAppPermission")
	@Operation(summary = "Remove group permission for an app")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission removed",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(schema = @Schema(implementation = Object.class))),
		@ApiResponse(responseCode = "401", description = "Unauthorized or invalid session",
			content = @Content(schema = @Schema(implementation = Object.class)))
	})
	public Response removeGroupAppPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "invalid user session trying to access authorization resources"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = WebUtility.inputSanitizer(form.getFirst("type"));
		String appId = WebUtility.inputSanitizer(form.getFirst("appId"));
		try {
			if(groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id cannot be null or empty");
			}
			if(type == null || (type = type.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group type cannot be null or empty");
			}
			if(appId == null || (appId = appId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The appId cannot be null or empty");
			}
			
			SecurityGroupEngineUtils.removeDatabaseGroupPermission(user, groupId, type, appId);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to remove group " + groupId + " and type " + type + " from having access to app " + appId + " without having proper access"));
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
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has removed group " + groupId + " and type " + type + " from having access to app " + appId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
}
