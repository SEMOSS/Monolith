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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;

import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.engine.api.IEngine;
import prerna.engine.impl.InsightAdministrator;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/auth/admin/insight")
public class AdminInsightAuthorizationResource extends AbstractAdminResource {

	private static final Logger logger = LogManager.getLogger(AdminInsightAuthorizationResource.class);

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
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			logger.warn(ResourceUtility.getLogMessage(request, request.getSession(), User.getSingleLogginName(user), "is trying to see all the insight for app " + appId + " when not an admin"));
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		List<Map<String, Object>> ret = adminUtils.getAppInsights(appId);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Get the app users and their permissions
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("getAllAppInsightUsers")
	public Response getAllAppInsightUsers(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String appId = form.getFirst("appId");
		String userId = form.getFirst("userId");
		
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			logger.warn(ResourceUtility.getLogMessage(request, request.getSession(), User.getSingleLogginName(user), "is trying to see all the user insight access for app " + appId + " when not an admin"));
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		return WebUtility.getResponse(adminUtils.getAllUserInsightAccess(appId, userId), 200);
	}
	
	
	/**
	 * Get the user insight permissions for a given insight
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("deleteAppInsights")
	public Response deleteAppInsights(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		
		String appId = form.getFirst("appId");
		Gson gson = new Gson();
		List<String> insightIds = gson.fromJson(form.getFirst("insightId"), List.class);

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			logger.warn(ResourceUtility.getLogMessage(request, request.getSession(), User.getSingleLogginName(user), "is trying to delete insight from app " + appId + " when not an admin"));
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			adminUtils.deleteAppInsights(appId, insightIds);
			// since modifying insight list
			// need to push to cloud storage
			ClusterUtil.reactorPushApp(appId);
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
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
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			logger.warn(ResourceUtility.getLogMessage(request, request.getSession(), User.getSingleLogginName(user), "is trying to get all users who have access to insight " + insightId + " in app " + appId + " when not an admin"));
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		List<Map<String, Object>> ret = null;
		try {
			ret = adminUtils.getInsightUsers(appId, insightId);
		} catch (IllegalAccessException e) {
			logger.error(Constants.STACKTRACE, e);
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
		User user = null;
		
		String newUserId = form.getFirst("id");
		String appId = form.getFirst("appId");
		String insightId = form.getFirst("insightId");
		String permission = form.getFirst("permission");
		
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			logger.warn(ResourceUtility.getLogMessage(request, request.getSession(), User.getSingleLogginName(user), "is trying to add user " + newUserId + " to insight " + insightId + " in app " + appId + " when not an admin"));
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			adminUtils.addInsightUser(newUserId, appId, insightId, permission);
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// log the operation
		logger.info(ResourceUtility.getLogMessage(request, request.getSession(), User.getSingleLogginName(user), "has added user " + newUserId + " to insight " + insightId + " in app " + appId + " with permission " + permission));
		
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
		User user = null;
		
		String existingUserId = form.getFirst("id");
		String appId = form.getFirst("appId");
		String insightId = form.getFirst("insightId");
		String newPermission = form.getFirst("permission");
		
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			logger.warn(ResourceUtility.getLogMessage(request, request.getSession(), User.getSingleLogginName(user), "is trying to edit user " + existingUserId + " permissions for insight " + insightId + " in app " + appId + " when not an admin"));
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			adminUtils.editInsightUserPermission(existingUserId, appId, insightId, newPermission);
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// log the operation
		logger.info(ResourceUtility.getLogMessage(request, request.getSession(), User.getSingleLogginName(user), "has edited user " + existingUserId + " permission to insight " + insightId + " in app " + appId + " with level " + newPermission));
		
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
		User user = null;

		String existingUserId = form.getFirst("id");
		String appId = form.getFirst("appId");
		String insightId = form.getFirst("insightId");
		
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			logger.warn(ResourceUtility.getLogMessage(request, request.getSession(), User.getSingleLogginName(user), "is trying to remove user " + existingUserId + " from having access to insight " + insightId + " in app " + appId + " when not an admin"));
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			adminUtils.removeInsightUser(existingUserId, appId, insightId);
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// log the operation
		logger.info(ResourceUtility.getLogMessage(request, request.getSession(), User.getSingleLogginName(user), "has removed user " + existingUserId + " from having access to insight " + insightId + " in app " + appId));
		
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
		User user = null;

		String appId = form.getFirst("appId");
		String insightId = form.getFirst("insightId");
		boolean isPublic = Boolean.parseBoolean(form.getFirst("isPublic"));
		String logPublic = isPublic ? " public " : " private";
		
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			logger.warn(ResourceUtility.getLogMessage(request, request.getSession(), User.getSingleLogginName(user), "is trying to set the insight " + insightId + " in app " + appId + logPublic + " when not an admin"));
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			adminUtils.setInsightGlobalWithinApp(appId, insightId, isPublic);
			
			// also update in the app itself
			// so it is properly synchronized with the security db
			ClusterUtil.reactorPullInsightsDB(appId);
			IEngine app = Utility.getEngine(appId);
			InsightAdministrator admin = new InsightAdministrator(app.getInsightDatabase());
			admin.updateInsightGlobal(insightId, !isPublic);
			ClusterUtil.reactorPushInsightDB(appId);

		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// log the operation
		logger.info(ResourceUtility.getLogMessage(request, request.getSession(), User.getSingleLogginName(user), "has set the insight " + insightId + " in app " + appId + logPublic));

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
}
