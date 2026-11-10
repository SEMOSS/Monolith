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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
import prerna.semoss.web.openapi.CommonSchemas.ErrorMessage;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.semoss.web.services.local.auth.PermissionBatchResourceSchemas.PermissionBatchBatchesRequest;
import prerna.semoss.web.services.local.auth.PermissionBatchResourceSchemas.PermissionBatchEffectivePermissions;
import prerna.semoss.web.services.local.auth.PermissionBatchResourceSchemas.PermissionBatchPermissionToCopy;
import prerna.semoss.web.services.local.auth.PermissionBatchResourceSchemas.PermissionBatchPermissions;
import prerna.semoss.web.services.local.auth.PermissionBatchResourceSchemas.PermissionBatchSaveResult;
import prerna.semoss.web.services.local.auth.PermissionBatchResourceSchemas.PermissionBatchUsageLimitPath;
import prerna.semoss.web.services.local.auth.PermissionBatchResourceSchemas.PermissionBatchValidation;
import prerna.web.services.util.WebUtility;

/**
 * Bulk Access Management in admin mode: the same calls as
 * {@link PermissionBatchResource}, apart from reading the signed in person's
 * own usage limits, for admins only, where an admin can change and read any
 * project, engine, insight or custom group whatever their own access. The admin
 * check runs once, and the admin utilities it returns prove it to
 * {@link SecurityAccessPermissionBatchUtils}.
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
	@Operation(operationId = "validatePermissionBatches", summary = "Validate permission batches as an admin", description = "Checks every change of the batches in admin mode, where an admin can change any project, engine, insight or custom team whatever their own access, without saving anything.")
	@RequestBody(required = true, content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = PermissionBatchBatchesRequest.class)))
	@ApiResponse(responseCode = "200", description = "For each batch, its target and whether each change can be made", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = PermissionBatchValidation.class))))
	@ApiResponse(responseCode = "400", description = "The batches are missing or not valid JSON", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; also carries techErrorMessage", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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
	@Operation(operationId = "savePermissionBatches", summary = "Save permission batches as an admin", description = "Saves every batch in admin mode in one transaction, or none of them when any change cannot be made.")
	@RequestBody(required = true, content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = PermissionBatchBatchesRequest.class)))
	@ApiResponse(responseCode = "200", description = "How many targets and changes were saved", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PermissionBatchSaveResult.class)))
	@ApiResponse(responseCode = "400", description = "The batches are not valid, or a change cannot be made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; also carries techErrorMessage", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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
	 * @param resourceType {@code PROJECT}, {@code ENGINE}, {@code TEAM} or
	 *                     {@code TEAM_MANAGERS}
	 */
	@GET
	@Produces("application/json")
	@Path("/getPermissions")
	@Operation(operationId = "getPermissions", summary = "Read who can use a target now, as an admin", description = "Who can use a project or an engine now, or who is in or manages a custom team, read in admin mode.")
	@ApiResponse(responseCode = "200", description = "The people and teams with access, and what the admin may change", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PermissionBatchPermissions.class)))
	@ApiResponse(responseCode = "400", description = "The resource type or id is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; also carries techErrorMessage", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getPermissions(@Context HttpServletRequest request, @Parameter(description = "The type of target: PROJECT, ENGINE, TEAM or TEAM_MANAGERS (PROJECT or ENGINE for effective permissions)", required = true) @QueryParam("resourceType") String resourceType,
			@Parameter(description = "The project or engine id, or the custom team's name", required = true) @QueryParam("id") String id) {
		String resource = WebUtility.inputSQLSanitizer(id);
		return ResourceUtility.respondAsAdmin(request, "read the permissions on " + resource + " as an admin",
				SecurityAdminUtils::getInstance, (user, adminUtils) -> SecurityAccessPermissionBatchUtils
						.getPermissions(adminUtils, user, PermissionBatchResource.toTarget(resourceType, resource)));
	}

	/**
	 * The people to copy from a project, engine or custom group into another.
	 */
	@GET
	@Produces("application/json")
	@Path("/getPermissionsToCopy")
	@Operation(operationId = "getPermissionsToCopy", summary = "Read the people to copy between targets, as an admin", description = "The people to copy from a project, engine or custom team into another, with the access each has on both, read in admin mode.")
	@ApiResponse(responseCode = "200", description = "One row per person on the source", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = PermissionBatchPermissionToCopy.class))))
	@ApiResponse(responseCode = "400", description = "A resource type or id is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; also carries techErrorMessage", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getPermissionsToCopy(@Context HttpServletRequest request,
			@Parameter(description = "The type of source: PROJECT, ENGINE, TEAM or TEAM_MANAGERS", required = true) @QueryParam("sourceResourceType") String sourceResourceType, @Parameter(description = "The source's id", required = true) @QueryParam("sourceId") String sourceId,
			@Parameter(description = "The type of target: PROJECT, ENGINE, TEAM or TEAM_MANAGERS", required = true) @QueryParam("targetResourceType") String targetResourceType, @Parameter(description = "The target's id", required = true) @QueryParam("targetId") String targetId) {
		String source = WebUtility.inputSQLSanitizer(sourceId);
		String target = WebUtility.inputSQLSanitizer(targetId);
		return ResourceUtility.respondAsAdmin(request,
				"read the permissions to copy from " + source + " to " + target + " as an admin",
				SecurityAdminUtils::getInstance,
				(user, adminUtils) -> SecurityAccessPermissionBatchUtils.getPermissionsToCopy(adminUtils, user,
						PermissionBatchResource.toTarget(sourceResourceType, source),
						PermissionBatchResource.toTarget(targetResourceType, target)));
	}

	/**
	 * Everyone who can use a project or an engine in the end, one row per person.
	 *
	 * @param resourceType {@code PROJECT} or {@code ENGINE}
	 */
	@GET
	@Produces("application/json")
	@Path("/getEffectivePermissions")
	@Operation(operationId = "getEffectivePermissions", summary = "Read who can use a project or engine in the end, as an admin", description = "Everyone who can use a project or an engine in the end, one row per person, read in admin mode.")
	@ApiResponse(responseCode = "200", description = "The effective access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PermissionBatchEffectivePermissions.class)))
	@ApiResponse(responseCode = "400", description = "The resource type or id is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; also carries techErrorMessage", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getEffectivePermissions(@Context HttpServletRequest request,
			@Parameter(description = "The type of target: PROJECT, ENGINE, TEAM or TEAM_MANAGERS (PROJECT or ENGINE for effective permissions)", required = true) @QueryParam("resourceType") String resourceType, @Parameter(description = "The project or engine id, or the custom team's name", required = true) @QueryParam("id") String id) {
		String resource = WebUtility.inputSQLSanitizer(id);
		return ResourceUtility.respondAsAdmin(request, "read the effective permissions on " + resource + " as an admin",
				SecurityAdminUtils::getInstance,
				(user, adminUtils) -> EffectivePermissionUtils.getEffectivePermissions(adminUtils, user,
						PermissionBatchResource.toTarget(resourceType, resource)));
	}

	/**
	 * How usage limits apply to one person on a model, with every step's rules and
	 * each team's total.
	 *
	 * @param engineId the model
	 * @param userId   the person
	 * @param userType the person's login type, required
	 */
	@GET
	@Produces("application/json")
	@Path("/getUsageLimitPath")
	@Operation(operationId = "getUsageLimitPath", summary = "Read how usage limits apply to a person on a model, as an admin", description = "How usage limits apply to any person on a model, with every step's rules and each team's total; no grant on the model is needed.")
	@ApiResponse(responseCode = "200", description = "The person's usage limit path", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PermissionBatchUsageLimitPath.class)))
	@ApiResponse(responseCode = "400", description = "An input is missing, or the engine does not exist or is not a model", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an admin", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; also carries techErrorMessage", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getUsageLimitPath(@Context HttpServletRequest request, @Parameter(description = "The model's engine id", required = true) @QueryParam("engineId") String engineId,
			@Parameter(description = "The person's user id", required = true) @QueryParam("userId") String userId, @Parameter(description = "The person's login type, such as NATIVE", required = true) @QueryParam("userType") String userType) {
		String engine = WebUtility.inputSQLSanitizer(engineId);
		String person = WebUtility.inputSQLSanitizer(userId);
		String type = WebUtility.inputSQLSanitizer(userType);
		return ResourceUtility.respondAsAdmin(request,
				"read how usage limits apply to " + person + " on model " + engine + " as an admin",
				SecurityAdminUtils::getInstance, (user, adminUtils) -> SecurityAccessPermissionBatchUtils
						.getUsageLimitPath(adminUtils, user, engine, person, type));
	}

}
