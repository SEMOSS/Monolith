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
package prerna.web.conf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityAPIUserUtils;
import prerna.semoss.web.services.local.UserResource;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;
import prerna.util.Utility;

/**
 * Signs in an API user, a robot account an admin creates, from
 * {@code Authorization: Basic <client id>:<secret>} on each request. It runs
 * only while {@code api_user_login} is on. Any other Authorization header is
 * left to the filters after it, such as {@link UserAccessKeyFilter} for a
 * person's access keys.
 */
public class ApiUserLoginFilter implements Filter {

	private static final Logger classLogger = LogManager.getLogger(ApiUserLoginFilter.class);

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
			throws IOException, ServletException {
		HttpServletRequest request = (HttpServletRequest) arg0;
		HttpSession session = request.getSession(false);
		User user = null;
		if (session != null) {
			user = (User) session.getAttribute(Constants.SESSION_USER);
		}

		if (user != null) {
			arg2.doFilter(arg0, arg1);
			return;
		}

		String path = request.getRequestURI();
		if (path.contains("api/model/openai") || path.contains("api/ext/")) {
			arg2.doFilter(arg0, arg1);
			return;
		}

		SocialPropertiesUtil socialData = SocialPropertiesUtil.getInstance();
		if (socialData.getLoginsAllowed().get("api_user") == null || !socialData.getLoginsAllowed().get("api_user")) {
			// api user login is not enabled
			arg2.doFilter(arg0, arg1);
			return;
		}

		String authValue = request.getHeader("Authorization");
		if (authValue == null) {
			authValue = request.getHeader("authorization");
		}
		if (authValue == null || !authValue.startsWith("Basic")) {
			arg2.doFilter(arg0, arg1);
			return;
		}

		String clientAndSecret;
		try {
			clientAndSecret = new String(Base64.getDecoder().decode(authValue.substring("Basic".length()).trim()),
					StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			classLogger.warn("Ignoring a Basic Authorization header that is not valid base64");
			arg2.doFilter(arg0, arg1);
			return;
		}

		// the secret may itself contain a colon, so only the first one separates them
		String[] split = clientAndSecret.split(":", 2);
		if (split.length == 2) {
			String clientId = split[0];
			if (SecurityAPIUserUtils.validCredentials(clientId, split[1])) {
				AccessToken token = new AccessToken();
				token.setId(clientId);
				token.setProvider(AuthProvider.API_USER);
				UserResource.addAccessToken(token, request, false);
				classLogger.info("API user {} is logging in with its client id and secret",
						Utility.cleanLogString(clientId));
			} else {
				classLogger.error("Could not login as API_USER with invalid credentials using client id = '{}'",
						Utility.cleanLogString(clientId));
			}
		}

		// doesn't matter if we made a user or didn't
		// we will continue the filter chain because the {@link NoUserInSessionFilter}
		// will catch unauthorized access
		arg2.doFilter(arg0, arg1);
	}

	@Override
	public void destroy() {
		// destroy

	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		// init
	}

}
