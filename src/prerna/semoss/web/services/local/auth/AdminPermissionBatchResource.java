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

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import prerna.auth.utils.EffectivePermissionUtils;
import prerna.auth.utils.SecurityAccessPermissionBatchUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.web.services.util.WebUtility;

/**
 * Bulk Access Management in admin mode: the same calls as
 * {@link PermissionBatchResource}, for admins only, where an admin can change
 * and read any project, engine, insight or custom group whatever their own
 * access. The admin check runs once, and the admin utilities it returns prove
 * it to {@link SecurityAccessPermissionBatchUtils}.
 */
@Path("/auth/admin/permissionBatch")
@PermitAll
public class AdminPermissionBatchResource {

	/**
	 * Validates batches without saving them.
	 */
	@POST
	@Produces("application/json")
	@Path("/validatePermissionBatches")
	public Response validatePermissionBatches(@Context HttpServletRequest request) {
		String batches = request.getParameter("batches");
		return ResourceUtility.respondAsAdmin(request, "validate a permission batch as an admin",
				SecurityAdminUtils::getInstance, (user, adminUtils) -> SecurityAccessPermissionBatchUtils
						.validateBatches(adminUtils, user, SecurityAccessPermissionBatchUtils.readBatches(batches)));
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
		return ResourceUtility.respondAsAdmin(request, "save a permission batch as an admin",
				SecurityAdminUtils::getInstance, (user, adminUtils) -> SecurityAccessPermissionBatchUtils
						.saveBatches(adminUtils, user, SecurityAccessPermissionBatchUtils.readBatches(batches)));
	}

	/**
	 * Who can use a project or an engine now, or who is in or manages a custom
	 * group.
	 *
	 * @param kind {@code PROJECT}, {@code ENGINE}, {@code TEAM} or
	 *             {@code TEAM_MANAGERS}
	 */
	@GET
	@Produces("application/json")
	@Path("/getPermissions")
	public Response getPermissions(@Context HttpServletRequest request, @QueryParam("kind") String kind,
			@QueryParam("id") String id) {
		String resource = WebUtility.inputSQLSanitizer(id);
		return ResourceUtility.respondAsAdmin(request, "read the permissions on " + resource + " as an admin",
				SecurityAdminUtils::getInstance, (user, adminUtils) -> SecurityAccessPermissionBatchUtils
						.getPermissions(adminUtils, user, PermissionBatchResource.toTarget(kind, resource)));
	}

	/**
	 * The people to copy from a project, engine or custom group into another.
	 */
	@GET
	@Produces("application/json")
	@Path("/getPermissionsToCopy")
	public Response getPermissionsToCopy(@Context HttpServletRequest request,
			@QueryParam("sourceKind") String sourceKind, @QueryParam("sourceId") String sourceId,
			@QueryParam("targetKind") String targetKind, @QueryParam("targetId") String targetId) {
		String source = WebUtility.inputSQLSanitizer(sourceId);
		String target = WebUtility.inputSQLSanitizer(targetId);
		return ResourceUtility.respondAsAdmin(request,
				"read the permissions to copy from " + source + " to " + target + " as an admin",
				SecurityAdminUtils::getInstance,
				(user, adminUtils) -> SecurityAccessPermissionBatchUtils.getPermissionsToCopy(adminUtils, user,
						PermissionBatchResource.toTarget(sourceKind, source),
						PermissionBatchResource.toTarget(targetKind, target)));
	}

	/**
	 * Everyone who can use a project or an engine in the end, one row per person.
	 *
	 * @param kind {@code PROJECT} or {@code ENGINE}
	 */
	@GET
	@Produces("application/json")
	@Path("/getEffectivePermissions")
	public Response getEffectivePermissions(@Context HttpServletRequest request, @QueryParam("kind") String kind,
			@QueryParam("id") String id) {
		String resource = WebUtility.inputSQLSanitizer(id);
		return ResourceUtility.respondAsAdmin(request, "read the effective permissions on " + resource + " as an admin",
				SecurityAdminUtils::getInstance, (user, adminUtils) -> EffectivePermissionUtils
						.getEffectivePermissions(adminUtils, user, PermissionBatchResource.toTarget(kind, resource)));
	}

}
