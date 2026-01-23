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
import java.io.Serializable;
import java.util.UUID;

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

import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

public class AnonymousUserFilter implements Filter, Serializable {

	private static final Logger logger = LogManager.getLogger(AnonymousUserFilter.class);
	private static final long serialVersionUID = -4657347128078864456L;

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
			throws IOException, ServletException {
		if (AbstractSecurityUtils.anonymousUsersEnabled()) {
			HttpSession session = ((HttpServletRequest) arg0).getSession(true);

			User user = (User) session.getAttribute(Constants.SESSION_USER);
			if (user == null) {
				user = new User();
				user.setAnonymous(true);

				String cookieToFind = DBLoader.getSessionIdKey() + "_unk";
				Cookie[] cookies = ((HttpServletRequest) arg0).getCookies();
				if (cookies != null) {
					// loop through and see if we have a cookie for this user
					for (Cookie c : cookies) {
						if (c.getName().equals(cookieToFind)) {
							String uId = WebUtility.cleanHttpResponse(c.getValue());
							user.setAnonymousId(uId);
							// found the cookie
							// no need to continue loop
							break;
						}
					}
				}

				boolean foundPrevoiusCookie = user.getAnonymousId() != null;
				if (!foundPrevoiusCookie) {
					// set a new id + add a cookie
					String uId = "UNK_" + UUID.randomUUID().toString();
					user.setAnonymousId(uId);

					Cookie c = new Cookie(cookieToFind, uId);
					c.setPath(((HttpServletRequest) arg0).getContextPath());
					c.setHttpOnly(true);
					c.setSecure(arg0.isSecure());
					((HttpServletResponse) arg1).addCookie(c);
				}
				// add to session
				session.setAttribute(Constants.SESSION_USER, user);
				session.setAttribute(Constants.SESSION_USER_ID_LOG, user.getAnonymousId());

				// log the user login
				if (foundPrevoiusCookie) {
					logger.info("User is logging in anonymously");
				} else {
					logger.info("User is logging in anonymously for first time");
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
		// initialize

	}

}
