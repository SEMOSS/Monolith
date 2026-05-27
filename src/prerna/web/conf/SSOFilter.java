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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.conf.util.CACTrackingUtil;
import prerna.web.conf.util.SSOUtil;
import prerna.web.conf.util.UserFileLogUtil;
import prerna.web.services.util.WebUtility;

/**
 * Gatekeeper filter for SAML-protected requests.
 *
 * <p>
 * This filter checks whether a request already has an authenticated SAML user
 * in session. If not, it captures redirect context and sends the client to the
 * SAML login entry page/URL.
 *
 * <p>
 * How this class connects to the SAML flow:
 *
 * <ol>
 * <li>Unauthenticated request reaches this filter first.</li>
 * <li>The filter stores redirect state under
 * {@link SSOUtil#SAML_REDIRECT_KEY}.</li>
 * <li>The browser is redirected to login flow endpoints ({@code IdpSSOServlet}
 * or {@code SPSSOServlet} via the configured login page).</li>
 * <li>After successful callback processing in {@code SamlVerifierServlet},
 * requests pass through this filter as authenticated.</li>
 * </ol>
 */
public class SSOFilter implements Filter {

	private static final Logger classLogger = LogManager.getLogger(SSOFilter.class);

	// filter init params
	private static final String COUNT_USER_ENTRY = "countUserEntry";
	private static final String COUNT_USER_ENTRY_DATABASE = "countUserEntryDb";
	private static final String LOG_USER_INFO = "logUserInfo";
	private static final String LOG_USER_INFO_PATH = "logUserInfoPath";
	private static final String LOG_USER_INFO_SEP = "logUserInfoSep";
	private static final String CUSTOM_DOMAIN = "customDomain";
	private static final String LOGIN_PATH = "loginPath";
	private static final String LOGIN_URL = "loginUrl";

	// realization of init params
	private static boolean init = false;
	private static CACTrackingUtil tracker = null;
	private static UserFileLogUtil userLogger = null;
	private static String customDomainForCookie = null;
	private static String loginPath = null;
	private static String loginUrl = null;

	private static final String LOG_USER = "LOG_USER";

	private static FilterConfig filterConfig;

	/**
	 * Main request gate for SAML authentication.
	 *
	 * <p>
	 * If no user is present in session, this method initializes login redirect
	 * state and returns a redirect-style response to start SSO. If a user exists,
	 * it continues the chain.
	 */
	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		setInitParams(request);

		HttpSession session = ((HttpServletRequest) request).getSession(false);
		User user = null;

		// Check if user is already there in the session. If there,
		// then get the existing user and travel down the chain.
		if (session != null) {
			user = (User) session.getAttribute(Constants.SESSION_USER);
		}

		// User has not logged in. Capture redirect context and send the browser
		// to the configured SAML login entry point.
		if (user == null) {
			classLogger.info("Starting saml transaction.");
			if (session == null) {
				session = ((HttpServletRequest) request).getSession(true);
			}

			// this will be the full path of the request
			// like http://localhost:8080/Monolith_Dev/api/engine/runPixel
			String fullUrl = WebUtility.cleanHttpResponse(((HttpServletRequest) request).getRequestURL().toString());

			// we need to store information in the session
			// so that we can properly come back to the referer once an admin has been added

			// we add a location to the headers when we want the browser to auto move
			// which is the case for portals as there is no FE and all managed by the BE
			// however, if we do this for the base application, it will cause issues as it
			// will redirect
			// and return the html as the response but doesn't seem like the FE knows what
			// to do with that
			// so only on portals do we add the location header...
			boolean addLocation = false;

			String referer = WebUtility.cleanHttpResponse(((HttpServletRequest) request).getHeader("referer"));
			if (referer != null) {
				classLogger.info(Utility.cleanLogString("Setting session redirect value to referer = " + referer));
			} else if (fullUrl.contains("/public_home/")) {
				addLocation = true;
				classLogger
						.info(Utility.cleanLogString("Setting session redirect value to the request URL = " + fullUrl));
				referer = fullUrl;
			} else {
				classLogger.info(Utility.cleanLogString("No session redirect value found..."));
			}
			// set the referer if we have it
			session.setAttribute(SSOUtil.SAML_REDIRECT_KEY, referer);

			// this will be the deployment name of the app
			String contextPath = request.getServletContext().getContextPath();

			// create the cookie with a custom domain?
			// this is important if you want multi-domain cookies
			if (customDomainForCookie != null) {
				Cookie k = new Cookie(DBLoader.getSessionIdKey(), session.getId());
				k.setHttpOnly(true);
				k.setSecure(request.isSecure());
				k.setPath(contextPath);
				k.setDomain(customDomainForCookie);
				((HttpServletResponse) response).addCookie(k);
				// in case there are other JSESSIONID
				// cookies, reset the value to the correct sessionId
				Cookie[] cookies = ((HttpServletRequest) request).getCookies();
				if (cookies != null) {
					for (Cookie c : cookies) {
						if (c.getName().equals(DBLoader.getSessionIdKey())) {
							c.setValue(session.getId());
							c.setDomain(customDomainForCookie);
							((HttpServletResponse) response).addCookie(c);
						}
					}
				}
			}

			// Redirect to either:
			// 1) configured external login URL, or
			// 2) internal SAML login page that routes into IdpSSOServlet/SPSSOServlet.
			if (loginUrl != null && !(loginUrl = loginUrl.trim()).isEmpty()) {
				((HttpServletResponse) response).setHeader("redirect", loginUrl);
				((HttpServletResponse) response).setHeader("location", loginUrl);
				((HttpServletResponse) response).sendError(302, "Need to redirect to " + loginUrl);
			} else {
				// if no login url defined
				// use the full url to do this
				// we redirect to the index.html page specifically created for the SAML call.
				String redirectUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length())
						+ loginPath;
				((HttpServletResponse) response).setHeader("redirect", redirectUrl);
				if (addLocation) {
					((HttpServletResponse) response).setHeader("location", redirectUrl);
				}
				((HttpServletResponse) response).sendError(302, "Need to redirect to " + redirectUrl);
			}

