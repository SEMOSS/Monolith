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
 * The requests and responses of {@link FileUploader}, for its OpenAPI
 * annotations.
 */
public final class FileUploaderSchemas {

	private FileUploaderSchemas() {
	}

	@Schema(description = "The column headers to check before an upload")
	public record UploadFileHeaderCheckRequest(
			@Schema(description = "\"EXCEL\" for Excel workbooks; any other value for delimited files", requiredMode = REQUIRED) String uploadType,
			@Schema(description = "JSON-encoded headers: for EXCEL a list with one object per workbook, mapping each sheet name to its headers; otherwise an object mapping each file name to its headers", requiredMode = REQUIRED) String userHeaders) {
	}

	@Schema(description = "The files to upload")
	public record UploadFileFilesRequest(@Schema(description = "A file to upload; repeat the field to upload several. Parts under any other name are ignored", types = "string", format = "binary") String file) {
	}

	@Schema(description = "A file the upload saved")
	public record UploadFileSavedFile(
			@Schema(description = "The name the file was saved under, made unique within its folder", requiredMode = REQUIRED) String fileName,
			@Schema(description = "The saved file's path relative to the folder it was uploaded to, starting with /", requiredMode = REQUIRED) String fileLocation) {
	}
}
