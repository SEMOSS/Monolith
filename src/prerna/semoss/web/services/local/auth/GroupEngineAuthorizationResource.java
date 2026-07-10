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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.AccessPermissionEnum;
import prerna.auth.User;
import prerna.auth.utils.SecurityGroupEngineUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("/auth/group/engine")
@PermitAll
public class GroupEngineAuthorizationResource {

	private static final Logger classLogger = LogManager.getLogger(GroupEngineAuthorizationResource.class);

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

		type = WebUtility.inputSanitizer(type);
		appId = WebUtility.inputSanitizer(appId);
		groupId = WebUtility.inputSQLSanitizer(groupId);

		Map<String, String> errorMap = new HashMap<String, String>();
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			if (groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id cannot be null or empty");
			}
			if (type == null || (type = type.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group type cannot be null or empty");
			}
			if (appId == null || (appId = appId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The appId cannot be null or empty");
			}

			Integer permissionCode = SecurityGroupEngineUtils.getGroupDatabasePermission(groupId, type, appId);
			String permission = permissionCode == null ? null
					: AccessPermissionEnum.getPermissionValueById(permissionCode);

			Map<String, String> ret = new HashMap<String, String>();
			ret.put("permission", permission);
			return WebUtility.getResponse(ret, 200);
		} catch (IllegalArgumentException e) {
			classLogger.error("Failed to retrieve group app permission.", e);
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Failed to retrieve group app permission.", e);
			errorMap.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorMap, 500);
		}
	}

	/**
	 * Add a group to an app
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("addGroupEnginePermission")
	public Response addGroupEnginePermission(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = WebUtility.inputSanitizer(form.getFirst("type"));
		String engineId = WebUtility.inputSanitizer(form.getFirst("engineId"));
		String permission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));

		try {
			if (groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id cannot be null or empty");
			}
			if (type == null || (type = type.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group type cannot be null or empty");
			}
			if (engineId == null || (engineId = engineId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The engineId cannot be null or empty");
			}
			if (permission == null || (permission = permission.trim()).isEmpty()) {
				throw new IllegalArgumentException("The permission cannot be null or empty");
			}

			SecurityGroupEngineUtils.addEngineGroupPermission(user, groupId, type, engineId, permission, endDate);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to add groups to engine {} without having proper access", engineId);
			classLogger.error("Failed to add group engine permission.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Failed to add group engine permission.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has added group {} and type {} to engine {} with permission {}", groupId, type, engineId,
				permission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
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
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = WebUtility.inputSanitizer(form.getFirst("type"));
		String appId = WebUtility.inputSanitizer(form.getFirst("appId"));
		String newPermission = WebUtility.inputSanitizer(form.getFirst("permission"));
		String endDate = WebUtility.inputSanitizer(form.getFirst("endDate"));
		try {
			if (groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id cannot be null or empty");
			}
			if (type == null || (type = type.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group type cannot be null or empty");
			}
			if (appId == null || (appId = appId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The appId cannot be null or empty");
			}
			if (newPermission == null || (newPermission = newPermission.trim()).isEmpty()) {
				throw new IllegalArgumentException("The permission cannot be null or empty");
			}
			SecurityGroupEngineUtils.editDatabaseGroupPermission(user, groupId, type, appId, newPermission, endDate);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to edit group {} and type {} permissions for app {} without having proper access",
					groupId, type, appId);
			classLogger.error("Failed to update group app permission.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Failed to update group app permission.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has edited group {} and type {} permission to app {} with level {}", groupId, type,
				appId, newPermission);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
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
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		String groupId = WebUtility.inputSQLSanitizer(form.getFirst("groupId"));
		String type = WebUtility.inputSanitizer(form.getFirst("type"));
		String appId = WebUtility.inputSanitizer(form.getFirst("appId"));
		try {
			if (groupId == null || (groupId = groupId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group id cannot be null or empty");
			}
			if (type == null || (type = type.trim()).isEmpty()) {
				throw new IllegalArgumentException("The group type cannot be null or empty");
			}
			if (appId == null || (appId = appId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The appId cannot be null or empty");
			}

			SecurityGroupEngineUtils.removeDatabaseGroupPermission(user, groupId, type, appId);
		} catch (IllegalAccessException e) {
			classLogger.warn(
					"User is trying to remove group {} and type {} from having access to app {} without having proper access",
					groupId, type, appId);
			classLogger.error("Failed to remove group app permission.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Failed to remove group app permission.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// log the operation
		classLogger.info("User has removed group {} and type {} from having access to app {}", groupId, type, appId);

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	@GET
	@Produces("application/json")
	@Path("getGroupsWithAccessToEngine")
	public Response getAllGroupsWithAccessToEngine(@Context HttpServletRequest request,
			@QueryParam("engineId") String engineId, @QueryParam("limit") long limit,
			@QueryParam("offset") long offset) {
		engineId = WebUtility.inputSanitizer(engineId);
		if (engineId == null || engineId.isEmpty()) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must define the engineId");
			return WebUtility.getResponse(errorMap, 400);
		}
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session trying to access authorization resources", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		Map<String, Object> ret = new HashMap<String, Object>();
		try {
			List<Map<String, Object>> groups = SecurityGroupEngineUtils.getGroupsWithAccessToEngine(user, engineId,
					limit, offset);
			long totalMembers = SecurityGroupEngineUtils.getNumGroupsWithAccessToEngine(user, engineId);
			ret.put("totalGroups", totalMembers);
			ret.put("groups", groups);
			return WebUtility.getResponse(ret, 200);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to get details for engine {} without having proper access", engineId);
			classLogger.error("Failed to retrieve all groups with access to engine.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		} catch (Exception e) {
			classLogger.error("Failed to retrieve all groups with access to engine.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
	}

}
