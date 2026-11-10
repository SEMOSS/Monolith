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

import java.util.Set;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Response;

/**
 * Resources whose operations show how published operationIds are qualified.
 */
public class NamingApplication extends Application {

	@Schema(description = "A name")
	public record NamingResult(@Schema(description = "The name", requiredMode = REQUIRED) String name) {
	}

	@Path("/project")
	public static class ProjectNaming {

		@POST
		@Path("/getProjects")
		@Produces("application/json")
		@Operation(operationId = "getProjects", summary = "The POST keeps the plain name")
		@ApiResponse(responseCode = "200", description = "A name", content = @Content(mediaType = "application/json", schema = @Schema(implementation = NamingResult.class)))
		public Response post() {
			return Response.ok().build();
		}

		@GET
		@Path("/getProjects")
		@Produces("application/json")
		@Operation(operationId = "getProjectsGet", summary = "The GET of the same path adds Get")
		@ApiResponse(responseCode = "200", description = "A name", content = @Content(mediaType = "application/json", schema = @Schema(implementation = NamingResult.class)))
		public Response get() {
			return Response.ok().build();
		}

		@Path("/sub")
		public SubNaming sub() {
			return new SubNaming();
		}
	}

	/** Reached only through {@link ProjectNaming#sub()}. */
	public static class SubNaming {

		@GET
		@Path("/list")
		@Produces("application/json")
		@Operation(operationId = "list", summary = "Read through a sub-resource locator")
		@ApiResponse(responseCode = "200", description = "A name", content = @Content(mediaType = "application/json", schema = @Schema(implementation = NamingResult.class)))
		public Response list() {
			return Response.ok().build();
		}
	}

	@Path("/admin/project")
	public static class AdminProjectNaming {

		@POST
		@Path("/getProjects")
		@Produces("application/json")
		@Operation(operationId = "getProjects", summary = "The same name in another module")
		@ApiResponse(responseCode = "200", description = "A name", content = @Content(mediaType = "application/json", schema = @Schema(implementation = NamingResult.class)))
		public Response getProjects() {
			return Response.ok().build();
		}
	}

	@Path("/e-{engineId}")
	public static class EngineNaming {

		@GET
		@Path("/type")
		@Produces("application/json")
		@Operation(operationId = "type", summary = "A segment with a path parameter keeps its literal part")
		@ApiResponse(responseCode = "200", description = "A name", content = @Content(mediaType = "application/json", schema = @Schema(implementation = NamingResult.class)))
		public Response type(@PathParam("engineId") String engineId) {
			return Response.ok().build();
		}

		@GET
		@Path("/{name}")
		@Produces("application/json")
		@Operation(operationId = "byName", summary = "A last segment that is a parameter takes a chosen name")
		@ApiResponse(responseCode = "200", description = "A name", content = @Content(mediaType = "application/json", schema = @Schema(implementation = NamingResult.class)))
		public Response byName(@PathParam("engineId") String engineId, @PathParam("name") String name) {
			return Response.ok().build();
		}
	}

	@Path("/browser-sessions/{id}")
	public static class KebabNaming {

		@GET
		@Path("/steps")
		@Produces("application/json")
		@Operation(operationId = "steps", summary = "A kebab-case segment is a lower camel module")
		@ApiResponse(responseCode = "200", description = "A name", content = @Content(mediaType = "application/json", schema = @Schema(implementation = NamingResult.class)))
		public Response steps(@PathParam("id") String id) {
			return Response.ok().build();
		}

		@POST
		@Path("/images/engine/delete")
		@Produces("application/json")
		@Operation(operationId = "delete", summary = "One name in two modules of one resource")
		@ApiResponse(responseCode = "200", description = "A name", content = @Content(mediaType = "application/json", schema = @Schema(implementation = NamingResult.class)))
		public Response deleteEngine(@PathParam("id") String id) {
			return Response.ok().build();
		}

		@POST
		@Path("/images/project/delete")
		@Produces("application/json")
		@Operation(operationId = "delete", summary = "One name in two modules of one resource")
		@ApiResponse(responseCode = "200", description = "A name", content = @Content(mediaType = "application/json", schema = @Schema(implementation = NamingResult.class)))
		public Response deleteProject(@PathParam("id") String id) {
			return Response.ok().build();
		}
	}

	@Override
	public Set<Object> getSingletons() {
		return Set.of(new ProjectNaming(), new AdminProjectNaming(), new EngineNaming(), new KebabNaming());
	}
}
