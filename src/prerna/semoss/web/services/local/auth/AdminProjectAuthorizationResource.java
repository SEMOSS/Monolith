/*******************************************************************************
 * Copyright 2015 Defense Health Agency (DHA)
 *
 * If your use of this software does not include any GPLv2 components:
 * 	Licensed under the Apache License, Version 2.0 (the "License");
 * 	you may not use this file except in compliance with the License.
 * 	You may obtain a copy of the License at
 *
 * 	  http://www.apache.org/licenses/LICENSE-2.0
 *
 * 	Unless required by applicable law or agreed to in writing, software
 * 	distributed under the License is distributed on an "AS IS" BASIS,
 * 	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * 	See the License for the specific language governing permissions and
 * 	limitations under the License.
 * ----------------------------------------------------------------------------
 * If your use of this software includes any GPLv2 components:
 * 	This program is free software; you can redistribute it and/or
 * 	modify it under the terms of the GNU General Public License
 * 	as published by the Free Software Foundation; either version 2
 * 	of the License, or (at your option) any later version.
 *
 * 	This program is distributed in the hope that it will be useful,
 * 	but WITHOUT ANY WARRANTY; without even the implied warranty of
 * 	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * 	GNU General Public License for more details.
 *******************************************************************************/
package prerna.semoss.web.services.local.auth;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import prerna.auth.AccessToken;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.reactors.admin.AdminMyProjectsReactor;
import prerna.graph.utility.MsGraphUtility;
import prerna.io.connector.ms.MicrosoftGraphUserLookup;
import prerna.om.Insight;
import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.web.services.util.WebUtility;

@Path("/auth/admin/project")
@PermitAll
public class AdminProjectAuthorizationResource {

	@Context
	protected ServletContext context;

