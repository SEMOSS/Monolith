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

import java.util.HashMap;
import java.util.Map;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import prerna.om.ThreadStore;
import prerna.rpa.config.JobConfigKeys;
import prerna.semoss.web.openapi.CommonSchemas.ErrorMessage;
import prerna.semoss.web.services.local.SchedulerResourceSchemas.ScheduleExecutePixelError;
import prerna.semoss.web.services.local.SchedulerResourceSchemas.ScheduleExecutePixelRequest;
import prerna.semoss.web.services.local.SchedulerResourceSchemas.ScheduleExecutePixelResult;
import prerna.web.requests.OverrideParametersServletRequest;

@Path("/schedule")
@PermitAll
public class SchedulerResource {

	@POST
	@Path("/executePixel")
	@Produces("application/json")
	@Operation(operationId = "executePixel", summary = "Run a scheduled job's pixel", description = "Runs the pixel in scheduler mode in a new insight, as the scheduler does for a job, then ends the session.")
	@RequestBody(content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = ScheduleExecutePixelRequest.class)))
	@ApiResponse(responseCode = "200", description = "The results of the pixel's steps", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ScheduleExecutePixelResult.class)))
	@ApiResponse(responseCode = "401", description = "The session has no user", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorMessage.class)))
	@ApiResponse(responseCode = "500", description = "The results could not be collected", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ScheduleExecutePixelError.class)))
	public Response executePixel(@Context HttpServletRequest request) {
		// we will flush the user object inside
		// and make sure the
		String pixel = request.getParameter(JobConfigKeys.PIXEL);
		return runPixel(request, pixel);
	}

	/**
	 * Utility method to execute the pixel on the insight
	 * 
	 * @param request
	 * @param pixel
	 * @return
	 */
	private Response runPixel(@Context HttpServletRequest request, String pixel) {
		// do not need this - will invalidate the session
//		if(pixel.endsWith(";")) {
//			pixel = pixel + "DropInsight();";
//		} else {
//			pixel = pixel + ";DropInsight();";
//		}
		pixel = pixel.trim();
		if (!pixel.endsWith(";")) {
			pixel = pixel + ";";
		}
		// set we are scheduler mode
		ThreadStore.setSchedulerMode(true);

		NameServer ns = new NameServer();
		OverrideParametersServletRequest requestWrapper = new OverrideParametersServletRequest(request);
		Map<String, String> paramMap = new HashMap<String, String>();
		paramMap.put("expression", pixel);
		requestWrapper.setParameters(paramMap);
		try {
			return ns.runPixelSync(requestWrapper);
		} finally {
			request.getSession().invalidate();
		}
	}

}
