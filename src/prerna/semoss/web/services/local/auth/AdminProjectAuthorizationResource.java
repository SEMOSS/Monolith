package prerna.semoss.web.services.local.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
import java.lang.reflect.Type;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.auth.utils.reactors.admin.AdminMyProjectsReactor;
import prerna.cluster.util.ClusterUtil;
import prerna.graph.utility.MsGraphUtility;
import prerna.om.Insight;
import prerna.project.api.IProject;
import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.Settings;
import prerna.util.SocialPropertiesUtil;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/auth/admin/project")
@PermitAll
@Tag(name = "auth", description = "Endpoints for managing authentication and authorization of users and applications, including user permissions, access control, and administrative actions.")
public class AdminProjectAuthorizationResource extends AbstractAdminResource {

	private static final Logger classLogger = LogManager.getLogger(AdminProjectAuthorizationResource.class);

	@Context
	protected ServletContext context;
	
	/**
	 * Get the projects the user has access to
	 * @param request
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getProjects")
	@Operation(summary = "List projects", description = "Returns projects filtered by optional parameters with pagination and metadata options.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Projects retrieved",
			content = @Content(mediaType = "application/json",
				array = @ArraySchema(schema = @Schema(type = "object"))
			)
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response getProjectsGET(@Context HttpServletRequest request, 
			@QueryParam("projectId") List<String> projectFilter,
			@QueryParam("filterWord") String searchTerm, 
			@QueryParam("limit") Integer limit,
			@QueryParam("offset") Integer offset,
			@QueryParam("metaKeys") List<String> metaKeys,
//			@QueryParam("metaFilters") Map<String, Object> metaFilters,
			@QueryParam("noMeta") Boolean noMeta,
			@QueryParam("userT") Boolean includeUserTracking
			) {
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);
		projectFilter = WebUtility.inputSQLSanitizer(projectFilter);
		metaKeys = WebUtility.inputSQLSanitizer(metaKeys);
		
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to get all projects when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		AdminMyProjectsReactor reactor = new AdminMyProjectsReactor();
		reactor.In();
		Insight temp = new Insight();
		temp.setUser(user);
		reactor.setInsight(temp);
		
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
		if(projectFilter != null && !projectFilter.isEmpty()) {
			GenRowStruct struct = new GenRowStruct();
			for(String engine : projectFilter) {
				struct.add(new NounMetadata(engine, PixelDataType.CONST_STRING));
			}
			reactor.getNounStore().addNoun(ReactorKeysEnum.PROJECT.getKey(), struct);
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
	@Path("getProjects")
	@Operation(summary = "List projects (POST)", description = "Returns projects using form parameters for filtering and pagination.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Projects retrieved",
			content = @Content(mediaType = "application/json",
				array = @ArraySchema(schema = @Schema(type = "object"))
			)
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response getProjectsPOST(@Context HttpServletRequest request) {
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
		
		AdminMyProjectsReactor reactor = new AdminMyProjectsReactor();
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
		if(parameterMap.containsKey("projectId") && parameterMap.get("projectId") != null && parameterMap.get("projectId").length > 0) {
			GenRowStruct struct = new GenRowStruct();
			String[] projectFilter = parameterMap.get("projectId");
			for(String project : projectFilter) {
				struct.add(new NounMetadata(project, PixelDataType.CONST_STRING));
			}
			reactor.getNounStore().addNoun(ReactorKeysEnum.PROJECT.getKey(), struct);
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
			Type metaFiltersType = new TypeToken<Map<String, Object>>(){}.getType();
			Map<String, Object> metaFilters = new Gson().fromJson(parameterMap.get("metaFilters")[0], metaFiltersType);
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
	@Path("/getAllUserProjects")
	@Produces("application/json")
	@Operation(summary = "List a user's projects", description = "Returns all projects the specified user has access to.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Projects retrieved",
			content = @Content(mediaType = "application/json",
				array = @ArraySchema(schema = @Schema(type = "object"))
			)
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response getAllUserProjects(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String userId = WebUtility.inputSQLSanitizer(form.getFirst("userId"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to pull the projects that user " + userId + " has access to when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		return WebUtility.getResponse(adminUtils.getAllUserProjects(userId), 200);
	}
	
	@POST
	@Path("/grantAllProjects")
	@Produces("application/json")
	@Operation(summary = "Grant all projects", description = "Grants a user access to all projects.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Access granted",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "500", description = "Server error",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response grantAllProjects(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;

		String userId = WebUtility.inputSQLSanitizer(form.getFirst("userId"));
		String permission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		boolean isAddNew = Boolean.parseBoolean(form.getFirst("isAddNew") + "");

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to grant all the projects to user " + userId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.grantAllProjects(userId, permission, isAddNew, user);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user),
				"has granted all projects to " + userId + "with permission " + permission));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	
	@POST
	@Path("/grantNewUsersProjectAccess")
	@Produces("application/json")
	@Operation(summary = "Grant new users project access", description = "Grants a project permission to new users.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Access granted",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "500", description = "Server error",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response grantNewUsersProjectAccess(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;

		String permission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to grant projects to new users when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.grantNewUsersProjectAccess(projectId, permission, user, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user),
				"has granted project " + projectId + "to new users with permission " + permission));

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Get the project users and their permissions
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getProjectUsers")
	@Operation(summary = "List project users", description = "Returns users and permissions for a project, with total and pagination.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Users retrieved",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response getProjectUsers(@Context HttpServletRequest request, 
			@QueryParam("projectId") String projectId, @QueryParam("userId") String userId, 
			@QueryParam("searchTerm") String searchTerm, @QueryParam("permission") String permission, 
			@QueryParam("limit") long limit, @QueryParam("offset") long offset) {
	    projectId = WebUtility.inputSQLSanitizer(projectId);
	    userId = WebUtility.inputSQLSanitizer(userId);
	    searchTerm = WebUtility.inputSQLSanitizer(searchTerm);
	    permission = WebUtility.inputSQLSanitizer(permission);
	    
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to pull all the users who use project " + projectId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		String searchParam = searchTerm != null ? searchTerm : userId;
		List<Map<String, Object>> members = adminUtils.getProjectUsers(projectId, searchParam, permission, limit, offset);
		long totalMembers = SecurityAdminUtils.getProjectUsersCount(projectId, searchParam, permission);
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("totalMembers", totalMembers);
		ret.put("members", members);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Add a user to an project
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addProjectUserPermission")
	@Operation(summary = "Add project user permission", description = "Adds a user to a project with a permission level.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission added",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "500", description = "Server error",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response addProjectUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String newUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String permission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to add user " + newUserId + " to project " + projectId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			adminUtils.addProjectUser(newUserId, projectId, permission, user, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has added user " + newUserId + " to project " + projectId + " with permission " + permission));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Add all users to an project
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addAllUsers")
	@Operation(summary = "Add all users to project", description = "Adds all users to a project with a permission level.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Users added",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "500", description = "Server error",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response addAllUsers(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String permission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to add all users to project " + projectId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			adminUtils.addAllProjectUsers(projectId, permission, user, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has added all users to project " + projectId + " with permission " + permission));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Edit user permission for an project
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editProjectUserPermission")
	@Operation(summary = "Edit project user permission", description = "Edits a user's permission for a project.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission updated",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "500", description = "Server error",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response editProjectUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;

		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String newPermission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit user " + existingUserId + " permissions for project " + projectId + " when not an admin"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			adminUtils.editProjectUserPermission(existingUserId, projectId, newPermission, user, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has edited user " + existingUserId + " permission to project " + projectId + " with level " + newPermission));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Edit user permission for a project
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editProjectUserPermissions")
	@Operation(summary = "Edit multiple project user permissions", description = "Edits multiple user permissions for a project in bulk.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permissions updated",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "500", description = "Server error",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response editProjectUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit user access permissions for project " + projectId + " when not an admin"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		Type requestsType = new TypeToken<List<Map<String, String>>>(){}.getType();
		List<Map<String, String>> requests = new Gson().fromJson(form.getFirst("userpermissions"), requestsType);
		try {
			SecurityAdminUtils.editProjectUserPermissions(projectId, requests, user, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has edited user access permissions to project " + projectId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * update all user's permission level to new permission level for an project
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("updateProjectUserPermissions")
	@Operation(summary = "Update all project user permissions", description = "Updates all users' permissions for a project to a new level.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permissions updated",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "500", description = "Server error",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response updateProjectUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String newPermission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit user permissions for project " + projectId + " when not an admin"));
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			adminUtils.updateProjectUserPermissions(projectId, newPermission, user, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has edited user permissions to project " + projectId + " with level " + newPermission));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Remove user permission for an project
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeProjectUserPermission")
	@Operation(summary = "Remove project user permission", description = "Removes a user's access to a project.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission removed",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "500", description = "Server error",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response removeProjectUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		
		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to remove user " + existingUserId + " from having access to project " + projectId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			adminUtils.removeProjectUser(existingUserId, projectId);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has removed user " + existingUserId + " from having access to project " + projectId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("setProjectGlobal")
	@Operation(summary = "Set project visibility", description = "Sets a project to public or private.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Visibility updated",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "500", description = "Server error",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response setProjectGlobal(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		boolean isPublic = Boolean.parseBoolean(form.getFirst("public"));
		String logPublic = isPublic ? " public " : " private";

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to set the project " + projectId + logPublic + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.setProjectGlobal(projectId, isPublic);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has set the project " + projectId + logPublic));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	} 
	
	/**
	 * Set the project as being discoverable for the entire semoss instance
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setProjectDiscoverable")
	@Operation(summary = "Set project discoverability", description = "Sets whether a project is discoverable across the instance.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Discoverability updated",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "500", description = "Server error",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response setProjectDiscoverable(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		boolean isDiscoverable = Boolean.parseBoolean(form.getFirst("discoverable"));
		String logDiscoverable = isDiscoverable ? " discoverable " : " not discoverable";

		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to set the project " + projectId + logDiscoverable + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			adminUtils.setProjectDiscoverable(projectId, isDiscoverable);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has set the project " + projectId + logDiscoverable));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Get users with no access to a given project
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getProjectUsersNoCredentials")
	@Operation(summary = "List users without project access", description = "Returns users who do not have access to the given project.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Users retrieved",
			content = @Content(mediaType = "application/json",
				array = @ArraySchema(schema = @Schema(type = "object"))
			)
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "500", description = "Server error",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response getProjectUsersNoCredentials(@Context HttpServletRequest request, 
			@QueryParam("projectId") String projectId, 
			@QueryParam("searchTerm") String searchTerm,
			@QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {
	    projectId = WebUtility.inputSQLSanitizer(projectId);
	    searchTerm = WebUtility.inputSQLSanitizer(searchTerm);
	   
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), " is trying to get all users when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		boolean graphApi = Boolean.parseBoolean("" + SocialPropertiesUtil.getInstance().getProperty("ms_graphapi_lookup"));

		// if not graph api
		// then we will look at our security db
		if(!graphApi) {
			List<Map<String, Object>> ret = adminUtils.getProjectUsersNoCredentials(projectId, searchTerm, limit, offset);
			return WebUtility.getResponse(ret, 200);
		}

		
		String graphApiGroupId = SocialPropertiesUtil.getInstance().getProperty("ms_graphapi_groupId");

		try {
			List<Map<String, Object>> filteredUsers = MsGraphUtility.getProjectUsers(request, user, projectId, searchTerm, graphApiGroupId, limit , offset);
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
	@Path("approveProjectUserAccessRequest")
	@Operation(summary = "Approve project user access requests", description = "Approves access requests for a project and grants permissions.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Requests approved",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "500", description = "Server error",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response approveProjectUserAccessRequest(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;

		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String endDate = null; // form.getFirst("endDate");
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to approve user request for permission to project " + projectId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		// adding user permissions and updating user access requests in bulk
		Type approveRequestsType = new TypeToken<List<Map<String, Object>>>(){}.getType();
		List<Map<String, Object>> requests = new Gson().fromJson(form.getFirst("requests"), approveRequestsType);
		try {
			AccessToken token = user.getAccessToken(user.getPrimaryLogin());
			String userId = token.getId();
			String userType = token.getProvider().toString();
			adminUtils.approveProjectUserAccessRequests(userId, userType, projectId, requests, endDate);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has approved user access requests and added user permissions to project " + projectId));
		
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
	@Path("denyProjectUserAccessRequest")
	@Operation(summary = "Deny project user access requests", description = "Denies user access requests for a project.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Requests denied",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "500", description = "Server error",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response denyProjectUserAccessRequest(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;

		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to deny user request for permission to project " + projectId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		// updating user access requests in bulk
		Type requestIdsType = new TypeToken<List<String>>(){}.getType();
		List<String> requestids = new Gson().fromJson(form.getFirst("requestids"), requestIdsType);
		try {
			AccessToken token = user.getAccessToken(user.getPrimaryLogin());
			String userId = token.getId();
			String userType = token.getProvider().toString();
			adminUtils.denyProjectUserAccessRequests(userId, userType, projectId, requestids);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has denied user access requests to project " + projectId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Add user permissions in bulk to a project
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addProjectUserPermissions")
	@Operation(summary = "Add multiple project user permissions", description = "Adds user permissions to a project in bulk.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permissions added",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "500", description = "Server error",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response addProjectUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to add user permission to project " + projectId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		boolean graphApi = Boolean.parseBoolean("" + SocialPropertiesUtil.getInstance().getProperty("ms_graphapi_lookup"));
		
		// adding user permissions in bulk
		Type permissionType = new TypeToken<List<Map<String, String>>>(){}.getType();
		List<Map<String, String>> permission = new Gson().fromJson(form.getFirst("userpermissions"), permissionType);
		try {
			// if we are doing the grpah api
			// then the users might not already exist in the security db
			if(graphApi) {
				// filter out users that already exist
				List<Map<String, String>> filteredUsers = permission.stream()
						.filter(map -> !SecurityQueryUtils.checkUserExist(map.get(Constants.MAP_USERID))).collect(Collectors.toList());
				if (filteredUsers != null && !filteredUsers.isEmpty()) {
					AccessToken token = null;
					  // Add new users to OAuth if they don't exist
					for (Map<String, String> map : filteredUsers) {
						token = new AccessToken();
						token.setId(map.get(Constants.MAP_USERID));
						token.setEmail(map.get(Constants.MAP_EMAIL));
						token.setName(map.get(Constants.MAP_NAME));
						token.setProvider(AuthProvider.getProviderFromString(map.get(AuthProvider.MICROSOFT.name())));
						token.setUsername(map.get(Constants.MAP_USERNAME));
						SecurityUpdateUtils.addOAuthUser(token);
					}
				}
			}
			adminUtils.addProjectUserPermissions(projectId, permission, user);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has added user permissions to project " + projectId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Remove user permissions for a project, in bulk
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeProjectUserPermissions")
	@Operation(summary = "Remove multiple project user permissions", description = "Removes user permissions from a project in bulk.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permissions removed",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "500", description = "Server error",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response removeProjectUserPermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to remove usersfrom having access to project " + projectId + " when not an admin"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		Gson gson = new Gson();
		Type idsType = new TypeToken<List<String>>(){}.getType();
		List<String> ids = gson.fromJson(form.getFirst("ids"), idsType);
		ids = WebUtility.inputSQLSanitizer(ids);
		try {
			adminUtils.removeProjectUsers(ids, projectId);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "has removed users from having access to project " + projectId));
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("setProjectPortal")
	@Operation(summary = "Set project portal", description = "Enables or disables the public portal for a project and updates configuration.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Portal updated",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "boolean"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "500", description = "Server error",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response setProjectPortal(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		boolean hasPortal = Boolean.parseBoolean(form.getFirst("hasPortal"));
		String portalName = WebUtility.inputSQLSanitizer(form.getFirst("portalName"));
		String logPortal = hasPortal ? " enable portal " : " disable portal";
		
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to " + logPortal + " for project " + projectId));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			adminUtils.setProjectPortal(user, projectId, hasPortal, portalName);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}

		IProject project = Utility.getProject(projectId);
		try {
			SecurityProjectUtils.setProjectPortal(user, projectId, hasPortal, portalName);
			project.setHasPortal(hasPortal);
		} catch(IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to " + logPortal + " for project " + projectId));
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
		
		try {
			String projectSmss = project.getSmssFilePath();
			Map<String, String> mods = new HashMap<>();
			mods.put(Settings.PUBLIC_HOME_ENABLE, hasPortal+"");
			Properties props = Utility.loadProperties(projectSmss);
			if(props.get(Settings.PUBLIC_HOME_ENABLE) == null) {
				classLogger.info(Utility.cleanLogString("Updating project smss to include public home property to " + logPortal + " for project " + projectId));
				Utility.addKeysAtLocationIntoPropertiesFile(projectSmss, Constants.CONNECTION_URL, mods);
			} else {
				classLogger.info(Utility.cleanLogString("Modifying project smss to " + logPortal + " for project " + projectId));
				Utility.changePropertiesFileValue(projectSmss, Settings.PUBLIC_HOME_ENABLE, hasPortal+"");
			}
			
			// reload and set the prop again
			Properties newSmssProp = Utility.loadProperties(projectSmss);
			project.setSmssProp(newSmssProp);
			
			// push to cloud
			ClusterUtil.pushProjectSmss(projectId);
		} catch(Exception e) {
			//ignore
		}
		
		// log the operation
		classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to " + logPortal + " for project " + projectId));
		
		return WebUtility.getResponse(true, 200);
	}
	
}