	/**
	 * Get the projects the user has access to
	 * 
	 * @param request
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getProjects")
	public Response getProjectsGET(@Context HttpServletRequest request,
			@QueryParam("projectId") List<String> projectFilter, @QueryParam("filterWord") String searchTerm,
			@QueryParam("limit") Integer limit, @QueryParam("offset") Integer offset,
			@QueryParam("metaKeys") List<String> metaKeys,
//			@QueryParam("metaFilters") Map<String, Object> metaFilters,
			@QueryParam("noMeta") Boolean noMeta, @QueryParam("userT") Boolean includeUserTracking,
			@QueryParam("permissionUser") String permissionUser,
			@QueryParam("effectivePermissions") List<Integer> effectivePermissions,
			@QueryParam("createdBy") String createdBy, @QueryParam("createdByMe") Boolean createdByMe) {
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		List<String> projects = WebUtility.inputSQLSanitizer(projectFilter);
		List<String> keys = WebUtility.inputSQLSanitizer(metaKeys);
		return ResourceUtility.respondAsAdmin(request, "list all projects", user -> {
			AdminMyProjectsReactor reactor = new AdminMyProjectsReactor();
			reactor.In();
			Insight temp = new Insight();
			temp.setUser(user);
			reactor.setInsight(temp);

			if (term != null) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(term, PixelDataType.CONST_STRING));
				reactor.getNounStore().addNoun(ReactorKeysEnum.FILTER_WORD.getKey(), struct);
			}
			if (limit != null) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(limit, PixelDataType.CONST_INT));
				reactor.getNounStore().addNoun(ReactorKeysEnum.LIMIT.getKey(), struct);
			}
			if (offset != null) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(offset, PixelDataType.CONST_INT));
				reactor.getNounStore().addNoun(ReactorKeysEnum.OFFSET.getKey(), struct);
			}
			if (projects != null && !projects.isEmpty()) {
				GenRowStruct struct = new GenRowStruct();
				for (String engine : projects) {
					struct.add(new NounMetadata(engine, PixelDataType.CONST_STRING));
				}
				reactor.getNounStore().addNoun(ReactorKeysEnum.PROJECT.getKey(), struct);
			}
			if (keys != null && !keys.isEmpty()) {
				GenRowStruct struct = new GenRowStruct();
				for (String metaK : keys) {
					struct.add(new NounMetadata(metaK, PixelDataType.CONST_STRING));
				}
				reactor.getNounStore().addNoun(ReactorKeysEnum.META_KEYS.getKey(), struct);
			}
//			if(metaFilters != null) {
//				GenRowStruct struct = new GenRowStruct();
//				struct.add(new NounMetadata(metaFilters, PixelDataType.MAP));
//				reactor.getNounStore().addNoun(ReactorKeysEnum.META_FILTERS.getKey(), struct);
//			}
			if (noMeta != null) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(noMeta, PixelDataType.BOOLEAN));
				reactor.getNounStore().addNoun(ReactorKeysEnum.NO_META.getKey(), struct);
			}
			if (includeUserTracking != null) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(includeUserTracking, PixelDataType.BOOLEAN));
				reactor.getNounStore().addNoun(ReactorKeysEnum.INCLUDE_USERTRACKING_KEY.getKey(), struct);
			}

			if (permissionUser != null && !permissionUser.trim().isEmpty()) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(permissionUser.trim(), PixelDataType.CONST_STRING));
				reactor.getNounStore().addNoun("permissionUser", struct);
			}
			if (effectivePermissions != null && !effectivePermissions.isEmpty()) {
				GenRowStruct struct = new GenRowStruct();
				for (Integer permission : effectivePermissions) {
					struct.add(new NounMetadata(permission, PixelDataType.CONST_INT));
				}
				reactor.getNounStore().addNoun("effectivePermissions", struct);
			}
			if (createdBy != null && !createdBy.trim().isEmpty()) {
				GenRowStruct struct = new GenRowStruct();
				for (Object creator : new Gson().fromJson(createdBy, List.class)) {
					struct.add(new NounMetadata(creator, PixelDataType.MAP));
				}
				reactor.getNounStore().addNoun("createdBy", struct);
			}
			if (createdByMe != null) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(createdByMe, PixelDataType.BOOLEAN));
				reactor.getNounStore().addNoun("createdByMe", struct);
			}

			NounMetadata outputNoun = reactor.execute();
			return outputNoun.getValue();
		});
	}

	@POST
	@Produces("application/json")
	@Path("getProjects")
	public Response getProjectsPOST(@Context HttpServletRequest request) {
		return ResourceUtility.respondAsAdmin(request, "list all projects", user -> {
			AdminMyProjectsReactor reactor = new AdminMyProjectsReactor();
			reactor.In();
			Insight temp = new Insight();
			temp.setUser(user);
			reactor.setInsight(temp);

			Map<String, String[]> parameterMap = request.getParameterMap();

			if (parameterMap.containsKey("filterWord") && parameterMap.get("filterWord") != null
					&& parameterMap.get("filterWord").length > 0) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(parameterMap.get("filterWord")[0], PixelDataType.CONST_STRING));
				reactor.getNounStore().addNoun(ReactorKeysEnum.FILTER_WORD.getKey(), struct);
			}
			if (parameterMap.containsKey("limit") && parameterMap.get("limit") != null
					&& parameterMap.get("limit").length > 0) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(parameterMap.get("limit")[0], PixelDataType.CONST_INT));
				reactor.getNounStore().addNoun(ReactorKeysEnum.LIMIT.getKey(), struct);
			}
			if (parameterMap.containsKey("offset") && parameterMap.get("offset") != null
					&& parameterMap.get("offset").length > 0) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(parameterMap.get("offset")[0], PixelDataType.CONST_INT));
				reactor.getNounStore().addNoun(ReactorKeysEnum.OFFSET.getKey(), struct);
			}
			if (parameterMap.containsKey("projectId") && parameterMap.get("projectId") != null
					&& parameterMap.get("projectId").length > 0) {
				GenRowStruct struct = new GenRowStruct();
				String[] projectFilter = parameterMap.get("projectId");
				for (String project : projectFilter) {
					struct.add(new NounMetadata(project, PixelDataType.CONST_STRING));
				}
				reactor.getNounStore().addNoun(ReactorKeysEnum.PROJECT.getKey(), struct);
			}
			if (parameterMap.containsKey("metaKeys") && parameterMap.get("metaKeys") != null
					&& parameterMap.get("metaKeys").length > 0) {
				GenRowStruct struct = new GenRowStruct();
				String[] metaKeys = parameterMap.get("metaKeys");
				for (String metaK : metaKeys) {
					struct.add(new NounMetadata(metaK, PixelDataType.CONST_STRING));
				}
				reactor.getNounStore().addNoun(ReactorKeysEnum.META_KEYS.getKey(), struct);
			}
			if (parameterMap.containsKey("metaFilters") && parameterMap.get("metaFilters") != null
					&& parameterMap.get("metaFilters").length > 0) {
				Map<String, Object> metaFilters = new Gson().fromJson(parameterMap.get("metaFilters")[0], Map.class);
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(metaFilters, PixelDataType.MAP));
				reactor.getNounStore().addNoun(ReactorKeysEnum.META_FILTERS.getKey(), struct);
			}
			if (parameterMap.containsKey("noMeta") && parameterMap.get("noMeta") != null
					&& parameterMap.get("noMeta").length > 0) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(parameterMap.get("noMeta")[0], PixelDataType.BOOLEAN));
				reactor.getNounStore().addNoun(ReactorKeysEnum.NO_META.getKey(), struct);
			}
			if (parameterMap.containsKey("userT") && parameterMap.get("userT") != null
					&& parameterMap.get("userT").length > 0) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(parameterMap.get("userT")[0], PixelDataType.BOOLEAN));
				reactor.getNounStore().addNoun(ReactorKeysEnum.INCLUDE_USERTRACKING_KEY.getKey(), struct);
			}

			if (parameterMap.containsKey("permissionUser") && parameterMap.get("permissionUser") != null
					&& parameterMap.get("permissionUser").length > 0) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(parameterMap.get("permissionUser")[0].trim(), PixelDataType.CONST_STRING));
				reactor.getNounStore().addNoun("permissionUser", struct);
			}
			if (parameterMap.containsKey("effectivePermissions") && parameterMap.get("effectivePermissions") != null
					&& parameterMap.get("effectivePermissions").length > 0) {
				GenRowStruct struct = new GenRowStruct();
				for (String permission : parameterMap.get("effectivePermissions")) {
					struct.add(new NounMetadata(Integer.valueOf(permission.trim()), PixelDataType.CONST_INT));
				}
				reactor.getNounStore().addNoun("effectivePermissions", struct);
			}
			if (parameterMap.containsKey("createdBy") && parameterMap.get("createdBy") != null
					&& parameterMap.get("createdBy").length > 0) {
				GenRowStruct struct = new GenRowStruct();
				for (Object creator : new Gson().fromJson(parameterMap.get("createdBy")[0], List.class)) {
					struct.add(new NounMetadata(creator, PixelDataType.MAP));
				}
				reactor.getNounStore().addNoun("createdBy", struct);
			}
			if (parameterMap.containsKey("createdByMe") && parameterMap.get("createdByMe") != null
					&& parameterMap.get("createdByMe").length > 0) {
				GenRowStruct struct = new GenRowStruct();
				struct.add(new NounMetadata(Boolean.parseBoolean(parameterMap.get("createdByMe")[0].trim()),
						PixelDataType.BOOLEAN));
				reactor.getNounStore().addNoun("createdByMe", struct);
			}

			NounMetadata outputNoun = reactor.execute();
			return outputNoun.getValue();
		});
	}

	@GET
	@Path("/getAllUserProjects")
	@Produces("application/json")
	public Response getAllUserProjects(@Context HttpServletRequest request, @QueryParam("userId") String userId,
			@QueryParam("projectTypes") List<String> projectTypes, @QueryParam("searchTerm") String searchTerm,
			@QueryParam("limit") long limit, @QueryParam("offset") long offset) {
		String id = WebUtility.inputSQLSanitizer(userId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		List<String> types = WebUtility.inputSanitizer(projectTypes);
		return ResourceUtility.respondAsAdmin(request, "list the projects of user " + id,
				SecurityAdminUtils::getInstance,
				(user, adminUtils) -> adminUtils.getAllUserProjects(id, types, term, limit, offset));
	}

	@GET
	@Path("/getUserProjectsNoCredentials")
	@Produces("application/json")
	public Response getUserProjectsNoCredentials(@Context HttpServletRequest request,
			@QueryParam("userId") String userId, @QueryParam("projectTypes") List<String> projectTypes,
			@QueryParam("searchTerm") String searchTerm, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {
		String id = WebUtility.inputSQLSanitizer(userId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		List<String> types = WebUtility.inputSanitizer(projectTypes);
		return ResourceUtility.respondAsAdmin(request, "list the projects user " + id + " does not have access to",
				SecurityAdminUtils::getInstance,
				(user, adminUtils) -> adminUtils.getUserProjectsNoCredentials(id, types, term, limit, offset));
	}

	@POST
	@Path("/grantAllProjects")
	@Produces("application/json")
	public Response grantAllProjects(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String userId = WebUtility.inputSQLSanitizer(form.getFirst("userId"));
		String permission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		boolean isAddNew = Boolean.parseBoolean(form.getFirst("isAddNew") + "");
		return ResourceUtility.respondAsAdmin(request,
				"grant all projects to user " + userId + " with permission " + permission,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					adminUtils.grantAllProjects(userId, permission, isAddNew, user);
					return success();
				});
	}

	@POST
	@Path("/grantNewUsersProjectAccess")
	@Produces("application/json")
	public Response grantNewUsersProjectAccess(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String permission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String endDate = null; // form.getFirst("endDate");
		return ResourceUtility.respondAsAdmin(request,
				"grant project " + projectId + " to new users with permission " + permission,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					adminUtils.grantNewUsersProjectAccess(projectId, permission, user, endDate);
					return success();
				});
	}

	/**
	 * Get the project users and their permissions
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getProjectUsers")
	public Response getProjectUsers(@Context HttpServletRequest request, @QueryParam("projectId") String projectId,
			@QueryParam("userId") String userId, @QueryParam("searchTerm") String searchTerm,
			@QueryParam("permission") String permission, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {
		String project = WebUtility.inputSQLSanitizer(projectId);
		String id = WebUtility.inputSQLSanitizer(userId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		String level = WebUtility.inputSQLSanitizer(permission);
		return ResourceUtility.respondAsAdmin(request, "list the users of project " + project,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					String searchParam = term != null ? term : id;
					List<Map<String, Object>> members = adminUtils.getProjectUsers(project, searchParam, level, limit,
							offset);
					long totalMembers = adminUtils.getProjectUsersCount(project, searchParam, level);
					Map<String, Object> ret = new HashMap<String, Object>();
					ret.put("totalMembers", totalMembers);
					ret.put("members", members);
					return ret;
				});
	}

	/**
	 * Add a user to an project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addProjectUserPermission")
	public Response addProjectUserPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String newUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String permission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");
		return ResourceUtility.respondAsAdmin(request,
				"add user " + newUserId + " to project " + projectId + " with permission " + permission,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					adminUtils.addProjectUser(newUserId, projectId, permission, user, endDate);
					return success();
				});
	}

	/**
	 * Add all users to an project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addAllUsers")
	public Response addAllUsers(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String permission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");
		return ResourceUtility.respondAsAdmin(request,
				"add all users to project " + projectId + " with permission " + permission,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					adminUtils.addAllProjectUsers(projectId, permission, user, endDate);
					return success();
				});
	}

	/**
	 * Edit user permission for an project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editProjectUserPermission")
	public Response editProjectUserPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String newPermission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");
		return ResourceUtility.respondAsAdmin(request,
				"change the permission of user " + existingUserId + " on project " + projectId + " to " + newPermission,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					adminUtils.editProjectUserPermission(existingUserId, projectId, newPermission, user, endDate);
					return success();
				});
	}

	/**
	 * Edit user permission for a project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editProjectUserPermissions")
	public Response editProjectUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String endDate = null; // form.getFirst("endDate");
		return ResourceUtility.respondAsAdmin(request, "edit the user permissions on project " + projectId,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					List<Map<String, String>> requests = new Gson().fromJson(form.getFirst("userpermissions"),
							List.class);
					adminUtils.editProjectUserPermissions(projectId, requests, user, endDate);
					return success();
				});
	}

	/**
	 * update all user's permission level to new permission level for an project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("updateProjectUserPermissions")
	public Response updateProjectUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String newPermission = WebUtility.inputSQLSanitizer(form.getFirst("permission"));
		String endDate = null; // form.getFirst("endDate");
		return ResourceUtility.respondAsAdmin(request,
				"change the permission of every user on project " + projectId + " to " + newPermission,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					adminUtils.updateProjectUserPermissions(projectId, newPermission, user, endDate);
					return success();
				});
	}

	/**
	 * Remove user permission for an project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeProjectUserPermission")
	public Response removeProjectUserPermission(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String existingUserId = WebUtility.inputSQLSanitizer(form.getFirst("id"));
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		return ResourceUtility.respondAsAdmin(request, "remove user " + existingUserId + " from project " + projectId,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					adminUtils.removeProjectUser(existingUserId, projectId);
					return success();
				});
	}

	/**
	 * Set whether a project can be cloned as a template by users who can view it.
	 *
	 * @param request current request
	 * @param form    projectId and template boolean
	 * @return operation status
	 */
	@POST
	@Produces("application/json")
	@Path("setProjectTemplate")
	public Response setProjectTemplate(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String projectIdInput = form.getFirst("projectId");
		String templateInput = form.getFirst("template");
		String projectId = WebUtility.inputSQLSanitizer(projectIdInput);
		boolean isTemplate = Boolean.parseBoolean(templateInput);
		return ResourceUtility.respondAsAdmin(request,
				"set the template status of project " + projectId + " to " + isTemplate,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					boolean validTemplate = "true".equalsIgnoreCase(templateInput)
							|| "false".equalsIgnoreCase(templateInput);
					if (projectIdInput == null || projectIdInput.trim().isEmpty() || !validTemplate) {
						throw new IllegalArgumentException("projectId and a true or false template value are required");
					}
					adminUtils.setProjectTemplate(projectId, isTemplate);
					return success();
				});
	}

	@POST
	@Produces("application/json")
	@Path("setProjectGlobal")
	public Response setProjectGlobal(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		boolean isPublic = Boolean.parseBoolean(form.getFirst("public"));
		return ResourceUtility.respondAsAdmin(request,
				"make project " + projectId + (isPublic ? " public" : " private"), SecurityAdminUtils::getInstance,
				(user, adminUtils) -> {
					adminUtils.setProjectGlobal(projectId, isPublic);
					return success();
				});
	}

	/**
	 * Set the project as being discoverable for the entire semoss instance
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setProjectDiscoverable")
	public Response setProjectDiscoverable(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		boolean isDiscoverable = Boolean.parseBoolean(form.getFirst("discoverable"));
		return ResourceUtility.respondAsAdmin(request,
				"make project " + projectId + (isDiscoverable ? " discoverable" : " not discoverable"),
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					adminUtils.setProjectDiscoverable(projectId, isDiscoverable);
					return success();
				});
	}

	/**
	 * Get users with no access to a given project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getProjectUsersNoCredentials")
	public Response getProjectUsersNoCredentials(@Context HttpServletRequest request,
			@QueryParam("projectId") String projectId, @QueryParam("searchTerm") String searchTerm,
			@QueryParam("limit") long limit, @QueryParam("offset") long offset,
			@QueryParam(MicrosoftGraphUserLookup.LOOKUP_PARAM) String msGraphLookup) {
		String project = WebUtility.inputSQLSanitizer(projectId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respondAsAdmin(request, "search for people to add to project " + project,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					// when the directory is not used
					// then we will look at our security db
					if (!MicrosoftGraphUserLookup.useDirectory(msGraphLookup)) {
						return adminUtils.getProjectUsersNoCredentials(project, term, limit, offset);
					}
					try {
						return MsGraphUtility.getProjectUsers(request, user, project, term, limit, offset, true);
					} catch (IllegalAccessException e) {
						// not being signed in to Microsoft is a bad request, not a missing session
						throw new IllegalArgumentException(e.getMessage(), e);
					}
				});
	}

	/**
	 * Admin approval of user access requests
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("approveProjectUserAccessRequest")
	public Response approveProjectUserAccessRequest(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		String endDate = null; // form.getFirst("endDate");
		return ResourceUtility.respondAsAdmin(request, "approve the access requests to project " + projectId,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					// adding user permissions and updating user access requests in bulk
					List<Map<String, Object>> requests = new Gson().fromJson(form.getFirst("requests"), List.class);
					AccessToken token = user.getAccessToken(user.getPrimaryLogin());
					String userId = token.getId();
					String userType = token.getProvider().toString();
					adminUtils.approveProjectUserAccessRequests(userId, userType, projectId, requests, endDate);
					return success();
				});
	}

	/**
	 * Admin deny of user access requests
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("denyProjectUserAccessRequest")
	public Response denyProjectUserAccessRequest(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		return ResourceUtility.respondAsAdmin(request, "deny the access requests to project " + projectId,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					// updating user access requests in bulk
					List<String> requestids = new Gson().fromJson(form.getFirst("requestids"), List.class);
					AccessToken token = user.getAccessToken(user.getPrimaryLogin());
					String userId = token.getId();
					String userType = token.getProvider().toString();
					adminUtils.denyProjectUserAccessRequests(userId, userType, projectId, requestids);
					return success();
				});
	}

	/**
	 * Add user permissions in bulk to a project
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addProjectUserPermissions")
	public Response addProjectUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		return ResourceUtility.respondAsAdmin(request, "add user permissions to project " + projectId,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					// adding user permissions in bulk
					List<Map<String, String>> permission = new Gson().fromJson(form.getFirst("userpermissions"),
							List.class);
					// users picked from the Microsoft directory may not be in the security db yet
					if (MicrosoftGraphUserLookup.isEnabled()) {
						MicrosoftGraphUserLookup.addMissingUsers(user, permission);
					}
					adminUtils.addProjectUserPermissions(projectId, permission, user);
					return success();
				});
	}

	/**
	 * Remove user permissions for a project, in bulk
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeProjectUserPermissions")
	public Response removeProjectUserPermissions(@Context HttpServletRequest request,
			MultivaluedMap<String, String> form) {
		String projectId = WebUtility.inputSQLSanitizer(form.getFirst("projectId"));
		return ResourceUtility.respondAsAdmin(request, "remove users from project " + projectId,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					Gson gson = new Gson();
					List<String> ids = gson.fromJson(form.getFirst("ids"), List.class);
					ids = WebUtility.inputSQLSanitizer(ids);
					adminUtils.removeProjectUsers(ids, projectId);
					return success();
				});
	}

	/**
	 * @return the body of a change that was made
	 */
	private static Map<String, Object> success() {
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return ret;
	}

}
