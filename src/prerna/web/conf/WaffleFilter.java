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
import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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
import prerna.semoss.web.services.local.UserResource;
import prerna.util.Constants;
import prerna.web.conf.util.CACTrackingUtil;
import prerna.web.conf.util.UserFileLogUtil;

public class WaffleFilter implements Filter {

	private static final Logger classLogger = LogManager.getLogger(WaffleFilter.class.getName());

	// filter init params
	private static final String AUTO_ADD = "autoAdd";
	private static final String COUNT_USER_ENTRY = "countUserEntry";
	private static final String COUNT_USER_ENTRY_DATABASE = "countUserEntryDb";
	private static final String LOG_USER_INFO = "logUserInfo";
	private static final String LOG_USER_INFO_PATH = "logUserInfoPath";
	private static final String LOG_USER_INFO_SEP = "logUserInfoSep";

	// realization of init params
	private static Boolean autoAdd = null;
	private static CACTrackingUtil tracker = null;
	private static UserFileLogUtil userLogger = null;

	private static FilterConfig filterConfig;

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
			throws IOException, ServletException {
		setInitParams(arg0);

		HttpSession session = ((HttpServletRequest) arg0).getSession(true);
		User user = (User) session.getAttribute(Constants.SESSION_USER);
		if (user == null) {
			// grab the waffle elements
			Principal principal = ((HttpServletRequest) arg0).getUserPrincipal();
			String id = principal.getName();
			String name = id.substring(id.lastIndexOf('\\') + 1);

			AccessToken token = new AccessToken();
			token.setProvider(AuthProvider.WINDOWS_USER);
			token.setId(id);
			token.setName(name);
			classLogger.info("Valid request coming from user {}", token.getName());
			// store in session, log in user tracking db, and add the user to security db if
			// autoadd
			UserResource.addAccessToken(token, ((HttpServletRequest) arg0), WaffleFilter.autoAdd);
			// do we need to count?
			if (tracker != null) {
				tracker.addToQueue(LocalDate.now());
			}

			// are we logging their information?
			if (userLogger != null) {
				userLogger.addToQueue(new String[] { id, name,
						LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) });
			}
		}

		arg2.doFilter(arg0, arg1);
	}

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		WaffleFilter.filterConfig = arg0;
	}

	private void setInitParams(ServletRequest arg0) {
		if (WaffleFilter.autoAdd == null) {
			String autoAddStr = WaffleFilter.filterConfig.getInitParameter(AUTO_ADD);
			if (autoAddStr != null) {
				WaffleFilter.autoAdd = Boolean.parseBoolean(autoAddStr);
			} else {
				// Default value is true
				WaffleFilter.autoAdd = true;
			}

			boolean logUsers = false;
			String logUserInfoStr = WaffleFilter.filterConfig.getInitParameter(LOG_USER_INFO);
			if (logUserInfoStr != null) {
				logUsers = Boolean.parseBoolean(logUserInfoStr);
			}
			if (logUsers) {
				String logInfoPath = WaffleFilter.filterConfig.getInitParameter(LOG_USER_INFO_PATH);
				String logInfoSep = WaffleFilter.filterConfig.getInitParameter(LOG_USER_INFO_SEP);
				if (logInfoPath == null) {
					classLogger.info(
							"SYSTEM HAS REGISTERED TO PERFORM A USER FILE LOG BUT NOT FILE PATH HAS BEEN ENTERED!!!");
				}
				try {
					userLogger = UserFileLogUtil.getInstance(logInfoPath, logInfoSep);
				} catch (Exception e) {
					classLogger.info(e.getMessage());
				}
			}

			boolean countUsers = false;
			String countUsersStr = WaffleFilter.filterConfig.getInitParameter(COUNT_USER_ENTRY);
			if (countUsersStr != null) {
				countUsers = Boolean.parseBoolean(countUsersStr);
			} else {
				countUsers = false;
			}

			if (countUsers) {
				String countDatabaseId = WaffleFilter.filterConfig.getInitParameter(COUNT_USER_ENTRY_DATABASE);
				if (countDatabaseId == null) {
					classLogger.info("SYSTEM HAS REGISTERED TO PERFORM A COUNT BUT NO DATABASE ID HAS BEEN ENTERED!!!");
				}
				try {
					tracker = CACTrackingUtil.getInstance(countDatabaseId);
				} catch (Exception e) {
					classLogger.info(e.getMessage());
				}
			}
		}
	}

}
