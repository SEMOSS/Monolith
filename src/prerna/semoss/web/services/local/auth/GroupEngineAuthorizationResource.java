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
import prerna.auth.AccessPermissionEnum;
import prerna.auth.AuthProvider;
import prerna.auth.utils.SecurityGroupEngineUtils;
import prerna.auth.utils.SecurityModelUsageLimitUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.ValueUtils;
import prerna.web.services.util.WebUtility;

@Path("/auth/group/engine")
@PermitAll
public class GroupEngineAuthorizationResource {

	private static final String GROUP_ID_REQUIRED = "The group id cannot be null or empty";
	private static final String GROUP_TYPE_REQUIRED = "The group type cannot be null or empty";
	private static final String APP_ID_REQUIRED = "The appId cannot be null or empty";
	private static final String PERMISSION_REQUIRED = "The permission cannot be null or empty";
	/** The form field for a team limit on a model, as JSON */
	private static final String USAGE_LIMIT = "usageLimit";

	@Context
	protected ServletContext context;

	/**
	 * Get the group app permission level
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getGroupAppPermission")
	public Response getGroupAppPermission(@Context HttpServletRequest request, @QueryParam("groupId") String groupId,
			@QueryParam("type") String type, @QueryParam("appId") String appId) {
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSanitizer(type));
		String engine = WebUtility.inputSanitizer(appId);
		String group = WebUtility.inputSQLSanitizer(groupId);
		return ResourceUtility.respondAllowingAnonymous(request,
				"read the permission of group " + group + " on engine " + engine, user -> {
					Integer permissionCode = SecurityGroupEngineUtils.getGroupDatabasePermission(
							ValueUtils.requireNonBlank(group, GROUP_ID_REQUIRED),
							ValueUtils.requireNonBlank(groupType, GROUP_TYPE_REQUIRED),
							ValueUtils.requireNonBlank(engine, APP_ID_REQUIRED));
					String permission = permissionCode == null ? null
							: AccessPermissionEnum.getPermissionValueById(permissionCode);

					Map<String, String> ret = new HashMap<>();
					ret.put("permission", permission);
					return ret;
				});
	}

	/**
	 * Add a group to an app. On a model, {@code usageLimit} (JSON, empty for no
	 * limit defined) is the team limit, with shared and each-member rules.
	 *
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addGroupEnginePermission")
	public Response addGroupEnginePermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = AuthProvider.getProviderLabel(WebUtility.inputSanitizer(form.getFirst("type")));
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));
		boolean usageLimitSent = form.containsKey(USAGE_LIMIT);
		String usageLimit = form.getFirst(USAGE_LIMIT);
		return ResourceUtility.respond(request, "add group " + groupId + " to engine " + engineId,
				new ResourceUtility.SecurityAuditEvent("PERMISSION_GRANTED", "ENGINE", engineId), user -> {
			String group = ValueUtils.requireNonBlank(groupId, GROUP_ID_REQUIRED);
			String groupType = ValueUtils.requireNonBlank(type, GROUP_TYPE_REQUIRED);
			String engine = ValueUtils.requireNonBlank(engineId, "The engineId cannot be null or empty");
			String level = ValueUtils.requireNonBlank(permission, PERMISSION_REQUIRED);
			if (usageLimitSent) {
				SecurityGroupEngineUtils.addEngineGroupPermission(user, group, groupType, engine, level, endDate,
						SecurityModelUsageLimitUtils.readJson(usageLimit));
			} else {
				SecurityGroupEngineUtils.addEngineGroupPermission(user, group, groupType, engine, level, endDate);
			}

			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
	}

	/**
	 * Edit group permission for an app
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("editGroupAppPermission")
	public Response editGroupAppPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = AuthProvider.getProviderLabel(WebUtility.inputSanitizer(form.getFirst("type")));
		String appId = WebUtility.inputSanitizer(form.getFirst("appId"));
		String newPermission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));
		return ResourceUtility.respond(request, "edit the permission of group " + groupId + " on engine " + appId,
				new ResourceUtility.SecurityAuditEvent("PERMISSION_MODIFIED", "ENGINE", appId),
				user -> {
					SecurityGroupEngineUtils.editDatabaseGroupPermission(user,
							ValueUtils.requireNonBlank(groupId, GROUP_ID_REQUIRED),
							ValueUtils.requireNonBlank(type, GROUP_TYPE_REQUIRED),
							ValueUtils.requireNonBlank(appId, APP_ID_REQUIRED),
							ValueUtils.requireNonBlank(newPermission, PERMISSION_REQUIRED), endDate);

					Map<String, Object> ret = new HashMap<>();
					ret.put("success", true);
					return ret;
				});
	}

	/**
	 * Remove group permission for an app
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("removeGroupAppPermission")
	public Response removeGroupAppPermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = AuthProvider.getProviderLabel(WebUtility.inputSanitizer(form.getFirst("type")));
		String appId = WebUtility.inputSanitizer(form.getFirst("appId"));
		return ResourceUtility.respond(request, "remove group " + groupId + " from engine " + appId,
				new ResourceUtility.SecurityAuditEvent("PERMISSION_REVOKED", "ENGINE", appId), user -> {
			SecurityGroupEngineUtils.removeDatabaseGroupPermission(user,
					ValueUtils.requireNonBlank(groupId, GROUP_ID_REQUIRED),
					ValueUtils.requireNonBlank(type, GROUP_TYPE_REQUIRED),
					ValueUtils.requireNonBlank(appId, APP_ID_REQUIRED));

			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			return ret;
		});
	}

	@GET
	@Produces("application/json")
	@Path("getGroupsWithAccessToEngine")
	public Response getAllGroupsWithAccessToEngine(@Context HttpServletRequest request,
			@QueryParam("engineId") String engineId, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {
		String engine = WebUtility.inputSanitizer(engineId);
		return ResourceUtility.respondAllowingAnonymous(request, "list the groups with access to engine " + engine,
				user -> {
					if (engine == null || engine.isEmpty()) {
						throw new IllegalArgumentException("Must define the engineId");
					}
					List<Map<String, Object>> groups = SecurityGroupEngineUtils.getGroupsWithAccessToEngine(user,
							engine, limit, offset);
					long totalGroups = SecurityGroupEngineUtils.getNumGroupsWithAccessToEngine(user, engine);
					Map<String, Object> ret = new HashMap<>();
					ret.put("totalGroups", totalGroups);
					ret.put("groups", groups);
					return ret;
				});
	}

	/**
	 * Groups that do not have access to an engine yet, for its owners to choose
	 * from
	 *
	 * @param request
	 * @param engineId
	 * @param searchTerm
	 * @param limit
	 * @param offset
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("getAvailableGroupsForEngine")
	public Response getAvailableGroupsForEngine(@Context HttpServletRequest request,
			@QueryParam("engineId") String engineId, @QueryParam("searchTerm") String searchTerm,
			@QueryParam("limit") long limit, @QueryParam("offset") long offset) {
		String engine = WebUtility.inputSanitizer(engineId);
		String term = WebUtility.inputSQLSanitizer(searchTerm);
		return ResourceUtility.respondAllowingAnonymous(request,
				"list the groups that can be given access to engine " + engine, user -> {
					if (engine == null || engine.isEmpty()) {
						throw new IllegalArgumentException("Must define the engineId");
					}
					return SecurityGroupEngineUtils.getAvailableGroupsForEngine(user, engine, term, limit, offset);
				});
	}

}
