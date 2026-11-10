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
package prerna.semoss.web.services.config;

import java.util.HashMap;
import java.util.Map;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import prerna.semoss.web.services.config.HealthResourceSchemas.HealthDetails;
import prerna.semoss.web.services.config.HealthResourceSchemas.HealthLiveness;
import prerna.semoss.web.services.config.HealthResourceSchemas.HealthReadiness;
import prerna.util.Constants;
import prerna.util.SystemEngineRegistry;
import prerna.util.Utility;
import prerna.web.conf.DBLoader;
import prerna.web.services.util.WebUtility;

/**
 * Liveness / readiness / health probes served from the dedicated
 * {@code /health} servlet (see {@link prerna.semoss.web.app.HealthApplication}
 * and web.xml) and bypasses the security / user-session filter chain.
 * <p>
 * None of these methods call
 * {@link jakarta.servlet.http.HttpServletRequest#getSession()}, so no
 * {@link jakarta.servlet.http.HttpSession} is ever created for a probe.
 * <p>
 * {@link prerna.web.conf.StartUpSuccessFilter} also skips {@code /health/*}, so
 * these probes remain reachable even when startup has failed.
 */
@Path("/")
public class HealthResource {

	/**
	 * Liveness: the servlet container is up and serving requests. Always 200 when
	 * reachable. Does not inspect any downstream resource.
	 */
	@GET
	@Path("/")
	@Produces("application/json")
	@Operation(operationId = "liveness", summary = "Check the server is up", description = "Liveness probe: answers whenever the servlet container is serving requests, without checking anything downstream.")
	@ApiResponse(responseCode = "200", description = "The server is up", content = @Content(mediaType = "application/json", schema = @Schema(implementation = HealthLiveness.class)))
	public Response liveness() {
		Map<String, Object> status = new HashMap<>();
		status.put("status", "UP");
		return WebUtility.getResponseNoCache(status, 200);
	}

	/**
	 * Readiness: has {@link DBLoader} finished running through a successful
	 * startup? 200 {@code READY} once booting is complete, otherwise 503
	 * {@code NOT_READY} while the application is still starting up (or if startup
	 * failed).
	 */
	@GET
	@Path("/ready")
	@Produces("application/json")
	@Operation(operationId = "ready", summary = "Check startup has finished", description = "Readiness probe: whether the server finished a successful startup.")
	@ApiResponse(responseCode = "200", description = "Startup is complete", content = @Content(mediaType = "application/json", schema = @Schema(implementation = HealthReadiness.class)))
	@ApiResponse(responseCode = "503", description = "The server is still starting, or startup failed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = HealthReadiness.class)))
	public Response readiness() {
		boolean complete = DBLoader.isStartupComplete();

		Map<String, Object> status = new HashMap<>();
		status.put("status", complete ? "READY" : "NOT_READY");
		status.put("startupComplete", complete);
		return WebUtility.getResponseNoCache(status, complete ? 200 : 503);
	}

	/**
	 * Health: confirm {@link DBLoader} successfully connected to every required and
	 * enabled system resource (localMaster, security, scheduler, ...). Returns a
	 * per-resource breakdown. 200 {@code UP} when every required/enabled resource
	 * is connected, otherwise 503 {@code DOWN}. Resources whose feature flag is off
	 * are reported as {@code DISABLED} and do not affect the overall status.
	 */
	@GET
	@Path("/details")
	@Produces("application/json")
	@Operation(operationId = "details", summary = "Check the system databases", description = "Reports whether each required and enabled system database is connected. Databases whose feature is off are reported as DISABLED and do not affect the overall status.")
	@ApiResponse(responseCode = "200", description = "Every required and enabled database is connected", content = @Content(mediaType = "application/json", schema = @Schema(implementation = HealthDetails.class)))
	@ApiResponse(responseCode = "503", description = "A required or enabled database is not connected", content = @Content(mediaType = "application/json", schema = @Schema(implementation = HealthDetails.class)))
	public Response health() {
		Map<String, Object> resources = new HashMap<>();
		boolean healthy = true;

		// required resources - must always be connected
		healthy &= addRequired(resources, Constants.LOCAL_MASTER_DB, SystemEngineRegistry.isLocalMasterDbLoaded());
		healthy &= addRequired(resources, Constants.SECURITY_DB, SystemEngineRegistry.isSecurityDbLoaded());

		// conditional resources - only checked when their feature flag is enabled
		healthy &= addConditional(resources, Constants.SCHEDULER_DB, !Utility.schedulerForceDisable(),
				SystemEngineRegistry.isSchedulerDbLoaded());
		healthy &= addConditional(resources, Constants.USER_TRACKING_DB, Utility.isUserTrackingEnabled(),
				SystemEngineRegistry.isUserTrackingDbLoaded());
		healthy &= addConditional(resources, Constants.AUDIT_LOGS_DB, Utility.isAuditLogsDatabaseEnabled(),
				SystemEngineRegistry.isAuditLogsDbLoaded());
		healthy &= addConditional(resources, Constants.MODEL_INFERENCE_LOGS_DB, Utility.isModelInferenceLogsEnabled(),
				SystemEngineRegistry.isModelInferenceLogsDbLoaded());
		healthy &= addConditional(resources, Constants.NOTIFICATION_DB, Utility.isNotificationDatabaseEnabled(),
				SystemEngineRegistry.isNotificationDbLoaded());

		Map<String, Object> body = new HashMap<>();
		body.put("status", healthy ? "UP" : "DOWN");
		body.put("startupComplete", DBLoader.isStartupComplete());
		body.put("startupSuccess", DBLoader.isStartupSuccess());
		body.put("resources", resources);
		return WebUtility.getResponseNoCache(body, healthy ? 200 : 503);
	}

	/**
	 * Record a required resource. Reports UP/DOWN and contributes its connected
	 * state to the overall health.
	 *
	 * @return true when the resource is connected
	 */
	private static boolean addRequired(Map<String, Object> resources, String name, boolean connected) {
		resources.put(name, connected ? "UP" : "DOWN");
		return connected;
	}

	/**
	 * Record a conditional resource. When the feature is disabled it is reported as
	 * DISABLED and does not affect overall health; when enabled it reports UP/DOWN
	 * and contributes its connected state.
	 *
	 * @return true when the resource is disabled or connected
	 */
	private static boolean addConditional(Map<String, Object> resources, String name, boolean enabled,
			boolean connected) {
		if (!enabled) {
			resources.put(name, "DISABLED");
			return true;
		}
		resources.put(name, connected ? "UP" : "DOWN");
		return connected;
	}
}
