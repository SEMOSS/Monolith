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
package prerna.upload;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The requests and responses of {@link ImageUploader}, for its OpenAPI
 * annotations.
 */
public final class ImageUploaderSchemas {

	private ImageUploaderSchemas() {
	}

	@Schema(description = "The image to set as a project's image")
	public record ImagesProjectImageUploadRequest(
			@Schema(description = "The image file", types = "string", format = "binary", requiredMode = REQUIRED) String file,
			@Schema(description = "The project id, or an alias of it the user has", requiredMode = REQUIRED) String projectId) {
	}

	@Schema(description = "The project whose image was set")
	public record ImagesProjectImageUploadResult(
			@Schema(description = "Always \"Successfully updated project image\"", requiredMode = REQUIRED) String message,
			@Schema(description = "The project id", requiredMode = REQUIRED) String app_id,
			@Schema(description = "The project name") String app_name) {
	}

	@Schema(description = "The project whose image to remove")
	public record ImagesProjectImageDeleteRequest(@Schema(description = "The project id, or an alias of it the user has", requiredMode = REQUIRED) String projectId) {
	}

	@Schema(description = "The project whose image was removed")
	public record ImagesProjectImageDeleteResult(
			@Schema(description = "Always \"Successfully deleted project image\"", requiredMode = REQUIRED) String message,
			@Schema(description = "The project id", requiredMode = REQUIRED) String project_id,
			@Schema(description = "The project name") String project_name) {
	}

	@Schema(description = "The engine whose image to remove")
	public record ImagesEngineDeleteRequest(@Schema(description = "The engine id", requiredMode = REQUIRED) String engineId) {
	}

	@Schema(description = "The engine whose image was removed")
	public record ImagesEngineDeleteResult(
			@Schema(description = "Always \"Successfully deleted engine image\"", requiredMode = REQUIRED) String message,
			@Schema(description = "The engine id", requiredMode = REQUIRED) String engine_id,
			@Schema(description = "The engine name") String engine_name) {
	}

	@Schema(description = "The insight whose image to remove")
	public record ImagesInsightImageDeleteRequest(
			@Schema(description = "The id of the insight's project, or an alias of it the user has", requiredMode = REQUIRED) String projectId,
			@Schema(description = "The insight id", requiredMode = REQUIRED) String insightId) {
	}

	@Schema(description = "The insight whose image was removed")
	public record ImagesInsightImageDeleteResult(
			@Schema(description = "Always \"Successfully deleted insight image\"", requiredMode = REQUIRED) String message,
			@Schema(description = "The project id", requiredMode = REQUIRED) String project_id,
			@Schema(description = "The project name") String project_name,
			@Schema(description = "The insight id", requiredMode = REQUIRED) String insight_id) {
	}

	@Schema(description = "The database whose image to remove; give engineId or databaseId")
	public record ImagesDatabaseImageDeleteRequest(
			@Schema(description = "The database id") String engineId,
			@Schema(description = "The database id, read when engineId is not given") String databaseId) {
	}

	@Schema(description = "The database whose image was removed")
	public record ImagesDatabaseImageDeleteResult(
			@Schema(description = "Always \"Successfully deleted database image\"", requiredMode = REQUIRED) String message,
			@Schema(description = "The database id", requiredMode = REQUIRED) String engine_id,
			@Schema(description = "The database name") String engine_name,
			@Schema(description = "The database id, the same as engine_id", requiredMode = REQUIRED) String database_id,
			@Schema(description = "The database name, the same as engine_name") String database_name) {
	}
}
