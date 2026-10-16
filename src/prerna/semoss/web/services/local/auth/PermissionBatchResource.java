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

import java.util.Locale;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import prerna.auth.access.PermissionTargetResource;
import prerna.auth.utils.EffectivePermissionUtils;
import prerna.auth.utils.SecurityAccessPermissionBatchUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.ValueUtils;
import prerna.web.services.util.WebUtility;

/**
 * Bulk Access Management: make many changes to who can use projects, engines
 * and insights, and to who is in custom groups, then validate and save them
 * together. Also reads who can use a project or engine in the end, through any
 * source, and how usage limits apply to one person on a model. A batch is sent
 * as JSON in the {@code batches} form field; see
 * {@link SecurityAccessPermissionBatchUtils#readBatches(String)}.
 * {@link SecurityAccessPermissionBatchUtils} checks every change against the
 * same rules as the single permission endpoints, with the user's own
 * permissions: being an admin counts for nothing here, and
 * {@link AdminPermissionBatchResource} is the admin mode. Anonymous sessions
 * can do nothing here.
 */
@Path("/auth/permissionBatch")
@PermitAll
public class PermissionBatchResource {

	/**
	 * Validates batches without saving them.
	 */
	@POST
	@Produces("application/json")
	@Path("/validatePermissionBatches")
	public Response validatePermissionBatches(@Context HttpServletRequest request) {
		String batches = request.getParameter("batches");
		return ResourceUtility.respond(request, "validate a permission batch",
				user -> SecurityAccessPermissionBatchUtils.validateBatches(user,
						SecurityAccessPermissionBatchUtils.readBatches(batches)));
	}

	/**
	 * Saves batches in one transaction, or none of them when any change cannot be
	 * made.
	 */
	@POST
	@Produces("application/json")
	@Path("/savePermissionBatches")
	public Response savePermissionBatches(@Context HttpServletRequest request) {
		String batches = request.getParameter("batches");
		return ResourceUtility.respond(request, "save a permission batch", user -> SecurityAccessPermissionBatchUtils
				.saveBatches(user, SecurityAccessPermissionBatchUtils.readBatches(batches)));
	}

	/**
	 * Who can use a project or an engine now, or who is in or manages a custom
	 * group, in the shape batches use, and what the signed in user may change.
	 *
	 * @param resourceType {@code PROJECT}, {@code ENGINE}, {@code TEAM} or
	 *                     {@code TEAM_MANAGERS}
	 */
	@GET
	@Produces("application/json")
	@Path("/getPermissions")
	public Response getPermissions(@Context HttpServletRequest request, @QueryParam("resourceType") String resourceType,
			@QueryParam("id") String id) {
		String resource = WebUtility.inputSQLSanitizer(id);
		return ResourceUtility.respond(request, "read the permissions on " + resource,
				user -> SecurityAccessPermissionBatchUtils.getPermissions(user, toTarget(resourceType, resource)));
	}

	/**
	 * The people to copy from a project, engine or custom group into another, with
	 * the access each has on both.
	 *
	 * @param sourceResourceType {@code PROJECT}, {@code ENGINE}, {@code TEAM} or
	 *                           {@code TEAM_MANAGERS}
	 * @param targetResourceType {@code PROJECT}, {@code ENGINE}, {@code TEAM} or
	 *                           {@code TEAM_MANAGERS}
	 */
	@GET
	@Produces("application/json")
	@Path("/getPermissionsToCopy")
	public Response getPermissionsToCopy(@Context HttpServletRequest request,
			@QueryParam("sourceResourceType") String sourceResourceType, @QueryParam("sourceId") String sourceId,
			@QueryParam("targetResourceType") String targetResourceType, @QueryParam("targetId") String targetId) {
		String source = WebUtility.inputSQLSanitizer(sourceId);
		String target = WebUtility.inputSQLSanitizer(targetId);
		return ResourceUtility.respond(request, "read the permissions to copy from " + source + " to " + target,
				user -> SecurityAccessPermissionBatchUtils.getPermissionsToCopy(user,
						toTarget(sourceResourceType, source), toTarget(targetResourceType, target)));
	}

	/**
	 * Everyone who can use a project or an engine in the end, one row per person,
	 * with the access they get directly and through each custom group.
	 *
	 * @param resourceType {@code PROJECT} or {@code ENGINE}
	 */
	@GET
	@Produces("application/json")
	@Path("/getEffectivePermissions")
	public Response getEffectivePermissions(@Context HttpServletRequest request,
			@QueryParam("resourceType") String resourceType, @QueryParam("id") String id) {
		String resource = WebUtility.inputSQLSanitizer(id);
		return ResourceUtility.respond(request, "read the effective permissions on " + resource,
				user -> EffectivePermissionUtils.getEffectivePermissions(user, toTarget(resourceType, resource)));
	}

	/**
	 * How usage limits apply to one person on a model: the ceilings, then each
	 * source in billing order with its state, rules and use. For the model's
	 * editors and owners, about a person with a grant on the model, their own or a
	 * team's. The all-models default and platform default steps leave out their
	 * {@code rules} and say {@code "rulesHidden": true}, and team steps leave out
	 * {@code teamTotal}, since those limits count use on other models.
	 *
	 * @param engineId the model
	 * @param userId   the person
	 * @param userType the person's login type, required
	 */
	@GET
	@Produces("application/json")
	@Path("/getUsageLimitPath")
	public Response getUsageLimitPath(@Context HttpServletRequest request, @QueryParam("engineId") String engineId,
			@QueryParam("userId") String userId, @QueryParam("userType") String userType) {
		String engine = WebUtility.inputSQLSanitizer(engineId);
		String person = WebUtility.inputSQLSanitizer(userId);
		String type = WebUtility.inputSQLSanitizer(userType);
		return ResourceUtility.respond(request, "read how usage limits apply to " + person + " on model " + engine,
				user -> SecurityAccessPermissionBatchUtils.getUsageLimitPath(user, engine, person, type));
	}

	/**
	 * How usage limits apply to the signed in person on a model, for anyone who can
	 * use the model.
	 *
	 * @param engineId the model
	 */
	@GET
	@Produces("application/json")
	@Path("/getMyUsageLimitPath")
	public Response getMyUsageLimitPath(@Context HttpServletRequest request, @QueryParam("engineId") String engineId) {
		String engine = WebUtility.inputSQLSanitizer(engineId);
		return ResourceUtility.respond(request, "read how usage limits apply to them on model " + engine,
				user -> SecurityAccessPermissionBatchUtils.getMyUsageLimitPath(user, engine));
	}

	/**
	 * How usage limits apply to the signed in person on every model they can use,
	 * for a dashboard of their use.
	 */
	@GET
	@Produces("application/json")
	@Path("/getMyUsageLimitPaths")
	public Response getMyUsageLimitPaths(@Context HttpServletRequest request) {
		return ResourceUtility.respond(request, "read how usage limits apply to them on their models",
				SecurityAccessPermissionBatchUtils::getMyUsageLimitPaths);
	}

	static PermissionTargetResource toTarget(String resourceType, String id) {
		String value = ValueUtils.requireNonBlank(resourceType, "Must define the type of resource");
		try {
			return new PermissionTargetResource(
					PermissionTargetResource.TargetResourceType.valueOf(value.trim().toUpperCase(Locale.ROOT)), id,
					null);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(value + " is not a type of resource Bulk Access Management knows");
		}
	}

}
