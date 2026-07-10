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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.WebApplicationException;

import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.web.services.util.WebUtility;

/**
 * Shared access guard for the GitHub App admin endpoints.
 * <p>
 * Creating a GitHub App stores its private key and configures the whole
 * instance, so the create/callback/list/delete endpoints are restricted to
 * logged-in instance admins. Centralizing the check keeps those endpoints
 * consistent (mirrors {@link GitHubProjectGuard}).
 */
class GitHubAdminGuard {

	private GitHubAdminGuard() {
		// utility class
	}

	/**
	 * Ensures the caller is a logged-in instance admin, otherwise throws a
	 * {@link WebApplicationException} carrying a JSON error (401 when there is no
	 * valid session, 403 when the user is not an admin) for the resource layer to
	 * return.
	 *
	 * @param req the incoming request (used to resolve the session user)
	 */
	static void requireAdmin(HttpServletRequest req) {
		User user;
		try {
			user = ResourceUtility.getUser(req);
		} catch (IllegalAccessException e) {
			throw new WebApplicationException(
					WebUtility.getResponse(Map.of("status", "error", "reason", "login required"), 401));
		}

		if (!Boolean.TRUE.equals(SecurityAdminUtils.userIsAdmin(user))) {
			throw new WebApplicationException(
					WebUtility.getResponse(Map.of("status", "error", "reason", "admin access required"), 403));
		}
	}
}
