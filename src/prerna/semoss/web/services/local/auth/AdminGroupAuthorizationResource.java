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
import javax.ws.rs.core.MultivaluedMap;
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
	
	private static final Logger logger = LogManager.getLogger(AdminGroupAuthorizationResource.class);
	
	@POST
	@Produces("application/json")
	@Path("/addGroup")
	public Response addGroup(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Hashtable<String, String> errorRet = new Hashtable<>();
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(ResourceUtility.ERROR_KEY, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(!SecurityAdminUtils.userIsAdmin(user)){
			errorRet.put(ResourceUtility.ERROR_KEY, "The user doesn't have the permissions to perform this action.");
			return WebUtility.getResponse(errorRet, 400);
		}
		
		boolean success = false;
		try {
			String newGroupId = form.getFirst("groupId");
			if(newGroupId == null || (newGroupId = newGroupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id cannot be null or empty");
			}
			String type = form.getFirst("type");
			if(type == null || (type = type.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group type cannot be null or empty");
			}
			String description = form.getFirst("description");
			if(description == null) {
				description = "";
			}
			description = description.trim();
			
			SecurityGroupUtils.getInstance(user).addGroup(newGroupId, type, description);
			
		} catch (IllegalArgumentException e){
    		logger.error(Constants.STACKTRACE, e);
			errorRet.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
    		logger.error(Constants.STACKTRACE, e);
			errorRet.put(ResourceUtility.ERROR_KEY, "An unexpected error happened. Please try again.");
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
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, Object>> ret = groupUtils.getAllGroups();
		return WebUtility.getResponse(ret, 200);
	}
}
