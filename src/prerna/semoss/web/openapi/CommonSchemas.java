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
package prerna.semoss.web.openapi;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response shapes many REST operations share, for their {@code @ApiResponse}s.
 * <p>
 * Every schema in the OpenAPI document is named by its record's simple name, so
 * a record here, or next to a resource, needs a name no other record uses.
 */
public final class CommonSchemas {

	private CommonSchemas() {
	}

	/**
	 * The body of most failed requests: {@code { "errorMessage": "..." }}, built
	 * with {@code Constants.ERROR_MESSAGE}.
	 */
	@Schema(description = "A failed request")
	public record ErrorMessage(@Schema(description = "Why the request failed", requiredMode = REQUIRED) String errorMessage) {
	}

	/** {@code { "error": "..." }}, which some resources send instead. */
	@Schema(description = "A failed request")
	public record ErrorText(@Schema(description = "Why the request failed", requiredMode = REQUIRED) String error) {
	}

	/** {@code { "success": true }}. */
	@Schema(description = "Whether the change was made")
	public record Success(@Schema(description = "True when the change was made", requiredMode = REQUIRED) boolean success) {
	}

	/** {@code { "output": "..." }}. */
	@Schema(description = "A text result")
	public record TextOutput(@Schema(description = "The result", requiredMode = REQUIRED) String output) {
	}
}
