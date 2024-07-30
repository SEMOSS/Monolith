package prerna.semoss.web.services.local.auth;

import java.util.HashMap;
import java.util.List;
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

import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.om.Insight;
import prerna.reactor.security.MyDatabasesReactor;
import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/auth/app")
@PermitAll
@Deprecated
public class DatabaseAuthorizationResource {

	private static final Logger classLogger = LogManager.getLogger(DatabaseAuthorizationResource.class);

	@Context
	protected ServletContext context;
	
	/**
	 * Get the apps the user has access to
	 * @param request
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getApps")
	public Response getApps(@Context HttpServletRequest request, 
			@QueryParam("databaseId") List<String> databaseFilter,
			@QueryParam("filterWord") String searchTerm, 
			@QueryParam("limit") Integer limit,
			@QueryParam("offset") Integer offset,
			@QueryParam("onlyFavorites") Boolean favoritesOnly,
			@QueryParam("metaKeys") List<String> metaKeys,
//			@QueryParam("metaFilters") Map<String, Object> metaFilters,
			@QueryParam("noMeta") Boolean noMeta
			) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getEngines WITH PARAM engineTypes");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getEngines WITH PARAM engineTypes");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getEngines WITH PARAM engineTypes");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getEngines WITH PARAM engineTypes");

		searchTerm=WebUtility.inputSanitizer(searchTerm);

	    
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
		
		MyDatabasesReactor reactor = new MyDatabasesReactor();
		reactor.In();
		Insight temp = new Insight();
		temp.setUser(user);
		reactor.setInsight(temp);
		searchTerm = WebUtility.inputSanitizer(searchTerm);
		if(searchTerm != null) {
			GenRowStruct struct = new GenRowStruct();
			struct.add(new NounMetadata(searchTerm, PixelDataType.CONST_STRING));
			reactor.getNounStore().addNoun(ReactorKeysEnum.FILTER_WORD.getKey(), struct);
		}
		if(limit != null) {
			GenRowStruct struct = new GenRowStruct();
			struct.add(new NounMetadata(limit, PixelDataType.CONST_INT));
			reactor.getNounStore().addNoun(ReactorKeysEnum.LIMIT.getKey(), struct);
		}
		if(offset != null) {
			GenRowStruct struct = new GenRowStruct();
			struct.add(new NounMetadata(offset, PixelDataType.CONST_INT));
			reactor.getNounStore().addNoun(ReactorKeysEnum.OFFSET.getKey(), struct);
		}
		if(favoritesOnly != null) {
			GenRowStruct struct = new GenRowStruct();
			struct.add(new NounMetadata(favoritesOnly, PixelDataType.BOOLEAN));
			reactor.getNounStore().addNoun(ReactorKeysEnum.ONLY_FAVORITES.getKey(), struct);
		}
		if(databaseFilter != null && !databaseFilter.isEmpty()) {
			GenRowStruct struct = new GenRowStruct();
			for(String db : databaseFilter) {
				struct.add(new NounMetadata(db, PixelDataType.CONST_STRING));
			}
			reactor.getNounStore().addNoun(ReactorKeysEnum.DATABASE.getKey(), struct);
		}
		if(metaKeys != null && !metaKeys.isEmpty()) {
			GenRowStruct struct = new GenRowStruct();
			for(String metaK : metaKeys) {
				struct.add(new NounMetadata(metaK, PixelDataType.CONST_STRING));
			}
			reactor.getNounStore().addNoun(ReactorKeysEnum.META_KEYS.getKey(), struct);
		}
//		if(metaFilters != null) {
//			GenRowStruct struct = new GenRowStruct();
//			struct.add(new NounMetadata(metaFilters, PixelDataType.MAP));
//			reactor.getNounStore().addNoun(ReactorKeysEnum.META_FILTERS.getKey(), struct);
//		}
		if(noMeta != null) {
			GenRowStruct struct = new GenRowStruct();
			struct.add(new NounMetadata(noMeta, PixelDataType.BOOLEAN));
			reactor.getNounStore().addNoun(ReactorKeysEnum.NO_META.getKey(), struct);
		}
		
		NounMetadata outputNoun = reactor.execute();
		return WebUtility.getResponse(outputNoun.getValue(), 200);
	}
	
	/**
	 * Get the user app permission level
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getUserAppPermission")
	public Response getUserAppPermission(@Context HttpServletRequest request, @QueryParam("appId") String appId) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getUserEnginePermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getUserEnginePermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getUserEnginePermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getUserEnginePermission with PARAM engineId");

		
		 appId=WebUtility.inputSanitizer( appId);
		
		
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
		
		String permission = SecurityEngineUtils.getActualUserEnginePermission(user, appId);
		if(permission == null) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to pull permission details for database " + appId + " without having proper access"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User does not have access to this database");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		Map<String, String> ret = new HashMap<String, String>();
		ret.put("permission", permission);
		return WebUtility.getResponse(ret, 200);
	}
	/**
	 * Get the database users and their permissions
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getAppUsers")
	public Response getAppUsers(@Context HttpServletRequest request, @QueryParam("appId") String appId) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getEngineUsers with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getEngineUsers with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getEngineUsers with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getEngineUsers with PARAM engineId");
		
		appId=WebUtility.inputSanitizer(appId);

	    
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
		
		List<Map<String, Object>> ret = null;
		try {
			ret = SecurityEngineUtils.getEngineUsers(user, appId, null, null, -1, -1);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to pull users for database " + appId + " without having proper access"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Add a user to an database
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addAppUserPermission")
	public Response addAppUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/addEngineUserPermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/addEngineUserPermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/addEngineUserPermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/addEngineUserPermission with PARAM engineId");
		
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
		
		String newUserId = WebUtility.inputSanitizer( form.getFirst("id"));
		String appId = WebUtility.inputSanitizer( form.getFirst("appId"));
		String permission =WebUtility.inputSanitizer(  form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		if (AbstractSecurityUtils.adminOnlyEngineAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to add users for database " + appId + " but is not an admin"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			SecurityEngineUtils.addEngineUser(user, newUserId, appId, permission, endDate);
		} catch (Exception e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to add users for database " + appId + " without having proper access"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has added user " + newUserId + " to database " + appId + " with permission " + permission));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Edit user permission for an database
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editAppUserPermission")
	public Response editAppUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/editEngineUserPermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/editEngineUserPermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/editEngineUserPermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/editEngineUserPermission with PARAM engineId");
		
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
		
		String existingUserId = WebUtility.inputSanitizer( form.getFirst("id"));
		String appId = WebUtility.inputSanitizer( form.getFirst("appId"));
		String newPermission = WebUtility.inputSanitizer( form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		if (AbstractSecurityUtils.adminOnlyEngineAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit user " + existingUserId + " permissions for database " + appId + " but is not an admin"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			SecurityEngineUtils.editEngineUserPermission(user, existingUserId, appId, newPermission, endDate);
		} catch(IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit user " + existingUserId + " permissions for database " + appId + " without having proper access"));
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
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has edited user " + existingUserId + " permission to database " + appId + " with level " + newPermission));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Remove user permission for an database
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeAppUserPermission")
	public Response removeAppUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/removeEngineUserPermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/removeEngineUserPermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/removeEngineUserPermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/removeEngineUserPermission with PARAM engineId");
		
		System.out.println("database auth resource... ");
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "invalid user session trying to access authorization resources"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String existingUserId = WebUtility.inputSanitizer( form.getFirst("id"));
		String appId = WebUtility.inputSanitizer( form.getFirst("appId"));

		if (AbstractSecurityUtils.adminOnlyEngineAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to remove user " + existingUserId + " from having access to database " + appId + " but is not an admin"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			SecurityEngineUtils.removeEngineUser(user, existingUserId, appId);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to remove user " + existingUserId + " from having access to database " + appId + " without having proper access"));
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
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has removed user " + existingUserId + " from having access to database " + appId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Set the database as being global (read only) for the entire semoss instance
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setAppGlobal")
	public Response setAppGlobal(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/setEngineGlobal with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/setEngineGlobal with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/setEngineGlobal with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/setEngineGlobal with PARAM engineId");
		
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
		
		String appId = WebUtility.inputSanitizer( form.getFirst("appId"));
		boolean isPublic = Boolean.parseBoolean(form.getFirst("public"));
		String logPublic = isPublic ? " public " : " private";

		boolean legacyAdminOnly = Boolean.parseBoolean(context.getInitParameter(Constants.ADMIN_SET_PUBLIC));
		if ( (legacyAdminOnly || AbstractSecurityUtils.adminOnlyEngineSetPublic()) && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to set the database " + appId + logPublic + " but is not an admin"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			SecurityEngineUtils.setEngineGlobal(user, appId, isPublic);
		} catch(IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to set the database " + appId + logPublic + " without having proper access"));
    		classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
    		classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}
		
		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has set the database " + appId + logPublic));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Set the database as being discoverable for the entire semoss instance
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setAppDiscoverable")
	public Response setAppDiscoverable(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/setEngineDiscoverable with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/setEngineDiscoverable with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/setEngineDiscoverable with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/setEngineDiscoverable with PARAM engineId");
		
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
		
		String appId = WebUtility.inputSanitizer( form.getFirst("appId"));
		boolean isDiscoverable = Boolean.parseBoolean(form.getFirst("discoverable"));
		String logDiscoverable = isDiscoverable ? " discoverable " : " not discoverable";

		if (AbstractSecurityUtils.adminOnlyEngineSetPublic() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to set the database " + appId + logDiscoverable + " but is not an admin"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			SecurityEngineUtils.setEngineDiscoverable(user, appId, isDiscoverable);
		} catch(IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to set the database " + appId + logDiscoverable + " without having proper access"));
    		classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
    		classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}
		
		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has set the database " + appId + logDiscoverable));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Set the database visibility for the user to be seen
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setAppVisibility")
	public Response setAppVisibility(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/setEngineVisibility with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/setEngineVisibility with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/setEngineVisibility with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/setEngineVisibility with PARAM engineId");
		
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
		
		String appId = WebUtility.inputSanitizer( form.getFirst("appId"));
		boolean visible = Boolean.parseBoolean(form.getFirst("visibility"));
		String logVisible = visible ? " visible " : " not visible";

		try {
			SecurityEngineUtils.setEngineVisibility(user, appId, visible);
		} catch(IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to set the database " + appId + logVisible + " without having proper access"));
    		classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
    		classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}
		
		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has set the database " + appId + logVisible));
		
		return WebUtility.getResponse(true, 200);
	}
	
	/**
	 * Set the database as favorited by the user
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setAppFavorite")
	public Response setAppFavorite(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/setEngineFavorite with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/setEngineFavorite with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/setEngineFavorite with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/setEngineFavorite with PARAM engineId");
		
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
		
		String appId = WebUtility.inputSanitizer(form.getFirst("appId"));
		boolean isFavorite = Boolean.parseBoolean(form.getFirst("isFavorite"));
		String logFavorited = isFavorite ? " favorited " : " not favorited";

		try {
			SecurityEngineUtils.setEngineFavorite(user, appId, isFavorite);
		} catch(IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to set the database " + appId + logFavorited + " without having proper access"));
    		classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
    		classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}
		
		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has set the database " + appId + logFavorited));
		
		return WebUtility.getResponse(true, 200);
	}
	
	/**
	 * Get users with no access to a given database
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getAppUsersNoCredentials")
	public Response getAppUsersNoCredentials(@Context HttpServletRequest request, @QueryParam("appId") String appId) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getEngineUsersNoCredentials with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getEngineUsersNoCredentials with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getEngineUsersNoCredentials with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getEngineUsersNoCredentials with PARAM engineId");
		
		appId=WebUtility.inputSanitizer(appId);

	    
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), " invalid user session trying to access authorization resources"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		List<Map<String, Object>> ret = null;
		try {
			ret = SecurityEngineUtils.getEngineUsersNoCredentials(user, appId);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), " is trying to pull users for " + appId + " that do not have credentials without having proper access"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		return WebUtility.getResponse(ret, 200);
	}
		
}
