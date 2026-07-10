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

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.util.Constants;

public class SetAdminSessionTimeoutFilter implements Filter {

	private static final Logger classLogger = LogManager.getLogger(SetAdminSessionTimeoutFilter.class);

	private static FilterConfig filterConfig = null;

	// filter init params
	private static final String TIMEOUT = "timeout";
	private static Integer sessionTimeout = null;

	private static final String SESSIOIN_ATTRIBUTE_CHECK = "adminSessionTimeout";

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
			throws IOException, ServletException {
		HttpSession session = ((HttpServletRequest) arg0).getSession(false);
		if (sessionTimeout != null && session != null) {
			User user = (User) session.getAttribute(Constants.SESSION_USER);

			if (user != null && session.getAttribute(SESSIOIN_ATTRIBUTE_CHECK) == null) {
				// we have a user to compare
				if (SecurityAdminUtils.userIsAdmin(user)) {
					// need to update the session for the admin user
					// the input is in minutes, so we need to turn that to seconds
					int interval = sessionTimeout * 60;
					session.setMaxInactiveInterval(interval);

					// store in session so we do not redo the check
					session.setAttribute(SESSIOIN_ATTRIBUTE_CHECK, true);
					classLogger.info("Setting the admin timeout to {} seconds", interval);
				} else {

					// also still store in the session so we do not redo the check
					session.setAttribute(SESSIOIN_ATTRIBUTE_CHECK, false);
				}
			}
		}

		arg2.doFilter(arg0, arg1);
	}

	@Override
	public void destroy() {
		// destroy
	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		SetAdminSessionTimeoutFilter.filterConfig = arg0;
		setInitParams();
	}

	private void setInitParams() {
		if (SetAdminSessionTimeoutFilter.sessionTimeout == null) {
			String timeoutStr = SetAdminSessionTimeoutFilter.filterConfig.getInitParameter(TIMEOUT);
			try {
				int timeoutValue = Integer.parseInt(timeoutStr);
				SetAdminSessionTimeoutFilter.sessionTimeout = timeoutValue;
			} catch (Exception e) {
				classLogger.error("Failed to parse the timeout filter init parameter value", e);
			}
		}
	}

}
