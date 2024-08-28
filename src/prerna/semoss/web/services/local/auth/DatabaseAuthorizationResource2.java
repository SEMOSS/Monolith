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

import com.google.gson.Gson;

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
import prerna.web.services.util.WebUtility;

@Path("/auth/database")
@PermitAll
@Deprecated
public class DatabaseAuthorizationResource2 {

	private static final Logger classLogger = LogManager.getLogger(DatabaseAuthorizationResource2.class);

	@Context
	protected ServletContext context;
	
	/**
	 * Get the databases the user has access to
	 * @param request
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getDatabases")
	public Response getDatabases(@Context HttpServletRequest request, 
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
		databaseFilter=WebUtility.inputSanitizer(databaseFilter);
		searchTerm=WebUtility.inputSanitizer(searchTerm);
		metaKeys=WebUtility.inputSanitizer(metaKeys);

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
	 * Get the user database permission level
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getUserDatabasePermission")
	public Response getUserDatabasePermission(@Context HttpServletRequest request, @QueryParam("databaseId") String databaseId) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getUserEnginePermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getUserEnginePermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getUserEnginePermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getUserEnginePermission with PARAM engineId");

		databaseId=WebUtility.inputSanitizer(databaseId);

	    
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
		
		String permission = SecurityEngineUtils.getActualUserEnginePermission(user, databaseId);
		if(permission == null) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to pull permission details for database " + databaseId + " without having proper access"));
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
	@Path("getDatabaseUsers")
	public Response getDatabaseUsers(@Context HttpServletRequest request, @QueryParam("databaseId") String databaseId,
			@QueryParam("userId") String userId, @QueryParam("userInfo") String userInfo,
			@QueryParam("permission") String permission, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getEngineUsers with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getEngineUsers with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getEngineUsers with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getEngineUsers with PARAM engineId");
		
		
		databaseId=WebUtility.inputSanitizer(databaseId);
	    userId=WebUtility.inputSanitizer(userId);
	    userInfo=WebUtility.inputSanitizer(userInfo);
	    permission=WebUtility.inputSanitizer(permission);
	    
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
		
		Map<String, Object> ret = new HashMap<String, Object>();
		try {
			String searchParam = userInfo != null ? userInfo : userId;
			List<Map<String, Object>> members = SecurityEngineUtils.getEngineUsers(user, databaseId, searchParam, permission, limit, offset);
			long totalMembers = SecurityEngineUtils.getEngineUsersCount(user, databaseId, searchParam, permission);
			ret.put("totalMembers", totalMembers);
			ret.put("members", members);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to pull users for database " + databaseId + " without having proper access"));
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
	@Path("addDatabaseUserPermission")
	public Response addDatabaseUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
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
		
		String newUserId = WebUtility.inputSanitizer(form.getFirst("id"));
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		if (AbstractSecurityUtils.adminOnlyEngineAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to add users for database " + databaseId + " but is not an admin"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			SecurityEngineUtils.addEngineUser(user, newUserId, databaseId, permission, endDate);
		} catch (Exception e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to add users for database " + databaseId + " without having proper access"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has added user " + newUserId + " to database " + databaseId + " with permission " + permission));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Add user permissions in bulk to a database
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addDatabaseUserPermissions")
	public Response addDatabaseUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/addEngineUserPermissions with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/addEngineUserPermissions with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/addEngineUserPermissions with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/addEngineUserPermissions with PARAM engineId");
		
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "invalid user session trying to access authorization resources"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));
		String endDate = null; // form.getFirst("endDate");

		if (AbstractSecurityUtils.adminOnlyEngineAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to add user permissions to database " + databaseId + " but is not an admin"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		// adding user permissions in bulk
		List<Map<String, String>> permission = new Gson().fromJson(form.getFirst("userpermissions"), List.class);
		try {
			SecurityEngineUtils.addEngineUserPermissions(user, databaseId, permission, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has added user permissions to database " + databaseId));
		
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
	@Path("editDatabaseUserPermission")
	public Response editDatabaseUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
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
		
		String existingUserId = WebUtility.inputSanitizer(form.getFirst("id"));
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));
		String newPermission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		if (AbstractSecurityUtils.adminOnlyEngineAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit user " + existingUserId + " permissions for database " + databaseId + " but is not an admin"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			SecurityEngineUtils.editEngineUserPermission(user, existingUserId, databaseId, newPermission, endDate);
		} catch(IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit user " + existingUserId + " permissions for database " + databaseId + " without having proper access"));
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
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has edited user " + existingUserId + " permission to database " + databaseId + " with level " + newPermission));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Edit user permission for an database, in bulk
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editDatabaseUserPermissions")
	public Response editDatabaseUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/editEngineUserPermissions with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/editEngineUserPermissions with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/editEngineUserPermissions with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/editEngineUserPermissions with PARAM engineId");
		
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

		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));
		String endDate = null; // form.getFirst("endDate");

		if (AbstractSecurityUtils.adminOnlyEngineAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit user permissions for database " + databaseId + " but is not an admin"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, String>> requests = new Gson().fromJson(form.getFirst("userpermissions"), List.class);
		try {
			SecurityEngineUtils.editEngineUserPermissions(user, databaseId, requests, endDate);
		} catch(IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit user permissions for database " + databaseId + " without having proper access"));
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
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has edited user permission to database " + databaseId));
		
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
	@Path("removeDatabaseUserPermission")
	public Response removeDatabaseUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/removeEngineUserPermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/removeEngineUserPermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/removeEngineUserPermission with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/removeEngineUserPermission with PARAM engineId");
		
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "invalid user session trying to access authorization resources"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String existingUserId = WebUtility.inputSanitizer(form.getFirst("id"));
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));

		if (AbstractSecurityUtils.adminOnlyEngineAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to remove user " + existingUserId + " from having access to database " + databaseId + " but is not an admin"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			SecurityEngineUtils.removeEngineUser(user, existingUserId, databaseId);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to remove user " + existingUserId + " from having access to database " + databaseId + " without having proper access"));
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
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has removed user " + existingUserId + " from having access to database " + databaseId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Remove user permissions for an database, in bulk
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeDatabaseUserPermissions")
	public Response removeDatabaseUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/removeEngineUserPermissions with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/removeEngineUserPermissions with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/removeEngineUserPermissions with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/removeEngineUserPermissions with PARAM engineId");
		
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "invalid user session trying to access authorization resources"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		Gson gson = new Gson();
		List<String> ids = gson.fromJson(form.getFirst("ids"), List.class);		ids=WebUtility.inputSanitizer(ids);		String databaseId =WebUtility.inputSanitizer( form.getFirst("databaseId"));

		if (AbstractSecurityUtils.adminOnlyEngineAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to remove users from having access to database " + databaseId + " but is not an admin"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			SecurityEngineUtils.removeEngineUsers(user, ids, databaseId);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to remove users from having access to database " + databaseId + " without having proper access"));
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
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has removed users from having access to database " + databaseId));
		
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
	@Path("setDatabaseGlobal")
	public Response setDatabaseGlobal(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
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
		
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));
		boolean isPublic = Boolean.parseBoolean(form.getFirst("public"));
		String logPublic = isPublic ? " public " : " private";

		boolean legacyAdminOnly = Boolean.parseBoolean(context.getInitParameter(Constants.ADMIN_SET_PUBLIC));
		if ( (legacyAdminOnly || AbstractSecurityUtils.adminOnlyEngineSetPublic()) && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to set the database " + databaseId + logPublic + " but is not an admin"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			SecurityEngineUtils.setEngineGlobal(user, databaseId, isPublic);
		} catch(IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to set the database " + databaseId + logPublic + " without having proper access"));
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
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has set the database " + databaseId + logPublic));
		
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
	@Path("setDatabaseDiscoverable")
	public Response setDatabaseDiscoverable(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
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
		
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));
		boolean isDiscoverable = Boolean.parseBoolean(form.getFirst("discoverable"));
		String logDiscoverable = isDiscoverable ? " discoverable " : " not discoverable";

		if (AbstractSecurityUtils.adminOnlyEngineSetPublic() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to set the database " + databaseId + logDiscoverable + " but is not an admin"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			SecurityEngineUtils.setEngineDiscoverable(user, databaseId, isDiscoverable);
		} catch(IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to set the database " + databaseId + logDiscoverable + " without having proper access"));
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
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has set the database " + databaseId + logDiscoverable));
		
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
	@Path("setDatabaseVisibility")
	public Response setDatabaseVisibility(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
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
		
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));
		boolean visible = Boolean.parseBoolean(form.getFirst("visibility"));
		String logVisible = visible ? " visible " : " not visible";

		try {
			SecurityEngineUtils.setEngineVisibility(user, databaseId, visible);
		} catch(IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to set the database " + databaseId + logVisible + " without having proper access"));
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
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has set the database " + databaseId + logVisible));
		
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
	@Path("setDatabaseFavorite")
	public Response setDatabaseFavorite(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
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
		
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));
		boolean isFavorite = Boolean.parseBoolean(form.getFirst("isFavorite"));
		String logFavorited = isFavorite ? " favorited " : " not favorited";

		try {
			SecurityEngineUtils.setEngineFavorite(user, databaseId, isFavorite);
		} catch(IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to set the database " + databaseId + logFavorited + " without having proper access"));
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
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has set the database " + databaseId + logFavorited));
		
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
	@Path("getDatabaseUsersNoCredentials")
	public Response getDatabaseUsersNoCredentials(@Context HttpServletRequest request, 
			@QueryParam("databaseId") String databaseId,
			@QueryParam("searchTerm") String searchTerm,
			@QueryParam("limit") long limit, 
			@QueryParam("offset") long offset) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getEngineUsersNoCredentials with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getEngineUsersNoCredentials with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getEngineUsersNoCredentials with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/getEngineUsersNoCredentials with PARAM engineId");
		
		databaseId = WebUtility.inputSanitizer(databaseId);
	    searchTerm = WebUtility.inputSanitizer(searchTerm);

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
			ret = SecurityEngineUtils.getEngineUsersNoCredentials(user, databaseId, searchTerm, limit, offset);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), " is trying to pull users for " + databaseId + " that do not have credentials without having proper access"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * approval of user access requests
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("approveDatabaseUserAccessRequest")
	public Response approveDatabaseUserAccessRequest(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/approveEngineUserAccessRequest with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/approveEngineUserAccessRequest with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/approveEngineUserAccessRequest with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/approveEngineUserAccessRequest with PARAM engineId");
		
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "invalid user session trying to access authorization resources"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));
		String endDate = null; // form.getFirst("endDate");

		if (AbstractSecurityUtils.adminOnlyEngineAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to approve user access to database " + databaseId + " but is not an admin"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		// adding user permissions and updating user access requests in bulk
		List<Map<String, String>> requests = new Gson().fromJson(form.getFirst("requests"), List.class);
		try {
			SecurityEngineUtils.approveEngineUserAccessRequests(user, databaseId, requests, endDate);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to grant user access to database " + databaseId + " without having proper access"));
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
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has approved user access and added user permissions to database " + databaseId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * deny of user access requests
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("denyDatabaseUserAccessRequest")
	public Response denyDatabaseUserAccessRequest(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/denyEngineUserAccessRequest with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/denyEngineUserAccessRequest with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/denyEngineUserAccessRequest with PARAM engineId");
		classLogger.warn("CALLING LEGACY ENDPOINT - NEED TO UPDATE TO GENERIC ENGINE ENDPOINT /auth/engine/denyEngineUserAccessRequest with PARAM engineId");
		
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "invalid user session trying to access authorization resources"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String databaseId = WebUtility.inputSanitizer(form.getFirst("databaseId"));

		if (AbstractSecurityUtils.adminOnlyEngineAddAccess() && !SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to deny user access to database " + databaseId + " but is not an admin"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "This functionality is limited to only admins");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		// updating user access requests in bulk
		List<String> requestIds = new Gson().fromJson(form.getFirst("requestIds"), List.class);
		requestIds = WebUtility.inputSanitizer(requestIds);
		try {

			SecurityEngineUtils.denyEngineUserAccessRequests(user, databaseId, requestIds);

		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has denied user access requests to database " + databaseId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
		
}
