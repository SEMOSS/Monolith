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
import java.util.Map;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import prerna.auth.AuthProvider;
import prerna.auth.access.ModelUsageLimit;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityModelUsageLimitUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.ValueUtils;
import prerna.web.services.util.WebUtility;

/**
 * The model usage limits that only admins set, which are not grants and so not
 * part of permission batches: the platform default, for everyone with no other
 * limit on any model, and a team's total across all models. A limit is sent as
 * JSON in the {@code usageLimit} form field, and an empty one removes it. Each
 * endpoint runs through {@link ResourceUtility#respondAsAdmin}, so anyone who
 * is not an admin is answered with 401, and each answers with
 * {@code {"usageLimit": ...}}, null for no limit defined.
 */
@Path("/auth/admin/modelUsage")
@PermitAll
public class AdminModelUsageResource {

	private static final String USAGE_LIMIT = "usageLimit";
	private static final String GROUP_ID_REQUIRED = "The group id ('groupId') cannot be null or empty";
	private static final String GROUP_TYPE_REQUIRED = "The group type ('type') cannot be null or empty";

	/*
	 * Platform default
	 */

	@GET
	@Produces("application/json")
	@Path("/getPlatformDefault")
	public Response getPlatformDefault(@Context HttpServletRequest request) {
		return ResourceUtility.respondAsAdmin(request, "read the General Platform Limit",
				user -> usageLimitBody(SecurityModelUsageLimitUtils.getPlatformDefault()));
	}

	@POST
	@Produces("application/json")
	@Path("/setPlatformDefault")
	public Response setPlatformDefault(@Context HttpServletRequest request) {
		String usageLimit = request.getParameter(USAGE_LIMIT);
		return ResourceUtility.respondAsAdmin(request, "set the General Platform Limit",
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					SecurityModelUsageLimitUtils.setPlatformDefault(adminUtils, user,
							SecurityModelUsageLimitUtils.readJson(usageLimit));
					return usageLimitBody(SecurityModelUsageLimitUtils.getPlatformDefault());
				});
	}

	/*
	 * Team totals
	 */

	@GET
	@Produces("application/json")
	@Path("/getTeamTotal")
	public Response getTeamTotal(@Context HttpServletRequest request, @QueryParam("groupId") String groupId,
			@QueryParam("type") String type) {
		String group = WebUtility.inputSQLSanitizer(groupId);
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(type));
		return ResourceUtility
				.respondAsAdmin(request, "read the model usage total of group " + group,
						user -> usageLimitBody(SecurityModelUsageLimitUtils.getTeamTotal(
								ValueUtils.requireNonBlank(group, GROUP_ID_REQUIRED),
								ValueUtils.requireNonBlank(groupType, GROUP_TYPE_REQUIRED))));
	}

	@POST
	@Produces("application/json")
	@Path("/setTeamTotal")
	public Response setTeamTotal(@Context HttpServletRequest request) {
		String groupId = WebUtility.inputSQLSanitizer(request.getParameter("groupId"));
		String groupType = AuthProvider.getProviderLabel(WebUtility.inputSQLSanitizer(request.getParameter("type")));
		String usageLimit = request.getParameter(USAGE_LIMIT);
		return ResourceUtility.respondAsAdmin(request, "set the model usage total of group " + groupId,
				SecurityAdminUtils::getInstance, (user, adminUtils) -> {
					String group = ValueUtils.requireNonBlank(groupId, GROUP_ID_REQUIRED);
					String type = ValueUtils.requireNonBlank(groupType, GROUP_TYPE_REQUIRED);
					SecurityModelUsageLimitUtils.setTeamTotal(adminUtils, user, group, type,
							SecurityModelUsageLimitUtils.readJson(usageLimit));
					return usageLimitBody(SecurityModelUsageLimitUtils.getTeamTotal(group, type));
				});
	}

	/**
	 * Builds the body every endpoint here answers with, holding the General
	 * Platform Limit or a team's Team Total.
	 *
	 * @param usageLimit the limit, or null for no limit defined
	 * @return the response body, {@code {"usageLimit": ...}}
	 */
	private static Map<String, Object> usageLimitBody(ModelUsageLimit usageLimit) {
		Map<String, Object> body = new HashMap<>();
		body.put(USAGE_LIMIT, usageLimit);
		return body;
	}
}
