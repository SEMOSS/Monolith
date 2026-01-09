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
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityUserAccessKeyUtils;
import prerna.io.connector.IAccessTokenFiller;
import prerna.semoss.web.services.local.UserResource;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;
import prerna.web.services.util.WebUtility;

public class UserAccessKeyFilter implements Filter {

	private static final Logger classLogger = LogManager.getLogger(UserAccessKeyFilter.class);

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

		/*
		 * Check if bearer token is passed Which will initiate a lookup against some SSO
		 * provider
		 * 
		 * Else if Basic is used This will initiate a platform access/secret key pair
		 * being used
		 */

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

		if (authValue.startsWith("Bearer") || authValue.startsWith("bearer")) {
			String bearerToken = authValue.substring("Bearer".length()).trim();

			// attempt to login using bearer token
			String provider = WebUtility.inputSanitizer(request.getHeader("Bearer-Provider"));
			if (provider == null) {
				// try to guess
				List<Map<String, Object>> loginsMap = SocialPropertiesUtil.getInstance().getAvailableProviders();
				Set<String> allowedLogins = new HashSet<>();
				for (Map<String, Object> loginInfo : loginsMap) {
					// check if this is OAuth
					if ((boolean) loginInfo.get("isOauth")) {
						allowedLogins.add((String) loginInfo.get("label"));
					}
				}

				if (allowedLogins.size() == 1) {
					provider = allowedLogins.iterator().next();
				} else {
					classLogger.warn("Bearer token passed but unknown provider to use");
					arg2.doFilter(arg0, arg1);
					return;
				}
			}

			if (provider != null) {
				SocialPropertiesUtil socialData = SocialPropertiesUtil.getInstance();

				Map<String, Boolean> loginsMap = socialData.getLoginsAllowed();
				Boolean providerLogin = loginsMap.get(provider.toLowerCase());
				if (providerLogin == null || !providerLogin) {
					classLogger.warn("User is attempting to login using bearer token for provider '" + provider
							+ "' but provider either does not exist or login is not allowed");
					arg2.doFilter(arg0, arg1);
					return;
				}

				AuthProvider thisProvider = AuthProvider.valueOf(provider.toUpperCase());
				String tokenFillerClass = thisProvider.getTokenFillerClass();
				if (tokenFillerClass == null) {
					classLogger.warn(
							"Attempting to login using access token but this functionality is not implemented for auth provider "
									+ thisProvider.getLabel());
					arg2.doFilter(arg0, arg1);
					return;
				}

				try {
					IAccessTokenFiller thisTokenFiller = (IAccessTokenFiller) Class.forName(tokenFillerClass)
							.newInstance();
					String prefix = thisProvider.getLabel().toLowerCase() + "_";
					String userInfoURL = socialData.getProperty(prefix + "userinfo_url");
					// "name","id","email"
					String beanProps = socialData.getProperty(prefix + "beanProps");
					String[] beanPropsArr = null;
					if (beanProps != null) {
						beanProps.split(",", -1);
					}
					String jsonPattern = socialData.getProperty(prefix + "jsonPattern");
					boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));
					// this is a check for sanitizing a response back from an IAM provider - not
					// common and should be false
					// examples would be unescaped special chars in the response that then can't be
					// parsed into a json.
					// this is not very common.
					boolean sanitizeResponse = Boolean
							.parseBoolean(socialData.getProperty(prefix + "sanitizeUserResponse"));

					AccessToken accessToken = new AccessToken();
					accessToken.setProvider(thisProvider);
					accessToken.setAccess_token(bearerToken);
					thisTokenFiller.fillAccessToken(accessToken, userInfoURL, jsonPattern, beanPropsArr, null,
							sanitizeResponse);
					// now store in the session
					UserResource.addAccessToken(accessToken, request, autoAdd);
				} catch (Exception e) {
					classLogger.error(Constants.STACKTRACE, e);
				}
			}
		} else if (authValue.startsWith("Basic") || authValue.startsWith("basic")) {
			String basicAuth = authValue.substring("basic".length()).trim();

			// this is a base64 encoded username:password
			byte[] decodedBytes = Base64.getDecoder().decode(basicAuth);
			String userpass = new String(decodedBytes);
			if (userpass != null && !userpass.isEmpty()) {
				String[] split = userpass.split(":");
				if (split != null && split.length == 2) {
					String accessKey = split[0];
					String secretKey = split[1];

					try {
						user = SecurityUserAccessKeyUtils.validateKeysAndReturnUser(accessKey, secretKey);
					} catch (IllegalAccessException e) {
						classLogger.error(Constants.STACKTRACE, e);
					}
					if (user == null) {
						classLogger.error("User could not login using user access key '" + accessKey
								+ "' with invalid secret key");
					}

					AccessToken token = null;
					if (user != null) {
						token = user.getPrimaryLoginToken();
						// let us make sure this login type is still allowed to login via access/secret
						// key
						{
							AuthProvider provider = token.getProvider();
							boolean accessKeysAllowed = SocialPropertiesUtil.getInstance().accessKeysAllowed(provider);
							if (!accessKeysAllowed) {
								classLogger.error(
										"User is trying to login using access/secret key but administrator has disabeled for provider "
												+ provider.name());
								user = null;
								token = null;
							}
						}
					}

					if (user != null && token != null) {
						SecurityUserAccessKeyUtils.updateAccessTokenLastUsed(accessKey);
						session = request.getSession(true);
						session.setAttribute(Constants.SESSION_USER, user);
						session.setAttribute(Constants.SESSION_USER_ID_LOG, token.getId());
						classLogger.info(
								"User is logging in with provider " + token.getProvider() + " with user access key");
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
