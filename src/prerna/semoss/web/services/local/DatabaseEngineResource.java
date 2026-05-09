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
import java.util.HashMap;
import java.util.Map;

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.engine.api.IDatabaseEngine;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/database-{databaseId}")
@PermitAll
public class DatabaseEngineResource {

	private static final Logger classLogger = LogManager.getLogger(DatabaseEngineResource.class);

	/**
	 * Return the {@link IDatabaseEngine.DATABASE_TYPE} and the engine subtype
	 * (the ENGINESUBTYPE value stored in the security DB) for this database.
	 */
	@GET
	@Path("/type")
	@Produces("application/json;charset=utf-8")
	public Response getDatabaseType(@Context HttpServletRequest request, @PathParam("databaseId") String databaseId) {
		databaseId = WebUtility.inputSanitizer(databaseId);

		User user;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			return EngineRouteResource.error("User session is invalid", 401);
		}
		if (!SecurityEngineUtils.userCanViewEngine(user, databaseId)
				&& !SecurityEngineUtils.engineIsDiscoverable(databaseId)) {
			return EngineRouteResource.error(
					"Database " + databaseId + " does not exist or user does not have access", 401);
		}

		IDatabaseEngine.DATABASE_TYPE databaseType;
		try {
			IDatabaseEngine engine = Utility.getDatabase(databaseId);
			if (engine == null) {
				return EngineRouteResource.error("Could not load database with id " + databaseId, 400);
			}
			databaseType = engine.getDatabaseType();
		} catch (Exception e) {
			classLogger.error("Failed to load database engine '{}' to read its database type", databaseId, e);
			return EngineRouteResource.error("Could not load database with id " + databaseId, 400);
		}

		String subtype = null;
		try {
			Object[] typeAndSubtype = SecurityEngineUtils.getEngineTypeAndSubtype(databaseId);
			if (typeAndSubtype.length > 1 && typeAndSubtype[1] != null) {
				subtype = typeAndSubtype[1].toString();
			}
		} catch (Exception e) {
			classLogger.error("Failed to look up engine subtype for database '{}'", databaseId, e);
		}

		Map<String, Object> ret = new HashMap<>();
		ret.put("engineId", databaseId);
		ret.put("databaseType", databaseType == null ? null : databaseType.toString());
		ret.put("subtype", subtype);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * ReloadDatabase(database=[databaseId])
	 */
	@POST
	@Path("/reload")
	@Produces("application/json;charset=utf-8")
	public Response reload(@Context HttpServletRequest request, @PathParam("databaseId") String databaseId) {
		databaseId = WebUtility.inputSanitizer(databaseId);

		String pixel = "ReloadDatabase(database=" + EngineRouteResource.GSON.toJson(databaseId) + ")";
		return ResourceUtility.runPixel(request, pixel);
	}

	/**
	 * Database(database=[databaseId]) | Query(query=[...]).
	 *
	 * Runs a raw query through the database engine.
	 */
	@POST
	@Path("/query")
	@Consumes({ "application/x-www-form-urlencoded", "application/json" })
	@Produces("application/json;charset=utf-8")
	public Response query(@Context HttpServletRequest request, @PathParam("databaseId") String databaseId) {
		databaseId = WebUtility.inputSanitizer(databaseId);

		Map<String, Object> body;
		try {
			body = ResourceUtility.parseRequestBody(request);
		} catch (IOException e) {
			classLogger.error("Failed to read request body for /query on database engine '{}'", databaseId, e);
			return EngineRouteResource.error("Invalid request body: " + e.getMessage(), 400);
		}

		Object query = body.get("query");
		if (query == null || query.toString().trim().isEmpty()) {
			return EngineRouteResource.error("Must pass in 'query' containing the query to execute", 400);
		}

		String pixel = "Database(database=" + EngineRouteResource.GSON.toJson(databaseId) + ")" + " | Query(query="
				+ EngineRouteResource.GSON.toJson(query) + ")";
		return ResourceUtility.runPixel(request, pixel);
	}

}
