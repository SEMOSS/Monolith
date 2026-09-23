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
import java.util.List;
import java.util.Map;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import prerna.auth.User;
import prerna.theme.AbstractThemeUtils;
import prerna.theme.AdminThemeUtils;
import prerna.web.services.util.WebUtility;

@Path("/themes")
@PermitAll
public class AdminThemeResource {

	private static void checkInit() throws IllegalAccessException {
		if (!AbstractThemeUtils.isInitalized()) {
			throw new IllegalAccessException("Theming database was not found to perform these operations");
		}
	}

	@GET
	@Path("/getActiveAdminTheme")
	@Produces("application/json")
	public Response getActiveAdminTheme(@Context HttpServletRequest request) {
		try {
			checkInit();
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		Object activeTheme = AdminThemeUtils.getActiveAdminTheme();
		return WebUtility.getResponse(activeTheme, 200);
	}

	@GET
	@Path("/getAdminThemes")
	@Produces("application/json")
	public Response getAdminThemes(@Context HttpServletRequest request, @QueryParam("limit") Integer limit,
			@QueryParam("offset") Integer offset) {
		try {
			checkInit();
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		AdminThemeUtils instance = AdminThemeUtils.getInstance(user);
		if (instance == null) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User is not an admin");
			return WebUtility.getResponse(errorMap, 401);
		}
		List<Map<String, Object>> themes = instance.getAdminThemes(limit, offset);
		return WebUtility.getResponse(themes, 200);
	}

	@POST
	@Path("/createAdminTheme")
	@Produces("application/json")
	public Response createAdminTheme(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		try {
			checkInit();
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		AdminThemeUtils instance = AdminThemeUtils.getInstance(user);
		if (instance == null) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User is not an admin");
			return WebUtility.getResponse(errorMap, 401);
		}

		String themeName = WebUtility.inputSanitizer(form.getFirst("name"));
		String themeMap = WebUtility.inputSQLSanitizer(form.getFirst("json"));
		boolean isActive = Boolean.parseBoolean(form.getFirst("isActive"));
		String themeId = instance.createAdminTheme(themeName, themeMap, isActive);
		if (themeId != null) {
			return WebUtility.getResponse(true, 200);
		} else {
			return WebUtility.getResponse(false, 400);
		}
	}

	@POST
	@Path("/editAdminTheme")
	@Produces("application/json")
	public Response editAdminTheme(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		try {
			checkInit();
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		AdminThemeUtils instance = AdminThemeUtils.getInstance(user);
		if (instance == null) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User is not an admin");
			return WebUtility.getResponse(errorMap, 401);
		}

		String themeId = WebUtility.inputSanitizer(form.getFirst("id"));
		String themeName = WebUtility.inputSanitizer(form.getFirst("name"));
		String themeMap = WebUtility.inputSQLSanitizer(form.getFirst("json"));
		boolean isActive = Boolean.parseBoolean(form.getFirst("isActive"));
		boolean success = instance.editAdminTheme(themeId, themeName, themeMap, isActive);
		if (success) {
			return WebUtility.getResponse(success, 200);
		} else {
			return WebUtility.getResponse(success, 400);
		}
	}

	@POST
	@Path("/deleteAdminTheme")
	@Produces("application/json")
	public Response deleteAdminTheme(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		try {
			checkInit();
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		AdminThemeUtils instance = AdminThemeUtils.getInstance(user);
		if (instance == null) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User is not an admin");
			return WebUtility.getResponse(errorMap, 401);
		}

		String themeId = WebUtility.inputSanitizer(form.getFirst("id"));
		boolean success = instance.deleteAdminTheme(themeId);
		if (success) {
			return WebUtility.getResponse(success, 200);
		} else {
			return WebUtility.getResponse(success, 400);
		}
	}

	@POST
	@Path("/setActiveAdminTheme")
	@Produces("application/json")
	public Response setActiveAdminTheme(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		try {
			checkInit();
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		AdminThemeUtils instance = AdminThemeUtils.getInstance(user);
		if (instance == null) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User is not an admin");
			return WebUtility.getResponse(errorMap, 401);
		}

		String themeId = WebUtility.inputSanitizer(form.getFirst("id"));
		boolean success = instance.setActiveTheme(themeId);
		if (success) {
			return WebUtility.getResponse(success, 200);
		} else {
			return WebUtility.getResponse(success, 400);
		}
	}

	@POST
	@Path("/setAllAdminThemesInactive")
	@Produces("application/json")
	public Response setAllAdminThemesInactive(@Context HttpServletRequest request) {
		try {
			checkInit();
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		AdminThemeUtils instance = AdminThemeUtils.getInstance(user);
		if (instance == null) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User is not an admin");
			return WebUtility.getResponse(errorMap, 401);
		}

		boolean success = instance.setAllThemesInactive();
		if (success) {
			return WebUtility.getResponse(success, 200);
		} else {
			return WebUtility.getResponse(success, 400);
		}
	}

}
