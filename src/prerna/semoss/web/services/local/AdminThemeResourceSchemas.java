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

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The requests and responses of {@link AdminThemeResource}, for its OpenAPI
 * annotations. Theme rows keep the column names of the theming database's
 * {@code ADMIN_THEME} table.
 */
public final class AdminThemeResourceSchemas {

	private AdminThemeResourceSchemas() {
	}

	@Schema(description = "An admin theme")
	public record AdminThemesTheme(@Schema(description = "The theme id", requiredMode = REQUIRED) String ID,
			@Schema(description = "The theme name") String THEME_NAME,
			@Schema(description = "JSON-encoded theme map") String THEME_MAP,
			@Schema(description = "Whether this is the active theme") Boolean IS_ACTIVE) {
	}

	@Schema(description = "The active admin theme; an empty object when no theme is active or the theme cannot be read")
	public record AdminThemesActiveTheme(@Schema(description = "The theme id") String ID,
			@Schema(description = "The theme name") String THEME_NAME,
			@Schema(description = "JSON-encoded theme map") String THEME_MAP,
			@Schema(description = "Whether this is the active theme; true when present") Boolean IS_ACTIVE) {
	}

	@Schema(description = "A new admin theme")
	public record AdminThemesCreateRequest(@Schema(description = "The theme name") String name,
			@Schema(description = "JSON-encoded theme map") String json,
			@Schema(description = "\"true\" to make it the active theme") String isActive) {
	}

	@Schema(description = "The new values of an admin theme")
	public record AdminThemesEditRequest(@Schema(description = "The theme id", requiredMode = REQUIRED) String id,
			@Schema(description = "The theme name") String name,
			@Schema(description = "JSON-encoded theme map") String json,
			@Schema(description = "\"true\" to make it the active theme") String isActive) {
	}

	@Schema(description = "The admin theme to act on")
	public record AdminThemesIdRequest(@Schema(description = "The theme id", requiredMode = REQUIRED) String id) {
	}
}
