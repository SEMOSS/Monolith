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

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The responses of {@link ServerConfigurationResource} other than
 * {@link ServerConfig}, for its OpenAPI annotations.
 */
public final class ServerConfigurationResourceSchemas {

	private ServerConfigurationResourceSchemas() {
	}

	@Schema(description = "Every REST endpoint this application registers")
	public record ConfigEndpointsList(@Schema(description = "The application's context path", requiredMode = REQUIRED) String contextPath,
			@Schema(description = "How many endpoints are listed", requiredMode = REQUIRED) int count,
			@Schema(description = "True when the caller is an admin, so the Admin resources are listed too", requiredMode = REQUIRED) boolean includesAdminResources,
			@Schema(description = "The endpoints, sorted by path then method", requiredMode = REQUIRED) List<ConfigEndpointsEntry> endpoints) {
	}

	@Schema(description = "One REST endpoint")
	public record ConfigEndpointsEntry(@Schema(description = "The HTTP method, such as GET", requiredMode = REQUIRED) String method,
			@Schema(description = "The path within the servlet, without the context path", requiredMode = REQUIRED) String path,
			@Schema(description = "The simple name of the resource class", requiredMode = REQUIRED) String resource,
			@Schema(description = "The Java method name", requiredMode = REQUIRED) String operation,
			@Schema(description = "The media types it consumes, when declared") List<String> consumes,
			@Schema(description = "The media types it produces, when declared") List<String> produces) {
	}
}
