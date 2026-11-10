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

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The responses of {@link HealthResource}, for its OpenAPI annotations.
 */
public final class HealthResourceSchemas {

	private HealthResourceSchemas() {
	}

	@Schema(description = "The server is serving requests")
	public record HealthLiveness(@Schema(description = "Always \"UP\"", allowableValues = "UP", requiredMode = REQUIRED) String status) {
	}

	@Schema(description = "Whether startup has finished")
	public record HealthReadiness(
			@Schema(description = "\"READY\" once startup is complete, otherwise \"NOT_READY\"", allowableValues = {
					"READY", "NOT_READY" }, requiredMode = REQUIRED) String status,
			@Schema(description = "True once startup is complete", requiredMode = REQUIRED) boolean startupComplete) {
	}

	@Schema(description = "Whether every required and enabled system database is connected")
	public record HealthDetails(
			@Schema(description = "\"UP\" when every required or enabled resource is connected, otherwise \"DOWN\"", allowableValues = {
					"UP", "DOWN" }, requiredMode = REQUIRED) String status,
			@Schema(description = "True once startup has finished", requiredMode = REQUIRED) boolean startupComplete,
			@Schema(description = "True when startup finished successfully", requiredMode = REQUIRED) boolean startupSuccess,
			@Schema(description = "The state of each system database", requiredMode = REQUIRED) HealthDetailsResources resources) {
	}

	@Schema(description = "The state of each system database: \"UP\" or \"DOWN\", or \"DISABLED\" when its feature is off")
	public record HealthDetailsResources(
			@Schema(description = "The local master database, always required", allowableValues = { "UP",
					"DOWN" }, requiredMode = REQUIRED) @JsonProperty("LocalMasterDatabase") String localMasterDatabase,
			@Schema(description = "The security database, always required", allowableValues = { "UP",
					"DOWN" }, requiredMode = REQUIRED) @JsonProperty("security") String security,
			@Schema(description = "The scheduler database", allowableValues = { "UP", "DOWN",
					"DISABLED" }, requiredMode = REQUIRED) @JsonProperty("scheduler") String scheduler,
			@Schema(description = "The user tracking database", allowableValues = { "UP", "DOWN",
					"DISABLED" }, requiredMode = REQUIRED) @JsonProperty("UserTrackingDatabase") String userTrackingDatabase,
			@Schema(description = "The audit logs database", allowableValues = { "UP", "DOWN",
					"DISABLED" }, requiredMode = REQUIRED) @JsonProperty("AuditLogs") String auditLogs,
			@Schema(description = "The model inference logs database", allowableValues = { "UP", "DOWN",
					"DISABLED" }, requiredMode = REQUIRED) @JsonProperty("ModelInferenceLogsDatabase") String modelInferenceLogsDatabase,
			@Schema(description = "The notification database", allowableValues = { "UP", "DOWN",
					"DISABLED" }, requiredMode = REQUIRED) @JsonProperty("Notification") String notification) {
	}
}
