package prerna.semoss.web.services.local.auth;

import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityGroupUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("/auth/admin/group")
public class AdminGroupAuthorizationResource extends AbstractAdminResource {
	
	private static final Logger classLogger = LogManager.getLogger(AdminGroupAuthorizationResource.class);
	
	@POST
	@Produces("application/json")
	@Path("/addGroup")
	public Response addGroup(@Context HttpServletRequest request) {
		Map<String, String> errorRet = new Hashtable<>();
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to add a group but couldn't find user session"));
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(!SecurityAdminUtils.userIsAdmin(user)){
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to add a group but is not an admin"));
			errorRet.put(Constants.ERROR_MESSAGE, "The user doesn't have the permissions to perform this action.");
			return WebUtility.getResponse(errorRet, 400);
		}
		
		boolean success = false;
		try {
			String newGroupId = request.getParameter("groupId");
			if(newGroupId == null || (newGroupId = newGroupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id cannot be null or empty");
			}
			String type = request.getParameter("type");
			if(type == null) {
				type = "";
			}
			String description = request.getParameter("description");
			if(description == null) {
				description = "";
			}
			description = description.trim();
			boolean isCustomGroup = Boolean.parseBoolean(request.getParameter("isCustomGroup")+"");
			
			// you can have a type or be a custom group
			// cannot be both
			if(!isCustomGroup && (type == null || (type = type.trim()).isEmpty()) ) {
				throw new IllegalArgumentException("The group type cannot be null or empty if this is not a custom group");
			} else if(isCustomGroup && (type != null && !(type = type.trim()).isEmpty()) ) {
				throw new IllegalArgumentException("A custom group cannot have a login type passed in");
			}
			
			SecurityGroupUtils.getInstance(user).addGroup(newGroupId, type, description, isCustomGroup);
		} catch (IllegalArgumentException e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}
		return WebUtility.getResponse(success, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("/deleteGroup")
	public Response deleteGroup(@Context HttpServletRequest request) {
		Map<String, String> errorRet = new Hashtable<>();
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to remove a group but couldn't find user session"));
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(!SecurityAdminUtils.userIsAdmin(user)){
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to remove a group but is not an admin"));
			errorRet.put(Constants.ERROR_MESSAGE, "The user doesn't have the permissions to perform this action.");
			return WebUtility.getResponse(errorRet, 400);
		}
		
		boolean success = false;
		try {
			String groupId = request.getParameter("groupId");
			if(groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id cannot be null or empty");
			}
			String type = request.getParameter("type");
			if(type == null) {
				type = "";
			}
			
			SecurityGroupUtils.getInstance(user).deleteGroupAndPropagate(groupId, type);
		} catch (IllegalArgumentException e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}
		return WebUtility.getResponse(success, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("/editGroup")
	public Response editGroup(@Context HttpServletRequest request) {
		Map<String, String> errorRet = new Hashtable<>();
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit a group but couldn't find user session"));
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(!SecurityAdminUtils.userIsAdmin(user)){
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit a group but is not an admin"));
			errorRet.put(Constants.ERROR_MESSAGE, "The user doesn't have the permissions to perform this action.");
			return WebUtility.getResponse(errorRet, 400);
		}
		
		boolean success = false;
		try {
			String groupId = request.getParameter("groupId");
			if(groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id cannot be null or empty");
			}
			String type = request.getParameter("type");
			if(type == null) {
				type = "";
			}
			boolean isCustomGroup = Boolean.parseBoolean(request.getParameter("isCustomGroup")+"");

			String newGroupId = request.getParameter("newGroupId");
			if(newGroupId == null || (newGroupId = newGroupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The new group id cannot be null or empty");
			}
			String newType = request.getParameter("newType");
			if(newType == null) {
				newType = "";
			}
			boolean newCustomGroup = Boolean.parseBoolean(request.getParameter("newIsCustomGroup")+"");
			String newDescription = request.getParameter("newDescription");
			
			// you can have a type or be a custom group
			// cannot be both
			if(!newCustomGroup && (newType == null || (newType = newType.trim()).isEmpty()) ) {
				throw new IllegalArgumentException("The group type cannot be null or empty if this is not a custom group");
			} else if(newCustomGroup && (newType != null && !(newType = newType.trim()).isEmpty()) ) {
				throw new IllegalArgumentException("A custom group cannot have a login type passed in");
			}
			
			SecurityGroupUtils.getInstance(user).editGroupAndPropagate(groupId, type, newGroupId, newType, newDescription, newCustomGroup);
		} catch (IllegalArgumentException e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}
		return WebUtility.getResponse(success, 200);
	}
	
	@GET
	@Path("/getAllGroups")
	@Produces("application/json")
	public Response getAllGroups(@Context HttpServletRequest request) {
		SecurityGroupUtils groupUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			groupUtils = SecurityGroupUtils.getInstance(user);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, Object>> ret = groupUtils.getAllGroups();
		return WebUtility.getResponse(ret, 200);
	}
}
