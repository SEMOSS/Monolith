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

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.web.services.util.WebUtility;

@Path("/vector-{vectorId}")
@PermitAll
public class VectorEngineResource {

	private static final Logger classLogger = LogManager.getLogger(VectorEngineResource.class);

	/**
	 * VectorDatabaseQuery(engine=[vectorId], command=[...], limit=[...],
	 * paramValues=[{...}])
	 */
	@POST
	@Path("/query")
	@Consumes({ "application/x-www-form-urlencoded", "application/json" })
	@Produces("application/json;charset=utf-8")
	public Response query(@Context HttpServletRequest request, @PathParam("vectorId") String vectorId) {
		vectorId = WebUtility.inputSanitizer(vectorId);

		Map<String, Object> body;
		try {
			body = ResourceUtility.parseRequestBody(request);
		} catch (IOException e) {
			classLogger.error("Failed to read request body for /query on vector engine '{}'", vectorId, e);
			return EngineRouteResource.error("Invalid request body: " + e.getMessage(), 400);
		}

		Object command = body.get("command");
		if (command == null || command.toString().trim().isEmpty()) {
			return EngineRouteResource.error("Must pass in 'command' containing the search statement", 400);
		}

		StringBuilder pixel = new StringBuilder("VectorDatabaseQuery(engine=");
		pixel.append(EngineRouteResource.GSON.toJson(vectorId));
		pixel.append(", command=").append(EngineRouteResource.GSON.toJson(command));
		EngineRouteResource.appendIfPresent(pixel, body, "limit");
		EngineRouteResource.appendIfPresent(pixel, body, "paramValues");
		pixel.append(")");

		return ResourceUtility.runPixel(request, pixel.toString());
	}

	/**
	 * ListDocumentsInVectorDatabase(engine=[vectorId], paramValues=[{...}])
	 */
	@POST
	@Path("/listDocuments")
	@Consumes({ "application/x-www-form-urlencoded", "application/json" })
	@Produces("application/json;charset=utf-8")
	public Response listDocuments(@Context HttpServletRequest request, @PathParam("vectorId") String vectorId) {
		vectorId = WebUtility.inputSanitizer(vectorId);

		Map<String, Object> body;
		try {
			body = ResourceUtility.parseRequestBody(request);
		} catch (IOException e) {
			classLogger.error("Failed to read request body for /listDocuments on vector engine '{}'", vectorId, e);
			return EngineRouteResource.error("Invalid request body: " + e.getMessage(), 400);
		}

		StringBuilder pixel = new StringBuilder("ListDocumentsInVectorDatabase(engine=");
		pixel.append(EngineRouteResource.GSON.toJson(vectorId));
		EngineRouteResource.appendIfPresent(pixel, body, "paramValues");
		pixel.append(")");

		return ResourceUtility.runPixel(request, pixel.toString());
	}

	/**
	 * RemoveDocumentFromVectorDatabase(engine=[vectorId], fileNames=[...],
	 * paramValues=[{...}])
	 */
	@POST
	@Path("/removeDocument")
	@Consumes({ "application/x-www-form-urlencoded", "application/json" })
	@Produces("application/json;charset=utf-8")
	public Response removeDocument(@Context HttpServletRequest request, @PathParam("vectorId") String vectorId) {
		vectorId = WebUtility.inputSanitizer(vectorId);

		Map<String, Object> body;
		try {
			body = ResourceUtility.parseRequestBody(request);
		} catch (IOException e) {
			classLogger.error("Failed to read request body for /removeDocument on vector engine '{}'", vectorId, e);
			return EngineRouteResource.error("Invalid request body: " + e.getMessage(), 400);
		}

		Object fileNames = body.get("fileNames");
		if (fileNames == null) {
			return EngineRouteResource.error("Must pass in 'fileNames' containing the document(s) to remove", 400);
		}

		StringBuilder pixel = new StringBuilder("RemoveDocumentFromVectorDatabase(engine=");
		pixel.append(EngineRouteResource.GSON.toJson(vectorId));
		pixel.append(", fileNames=").append(EngineRouteResource.GSON.toJson(fileNames));
		EngineRouteResource.appendIfPresent(pixel, body, "paramValues");
		pixel.append(")");

		return ResourceUtility.runPixel(request, pixel.toString());
	}

}
