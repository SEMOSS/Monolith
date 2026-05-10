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
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.web.services.util.WebUtility;

@Path("/function-{functionId}")
@PermitAll
public class FunctionEngineResource {

	private static final Logger classLogger = LogManager.getLogger(FunctionEngineResource.class);

	/**
	 * ExecuteFunctionEngine(engine=[functionId], map=[{...}])
	 */
	@POST
	@Path("/execute")
	@Consumes({ "application/x-www-form-urlencoded", "application/json" })
	@Produces("application/json;charset=utf-8")
	public Response execute(@Context HttpServletRequest request, @PathParam("functionId") String functionId) {
		functionId = WebUtility.inputSanitizer(functionId);

		Map<String, Object> body;
		try {
			body = ResourceUtility.parseRequestBody(request);
		} catch (IOException e) {
			classLogger.error("Failed to read request body for /execute on function engine '{}'", functionId, e);
			return EngineRouteResource.error("Invalid request body: " + e.getMessage(), 400);
		}

		// Allow inputs as either "map" (raw map of inputs) or under "inputs"
		Object map = body.get("map");
		if (map == null) {
			map = body.get("inputs");
		}

		StringBuilder pixel = new StringBuilder("ExecuteFunctionEngine(engine=");
		pixel.append(EngineRouteResource.GSON.toJson(functionId));
		if (map != null) {
			pixel.append(", map=").append(EngineRouteResource.GSON.toJson(map));
		}
		pixel.append(")");

		return ResourceUtility.runPixel(request, pixel.toString());
	}

	/**
	 * GetFunctionEngineDefintion(engine=[functionId])
	 */
	@GET
	@Path("/definition")
	@Produces("application/json;charset=utf-8")
	public Response getDefinition(@Context HttpServletRequest request, @PathParam("functionId") String functionId) {
		functionId = WebUtility.inputSanitizer(functionId);

		String pixel = "GetFunctionEngineDefintion(engine=" + EngineRouteResource.GSON.toJson(functionId) + ")";
		return ResourceUtility.runPixel(request, pixel);
	}

}
