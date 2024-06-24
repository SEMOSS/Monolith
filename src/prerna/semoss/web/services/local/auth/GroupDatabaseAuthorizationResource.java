package prerna.semoss.web.services.local.auth;

import java.util.HashMap;
import java.util.Map;

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
import prerna.auth.utils.SecurityGroupDatabaseUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("/auth/appGroup")
public class GroupDatabaseAuthorizationResource {

	private static final Logger logger = LogManager.getLogger(GroupDatabaseAuthorizationResource.class);

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
	public Response getGroupAppPermission(@Context HttpServletRequest request, @QueryParam("groupId") String groupId, 
			@QueryParam("type") String type, @QueryParam("appId") String appId) {
		Map<String, String> errorMap = new HashMap<String, String>();
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			logger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "invalid user session trying to access authorization resources"));
			logger.error(Constants.STACKTRACE, e);
			errorMap.put(ResourceUtility.ERROR_KEY, "User session is invalid");
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
			
			Integer permissionCode = SecurityGroupDatabaseUtils.getGroupDatabasePermission(groupId, type, appId);
			String permission = permissionCode == null ? null : AccessPermissionEnum.getPermissionValueById(permissionCode);
			
			Map<String, String> ret = new HashMap<String, String>();
			ret.put("permission", permission);
			return WebUtility.getResponse(ret, 200);
		} catch (IllegalArgumentException e){
			logger.error(Constants.STACKTRACE, e);
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e){
			logger.error(Constants.STACKTRACE, e);
			errorMap.put(ResourceUtility.ERROR_KEY, "An unexpected error happened. Please try again.");
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
	@Path("addGroupAppPermission")
	public Response addGroupAppPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			logger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "invalid user session trying to access authorization resources"));
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String groupId = form.getFirst("groupId");
		String type = form.getFirst("type");
		String appId = form.getFirst("appId");
		String permission = form.getFirst("permission");
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
			if(permission == null || (permission = permission.trim()).isEmpty()) {
				throw new IllegalArgumentException("The permission cannot be null or empty");
			}

			SecurityGroupDatabaseUtils.addDatabaseGroupPermission(user, groupId, type, appId, permission);
		} catch (IllegalAccessException e) {
			logger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to add groups to app " + appId + " without having proper access"));
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// log the operation
		logger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has added group " + groupId + " and type " + type + " to app " + appId + " with permission " + permission));
		
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
	public Response editGroupAppPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			logger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "invalid user session trying to access authorization resources"));
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String groupId = form.getFirst("groupId");
		String type = form.getFirst("type");
		String appId = form.getFirst("appId");
		String newPermission = form.getFirst("permission");
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
			SecurityGroupDatabaseUtils.editDatabaseGroupPermission(user, groupId, type, appId, newPermission);
		} catch(IllegalAccessException e) {
			logger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit group " + groupId + " and type " + type + " permissions for app " + appId + " without having proper access"));
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// log the operation
		logger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has edited group " + groupId + " and type " + type + " permission to app " + appId + " with level " + newPermission));
		
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
	public Response removeGroupAppPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			logger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "invalid user session trying to access authorization resources"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String groupId = form.getFirst("groupId");
		String type = form.getFirst("type");
		String appId = form.getFirst("appId");
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
			
			SecurityGroupDatabaseUtils.removeDatabaseGroupPermission(user, groupId, type, appId);
		} catch (IllegalAccessException e) {
			logger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to remove group " + groupId + " and type " + type + " from having access to app " + appId + " without having proper access"));
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// log the operation
		logger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has removed group " + groupId + " and type " + type + " from having access to app " + appId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
}