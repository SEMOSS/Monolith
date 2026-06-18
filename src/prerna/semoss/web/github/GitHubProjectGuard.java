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
package prerna.semoss.web.github;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.WebApplicationException;

import prerna.auth.User;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.web.services.util.WebUtility;

/**
 * Shared access guard for the per-project GitHub endpoints.
 * <p>
 * Linking, re-pointing, or disconnecting a repository changes a specific
 * project, so these endpoints require a logged-in user who owns that project.
 * Centralizing the check keeps those endpoints consistent (mirrors
 * {@link GitHubAdminGuard}).
 */
class GitHubProjectGuard {

	private GitHubProjectGuard() {
		// utility class
	}

	/**
	 * Ensures the caller is logged in and owns {@code projectId}, otherwise throws
	 * a {@link WebApplicationException} carrying a JSON error (400 when no project
	 * id was given, 401 when there is no valid session, 403 when the user does not
	 * own the project) for the resource layer to return.
	 *
	 * @param req       the incoming request (used to resolve the session user)
	 * @param projectId the project the caller is trying to act on
	 */
	static void requireProjectOwner(HttpServletRequest req, String projectId) {
		if (projectId == null || projectId.trim().isEmpty()) {
			throw new WebApplicationException(
					WebUtility.getResponse(Map.of("status", "error", "reason", "projectId is required"), 400));
		}

		User user;
		try {
			user = ResourceUtility.getUser(req);
		} catch (IllegalAccessException e) {
			throw new WebApplicationException(
					WebUtility.getResponse(Map.of("status", "error", "reason", "login required"), 401));
		}

		if (!SecurityProjectUtils.userIsOwner(user, projectId)) {
			throw new WebApplicationException(WebUtility
					.getResponse(Map.of("status", "error", "reason", "owner access to this project is required"), 403));
		}
	}
}
