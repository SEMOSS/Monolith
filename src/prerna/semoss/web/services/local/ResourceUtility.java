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
package prerna.semoss.web.services.local;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.core.Context;

import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

public class ResourceUtility {

	protected static List<String> allowAccessWithoutUsers = new ArrayList<>();
	static {
		allowAccessWithoutUsers.add("config");
		allowAccessWithoutUsers.add("config/fetchCsrf");
	}

	public static List<String> allowAccessWithoutLogin = new ArrayList<>();
	static {
		// allow these for successful dropping of
		// sessions when browser is closed/refreshed
		// these do their own session checks
		allowAccessWithoutLogin.add("session/active");
		allowAccessWithoutLogin.add("session/cleanSession");
		allowAccessWithoutLogin.add("session/cancelCleanSession");
		allowAccessWithoutLogin.add("session/invalidateSession");

		allowAccessWithoutLogin.add("config");
		allowAccessWithoutLogin.add("config/fetchCsrf");
		allowAccessWithoutLogin.add("auth/logins");
		allowAccessWithoutLogin.add("auth/loginsAllowed");
		allowAccessWithoutLogin.add("auth/login");
		allowAccessWithoutLogin.add("auth/loginLDAP");
		allowAccessWithoutLogin.add("auth/changeADPassword");
		allowAccessWithoutLogin.add("auth/loginLinOTP");
		allowAccessWithoutLogin.add("auth/createUser");
		allowAccessWithoutLogin.add("auth/whoami");
		allowAccessWithoutLogin.add("auth/user/setupResetPassword");
		allowAccessWithoutLogin.add("auth/user/resetPassword");
		for (AuthProvider v : AuthProvider.values()) {
			allowAccessWithoutLogin.add("auth/userinfo/" + v.toString().toLowerCase());
			allowAccessWithoutLogin.add("auth/login/" + v.toString().toLowerCase());
		}
		// legacy ms login
		allowAccessWithoutLogin.add("auth/userinfo/ms");
		allowAccessWithoutLogin.add("auth/login/ms");

		// MCP OAuth discovery endpoints - must be accessible without login for ChatGPT integration
		allowAccessWithoutLogin.add(".well-known/oauth-protected-resource");
		allowAccessWithoutLogin.add("/health"); // Allow MCP health check
	}

	/**
	 * Get the user
	 * 
	 * @param request
	 * @return
	 * @throws IOException
	 */
	public static User getUser(@Context HttpServletRequest request) throws IllegalAccessException {
		HttpSession session = request.getSession(false);
		if (session == null) {
			throw new IllegalAccessException("User session is invalid");
		}

		User user = (User) session.getAttribute(Constants.SESSION_USER);
		if (user == null) {
			throw new IllegalAccessException("User session is invalid");
		}

		return user;
	}

	public static String getClientIp(@Context HttpServletRequest request) {
		String remoteAddr = "";
		if (request != null) {
			remoteAddr = WebUtility.inputSanitizer(request.getHeader("X-FORWARDED-FOR"));
			if (remoteAddr == null || "".equals(remoteAddr)) {
				remoteAddr = request.getRemoteAddr();
			}
		}

		return WebUtility.inputSanitizer(remoteAddr);
	}

	/**
	 * Need to ignore some URLs
	 * 
	 * @param fullUrl
	 * @return
	 */
	public static boolean allowAccessWithoutUsers(String fullUrl) {
		for (String ignore : allowAccessWithoutUsers) {
			if (fullUrl.endsWith(ignore)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Need to ignore some URLs
	 * 
	 * @param fullUrl
	 * @return
	 */
	public static boolean allowAccessWithoutLogin(String fullUrl) {
		for (String ignore : allowAccessWithoutLogin) {
			if (fullUrl.endsWith(ignore)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Need to ignore some URLs
	 * 
	 * @param fullUrl
	 * @return
	 */
	public static boolean endsWithMatch(Collection<String> ignoreForFE, String fullUrl) {
		for (String ignore : ignoreForFE) {
			if (fullUrl.endsWith(ignore)) {
				return true;
			}
		}
		return false;
	}
}
