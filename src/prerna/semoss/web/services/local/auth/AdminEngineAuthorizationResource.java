package prerna.semoss.web.services.local.auth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.HashMap;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
import com.google.gson.reflect.TypeToken;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.auth.utils.reactors.admin.AdminMyEnginesReactor;
import prerna.graph.utility.MsGraphUtility;
import prerna.om.Insight;
import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;
import prerna.web.services.util.WebUtility;

@Path("/auth/admin/engine")
@PermitAll
@Tag(name = "auth", description = "Endpoints for managing authentication and authorization of users and applications, including user permissions, access control, and administrative actions.")
public class AdminEngineAuthorizationResource extends AbstractAdminResource {

	private static final Logger classLogger = LogManager.getLogger(AdminEngineAuthorizationResource.class);

	@Context
	protected ServletContext context;
	
	/**
	 * Get the apps the user has access to
	 * @param request
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getEngines")
	@Operation(
		summary = "Get engines",
		description = "Returns engines with optional filters.",
		parameters = {
			@Parameter(name = "engineId", in = ParameterIn.QUERY, description = "Filter by engineId", required = false),
			@Parameter(name = "engineTypes", in = ParameterIn.QUERY, description = "Filter by engine types", required = false),
			@Parameter(name = "filterWord", in = ParameterIn.QUERY, description = "Search term", required = false),
			@Parameter(name = "limit", in = ParameterIn.QUERY, description = "Limit", required = false),
			@Parameter(name = "offset", in = ParameterIn.QUERY, description = "Offset", required = false),
			@Parameter(name = "metaKeys", in = ParameterIn.QUERY, description = "Meta keys to include", required = false),
			@Parameter(name = "noMeta", in = ParameterIn.QUERY, description = "Exclude metadata", required = false),
			@Parameter(name = "userT", in = ParameterIn.QUERY, description = "Include user tracking", required = false)
		},
		responses = {
			@ApiResponse(responseCode = "200", description = "Engines retrieved",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = java.lang.Object.class))),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response getEnginesGET(@Context HttpServletRequest request, 
			@QueryParam("engineId") List<String> engineFilter,
			@QueryParam("engineTypes") List<String> engineTypes,
			@QueryParam("filterWord") String searchTerm, 
			@QueryParam("limit") Integer limit,
			@QueryParam("offset") Integer offset,
			@QueryParam("metaKeys") List<String> metaKeys,
//			@QueryParam("metaFilters") Map<String, Object> metaFilters,
			@QueryParam("noMeta") Boolean noMeta,
			@QueryParam("userT") Boolean includeUserTracking
			) {
		engineFilter = WebUtility.inputSanitizer(engineFilter);
		engineTypes = WebUtility.inputSanitizer(engineTypes);
		metaKeys = WebUtility.inputSanitizer(metaKeys);
		searchTerm = WebUtility.inputSanitizer(searchTerm);
	    
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to get all engines when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		AdminMyEnginesReactor reactor = new AdminMyEnginesReactor();
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
		if(engineFilter != null && !engineFilter.isEmpty()) {
			GenRowStruct struct = new GenRowStruct();
			for(String engine : engineFilter) {
				struct.add(new NounMetadata(WebUtility.inputSanitizer(engine), PixelDataType.CONST_STRING));
			}
			reactor.getNounStore().addNoun(ReactorKeysEnum.ENGINE.getKey(), struct);
		}
		if(engineTypes != null && !engineTypes.isEmpty()) {
			GenRowStruct struct = new GenRowStruct();
			for(String eType : engineTypes) {
				struct.add(new NounMetadata(WebUtility.inputSanitizer(eType), PixelDataType.CONST_STRING));
			}
			reactor.getNounStore().addNoun(ReactorKeysEnum.ENGINE_TYPE.getKey(), struct);
		}
		if(metaKeys != null && !metaKeys.isEmpty()) {
			GenRowStruct struct = new GenRowStruct();
			for(String metaK : metaKeys) {
				struct.add(new NounMetadata(WebUtility.inputSanitizer(metaK), PixelDataType.CONST_STRING));
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
		if(includeUserTracking != null) {
			GenRowStruct struct = new GenRowStruct();
			struct.add(new NounMetadata(includeUserTracking, PixelDataType.BOOLEAN));
			reactor.getNounStore().addNoun(ReactorKeysEnum.INCLUDE_USERTRACKING_KEY.getKey(), struct);
		}
		
		NounMetadata outputNoun = reactor.execute();
		return WebUtility.getResponse(outputNoun.getValue(), 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("getEngines")
	@Operation(
		summary = "Get engines (POST)",
		description = "Returns engines using form parameters in request body.",
		responses = {
			@ApiResponse(responseCode = "200", description = "Engines retrieved",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = java.lang.Object.class))),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response getEnginesPOST(@Context HttpServletRequest request) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to get all engines when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		AdminMyEnginesReactor reactor = new AdminMyEnginesReactor();
		reactor.In();
		Insight temp = new Insight();
		temp.setUser(user);
		reactor.setInsight(temp);
		
		Map<String, String[]> parameterMap = request.getParameterMap();
		
		if(parameterMap.containsKey("filterWord") && parameterMap.get("filterWord") != null && parameterMap.get("filterWord").length > 0) {
			GenRowStruct struct = new GenRowStruct();
			struct.add(new NounMetadata(parameterMap.get("filterWord")[0], PixelDataType.CONST_STRING));
			reactor.getNounStore().addNoun(ReactorKeysEnum.FILTER_WORD.getKey(), struct);
		}
		if(parameterMap.containsKey("limit") && parameterMap.get("limit") != null && parameterMap.get("limit").length > 0) {
			GenRowStruct struct = new GenRowStruct();
			struct.add(new NounMetadata(parameterMap.get("limit")[0], PixelDataType.CONST_INT));
			reactor.getNounStore().addNoun(ReactorKeysEnum.LIMIT.getKey(), struct);
		}
		if(parameterMap.containsKey("offset") && parameterMap.get("offset") != null && parameterMap.get("offset").length > 0) {
			GenRowStruct struct = new GenRowStruct();
			struct.add(new NounMetadata(parameterMap.get("offset")[0], PixelDataType.CONST_INT));
			reactor.getNounStore().addNoun(ReactorKeysEnum.OFFSET.getKey(), struct);
		}
		if(parameterMap.containsKey("engineId") && parameterMap.get("engineId") != null && parameterMap.get("engineId").length > 0) {
			GenRowStruct struct = new GenRowStruct();
			String[] engineFilter = parameterMap.get("engineId");
			for(String engine : engineFilter) {
				struct.add(new NounMetadata(engine, PixelDataType.CONST_STRING));
			}
			reactor.getNounStore().addNoun(ReactorKeysEnum.ENGINE.getKey(), struct);
		}
		if(parameterMap.containsKey("engineTypes") && parameterMap.get("engineTypes") != null && parameterMap.get("engineTypes").length > 0) {
			GenRowStruct struct = new GenRowStruct();
			String[] engineTypes = parameterMap.get("engineTypes");
			for(String eType : engineTypes) {
				struct.add(new NounMetadata(eType, PixelDataType.CONST_STRING));
			}
			reactor.getNounStore().addNoun(ReactorKeysEnum.ENGINE_TYPE.getKey(), struct);
		}
		if(parameterMap.containsKey("metaKeys") && parameterMap.get("metaKeys") != null && parameterMap.get("metaKeys").length > 0) {
			GenRowStruct struct = new GenRowStruct();
			String[] metaKeys = parameterMap.get("metaKeys");
			for(String metaK : metaKeys) {
				struct.add(new NounMetadata(metaK, PixelDataType.CONST_STRING));
			}
			reactor.getNounStore().addNoun(ReactorKeysEnum.META_KEYS.getKey(), struct);
		}
		if(parameterMap.containsKey("metaFilters") && parameterMap.get("metaFilters") != null && parameterMap.get("metaFilters").length > 0) {
			Type mapStringObject = new TypeToken<Map<String, Object>>(){}.getType();
			Map<String, Object> metaFilters = new Gson().fromJson(parameterMap.get("metaFilters")[0], mapStringObject);
			GenRowStruct struct = new GenRowStruct();
			struct.add(new NounMetadata(metaFilters, PixelDataType.MAP));
			reactor.getNounStore().addNoun(ReactorKeysEnum.META_FILTERS.getKey(), struct);
		}
		if(parameterMap.containsKey("noMeta") && parameterMap.get("noMeta") != null && parameterMap.get("noMeta").length > 0) {
			GenRowStruct struct = new GenRowStruct();
			struct.add(new NounMetadata(parameterMap.get("noMeta")[0], PixelDataType.BOOLEAN));
			reactor.getNounStore().addNoun(ReactorKeysEnum.NO_META.getKey(), struct);
		}
		if(parameterMap.containsKey("userT") && parameterMap.get("userT") != null && parameterMap.get("userT").length > 0) {
			GenRowStruct struct = new GenRowStruct();
			struct.add(new NounMetadata(parameterMap.get("userT")[0], PixelDataType.BOOLEAN));
			reactor.getNounStore().addNoun(ReactorKeysEnum.INCLUDE_USERTRACKING_KEY.getKey(), struct);
		}
		
		NounMetadata outputNoun = reactor.execute();
		return WebUtility.getResponse(outputNoun.getValue(), 200);
	}
	
	@POST
	@Path("/getAllUserEngines")
	@Produces("application/json")
	@Operation(
		summary = "Get all user engines",
		description = "Lists engines a user has access to, optionally filtered by types.",
		responses = {
			@ApiResponse(responseCode = "200", description = "User engines retrieved",
				content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = java.lang.Object.class)))),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response getAllUserEngines(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String userId = WebUtility.inputSQLSanitizer(form.getFirst("userId"));
		List<String> engineTypes = null;
		if(WebUtility.inputSQLSanitizer(form.getFirst("engineTypes")) != null) {
			Type listOfString = new TypeToken<List<String>>(){}.getType();
			engineTypes = new Gson().fromJson(form.getFirst("engineTypes"), listOfString);
			engineTypes = WebUtility.inputSanitizer(engineTypes);  
		}
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to pull the engines that user " + userId + " has access to when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		return WebUtility.getResponse(adminUtils.getAllUserEngines(userId, engineTypes), 200);
	}
	
	@POST
	@Path("/grantAllEngines")
	@Produces("application/json")
	@Operation(
		summary = "Grant all engines",
		description = "Grants a user permissions to all engines, optionally filtered by types.",
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = java.lang.Object.class))),
			@ApiResponse(responseCode = "400", description = "Bad request",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response grantAllEngines(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String userId = WebUtility.inputSQLSanitizer(form.getFirst("userId"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		boolean isAddNew = Boolean.parseBoolean(form.getFirst("isAddNew") + "");
		List<String> engineTypes = null;
		if(form.getFirst("engineTypes") != null) {
			Type listOfString = new TypeToken<List<String>>(){}.getType();
			engineTypes = new Gson().fromJson(form.getFirst("engineTypes"), listOfString);
			engineTypes = WebUtility.inputSanitizer(engineTypes);
		}

		String logETypes = (engineTypes == null || engineTypes.isEmpty()) ? "[ALL]" : ("[" + String.join(", ", engineTypes) + "]");
		
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to grant all the engines of type " + logETypes + " to user " + userId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.grantAllEngines(userId, permission, isAddNew, engineTypes, user);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has granted all engines of type " + logETypes + " to " + userId + "with permission " + permission));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	
	@POST
	@Path("/grantNewUsersEngineAccess")
	@Produces("application/json")
	@Operation(
		summary = "Grant new users engine access",
		description = "Grants all new users access to an engine.",
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = java.lang.Object.class))),
			@ApiResponse(responseCode = "400", description = "Bad request",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response grantNewUsersEngineAccess(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to grant engine to new users when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.grantNewUsersEngineAccess(engineId, permission, user, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has granted engine " + engineId + "to new users with permission " + permission));

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Get the engine users and their permissions
	 * @param request
	 * @param engineId
	 * @param userId
	 * @param permission
	 * @param limit
	 * @param offset
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getEngineUsers")
	@Operation(
		summary = "Get engine users",
		description = "Gets users and permissions for an engine.",
		parameters = {
			@Parameter(name = "engineId", in = ParameterIn.QUERY, description = "Engine identifier"),
			@Parameter(name = "userId", in = ParameterIn.QUERY, description = "User identifier to filter"),
			@Parameter(name = "searchTerm", in = ParameterIn.QUERY, description = "Search term"),
			@Parameter(name = "permission", in = ParameterIn.QUERY, description = "Permission filter"),
			@Parameter(name = "limit", in = ParameterIn.QUERY, description = "Limit"),
			@Parameter(name = "offset", in = ParameterIn.QUERY, description = "Offset")
		},
		responses = {
			@ApiResponse(responseCode = "200", description = "Users retrieved",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = java.lang.Object.class))),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response getEngineUsers(@Context HttpServletRequest request, 
			@QueryParam("engineId") String engineId, @QueryParam("userId") String userId, 
			@QueryParam("searchTerm") String searchTerm, @QueryParam("permission") String permission, 
			@QueryParam("limit") long limit, @QueryParam("offset") long offset) {
		engineId = WebUtility.inputSanitizer(engineId);
	    userId = WebUtility.inputSQLSanitizer(userId);
	    searchTerm = WebUtility.inputSanitizer(searchTerm);
	    permission = WebUtility.inputSanitizer(permission);
	    
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to pull all the users who use engine " + engineId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		Map<String, Object> ret = new HashMap<String, Object>();
		String searchParam = searchTerm != null ? searchTerm : userId;
		List<Map<String, Object>> members = adminUtils.getEngineUsers(engineId, searchParam, permission, limit, offset);
		long totalMembers = SecurityAdminUtils.getEngineUsersCount(engineId, searchParam, permission);
		ret.put("totalMembers", totalMembers);
		ret.put("members", members);

		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Add a user to a engine
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addEngineUserPermission")
	@Operation(
		summary = "Add engine user permission",
		description = "Adds a user's permission for an engine.",
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = java.lang.Object.class))),
			@ApiResponse(responseCode = "400", description = "Bad request",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response addEngineUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Map<String, Object> ret = new HashMap<String, Object>();

		SecurityAdminUtils adminUtils = null;
		User user = null;
		String newUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to add user " + newUserId + " to engine " + engineId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			ret.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(ret, 401);
		}
		
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");
		
		String usageRestriction = form.containsKey("usageRestriction") ? WebUtility.inputSQLSanitizer(form.getFirst("usageRestriction")) : null;
	    String usageFrequency = form.containsKey("usageFrequency") ? WebUtility.inputSQLSanitizer(form.getFirst("usageFrequency")) : null;
	    int maxTokens = 0;
		String maxTokensStr = WebUtility.inputSanitizer(request.getParameter("maxTokens"));
		if(maxTokensStr != null && !(maxTokensStr=maxTokensStr.trim()).isEmpty()) {
			// must be a valid integer
			try {
				maxTokens = Integer.parseInt(maxTokensStr);
			} catch(NumberFormatException e) {
				classLogger.error(Constants.STACKTRACE, e);
				ret.put(Constants.ERROR_MESSAGE, "maxTokens must be a valid integer value");
				return WebUtility.getResponse(ret, 400);
			}
		}
		double maxResponseTime = 0.0;
		String maxResponseTimeStr = WebUtility.inputSanitizer(request.getParameter("maxResponseTime"));
		if(maxResponseTimeStr != null && !(maxResponseTimeStr=maxResponseTimeStr.trim()).isEmpty()) {
			// must be a valid double
			try {
				maxResponseTime = Double.parseDouble(maxResponseTimeStr);
			} catch(NumberFormatException e) {
				classLogger.error(Constants.STACKTRACE, e);
				ret.put(Constants.ERROR_MESSAGE, "maxResponseTime must be a valid double value");
				return WebUtility.getResponse(ret, 400);
			}
		}
		
		try {
			adminUtils.addEngineUser(newUserId, engineId, permission, user, endDate, usageRestriction, usageFrequency, maxTokens, maxResponseTime);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			ret.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(ret, 400);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has added user " + newUserId + " to engine " + engineId + " with permission " + permission));
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Add user permissions in bulk to a engine
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addEngineUserPermissions")
	@Operation(
		summary = "Add engine user permissions (bulk)",
		description = "Adds user permissions in bulk for an engine.",
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = java.lang.Object.class))),
			@ApiResponse(responseCode = "400", description = "Bad request",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response addEngineUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String engineId = WebUtility.inputSanitizer( form.getFirst("engineId"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to add user permission to engine " + engineId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		boolean graphApi = Boolean.parseBoolean("" + SocialPropertiesUtil.getInstance().getProperty("ms_graphapi_lookup"));

		// adding user permissions in bulk
	Type listOfMapStringObject = new TypeToken<List<Map<String, Object>>>(){}.getType();
	List<Map<String, Object>> permission = new Gson().fromJson(form.getFirst("userpermissions"), listOfMapStringObject);
		try {
			// if we are doing the grpah api
			// then the users might not already exist in the security db
			if(graphApi) {
				// filter out users that already exist
				List<Map<String, Object>> filteredUsers = permission.stream()
						.filter(map -> !SecurityQueryUtils.checkUserExist((String) map.get(Constants.MAP_USERID))).collect(Collectors.toList());
				if (filteredUsers != null && !filteredUsers.isEmpty()) {
					AccessToken token = null;
					  // Add new users to OAuth if they don't exist
					for (Map<String, Object> map : filteredUsers) {
						token = new AccessToken();
						token.setId((String) map.get(Constants.MAP_USERID));
						token.setEmail((String) map.get(Constants.MAP_EMAIL));
						token.setName((String) map.get(Constants.MAP_NAME));
						token.setProvider(AuthProvider.getProviderFromString((String) map.get(AuthProvider.MICROSOFT.name())));
						token.setUsername((String) map.get(Constants.MAP_USERNAME));
						SecurityUpdateUtils.addOAuthUser(token);
					}
				}
			}
			adminUtils.addEngineUserPermissions(engineId, permission, user);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has added user permissions to engine " + engineId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Add all users to a engine
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addAllUsers")
	@Operation(
		summary = "Add all users",
		description = "Adds all users to an engine.",
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = java.lang.Object.class))),
			@ApiResponse(responseCode = "400", description = "Bad request",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response addAllUsers(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to add all users to engine " + engineId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			adminUtils.addAllEngineUsers(engineId, permission, user, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has added all users to engine " + engineId + " with permission " + permission));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Edit user permission for a engine
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editEngineUserPermission")
	@Operation(
		summary = "Edit engine user permission",
		description = "Edits a user's permission for an engine.",
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = java.lang.Object.class))),
			@ApiResponse(responseCode = "400", description = "Bad request",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response editEngineUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Map<String, Object> ret = new HashMap<String, Object>();

		SecurityAdminUtils adminUtils = null;
		User user = null;
		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit user " + existingUserId + " permissions for engine " + engineId + " when not an admin"));
			ret.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(ret, 401);
		}
		
		String newPermission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		String usageRestriction = form.containsKey("usageRestriction") ? WebUtility.inputSQLSanitizer(form.getFirst("usageRestriction")) : null;
	    String usageFrequency = form.containsKey("usageFrequency") ? WebUtility.inputSQLSanitizer(form.getFirst("usageFrequency")) : null;
	    int maxTokens = 0;
		String maxTokensStr = WebUtility.inputSanitizer(request.getParameter("maxTokens"));
		if(maxTokensStr != null && !(maxTokensStr=maxTokensStr.trim()).isEmpty()) {
			// must be a valid integer
			try {
				maxTokens = Integer.parseInt(maxTokensStr);
			} catch(NumberFormatException e) {
				classLogger.error(Constants.STACKTRACE, e);
				ret.put(Constants.ERROR_MESSAGE, "maxTokens must be a valid integer value");
				return WebUtility.getResponse(ret, 400);
			}
		}
		double maxResponseTime = 0.0;
		String maxResponseTimeStr = WebUtility.inputSanitizer(request.getParameter("maxResponseTime"));
		if(maxResponseTimeStr != null && !(maxResponseTimeStr=maxResponseTimeStr.trim()).isEmpty()) {
			// must be a valid double
			try {
				maxResponseTime = Double.parseDouble(maxResponseTimeStr);
			} catch(NumberFormatException e) {
				classLogger.error(Constants.STACKTRACE, e);
				ret.put(Constants.ERROR_MESSAGE, "maxResponseTime must be a valid double value");
				return WebUtility.getResponse(ret, 400);
			}
		}
		
		try {
			adminUtils.editEngineUserPermission(existingUserId, engineId, newPermission, user, endDate, usageRestriction, usageFrequency, maxTokens, maxResponseTime);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			ret.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(ret, 400);
		}
		
		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has edited user " + existingUserId + " permission to engine " + engineId + " with level " + newPermission));
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Edit user permission for a engine
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editEngineUserPermissions")
	@Operation(
		summary = "Edit engine user permissions (bulk)",
		description = "Edits multiple user permissions for an engine.",
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = java.lang.Object.class))),
			@ApiResponse(responseCode = "400", description = "Bad request",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response editEngineUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		try {
			user = ResourceUtility.getUser(request);
			performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit user access permissions for engine " + engineId + " when not an admin"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
	Type listOfMapStringObject = new TypeToken<List<Map<String, Object>>>(){}.getType();
	List<Map<String, Object>> requests = new Gson().fromJson(form.getFirst("userpermissions"), listOfMapStringObject);
		try {
			SecurityAdminUtils.editEngineUserPermissions(engineId, requests, user);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has edited user access permissions to engine " + engineId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * update all user's permission level to new permission level for a engine
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("updateEngineUserPermissions")
	@Operation(
		summary = "Update engine user permissions",
		description = "Updates all users' permissions for an engine.",
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = java.lang.Object.class))),
			@ApiResponse(responseCode = "400", description = "Bad request",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response updateEngineUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		String newPermission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit user permissions for engine " + engineId + " when not an admin"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			adminUtils.updateEngineUserPermissions(engineId, newPermission, user, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has edited user permissions to engine " + engineId + " with level " + newPermission));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Remove user permission for a engine
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeEngineUserPermission")
	@Operation(
		summary = "Remove engine user permission",
		description = "Removes a user's permission for an engine.",
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = java.lang.Object.class))),
			@ApiResponse(responseCode = "400", description = "Bad request",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response removeEngineUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		
		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to remove user " + existingUserId + " from having access to engine " + engineId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			adminUtils.removeEngineUser(existingUserId, engineId);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has removed user " + existingUserId + " from having access to engine " + engineId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Remove user permissions for a engine, in bulk
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeEngineUserPermissions")
	@Operation(
		summary = "Remove engine user permissions (bulk)",
		description = "Removes multiple users' permissions for an engine.",
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = java.lang.Object.class))),
			@ApiResponse(responseCode = "400", description = "Bad request",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response removeEngineUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to remove usersfrom having access to engine " + engineId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
	Gson gson = new Gson();
	Type listOfString = new TypeToken<List<String>>(){}.getType();
	List<String> ids = gson.fromJson(form.getFirst("ids"), listOfString);
		ids = WebUtility.inputSQLSanitizer(ids);
		try {
			adminUtils.removeEngineUsers(ids, engineId);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has removed users from having access to engine " + engineId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("setEngineGlobal")
	@Operation(
		summary = "Set engine global",
		description = "Sets the engine public/private.",
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = java.lang.Object.class))),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "500", description = "Internal server error",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response setEngineGlobal(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		boolean isPublic = Boolean.parseBoolean(form.getFirst("public"));
		String logPublic = isPublic ? " public " : " private";

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to set the engine " + engineId + logPublic + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.setEngineGlobal(engineId, isPublic);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE,e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has set the engine " + engineId + logPublic));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	} 
	
	/**
	 * Set the engine as being discoverable for the entire semoss instance
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setEngineDiscoverable")
	@Operation(
		summary = "Set engine discoverable",
		description = "Sets the engine discoverability.",
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = java.lang.Object.class))),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "500", description = "Internal server error",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response setEngineDiscoverable(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		boolean isDiscoverable = Boolean.parseBoolean(form.getFirst("discoverable"));
		String logDiscoverable = isDiscoverable ? " discoverable " : " not discoverable";

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to set the engine " + engineId + logDiscoverable + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			adminUtils.setEngineDiscoverable(engineId, isDiscoverable);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has set the engine " + engineId + logDiscoverable));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Get users with no access to a given engine
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getEngineUsersNoCredentials")
	@Operation(
		summary = "Get engine users without credentials",
		description = "Lists users without engine credentials.",
		parameters = {
			@Parameter(name = "engineId", in = ParameterIn.QUERY, description = "Engine identifier"),
			@Parameter(name = "searchTerm", in = ParameterIn.QUERY, description = "Search term"),
			@Parameter(name = "limit", in = ParameterIn.QUERY, description = "Limit"),
			@Parameter(name = "offset", in = ParameterIn.QUERY, description = "Offset")
		},
		responses = {
			@ApiResponse(responseCode = "200", description = "Users retrieved",
				content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = java.lang.Object.class)))),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "500", description = "Internal server error",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response getEngineUsersNoCredentials(@Context HttpServletRequest request,
			@QueryParam("engineId") String engineId, 
			@QueryParam("searchTerm") String searchTerm,
			@QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {
		engineId = WebUtility.inputSanitizer(engineId);
		searchTerm = WebUtility.inputSanitizer(searchTerm);
	    
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false),
					User.getSingleLogginName(user), " is trying to get all users when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		boolean graphApi = Boolean.parseBoolean("" + SocialPropertiesUtil.getInstance().getProperty("ms_graphapi_lookup"));
		
		// if not graph api
		// then we will look at our security db
		if(!graphApi) {
			List<Map<String, Object>> ret = adminUtils.getEngineUsersNoCredentials(engineId, searchTerm, limit, offset);
			return WebUtility.getResponse(ret, 200);
		}

		String graphApiGroupId = SocialPropertiesUtil.getInstance().getProperty("ms_graphapi_groupId");

		try {
			List<Map<String, Object>> filteredUsers = MsGraphUtility.getEngineUsers(request, user, engineId, searchTerm, graphApiGroupId , limit, offset, true);
			return WebUtility.getResponse(filteredUsers, 200);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 500); 
		}
	}
	
	
	/**
	 * Admin approval of user access requests
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("approveEngineUserAccessRequest")
	@Operation(
		summary = "Approve engine user access request",
		description = "Approves user access requests and adds permissions.",
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = java.lang.Object.class))),
			@ApiResponse(responseCode = "400", description = "Bad request",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response approveEngineUserAccessRequest(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;

		User user = null;
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to approve user request for permission to engine " + engineId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		// adding user permissions and updating user access requests in bulk
	Type listOfMapStringObject = new TypeToken<List<Map<String, Object>>>(){}.getType();
	List<Map<String, Object>> requests = new Gson().fromJson(form.getFirst("requests"), listOfMapStringObject);
		try {
			AccessToken token = user.getAccessToken(user.getPrimaryLogin());
			String userId = token.getId();
			String userType = token.getProvider().toString();
			adminUtils.approveEngineUserAccessRequests(userId, userType, engineId, requests, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has approved user access requests and added user permissions to engine " + engineId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Admin deny of user access requests
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("denyEngineUserAccessRequest")
	@Operation(
		summary = "Deny engine user access request",
		description = "Denies user access requests.",
		responses = {
			@ApiResponse(responseCode = "200", description = "Operation successful",
				content = @Content(mediaType = "application/json", schema = @Schema(implementation = java.lang.Object.class))),
			@ApiResponse(responseCode = "400", description = "Bad request",
				content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "401", description = "Unauthorized",
				content = @Content(mediaType = "application/json"))
		}
	)
	public Response denyEngineUserAccessRequest(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;

		User user = null;
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to deny user request for permission to engine " + engineId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		// updating user access requests in bulk
	Type listOfString = new TypeToken<List<String>>(){}.getType();
	List<String> requestIds = new Gson().fromJson(form.getFirst("requestIds"), listOfString);
		requestIds = WebUtility.inputSQLSanitizer(requestIds);
		try {
			AccessToken token = user.getAccessToken(user.getPrimaryLogin());
			String userId = token.getId();
			String userType = token.getProvider().toString();
			adminUtils.denyEngineUserAccessRequests(userId, userType, engineId, requestIds);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has denied user access requests to engine " + engineId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
}
