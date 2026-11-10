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
import prerna.auth.access.PermissionTargetResource;
import prerna.auth.utils.EffectivePermissionUtils;
import prerna.auth.utils.SecurityAccessPermissionBatchUtils;
import prerna.semoss.web.openapi.CommonSchemas.ErrorMessage;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.semoss.web.services.local.auth.PermissionBatchResourceSchemas.PermissionBatchBatchesRequest;
import prerna.semoss.web.services.local.auth.PermissionBatchResourceSchemas.PermissionBatchEffectivePermissions;
import prerna.semoss.web.services.local.auth.PermissionBatchResourceSchemas.PermissionBatchMyUsageLimitPaths;
import prerna.semoss.web.services.local.auth.PermissionBatchResourceSchemas.PermissionBatchPermissionToCopy;
import prerna.semoss.web.services.local.auth.PermissionBatchResourceSchemas.PermissionBatchPermissions;
import prerna.semoss.web.services.local.auth.PermissionBatchResourceSchemas.PermissionBatchSaveResult;
import prerna.semoss.web.services.local.auth.PermissionBatchResourceSchemas.PermissionBatchUsageLimitPath;
import prerna.semoss.web.services.local.auth.PermissionBatchResourceSchemas.PermissionBatchValidation;
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
 * {@link AdminPermissionBatchResource} is the admin mode.
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
	@Operation(operationId = "validatePermissionBatches", summary = "Validate permission batches", description = "Checks every change of the batches against the rules of the single permission endpoints, with the signed in user's own permissions, without saving anything.")
	@RequestBody(required = true, content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = PermissionBatchBatchesRequest.class)))
	@ApiResponse(responseCode = "200", description = "For each batch, its target and whether each change can be made", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = PermissionBatchValidation.class))))
	@ApiResponse(responseCode = "400", description = "The batches are missing or not valid JSON", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; also carries techErrorMessage", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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
	@Operation(operationId = "savePermissionBatches", summary = "Save permission batches", description = "Saves every batch in one transaction, or none of them when any change cannot be made, with the signed in user's own permissions.")
	@RequestBody(required = true, content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = PermissionBatchBatchesRequest.class)))
	@ApiResponse(responseCode = "200", description = "How many targets and changes were saved", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PermissionBatchSaveResult.class)))
	@ApiResponse(responseCode = "400", description = "The batches are not valid, or a change cannot be made", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; also carries techErrorMessage", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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
	@Operation(operationId = "getPermissions", summary = "Read who can use a target now", description = "Who can use a project or an engine now, or who is in or manages a custom team, in the shape batches use, and what the signed in user may change.")
	@ApiResponse(responseCode = "200", description = "The people and teams with access, and what the user may change", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PermissionBatchPermissions.class)))
	@ApiResponse(responseCode = "400", description = "The resource type or id is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not see the target's access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; also carries techErrorMessage", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getPermissions(@Context HttpServletRequest request, @Parameter(description = "The type of target: PROJECT, ENGINE, TEAM or TEAM_MANAGERS (PROJECT or ENGINE for effective permissions)", required = true) @QueryParam("resourceType") String resourceType,
			@Parameter(description = "The project or engine id, or the custom team's name", required = true) @QueryParam("id") String id) {
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
	@Operation(operationId = "getPermissionsToCopy", summary = "Read the people to copy between targets", description = "The people to copy from a project, engine or custom team into another, with the access each has on both.")
	@ApiResponse(responseCode = "200", description = "One row per person on the source", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = PermissionBatchPermissionToCopy.class))))
	@ApiResponse(responseCode = "400", description = "A resource type or id is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not see the source's or target's access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; also carries techErrorMessage", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getPermissionsToCopy(@Context HttpServletRequest request,
			@Parameter(description = "The type of source: PROJECT, ENGINE, TEAM or TEAM_MANAGERS", required = true) @QueryParam("sourceResourceType") String sourceResourceType, @Parameter(description = "The source's id", required = true) @QueryParam("sourceId") String sourceId,
			@Parameter(description = "The type of target: PROJECT, ENGINE, TEAM or TEAM_MANAGERS", required = true) @QueryParam("targetResourceType") String targetResourceType, @Parameter(description = "The target's id", required = true) @QueryParam("targetId") String targetId) {
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
	@Operation(operationId = "getEffectivePermissions", summary = "Read who can use a project or engine in the end", description = "Everyone who can use a project or an engine in the end, one row per person, with the access they get directly and through each custom team, and on a model which usage limit they are billed to.")
	@ApiResponse(responseCode = "200", description = "The effective access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PermissionBatchEffectivePermissions.class)))
	@ApiResponse(responseCode = "400", description = "The resource type or id is missing or not valid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not see the target's access", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; also carries techErrorMessage", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getEffectivePermissions(@Context HttpServletRequest request,
			@Parameter(description = "The type of target: PROJECT, ENGINE, TEAM or TEAM_MANAGERS (PROJECT or ENGINE for effective permissions)", required = true) @QueryParam("resourceType") String resourceType, @Parameter(description = "The project or engine id, or the custom team's name", required = true) @QueryParam("id") String id) {
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
	@Operation(operationId = "getUsageLimitPath", summary = "Read how usage limits apply to a person on a model", description = "For the model's editors and owners, about a person with a grant on the model: the ceilings, then each source in billing order with its state, rules and use. The ALL_MODELS and PLATFORM steps leave out their rules and say rulesHidden, and team steps leave out teamTotal.")
	@ApiResponse(responseCode = "200", description = "The person's usage limit path", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PermissionBatchUsageLimitPath.class)))
	@ApiResponse(responseCode = "400", description = "An input is missing, the engine is not a model, or the person has no access to it", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user is not an editor of the model", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; also carries techErrorMessage", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getUsageLimitPath(@Context HttpServletRequest request, @Parameter(description = "The model's engine id", required = true) @QueryParam("engineId") String engineId,
			@Parameter(description = "The person's user id", required = true) @QueryParam("userId") String userId, @Parameter(description = "The person's login type, such as NATIVE", required = true) @QueryParam("userType") String userType) {
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
	@Operation(operationId = "getMyUsageLimitPath", summary = "Read how usage limits apply to me on a model", description = "How usage limits apply to the signed in person on a model they can use, with every step's rules and use.")
	@ApiResponse(responseCode = "200", description = "The signed in person's usage limit path", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PermissionBatchUsageLimitPath.class)))
	@ApiResponse(responseCode = "400", description = "The engine id is missing or the engine is not a model", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user cannot use the model", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; also carries techErrorMessage", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	public Response getMyUsageLimitPath(@Context HttpServletRequest request, @Parameter(description = "The model's engine id", required = true) @QueryParam("engineId") String engineId) {
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
	@Operation(operationId = "getMyUsageLimitPaths", summary = "Read how usage limits apply to me on every model", description = "How usage limits apply to the signed in person on every model they can use, for a dashboard of their use.")
	@ApiResponse(responseCode = "200", description = "One usage limit path per model", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PermissionBatchMyUsageLimitPaths.class)))
	@ApiResponse(responseCode = "400", description = "The request could not be read", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "401", description = "There is no signed in user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "403", description = "The user may not do this", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "An unexpected error; also carries techErrorMessage", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
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
