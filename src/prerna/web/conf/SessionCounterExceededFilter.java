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

import org.apache.catalina.session.StandardManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import prerna.auth.User;
import prerna.semoss.web.services.local.SessionResource;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

public class SessionCounterExceededFilter implements Filter {

	private static final Logger classLogger = LogManager.getLogger(SessionCounterExceededFilter.class);

	private static final String FAIL_HTML = "/sessionCounterFail/";

	private static final String SESSION_LIMIT = "sessionLimit";
	private static Integer sessionLimit = null;

	private static FilterConfig filterConfig;

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
			throws IOException, ServletException {
		setInitParams(arg0);

		HttpSession session = ((HttpServletRequest) arg0).getSession(false);
		User user = null;
		if (session != null) {
			user = (User) session.getAttribute(Constants.SESSION_USER);
		}

		if (user == null && sessionLimit != null && sessionLimit > 0) {
			int valid = performCheck(session, arg0, arg1);
			if (valid != 0) {
				return;
			}
		}

		arg2.doFilter(arg0, arg1);
	}

	private static synchronized int performCheck(HttpSession session, ServletRequest arg0, ServletResponse arg1)
			throws IOException {
		ServletContext context = arg0.getServletContext();
		if (session == null) {
			session = ((HttpServletRequest) arg0).getSession();
		}

		StandardManager manager = SessionResource.getManager(session);
		if (manager != null) {
			// note this includes the new session that was just created here
			int currentSessions = manager.getActiveSessions();
			if (currentSessions > sessionLimit) {
				classLogger.info("New user exceeds the # of allowed sessions = {}", sessionLimit);

				// invalidate the session that was created for the manager
				session.invalidate();
				// too many users
				// this will be the deployment name of the app
				String contextPath = context.getContextPath();

				// this will be the full path of the request
				// like http://localhost:8080/Monolith_Dev/api/engine/runPixel
				String fullUrl = WebUtility.cleanHttpResponse(((HttpServletRequest) arg0).getRequestURL().toString());

				if (!fullUrl.endsWith(FAIL_HTML)) {
					// we redirect to the index.html page where we have pushed the admin page
					String redirectUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length())
							+ FAIL_HTML;
					((HttpServletResponse) arg1).setHeader("redirect", redirectUrl);
					((HttpServletResponse) arg1).sendError(302);
					return -1;
				}
			} else {
				classLogger.info("New user login makes session #{}", currentSessions);
			}
		}

		return 0;
	}

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		SessionCounterExceededFilter.filterConfig = arg0;
	}

	private void setInitParams(ServletRequest arg0) {
		if (SessionCounterExceededFilter.sessionLimit == null) {
			String sessionLimitString = SessionCounterExceededFilter.filterConfig.getInitParameter(SESSION_LIMIT);
			if (sessionLimitString != null) {
				try {
					SessionCounterExceededFilter.sessionLimit = (int) Double.parseDouble(sessionLimitString);
				} catch (Exception e) {
					classLogger.error("Failed to parse the {} configuration value '{}'", SESSION_LIMIT,
							sessionLimitString, e);
				}
			}
		}
	}

}
