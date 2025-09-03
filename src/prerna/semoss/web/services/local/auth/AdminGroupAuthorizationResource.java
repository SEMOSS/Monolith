package prerna.semoss.web.services.local.auth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.AdminSecurityGroupUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("/auth/admin/group")
@PermitAll
@Tag(name = "auth", description = "Endpoints for managing authentication and authorization of users and applications, including user permissions, access control, and administrative actions.")
public class AdminGroupAuthorizationResource extends AbstractAdminResource {
	
	private static final Logger classLogger = LogManager.getLogger(AdminGroupAuthorizationResource.class);
	
	///////////////////////////////////////////////////////////////

	/*
	 * Groups
	 */
	
	@GET
	@Path("/getGroups")
	@Produces("application/json")
	@Operation(summary = "List groups", description = "Returns groups matching an optional search term with pagination.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Groups retrieved",
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
	public Response getAllGroups(@Context HttpServletRequest request, @QueryParam("searchTerm") String searchTerm, @QueryParam("limit") long limit, @QueryParam("offset") long offset) {
		searchTerm = WebUtility.inputSanitizer(searchTerm);
		AdminSecurityGroupUtils groupUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			groupUtils = AdminSecurityGroupUtils.getInstance(user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to get list of groups"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, Object>> ret = groupUtils.getGroups(searchTerm, limit, offset);
		return WebUtility.getResponse(ret, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("/addGroup")
	@Operation(summary = "Create a group", description = "Creates a new security group.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Group created",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "boolean"))
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
			String newGroupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
			if(newGroupId == null || (newGroupId = newGroupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id cannot be null or empty");
			}
			String newGroupType = WebUtility.inputSanitizer(request.getParameter("type"));
			String description = WebUtility.inputSanitizer(request.getParameter("description"));
			if(description == null) {
				description = "";
			}
			description = description.trim();
			if((newGroupType == null || (newGroupType = newGroupType.trim()).isEmpty()) ) {
				throw new IllegalArgumentException("The group type cannot be null");
			} 
			AdminSecurityGroupUtils.getInstance(user).addGroup(user, newGroupId, newGroupType, description);
		} catch (IllegalArgumentException e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorRet.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 500);
		}
		return WebUtility.getResponse(success, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("/deleteGroup")
	@Operation(summary = "Delete a group", description = "Deletes a security group and propagates changes.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Group deleted",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "boolean"))
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
			String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
			if(groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id cannot be null or empty");
			}
			String groupType =WebUtility.inputSanitizer(request.getParameter("type"));
			
			AdminSecurityGroupUtils.getInstance(user).deleteGroupAndPropagate(groupId, groupType);
		} catch (IllegalArgumentException e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorRet.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 500);
		}
		return WebUtility.getResponse(success, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("/editGroup")
	@Operation(summary = "Edit a group", description = "Edits a group's id, type, or description and propagates changes.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Group edited",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "boolean"))
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
			String groupId = WebUtility.inputSQLSanitizer( request.getParameter("groupId"));
			if(groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id cannot be null or empty");
			}
			String groupType =WebUtility.inputSanitizer(request.getParameter("type"));
			String newGroupId =WebUtility.inputSQLSanitizer(request.getParameter("newGroupId"));
			if(newGroupId == null || (newGroupId = newGroupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The new group id cannot be null or empty");
			}
			String newType =WebUtility.inputSanitizer(request.getParameter("newType"));
			String newDescription = WebUtility.inputSanitizer(request.getParameter("newDescription"));
			if((newType == null || (newType = newType.trim()).isEmpty()) ) {
				throw new IllegalArgumentException("The new group type cannot be null");
			} 
			
			AdminSecurityGroupUtils.getInstance(user).editGroupAndPropagate(user, groupId, groupType, newGroupId, newType, newDescription);
		} catch (IllegalArgumentException e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorRet.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 500);
		}
		return WebUtility.getResponse(success, 200);
	}
	
	
	@POST
	@Produces("application/json")
	@Path("/editGroupDetails")
	@Operation(summary = "Edit group details", description = "Updates group id, type, and description without membership changes.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Group details updated",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "boolean"))
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
	public Response editGroupDetails(@Context HttpServletRequest request) {
		Map<String, String> errorRet = new Hashtable<>();
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false),
					User.getSingleLogginName(user), "is trying to edit a group but couldn't find user session"));
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		if (!SecurityAdminUtils.userIsAdmin(user)) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false),
					User.getSingleLogginName(user), "is trying to edit a group but is not an admin"));
			errorRet.put(Constants.ERROR_MESSAGE, "The user doesn't have the permissions to perform this action.");
			return WebUtility.getResponse(errorRet, 400);
		}

		boolean success = false;
		try {
			String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
			if (groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id cannot be null or empty");
			}
			String newGroupId = WebUtility.inputSQLSanitizer(request.getParameter("newGroupId"));
			if (newGroupId == null || (newGroupId = newGroupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The new group id cannot be null or empty");
			}
			String groupType = WebUtility.inputSanitizer(request.getParameter("type"));
			String newType = WebUtility.inputSanitizer(request.getParameter("newType"));
			String newDescription = WebUtility.inputSanitizer(request.getParameter("newDescription"));
			if((newType == null || (newType = newType.trim()).isEmpty()) ) {
				throw new IllegalArgumentException("The new group type cannot be null");
			} 
			
			AdminSecurityGroupUtils.getInstance(user).editGroupDetailsAndPropagate(user, groupId, groupType,
						newGroupId, newType, newDescription);
			success = true;
		} catch (IllegalArgumentException e) {
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorRet.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 500);
		}
		return WebUtility.getResponse(success, 200);
	}

	/*
	 * Group Members For Custom Groups
	 */
	
	@GET
	@Path("/getGroupMembers")
	@Produces("application/json")
	@Operation(summary = "List group members", description = "Returns members of a group with optional search and pagination.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Members retrieved",
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
	public Response getGroupMembers(@Context HttpServletRequest request, @QueryParam("groupId") String groupId, @QueryParam("searchTerm") String searchTerm,
			@QueryParam("limit") long limit, @QueryParam("offset") long offset) {
		groupId = WebUtility.inputSanitizer(groupId);
		searchTerm = WebUtility.inputSanitizer(searchTerm);
		AdminSecurityGroupUtils groupUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			groupUtils = AdminSecurityGroupUtils.getInstance(user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to get users assigned to a group"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(groupId == null || (groupId=groupId.trim()).isEmpty()) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must define the group id");
			return WebUtility.getResponse(errorMap, 400);
		}
		
		try {
			List<Map<String, Object>> ret = groupUtils.getGroupMembers(groupId, searchTerm, limit, offset);
			return WebUtility.getResponse(ret, 200);
		} catch(IllegalArgumentException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorMap.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
	}
	
	@GET
	@Path("/getNumMembersInGroup")
	@Produces("application/json")
	@Operation(summary = "Count group members", description = "Returns the number of users in a group, optionally filtered by a search term.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Count returned",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "integer", format = "int64"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response getNumMembersInGroup(@Context HttpServletRequest request, @QueryParam("groupId") String groupId, @QueryParam("searchTerm") String searchTerm) {
		groupId = WebUtility.inputSanitizer(groupId);
		searchTerm = WebUtility.inputSanitizer(searchTerm);
		AdminSecurityGroupUtils groupUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			groupUtils = AdminSecurityGroupUtils.getInstance(user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to the number of users assinged to a group"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(groupId == null || (groupId=groupId.trim()).isEmpty()) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must define the group id");
			return WebUtility.getResponse(errorMap, 400);
		}
		
		try {
			Long numUsers = groupUtils.getNumMembersInGroup(groupId, searchTerm);
			return WebUtility.getResponse(numUsers, 200);
		} catch(IllegalArgumentException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorMap.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
	}
	
	@GET
	@Path("/getNonGroupMembers")
	@Produces("application/json")
	@Operation(summary = "List non-members", description = "Returns users not assigned to the specified group.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Users retrieved",
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
	public Response getNonGroupMembers(@Context HttpServletRequest request,  @QueryParam("groupId") String groupId, @QueryParam("searchTerm") String searchTerm,
			@QueryParam("limit") long limit, @QueryParam("offset") long offset) {
		searchTerm = WebUtility.inputSanitizer(searchTerm);
		groupId = WebUtility.inputSanitizer(groupId);
		AdminSecurityGroupUtils groupUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			groupUtils = AdminSecurityGroupUtils.getInstance(user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to users who are not assigned to a group"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(groupId == null || (groupId=groupId.trim()).isEmpty()) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must define the group id");
			return WebUtility.getResponse(errorMap, 400);
		}

		try {
			List<Map<String, Object>> ret = groupUtils.getNonGroupMembers(groupId, searchTerm, limit, offset);
			return WebUtility.getResponse(ret, 200);
		} catch(IllegalArgumentException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorMap.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
	}
	
	@GET
	@Path("/getNumNonMembersInGroup")
	@Produces("application/json")
	@Operation(summary = "Count non-members", description = "Returns the number of users not assigned to a group.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Count returned",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "integer", format = "int64"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response getNumNonMembersInGroup(@Context HttpServletRequest request, @QueryParam("groupId") String groupId, @QueryParam("searchTerm") String searchTerm) {
		groupId = WebUtility.inputSanitizer(groupId);
		searchTerm = WebUtility.inputSanitizer(searchTerm);
		AdminSecurityGroupUtils groupUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			groupUtils = AdminSecurityGroupUtils.getInstance(user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to the number of users assinged to a group"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(groupId == null || (groupId=groupId.trim()).isEmpty()) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must define the group id");
			return WebUtility.getResponse(errorMap, 400);
		}
		
		try {
			Long numUsers = groupUtils.getNumNonMembersInGroup(groupId, searchTerm);
			return WebUtility.getResponse(numUsers, 200);
		} catch(IllegalArgumentException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorMap.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
	}
	
	@POST
	@Produces("application/json")
	@Path("/addGroupMember")
	@Operation(summary = "Add group member", description = "Adds a user to a group, optionally until an end date.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Member added",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "boolean"))
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
	public Response addGroupMember(@Context HttpServletRequest request) {
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
			String groupId = WebUtility.inputSQLSanitizer( request.getParameter("groupId"));
			if(groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id ('groupId') cannot be null or empty");
			}
			String userId = WebUtility.inputSQLSanitizer(request.getParameter("userId"));
			if(userId == null || (userId = userId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The user id ('userId') cannot be null or empty");
			}
			String userLoginType = WebUtility.inputSanitizer(request.getParameter("type"));
			if(userLoginType == null || (userLoginType = userLoginType.trim()).isEmpty()) {
				throw new IllegalArgumentException("The user login type ('type') cannot be null or empty");
			}
			String endDate = WebUtility.inputSanitizer(request.getParameter("endDate"));
			
			AdminSecurityGroupUtils.getInstance(user).addUserToGroup(user, groupId, userId, userLoginType, endDate);
		} catch (IllegalArgumentException e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorRet.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 500);
		}
		return WebUtility.getResponse(success, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("/deleteGroupMember")
	@Operation(summary = "Remove group member", description = "Removes a user from a group.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Member removed",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "boolean"))
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
	public Response deleteGroupMember(@Context HttpServletRequest request) {
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
			String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
			if(groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id ('groupId') cannot be null or empty");
			}
			String userId = WebUtility.inputSQLSanitizer(request.getParameter("userId"));
			if(userId == null || (userId = userId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The user id ('userId') cannot be null or empty");
			}
			String userLoginType = WebUtility.inputSanitizer(request.getParameter("type"));
			if(userLoginType == null || (userLoginType = userLoginType.trim()).isEmpty()) {
				throw new IllegalArgumentException("The user login type ('type') cannot be null or empty");
			}
			
			AdminSecurityGroupUtils.getInstance(user).removeUserFromGroup(groupId, userId, userLoginType);
		} catch (IllegalArgumentException e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorRet.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 500);
		}
		return WebUtility.getResponse(success, 200);
	}
	
	
	
	
	///////////////////////////////////////////////////////////////
	
	/*
	 * Group Project Permissions
	 */
	
	@POST
	@Produces("application/json")
	@Path("/addGroupProjectPermission")
	@Operation(summary = "Grant project permission to group", description = "Adds a project permission for a group.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission granted",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "boolean"))
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
	public Response addGroupProjectPermission(@Context HttpServletRequest request) {
		Map<String, String> errorRet = new Hashtable<>();
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to add a group project permission but couldn't find user session"));
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(!SecurityAdminUtils.userIsAdmin(user)){
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to add a group project permission but is not an admin"));
			errorRet.put(Constants.ERROR_MESSAGE, "The user doesn't have the permissions to perform this action.");
			return WebUtility.getResponse(errorRet, 400);
		}
		
		boolean success = false;
		try {
			String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
			if(groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id ('groupId') cannot be null or empty");
			}
			String projectId = WebUtility.inputSanitizer(request.getParameter("projectId"));
			if(projectId == null || (projectId = projectId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The project id ('projectId') cannot be null or empty");
			}
			String permissionStr = WebUtility.inputSanitizer(request.getParameter("permission"));
			if(permissionStr == null || (permissionStr = permissionStr.trim()).isEmpty()) {
				throw new IllegalArgumentException("The permission integer value ('permission') cannot be null or empty");
			}
			int permission = -1;
			try {
				permission = Integer.parseInt(permissionStr);
			} catch(NumberFormatException nbe) {
				classLogger.error(Constants.STACKTRACE, nbe);
				throw new IllegalArgumentException("Must pass a valid integer value. Received value = " + permissionStr);
			}
			
			String groupType = WebUtility.inputSanitizer(request.getParameter("type"));
			String endDate = WebUtility.inputSanitizer(request.getParameter("endDate"));

			AdminSecurityGroupUtils.getInstance(user).addGroupProjectPermission(user, groupId, groupType, projectId, permission, endDate);
		} catch (IllegalArgumentException e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorRet.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 500);
		}
		return WebUtility.getResponse(success, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("/editGroupProjectPermission")
	@Operation(summary = "Edit project permission for group", description = "Edits a group's project permission.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission updated",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "boolean"))
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
	public Response editGroupProjectPermission(@Context HttpServletRequest request) {
		Map<String, String> errorRet = new Hashtable<>();
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit a group project permission but couldn't find user session"));
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(!SecurityAdminUtils.userIsAdmin(user)){
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit a group project permission but is not an admin"));
			errorRet.put(Constants.ERROR_MESSAGE, "The user doesn't have the permissions to perform this action.");
			return WebUtility.getResponse(errorRet, 400);
		}
		
		boolean success = false;
		try {
			String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
			if(groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id ('groupId') cannot be null or empty");
			}
			String projectId = WebUtility.inputSanitizer(request.getParameter("projectId"));
			if(projectId == null || (projectId = projectId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The project id ('projectId') cannot be null or empty");
			}
			String permissionStr =WebUtility.inputSanitizer(request.getParameter("permission"));
			if(permissionStr == null || (permissionStr = permissionStr.trim()).isEmpty()) {
				throw new IllegalArgumentException("The permission integer value ('permission') cannot be null or empty");
			}
			int permission = -1;
			try {
				permission = Integer.parseInt(permissionStr);
			} catch(NumberFormatException nbe) {
				classLogger.error(Constants.STACKTRACE, nbe);
				throw new IllegalArgumentException("Must pass a valid integer value. Received value = " + permissionStr);
			}
			
			String groupType = WebUtility.inputSanitizer(request.getParameter("type"));
			String endDate = WebUtility.inputSanitizer(request.getParameter("endDate"));

			AdminSecurityGroupUtils.getInstance(user).editGroupProjectPermission(user, groupId, groupType, projectId, permission, endDate);
			success = true;
		} catch (IllegalArgumentException e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorRet.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 500);
		}
		return WebUtility.getResponse(success, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("/removeGroupProjectPermission")
	@Operation(summary = "Revoke project permission from group", description = "Removes a project's permission for a group.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission removed",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "boolean"))
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
	public Response removeGroupProjectPermission(@Context HttpServletRequest request) {
		Map<String, String> errorRet = new Hashtable<>();
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to remove a group project permission but couldn't find user session"));
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(!SecurityAdminUtils.userIsAdmin(user)){
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to remove a group project permission but is not an admin"));
			errorRet.put(Constants.ERROR_MESSAGE, "The user doesn't have the permissions to perform this action.");
			return WebUtility.getResponse(errorRet, 400);
		}
		
		boolean success = false;
		try {
			String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
			if(groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id ('groupId') cannot be null or empty");
			}
			String projectId = WebUtility.inputSanitizer(request.getParameter("projectId"));
			if(projectId == null || (projectId = projectId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The project id ('projectId') cannot be null or empty");
			}
			String groupType = WebUtility.inputSanitizer(request.getParameter("type"));

			AdminSecurityGroupUtils.getInstance(user).removeGroupProjectPermission(user, groupId, groupType, projectId);
		} catch (IllegalArgumentException e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorRet.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 500);
		}
		return WebUtility.getResponse(success, 200);
	}
	
	@GET
	@Path("/getProjectsForGroup")
	@Produces("application/json")
	@Operation(summary = "List projects for group", description = "Returns projects assigned to the specified group with optional filters and pagination.")
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
	public Response getProjectsForGroup(@Context HttpServletRequest request, @QueryParam("groupId") String groupId, @QueryParam("groupType") String groupType, @QueryParam("searchTerm") String searchTerm,
			@QueryParam("limit") long limit, @QueryParam("offset") long offset, @QueryParam("onlyApps") boolean onlyApps) {
		groupType = WebUtility.inputSanitizer(groupType);
		groupId = WebUtility.inputSanitizer(groupId);
		searchTerm = WebUtility.inputSanitizer(searchTerm);
		AdminSecurityGroupUtils groupUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			groupUtils = AdminSecurityGroupUtils.getInstance(user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to get projects assinged to a group"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(groupId == null || (groupId=groupId.trim()).isEmpty()) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must define the group id");
			return WebUtility.getResponse(errorMap, 400);
		}

		try {
			List<Map<String, Object>> ret = groupUtils.getProjectsForGroup(groupId, groupType, searchTerm, limit, offset, onlyApps);
			return WebUtility.getResponse(ret, 200);
		} catch(IllegalArgumentException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorMap.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
	}
	
	@GET
	@Path("/getNumProjectsForGroup")
	@Produces("application/json")
	@Operation(summary = "Count projects for group", description = "Returns the number of projects assigned to a group, with optional filters.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Count returned",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "integer", format = "int64"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response getNumProjectsForGroup(@Context HttpServletRequest request, @QueryParam("groupId") String groupId, @QueryParam("groupType") String groupType, 
			@QueryParam("searchTerm") String searchTerm, @QueryParam("onlyApps") boolean onlyApps) {
		groupType = WebUtility.inputSanitizer(groupType);
		groupId = WebUtility.inputSanitizer(groupId);
		searchTerm = WebUtility.inputSanitizer(searchTerm);
		AdminSecurityGroupUtils groupUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			groupUtils = AdminSecurityGroupUtils.getInstance(user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to get projects assinged to a group"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(groupId == null || (groupId=groupId.trim()).isEmpty()) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must define the group id");
			return WebUtility.getResponse(errorMap, 400);
		}
		
		try {
			Long numProjects = groupUtils.getNumProjectsForGroup(groupId, groupType, searchTerm, onlyApps);
			return WebUtility.getResponse(numProjects, 200);
		} catch(IllegalArgumentException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorMap.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
	}
	
	@GET
	@Path("/getAvailableProjectsForGroup")
	@Produces("application/json")
	@Operation(summary = "List available projects for group", description = "Returns projects that can be assigned to the group.")
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
	public Response getAvailableProjectsForGroup(@Context HttpServletRequest request, @QueryParam("groupId") String groupId, @QueryParam("groupType") String groupType, @QueryParam("searchTerm") String searchTerm,
			@QueryParam("limit") long limit, @QueryParam("offset") long offset, @QueryParam("onlyApps") boolean onlyApps) {
		groupType = WebUtility.inputSanitizer(groupType);
		groupId = WebUtility.inputSanitizer(groupId);
		searchTerm = WebUtility.inputSanitizer(searchTerm);
		AdminSecurityGroupUtils groupUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			groupUtils = AdminSecurityGroupUtils.getInstance(user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to get projects assinged to a group"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(groupId == null || (groupId=groupId.trim()).isEmpty()) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must define the group id");
			return WebUtility.getResponse(errorMap, 400);
		}
		
		try {
			List<Map<String, Object>> ret = groupUtils.getAvailableProjectsForGroup(groupId, groupType, searchTerm, limit, offset, onlyApps);
			return WebUtility.getResponse(ret, 200);
		} catch(IllegalArgumentException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorMap.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
	}
	
	@GET
	@Path("/getNumAvailableProjectsForGroup")
	@Produces("application/json")
	@Operation(summary = "Count available projects for group", description = "Returns the number of projects that can be assigned to the group.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Count returned",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "integer", format = "int64"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response getNumAvailableProjectsForGroup(@Context HttpServletRequest request, @QueryParam("groupId") String groupId, @QueryParam("groupType") String groupType, 
			@QueryParam("searchTerm") String searchTerm, @QueryParam("onlyApps") boolean onlyApps) {
		groupType = WebUtility.inputSanitizer(groupType);
		groupId = WebUtility.inputSanitizer(groupId);
		searchTerm = WebUtility.inputSanitizer(searchTerm);
		AdminSecurityGroupUtils groupUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			groupUtils = AdminSecurityGroupUtils.getInstance(user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to get projects assinged to a group"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(groupId == null || (groupId=groupId.trim()).isEmpty()) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must define the group id");
			return WebUtility.getResponse(errorMap, 400);
		}
		
		try {
			Long numProjects = groupUtils.getNumAvailableProjectsForGroup(groupId, groupType, searchTerm, onlyApps);
			return WebUtility.getResponse(numProjects, 200);
		} catch(IllegalArgumentException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorMap.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
	}
	
	
	
	
	
	
	
	
	///////////////////////////////////////////////////////////////
	
	/*
	 * Group Engine Permissions
	 */
	
	
	
	@POST
	@Produces("application/json")
	@Path("/addGroupEnginePermission")
	@Operation(summary = "Grant engine permission to group", description = "Adds an engine permission for a group.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission granted",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "boolean"))
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
	public Response addGroupEnginePermission(@Context HttpServletRequest request) {
		Map<String, String> errorRet = new Hashtable<>();
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to add a group engine permission but couldn't find user session"));
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(!SecurityAdminUtils.userIsAdmin(user)){
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to add a group engine permission but is not an admin"));
			errorRet.put(Constants.ERROR_MESSAGE, "The user doesn't have the permissions to perform this action.");
			return WebUtility.getResponse(errorRet, 400);
		}
		
		boolean success = false;
		try {
			String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
			if(groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id ('groupId') cannot be null or empty");
			}
			String engineId =WebUtility.inputSanitizer(request.getParameter("engineId"));
			if(engineId == null || (engineId = engineId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The engine id ('engineId') cannot be null or empty");
			}
			String permissionStr = WebUtility.inputSanitizer(request.getParameter("permission"));
			if(permissionStr == null || (permissionStr = permissionStr.trim()).isEmpty()) {
				throw new IllegalArgumentException("The permission integer value ('permission') cannot be null or empty");
			}
			int permission = -1;
			try {
				permission = Integer.parseInt(permissionStr);
			} catch(NumberFormatException nbe) {
				classLogger.error(Constants.STACKTRACE, nbe);
				throw new IllegalArgumentException("Must pass a valid integer value. Received value = " + permissionStr);
			}
			
			String groupType = WebUtility.inputSanitizer(request.getParameter("type"));
			String endDate = WebUtility.inputSanitizer(request.getParameter("endDate"));

			AdminSecurityGroupUtils.getInstance(user).addGroupEnginePermission(user, groupId, groupType, engineId, permission, endDate);
		} catch (IllegalArgumentException e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorRet.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 500);
		}
		return WebUtility.getResponse(success, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("/editGroupEnginePermission")
	@Operation(summary = "Edit engine permission for group", description = "Edits a group's engine permission.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission updated",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "boolean"))
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
	public Response editGroupEnginePermission(@Context HttpServletRequest request) {
		Map<String, String> errorRet = new Hashtable<>();
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit a group engine permission but couldn't find user session"));
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(!SecurityAdminUtils.userIsAdmin(user)){
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to edit a group engine permission but is not an admin"));
			errorRet.put(Constants.ERROR_MESSAGE, "The user doesn't have the permissions to perform this action.");
			return WebUtility.getResponse(errorRet, 400);
		}
		
		boolean success = false;
		try {
			String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
			if(groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id ('groupId') cannot be null or empty");
			}
			String engineId = WebUtility.inputSanitizer(request.getParameter("engineId"));
			if(engineId == null || (engineId = engineId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The engine id ('engineId') cannot be null or empty");
			}
			String permissionStr = WebUtility.inputSanitizer(request.getParameter("permission"));
			if(permissionStr == null || (permissionStr = permissionStr.trim()).isEmpty()) {
				throw new IllegalArgumentException("The permission integer value ('permission') cannot be null or empty");
			}
			int permission = -1;
			try {
				permission = Integer.parseInt(permissionStr);
			} catch(NumberFormatException nbe) {
				classLogger.error(Constants.STACKTRACE, nbe);
				throw new IllegalArgumentException("Must pass a valid integer value. Received value = " + permissionStr);
			}
			
			String groupType = WebUtility.inputSanitizer(request.getParameter("type"));
			String endDate = WebUtility.inputSanitizer(request.getParameter("endDate"));

			AdminSecurityGroupUtils.getInstance(user).editGroupEnginePermission(user, groupId, groupType, engineId, permission, endDate);
			success = true;
		} catch (IllegalArgumentException e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorRet.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 500);
		}
		return WebUtility.getResponse(success, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("/removeGroupEnginePermission")
	@Operation(summary = "Revoke engine permission from group", description = "Removes an engine's permission for a group.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Permission removed",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "boolean"))
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
	public Response removeGroupEnginePermission(@Context HttpServletRequest request) {
		Map<String, String> errorRet = new Hashtable<>();
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to remove a group engine permission but couldn't find user session"));
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(!SecurityAdminUtils.userIsAdmin(user)){
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to remove a group engine permission but is not an admin"));
			errorRet.put(Constants.ERROR_MESSAGE, "The user doesn't have the permissions to perform this action.");
			return WebUtility.getResponse(errorRet, 400);
		}
		
		boolean success = false;
		try {
			String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
			if(groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id ('groupId') cannot be null or empty");
			}
			String engineId = WebUtility.inputSanitizer(request.getParameter("engineId"));
			if(engineId == null || (engineId = engineId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The project id ('projectId') cannot be null or empty");
			}
			String groupType = WebUtility.inputSanitizer(request.getParameter("type"));

			AdminSecurityGroupUtils.getInstance(user).removeGroupEnginePermission(user, groupId, groupType, engineId);
		} catch (IllegalArgumentException e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE, e);
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorRet.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 500);
		}
		return WebUtility.getResponse(success, 200);
	}
	
	@GET
	@Path("/getEnginesForGroup")
	@Produces("application/json")
	@Operation(summary = "List engines for group", description = "Returns engines assigned to the specified group with optional filters and pagination.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Engines retrieved",
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
	public Response getEnginesForGroup(@Context HttpServletRequest request, @QueryParam("groupId") String groupId, @QueryParam("groupType") String groupType, @QueryParam("searchTerm") String searchTerm,
			@QueryParam("limit") long limit, @QueryParam("offset") long offset) {
		groupType = WebUtility.inputSanitizer(groupType);
		groupId = WebUtility.inputSanitizer(groupId);
		searchTerm = WebUtility.inputSanitizer(searchTerm);
		AdminSecurityGroupUtils groupUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			groupUtils = AdminSecurityGroupUtils.getInstance(user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to get engines assinged to a group"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(groupId == null || (groupId=groupId.trim()).isEmpty()) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must define the group id");
			return WebUtility.getResponse(errorMap, 400);
		}
		
		try {
			List<Map<String, Object>> ret = groupUtils.getEnginesForGroup(groupId, groupType, searchTerm, limit, offset);
			return WebUtility.getResponse(ret, 200);
		} catch(IllegalArgumentException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorMap.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
	}
	
	@GET
	@Path("/getNumEnginesForGroup")
	@Produces("application/json")
	@Operation(summary = "Count engines for group", description = "Returns the number of engines assigned to the specified group.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Count returned",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "integer", format = "int64"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response getNumEnginesForGroup(@Context HttpServletRequest request, 
			@QueryParam("groupId") String groupId, @QueryParam("groupType") String groupType, @QueryParam("searchTerm") String searchTerm) {
		groupType = WebUtility.inputSanitizer(groupType);
		groupId = WebUtility.inputSanitizer(groupId);
		searchTerm = WebUtility.inputSanitizer(searchTerm);
		AdminSecurityGroupUtils groupUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			groupUtils = AdminSecurityGroupUtils.getInstance(user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to get engines assinged to a group"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(groupId == null || (groupId=groupId.trim()).isEmpty()) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must define the group id");
			return WebUtility.getResponse(errorMap, 400);
		}

		try {
			Long numEngines = groupUtils.getNumEnginesForGroup(groupId, groupType, searchTerm);
			return WebUtility.getResponse(numEngines, 200);
		} catch(IllegalArgumentException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorMap.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
	}
	
	@GET
	@Path("/getAvailableEnginesForGroup")
	@Produces("application/json")
	@Operation(summary = "List available engines for group", description = "Returns engines that can be assigned to the group.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Engines retrieved",
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
	public Response getAvailableEnginesForGroup(@Context HttpServletRequest request, 
			@QueryParam("groupId") String groupId, @QueryParam("groupType") String groupType, @QueryParam("searchTerm") String searchTerm,
			@QueryParam("limit") long limit, @QueryParam("offset") long offset) {
		groupType = WebUtility.inputSanitizer(groupType);
		groupId = WebUtility.inputSanitizer(groupId);
		searchTerm = WebUtility.inputSanitizer(searchTerm);	    
		AdminSecurityGroupUtils groupUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			groupUtils = AdminSecurityGroupUtils.getInstance(user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to get engines assinged to a group"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(groupId == null || (groupId=groupId.trim()).isEmpty()) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must define the group id");
			return WebUtility.getResponse(errorMap, 400);
		}

		try {
			List<Map<String, Object>> ret = groupUtils.getAvailableEnginesForGroup(groupId, groupType, searchTerm, limit, offset);
			return WebUtility.getResponse(ret, 200);
		} catch(IllegalArgumentException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorMap.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
	}
	
	@GET
	@Path("/getNumAvailableEnginesForGroup")
	@Produces("application/json")
	@Operation(summary = "Count available engines for group", description = "Returns the number of engines that can be assigned to the group.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Count returned",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "integer", format = "int64"))
		),
		@ApiResponse(responseCode = "401", description = "Unauthorized",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		),
		@ApiResponse(responseCode = "400", description = "Bad request",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))
		)
	})
	public Response getNumAvailableEnginesForGroup(@Context HttpServletRequest request, @QueryParam("groupId") String groupId, @QueryParam("groupType") String groupType, @QueryParam("searchTerm") String searchTerm) {
		groupType = WebUtility.inputSanitizer(groupType);
		groupId = WebUtility.inputSanitizer(groupId);
		searchTerm = WebUtility.inputSanitizer(searchTerm);
		AdminSecurityGroupUtils groupUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			groupUtils = AdminSecurityGroupUtils.getInstance(user);
		} catch (IllegalAccessException e) {
			classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to get engines assinged to a group"));
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(groupId == null || (groupId=groupId.trim()).isEmpty()) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must define the group id");
			return WebUtility.getResponse(errorMap, 400);
		}

		try {
			Long numEngines = groupUtils.getNumAvailableEnginesForGroup(groupId, groupType, searchTerm);
			return WebUtility.getResponse(numEngines, 200);
		} catch(IllegalArgumentException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please reach out to an admin.");
			errorMap.put(Constants.TECH_ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
	}
}
