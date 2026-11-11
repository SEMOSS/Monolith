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
package openapi.fixtures;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import prerna.openapi.Internal;

/** One published, one hidden, one internal and one unannotated method. */
@Path("/things")
public class FixtureResource {

	@Schema(description = "A thing")
	public record Thing(@Schema(description = "Its name", requiredMode = REQUIRED) String name) {
	}

	@GET
	@Path("/list")
	@Produces("application/json")
	@Operation(operationId = "list", summary = "List things")
	@ApiResponse(responseCode = "200", description = "The things", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Thing.class)))
	public Response list() {
		return Response.ok().build();
	}

	@GET
	@Path("/secret")
	@Produces("application/json")
	@Hidden
	@Operation(operationId = "secret", summary = "Not published")
	@ApiResponse(responseCode = "200", description = "A thing", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Thing.class)))
	public Response secret() {
		return Response.ok().build();
	}

	@GET
	@Path("/admin")
	@Produces("application/json")
	@Internal("Admin console only")
	@Operation(operationId = "admin", summary = "Used by the admin console")
	@ApiResponse(responseCode = "200", description = "A thing", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Thing.class)))
	public Response admin() {
		return Response.ok().build();
	}

	@GET
	@Path("/plain")
	@Produces("application/json")
	public Response plain() {
		return Response.ok().build();
	}
}
