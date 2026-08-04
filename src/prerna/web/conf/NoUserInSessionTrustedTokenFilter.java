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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.catalina.session.StandardManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.semoss.web.services.local.SessionResource;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

public class NoUserInSessionTrustedTokenFilter implements Filter {

	private static final Logger classLogger = LogManager.getLogger(NoUserInSessionTrustedTokenFilter.class);

	private static String TRUSTED_TOKEN_PREFIX = "trustedTokenPrefix";
	private static String TRUSTED_TOKEN_DOMAIN = "trustedTokenDomain";

	private static String tokenName = null;
	private static List<String> trustedDomains = null;

	// maps from the IP the user is coming in with the cookie
	private static Map<String, String> sessionMapper = new ConcurrentHashMap<>();

	private FilterConfig filterConfig;

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
			throws IOException, ServletException {
		setInitParams(arg0);

		// this will be the full path of the request
		// like http://localhost:8080/Monolith_Dev/api/engine/runPixel
		String fullUrl = WebUtility.cleanHttpResponse(((HttpServletRequest) arg0).getRequestURL().toString());
		String contextPath = ((HttpServletRequest) arg0).getContextPath();
		HttpSession session = ((HttpServletRequest) arg0).getSession(false);

		User user = null;
		if (session != null) {
			user = (User) session.getAttribute(Constants.SESSION_USER);
		}

		// if we have a user, there is nothing to do
		if (user == null) {

			// the front end comes with
			// fullUrl?prefix_token=userId
			// check if the ip address is allowed
			// check if the userId actually exists
			// if first time, add the user
			// if not, redirect the GET/POST call

			HttpServletRequest req = (HttpServletRequest) arg0;
			// grab the token id
			// if the token exists
			String userId = WebUtility.cleanHttpResponse(req.getParameter(tokenName));
			if (userId != null) {
				boolean redirectToExistingSession = false;
				String redirectSessionId = sessionMapper.get(userId);
				if (redirectSessionId != null) {
					// validate that the session exists within tomcats session manager
					session = ((HttpServletRequest) arg0).getSession();
					StandardManager manager = SessionResource.getManager(session);
					if (manager.getSession(redirectSessionId) != null) {
						redirectToExistingSession = true;
						// we are going to try to redirect
						// so invalidate this new session
						if (((HttpServletRequest) arg0).isRequestedSessionIdValid()) {
							session.invalidate();
						}
					} else {
						// remove from the session mapper
						sessionMapper.remove(userId);
					}
				}
				if (!redirectToExistingSession) {
					// grab the ip address
					String ipAddress = req.getHeader("X-FORWARDED-FOR");
					if (ipAddress == null) {
						ipAddress = req.getRemoteAddr();
					}
					// check if the ip address is allowed
					boolean allow = trustedDomains.contains("*");
					if (!allow) {
						for (String domain : trustedDomains) {
							if (ipAddress.matches(domain)) {
								allow = true;
								break;
							}
						}
					}
					if (allow && SecurityQueryUtils.checkUserExist(userId)) {
						// you are allowed
						// i just have to check if the token id exists
						// and id you do, i make the user object
						user = new User();
						AccessToken token = new AccessToken();
						token.setProvider(AuthProvider.WINDOWS_USER);
						token.setId(userId);
						token.setName(userId);
						user.setAccessToken(token);
						// if the session hasn't been instantiated yet
						// start one
						if (session == null) {
							session = ((HttpServletRequest) arg0).getSession();
						}
						session.setAttribute(Constants.SESSION_USER, user);

						String sessionId = session.getId();
						sessionMapper.put(userId, sessionId);

						// add the session id cookie
						// use addHeader to allow for SameSite option
						// SameSite only works if Secure tag also there
						String setCookieString = DBLoader.getSessionIdKey() + "=" + sessionId + "; Path=" + contextPath
								+ "; HttpOnly"
								+ ((ClusterUtil.IS_CLUSTER || req.isSecure()) ? "; Secure; SameSite=None" : "");
						((HttpServletResponse) arg1).addHeader("Set-Cookie", setCookieString);
					} else {
						// invalidate the session
						if (((HttpServletRequest) arg0).isRequestedSessionIdValid()) {
							session.invalidate();
						}
					}
				} else {
					// this is the case where you redirect
					// we have also validated that the session id is active

					// add the session id cookie
//						Cookie k = new Cookie(DBLoader.getSessionIdKey(), redirectSessionId);
//						k.setHttpOnly(true);
//						k.setSecure(req.isSecure());
//						k.setPath(contextPath);
//						((HttpServletResponse)arg1).addCookie(k);
					// replace any other session id cookies
					Cookie[] cookies = req.getCookies();
					if (cookies != null) {
						classLogger.info("Forcing session value !");
						for (Cookie c : cookies) {
							if (c.getName().equals(DBLoader.getSessionIdKey())) {
								if (c.getName().equalsIgnoreCase(DBLoader.getSessionIdKey())) {
									c.setValue(redirectSessionId);
								}
							}
						}
					}

					// add the session id cookie
					// use addHeader to allow for SameSite option
					// SameSite only works if Secure tag also there
					String setCookieString = DBLoader.getSessionIdKey() + "=" + redirectSessionId + "; Path="
							+ contextPath + "; HttpOnly"
							+ ((ClusterUtil.IS_CLUSTER || req.isSecure()) ? "; Secure; SameSite=None" : "");

					String method = req.getMethod();
					if (method.equalsIgnoreCase("GET")) {
						((HttpServletResponse) arg1).addHeader("Set-Cookie", setCookieString);
						((HttpServletResponse) arg1).setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY);
						((HttpServletResponse) arg1).sendRedirect(fullUrl + "?" + req.getQueryString());
						return;
					} else if (method.equalsIgnoreCase("POST")) {
						((HttpServletResponse) arg1).addHeader("Set-Cookie", setCookieString);
						((HttpServletResponse) arg1).setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
						((HttpServletResponse) arg1).setHeader("Location", fullUrl);
						return;
					}
				}
			}
		}
//		else {
//			System.out.println("Have user = " + user.getAccessToken(user.getPrimaryLogin()).getId());
//		}
		arg2.doFilter(arg0, arg1);
	}

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}

	/**
	 * Remove the session from the mapper
	 * 
	 * @param sessionId
	 */
	public static void removeSession(String sessionId) {
		Iterator<String> iterator = NoUserInSessionTrustedTokenFilter.sessionMapper.keySet().iterator();
		while (iterator.hasNext()) {
			String key = iterator.next();
			if (NoUserInSessionTrustedTokenFilter.sessionMapper.get(key).equals(sessionId)) {
				// remove this
				iterator.remove();
			}
		}
	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		this.filterConfig = arg0;
	}

	private void setInitParams(ServletRequest arg0) {
		// the token name
		if (NoUserInSessionTrustedTokenFilter.tokenName == null) {
			NoUserInSessionTrustedTokenFilter.tokenName = this.filterConfig
					.getInitParameter(NoUserInSessionTrustedTokenFilter.TRUSTED_TOKEN_PREFIX);
		}

		// the token domains
		if (NoUserInSessionTrustedTokenFilter.trustedDomains == null) {
			String[] trustedIPs = this.filterConfig
					.getInitParameter(NoUserInSessionTrustedTokenFilter.TRUSTED_TOKEN_DOMAIN).split(",");
			NoUserInSessionTrustedTokenFilter.trustedDomains = new Vector<>();
			for (String trustedIP : trustedIPs) {
				NoUserInSessionTrustedTokenFilter.trustedDomains.add(trustedIP.toLowerCase());
			}
		}
	}

}
