package prerna.semoss.web.services.local.auth;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.web.services.util.WebUtility;

@Path("/auth/admin/insight")
public class AdminInsightAuthorizationResource {

	private SecurityAdminUtils performAdminCheck(@Context HttpServletRequest request) throws IllegalAccessException {
		User user = ResourceUtility.getUser(request);
		SecurityAdminUtils adminUtils = SecurityAdminUtils.getInstance(user);
		if(adminUtils == null) {
			throw new IllegalArgumentException("User is not an admin");
		}
		return adminUtils;
	}
	
	/**
	 * Get the user insight permissions for a given insight
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getAppInsights")
	public Response getAppInsights(@Context HttpServletRequest request, @QueryParam("appId") String appId) {
		SecurityAdminUtils adminUtils = null;
		try {
			adminUtils = performAdminCheck(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		List<Map<String, Object>> ret = adminUtils.getAppInsights(appId);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Get the user insight permissions for a given insight
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getInsightUsers")
	public Response getInsightUsers(@Context HttpServletRequest request, @QueryParam("appId") String appId, @QueryParam("insightId") String insightId) {
		SecurityAdminUtils adminUtils = null;
		try {
			adminUtils = performAdminCheck(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		List<Map<String, Object>> ret = null;
		try {
			ret = adminUtils.getInsightUsers(appId, insightId);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		return WebUtility.getResponse(ret, 200);
	}
	
	
	/**
	 * Add a user to an insight
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addInsightUserPermission")
	public Response addInsightUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		try {
			adminUtils = performAdminCheck(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String newUserId = form.getFirst("id");
		String appId = form.getFirst("appId");
		String insightId = form.getFirst("insightId");
		String permission = form.getFirst("permission");

		try {
			adminUtils.addInsightUser(newUserId, appId, insightId, permission);
		} catch (Exception e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Edit user permission for an insight
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editInsightUserPermission")
	public Response editInsightUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		try {
			adminUtils = performAdminCheck(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String existingUserId = form.getFirst("id");
		String appId = form.getFirst("appId");
		String insightId = form.getFirst("insightId");
		String newPermission = form.getFirst("permission");

		try {
			adminUtils.editInsightUserPermission(existingUserId, appId, insightId, newPermission);
		} catch (Exception e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Remove user permission for an insight
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeInsightUserPermission")
	public Response removeInsightUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		try {
			adminUtils = performAdminCheck(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String existingUserId = form.getFirst("id");
		String appId = form.getFirst("appId");
		String insightId = form.getFirst("insightId");

		try {
			adminUtils.removeInsightUser(existingUserId, appId, insightId);
		} catch (Exception e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Remove user permission for an insight
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setInsightGlobal")
	public Response setInsightGlobal(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		try {
			adminUtils = performAdminCheck(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String appId = form.getFirst("appId");
		String insightId = form.getFirst("insightId");
		boolean isPublic = Boolean.parseBoolean(form.getFirst("isPublic"));
		
		try {
			adminUtils.setInsightGlobalWithinApp(appId, insightId, isPublic);
		} catch (Exception e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
}
