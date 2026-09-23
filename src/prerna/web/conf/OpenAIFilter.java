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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpSession;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityUserAccessKeyUtils;
import prerna.semoss.web.services.local.UserResource;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;

public class OpenAIFilter implements Filter {

	private static final Logger classLogger = LogManager.getLogger(OpenAIFilter.class);

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

		// see if there is an auth value
		String authValue = request.getHeader("Authorization");
		if (authValue == null) {
			authValue = request.getHeader("authorization");
			if (authValue == null) {
				// no token? just go through and other filters will validate
				arg2.doFilter(arg0, arg1);
				return;
			}
		}

		// We pass our access key and secret key in an f string separated by a colon
		// (ex. f"{access_key}:{secret_key}") in the api_key parameter
		// OpenAI sets this as the "Bearer " authorization
		if (authValue.startsWith("Bearer") || authValue.startsWith("bearer")) {
			String bearerToken = authValue.substring("Bearer".length()).trim();

			if (bearerToken != null && !bearerToken.isEmpty()) {
				String[] split = bearerToken.split(":");
				if (split != null && split.length == 2) {
					String accessKey = split[0];
					String secretKey = split[1];

					AccessToken token = null;
					try {
						token = SecurityUserAccessKeyUtils.validateKeysAndReturnToken(accessKey, secretKey);
					} catch (IllegalAccessException e) {
						classLogger.error("Error validating user access key '{}' against secret key", accessKey, e);
					}
					if (token == null) {
						classLogger.error("User could not login using user access key '{}' with invalid secret key",
								accessKey);
					} else {
						// let us make sure this login type is still allowed to login via access/secret
						// key
						{
							AuthProvider provider = token.getProvider();
							boolean accessKeysAllowed = SocialPropertiesUtil.getInstance().accessKeysAllowed(provider);
							if (!accessKeysAllowed) {
								classLogger.error(
										"User is trying to login using access/secret key but administrator has disabeled for provider {}",
										provider.name());
								user = null;
								token = null;
							}
						}
					}

					if (token != null) {
						SecurityUserAccessKeyUtils.updateAccessTokenLastUsed(accessKey);
						UserResource.addAccessToken(token, request, false);
						classLogger.info("User is logging in with provider {} with user access key",
								token.getProvider());
					}
				}
			}
		}

		// doesn't matter if we made a user or didn't
		// we will continue the filter chain because the {@link NoUserInSessionFilter}
		// will catch unauthorized access

		// wrap the request to allow subsequent reading
		HttpServletRequestWrapper requestWrapper = new HttpServletRequestWrapper(request);
		arg2.doFilter(requestWrapper, arg1);
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
