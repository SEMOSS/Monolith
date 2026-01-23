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
import java.util.HashMap;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.AccessToken;
import prerna.auth.User;
import prerna.auth.utils.SecurityShareSessionUtils;
import prerna.semoss.web.services.local.UserResource;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

public class ShareSessionFilter implements Filter {

	private static final Logger classLogger = LogManager.getLogger(ShareSessionFilter.class);
	private static final String SHARE_TOKEN_KEY = "sessionToken";

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
			throws IOException, ServletException {

		// this will be the full path of the request
		// like http://localhost:8080/Monolith_Dev/api/engine/runPixel

		String fullUrl = WebUtility.cleanHttpResponse(((HttpServletRequest) arg0).getRequestURL().toString());
		String contextPath = ((HttpServletRequest) arg0).getContextPath();

		// due to FE being annoying
		// we need to push a response for this one end point
		// since security is embedded w/ normal semoss and not standalone

		HttpSession session = ((HttpServletRequest) arg0).getSession(false);
		User user = null;
		if (session != null) {
			// System.out.println("Session ID >> " + session.getId());
			user = (User) session.getAttribute(Constants.SESSION_USER);
		}

		HttpServletRequest req = (HttpServletRequest) arg0;
		String currentQueryString = req.getQueryString();
		Map<String, String> parsedQueryParams = parseQueryParameters(currentQueryString);
		if (parsedQueryParams.get(SHARE_TOKEN_KEY) != null) {
			String shareToken = WebUtility.cleanHttpResponse(parsedQueryParams.get(SHARE_TOKEN_KEY));

			// user doesn't exist, lets try to validate
			if (user == null || user.getLogins().isEmpty()) {
				try {
					Object[] shareDetails = SecurityShareSessionUtils.getShareSessionDetails(shareToken);
					if (shareDetails == null) {
						classLogger.info("User is trying to login through a share token but the token '" + shareToken
								+ "' doesn't exist");
					}

					boolean shareSession = (boolean) shareDetails[6];
					boolean shareAuth = (boolean) shareDetails[7];
					// this either returns true or throws an error
					SecurityShareSessionUtils.validateShareSessionDetails(shareDetails);
					if (shareSession) {
						classLogger.info("User has successfully used a share token '" + shareToken
								+ "' to attempt to redirect to the session and login");

						String sessionId = (String) shareDetails[1];
						String routeId = (String) shareDetails[2];
						// create the cookie add it and sent it back
						Cookie k = new Cookie(DBLoader.getSessionIdKey(), sessionId);
						k.setHttpOnly(true);
						k.setSecure(req.isSecure());
						k.setPath(contextPath);
						((HttpServletResponse) arg1).addCookie(k);
						// in case there are other JSESSIONID
						// cookies, reset the value to the correct sessionId
						Cookie[] cookies = req.getCookies();
						if (cookies != null) {
							for (Cookie c : cookies) {
								if (c.getName().equals(DBLoader.getSessionIdKey())) {
									c.setValue(sessionId);
									((HttpServletResponse) arg1).addCookie(c);
								}
							}
						}

						// add route if it exists
						String routeCookieName = Utility.getDIHelperProperty(Constants.LOAD_BALANCER_COOKIE_NAME);
						if (routeCookieName != null && !routeCookieName.isEmpty() && routeId != null
								&& !routeId.isEmpty()) {
							Cookie c = new Cookie(routeCookieName, routeId);
							c.setHttpOnly(true);
							c.setSecure(req.isSecure());
							c.setPath(contextPath);
							((HttpServletResponse) arg1).addCookie(c);
						}
					} else if (shareAuth) {
						classLogger.info(
								"User has successfully used a share token '" + shareToken + "' to for authentication");

						AccessToken token = SecurityShareSessionUtils.generateAccessTokenForShareAuth(shareDetails);
						UserResource.addAccessToken(token, req, false);
						// continue with the filter chain
						// wrap the request to allow subsequent reading
						HttpServletRequestWrapper requestWrapper = new HttpServletRequestWrapper(req);
						// continue with the filter chain
						arg2.doFilter(requestWrapper, arg1);
						return;
					} else {
						classLogger.warn(
								"ShareSessionFilter token is not properly set for sharing a session or authorization");
					}
				} catch (Exception e) {
					classLogger.info("User is trying to login through a auth/share token but the token '" + shareToken
							+ "' resulted in the error: " + e.getMessage());
					classLogger.error(Constants.STACKTRACE, e);
					arg2.doFilter(arg0, arg1);
					return;
				}
			} else {
				// user does exist
				// why do you have the share session still?
				// i'm going to remove it
				// and redirect you back
				classLogger.info("User is already logged in but trying to login again using a auth/share token '"
						+ shareToken + "'");
				arg2.doFilter(arg0, arg1);
				return;
			}

			// and now redirect back to the URL
			// if get, we can do it
			String method = req.getMethod();
			if (method.equalsIgnoreCase("GET")) {
				// modify the prefix if necessary
				Map<String, String> envMap = System.getenv();
				if (envMap.containsKey(Constants.MONOLITH_PREFIX)) {
					fullUrl = fullUrl.replace(contextPath, envMap.get(Constants.MONOLITH_PREFIX));
				}

				((HttpServletResponse) arg1).setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY);
				String newQueryString = removeQueryParam(currentQueryString, SHARE_TOKEN_KEY);
				if (newQueryString != null && !newQueryString.isEmpty()) {
					((HttpServletResponse) arg1).sendRedirect(fullUrl + "?" + newQueryString);
				} else {
					((HttpServletResponse) arg1).sendRedirect(fullUrl);
				}
				return;
			} else if (method.equalsIgnoreCase("POST")) {
				// modify the prefix if necessary
				Map<String, String> envMap = System.getenv();
				if (envMap.containsKey(Constants.MONOLITH_PREFIX)) {
					fullUrl = fullUrl.replace(contextPath, envMap.get(Constants.MONOLITH_PREFIX));
				}

				((HttpServletResponse) arg1).setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
				((HttpServletResponse) arg1).setHeader("Location", fullUrl);
				return;
			}
		}

		// wrap the request to allow subsequent reading
		HttpServletRequestWrapper requestWrapper = new HttpServletRequestWrapper(req);
		// continue with the filter chain
		arg2.doFilter(requestWrapper, arg1);
	}

	/**
	 * 
	 * @param query
	 * @param parameterToRemove
	 * @return
	 */
	private static String removeQueryParam(String query, String parameterToRemove) {
		if (query == null) {
			return null;
		}
		String[] params = query.split("&");
		StringBuilder updatedQuery = new StringBuilder();

		for (String param : params) {
			String[] keyValue = param.split("=");
			if (keyValue.length > 0 && !keyValue[0].equals(parameterToRemove)) {
				if (updatedQuery.length() > 0) {
					updatedQuery.append("&");
				}
				updatedQuery.append(param);
			}
		}

		return updatedQuery.toString();
	}

	/**
	 * 
	 * @param query
	 * @return
	 */
	private static Map<String, String> parseQueryParameters(String query) {
		Map<String, String> queryParams = new HashMap<>();
		if (query == null) {
			return queryParams;
		}

		// Split query string by "&" to get individual parameters
		String[] params = query.split("&");
		for (String param : params) {
			// Split each parameter by "=" to get key-value pairs
			String[] keyValue = param.split("=");
			if (keyValue.length == 2) {
				// Store key-value pairs in the map
				String key = keyValue[0];
				String value = keyValue[1];
				queryParams.put(key, value);
			}
		}

		return queryParams;
	}

	@Override
	public void destroy() {
		// destroy
	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		// initialize
	}

}
