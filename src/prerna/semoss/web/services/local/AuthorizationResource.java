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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import prerna.auth.User;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.graph.utility.MsGraphUtility;
import prerna.io.connector.ms.MicrosoftGraphUserLookup;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("/authorization")
public class AuthorizationResource {

	private static final Logger classLogger = LogManager.getLogger(AuthorizationResource.class);
	@Context
	protected ServletContext context;

	/**
	 * Search for users, in the Microsoft directory when it is available, or in the
	 * security database.
	 * 
	 * @param request
	 * @param searchTerm    text to search for
	 * @param limit         page size
	 * @param offset        number of users already returned
	 * @param msGraphLookup {@code false} to search the security database when the
	 *                      directory is available; defaults to the directory
	 * @return
	 */
	@GET
	@Produces("application/json")
	@Path("searchForUser")
	public Response searchForUser(@Context HttpServletRequest request, @QueryParam("searchTerm") String searchTerm,
			@QueryParam("limit") long limit, @QueryParam("offset") long offset,
			@QueryParam(MicrosoftGraphUserLookup.LOOKUP_PARAM) String msGraphLookup) {

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.warn("User  invalid user session trying to access authorization resources");
			classLogger.error("Failed to search for user.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		searchTerm = searchTerm == null ? "" : searchTerm;

		// when the directory is not used
		// we will look at our security db
		if (!MicrosoftGraphUserLookup.useDirectory(msGraphLookup)) {
			try {
				List<Map<String, Object>> users = SecurityQueryUtils.searchForUser(searchTerm);
				int fromIndex = (int) Math.min(Math.max(offset, 0L), users.size());
				int toIndex = limit > 0 ? fromIndex + (int) Math.min((long) users.size() - fromIndex, limit)
						: users.size();
				return WebUtility.getResponse(users.subList(fromIndex, toIndex), 200);
			} catch (Exception e) {
				classLogger.error("Failed to search for user.", e);
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
				return WebUtility.getResponse(errorMap, 500);
			}
		}

		try {
			List<Map<String, Object>> users = MsGraphUtility.searchDirectory(request, user, searchTerm, limit, offset);
			return WebUtility.getResponse(users, 200);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Failed to search for user.", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 500);
		}
	}

}