			if (tracker != null) {
				((HttpServletRequest) request).getSession().setAttribute(LOG_USER, "true");
			}

			return;
		}

		if (session != null && session.getAttribute(LOG_USER) != null) {
			session.removeAttribute(LOG_USER);

			AccessToken token = user.getAccessToken(AuthProvider.SAML);
			// new user has entered!
			// do we need to count?
			if (tracker != null && !token.getName().equals("TOPAZ")) {
				tracker.addToQueue(LocalDate.now());
			}

			// are we logging their information?
			if (userLogger != null && !token.getName().equals("TOPAZ")) {
				// grab the ip address
				userLogger.addToQueue(new String[] { token.getId(), token.getName(),
						LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
						WebUtility.getClientIp((HttpServletRequest) request) });
			}
		}

		chain.doFilter(request, response);
	}

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		SSOFilter.filterConfig = arg0;
	}

	/**
	 * Lazily reads filter init parameters and wires optional tracking/logging
	 * integrations.
	 *
	 * <p>
	 * Called on each request, but initialization work executes once due to the
	 * {@code init} flag.
	 */
	private void setInitParams(ServletRequest arg0) {
		if (!init) {
			boolean logUsers = false;
			String logUserInfoStr = SSOFilter.filterConfig.getInitParameter(LOG_USER_INFO);
			if (logUserInfoStr != null) {
				logUsers = Boolean.parseBoolean(logUserInfoStr);
			}
			if (logUsers) {
				String logInfoPath = SSOFilter.filterConfig.getInitParameter(LOG_USER_INFO_PATH);
				String logInfoSep = SSOFilter.filterConfig.getInitParameter(LOG_USER_INFO_SEP);
				if (logInfoPath == null) {
					classLogger.warn(
							"SYSTEM HAS REGISTERED TO PERFORM A USER FILE LOG BUT NOT FILE PATH HAS BEEN ENTERED!!!");
				}
				try {
					userLogger = UserFileLogUtil.getInstance(logInfoPath, logInfoSep);
				} catch (Exception e) {
					classLogger.error("Unable to initialize user file logging for SSOFilter.", e);
				}
			}

			boolean countUsers = false;
			String countUsersStr = SSOFilter.filterConfig.getInitParameter(COUNT_USER_ENTRY);
			if (countUsersStr != null) {
				countUsers = Boolean.parseBoolean(countUsersStr);
			} else {
				countUsers = false;
			}

			if (countUsers) {
				String countDatabaseId = SSOFilter.filterConfig.getInitParameter(COUNT_USER_ENTRY_DATABASE);
				if (countDatabaseId == null) {
					classLogger.warn("SYSTEM HAS REGISTERED TO PERFORM A COUNT BUT NO DATABASE ID HAS BEEN ENTERED!!!");
				}
				try {
					tracker = CACTrackingUtil.getInstance(countDatabaseId);
				} catch (Exception e) {
					classLogger.error("Unable to initialize user entry tracking for SSOFilter.", e);
				}
			}

			String customDomainForCookie = SSOFilter.filterConfig.getInitParameter(CUSTOM_DOMAIN);
			if (customDomainForCookie != null && !(customDomainForCookie = customDomainForCookie.trim()).isEmpty()) {
				SSOFilter.customDomainForCookie = customDomainForCookie;
			}

			String providedLoginPath = SSOFilter.filterConfig.getInitParameter(LOGIN_PATH);
			if (providedLoginPath != null) {
				loginPath = providedLoginPath;
			} else {
				loginPath = "/samlLogin/";
			}

			String providedLoginUrl = SSOFilter.filterConfig.getInitParameter(LOGIN_URL);
			if (providedLoginUrl != null) {
				loginUrl = providedLoginUrl;
			}

			// change init to true
			init = true;
		}
	}

}
