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
package prerna.semoss.web.services.local;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The requests and responses of {@link EngineRouteResource}, for its OpenAPI
 * annotations.
 */
public final class EngineRouteResourceSchemas {

	private EngineRouteResourceSchemas() {
	}

	@Schema(description = "The new smss file of an engine")
	public record EngineRouteUpdateSmssFileRequest(@Schema(description = "The full text of the new smss file. The engine id, name and type in it must match the current ones; concealed values left unchanged keep their current value", requiredMode = REQUIRED) String smss) {
	}

	@Schema(description = "The image to set as an engine's catalog image")
	public record EngineRouteImageUploadRequest(@Schema(description = "The image, a PNG, JPEG or GIF of at most 10 MiB; it must be the only part of the form", types = "string", format = "binary", requiredMode = REQUIRED) String file) {
	}

	@Schema(description = "The catalog image an engine now has")
	public record EngineRouteImageUploadResult(
			@Schema(description = "Always \"Successfully updated image\"", requiredMode = REQUIRED) String message,
			@Schema(description = "The engine id", requiredMode = REQUIRED) String id,
			@Schema(description = "The engine name", requiredMode = REQUIRED) String name,
			@Schema(description = "The URL the image downloads from, under this server's context path", requiredMode = REQUIRED) String imageUrl,
			@Schema(description = "The stored image's media type, such as image/png", requiredMode = REQUIRED) String contentType) {
	}
}
