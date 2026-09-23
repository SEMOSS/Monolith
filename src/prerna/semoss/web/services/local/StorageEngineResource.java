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

import java.io.IOException;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import prerna.web.services.util.WebUtility;

@Path("/storage-{storageId}")
@PermitAll
public class StorageEngineResource {

	private static final Logger classLogger = LogManager.getLogger(StorageEngineResource.class);

	/**
	 * ListStoragePath(storage=[storageId], storagePath=[...])
	 */
	@POST
	@Path("/list")
	@Consumes({ "application/x-www-form-urlencoded", "application/json" })
	@Produces("application/json;charset=utf-8")
	public Response list(@Context HttpServletRequest request, @PathParam("storageId") String storageId) {
		storageId = WebUtility.inputSanitizer(storageId);

		Map<String, Object> body;
		try {
			body = ResourceUtility.parseRequestBody(request);
		} catch (IOException e) {
			classLogger.error("Failed to read request body for /list on storage engine '{}'", storageId, e);
			return EngineRouteResource.error("Invalid request body: " + e.getMessage(), 400);
		}

		Object storagePath = body.get("storagePath");
		if (storagePath == null || storagePath.toString().trim().isEmpty()) {
			return EngineRouteResource.error("Must pass in 'storagePath' to list", 400);
		}

		String pixel = "ListStoragePath(storage=" + EngineRouteResource.GSON.toJson(storageId) + ", storagePath="
				+ EngineRouteResource.GSON.toJson(storagePath) + ")";
		return ResourceUtility.runPixel(request, pixel);
	}

	/**
	 * ListStoragePathDetails(storage=[storageId], storagePath=[...])
	 */
	@POST
	@Path("/listDetails")
	@Consumes({ "application/x-www-form-urlencoded", "application/json" })
	@Produces("application/json;charset=utf-8")
	public Response listDetails(@Context HttpServletRequest request, @PathParam("storageId") String storageId) {
		storageId = WebUtility.inputSanitizer(storageId);

		Map<String, Object> body;
		try {
			body = ResourceUtility.parseRequestBody(request);
		} catch (IOException e) {
			classLogger.error("Failed to read request body for /listDetails on storage engine '{}'", storageId, e);
			return EngineRouteResource.error("Invalid request body: " + e.getMessage(), 400);
		}

		Object storagePath = body.get("storagePath");
		if (storagePath == null || storagePath.toString().trim().isEmpty()) {
			return EngineRouteResource.error("Must pass in 'storagePath' to list", 400);
		}

		String pixel = "ListStoragePathDetails(storage=" + EngineRouteResource.GSON.toJson(storageId) + ", storagePath="
				+ EngineRouteResource.GSON.toJson(storagePath) + ")";
		return ResourceUtility.runPixel(request, pixel);
	}

	/**
	 * DeleteFromStorage(storage=[storageId], storagePath=[...])
	 */
	@POST
	@Path("/delete")
	@Consumes({ "application/x-www-form-urlencoded", "application/json" })
	@Produces("application/json;charset=utf-8")
	public Response delete(@Context HttpServletRequest request, @PathParam("storageId") String storageId) {
		storageId = WebUtility.inputSanitizer(storageId);

		Map<String, Object> body;
		try {
			body = ResourceUtility.parseRequestBody(request);
		} catch (IOException e) {
			classLogger.error("Failed to read request body for /delete on storage engine '{}'", storageId, e);
			return EngineRouteResource.error("Invalid request body: " + e.getMessage(), 400);
		}

		Object storagePath = body.get("storagePath");
		if (storagePath == null || storagePath.toString().trim().isEmpty()) {
			return EngineRouteResource.error("Must pass in 'storagePath' to delete", 400);
		}

		String pixel = "DeleteFromStorage(storage=" + EngineRouteResource.GSON.toJson(storageId) + ", storagePath="
				+ EngineRouteResource.GSON.toJson(storagePath) + ")";
		return ResourceUtility.runPixel(request, pixel);
	}

}
