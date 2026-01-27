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

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.util.Constants;

public class FilespaceAccessFilter implements Filter {
	
	private static final Logger classLogger = LogManager.getLogger(FilespaceAccessFilter.class);

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
		try {
			// get the space it is trying to access
			// get the user from the session
			// see if this user can access this space
			// allow / redirect
			HttpServletRequest hsr = (HttpServletRequest)request;
			String url = hsr.getRequestURI();
			// this is typically of the pattern
			// http://blah blah/Monolith/public_home/app__app_id/something
			String publicHome = "/" + Constants.PUBLIC_HOME + "/";
			int publicHomeIndex = url.indexOf(publicHome);
			if(publicHomeIndex >= 0) {
				String appHome = url.substring(publicHomeIndex + publicHome.length());
				int appRootIndex = appHome.indexOf("/");
				if(appRootIndex >= 0) {
					String appRoot = appHome.substring(0, appRootIndex);
					String [] appRootElements = appRoot.split("__");
					User user = (User) hsr.getSession().getAttribute(Constants.SESSION_USER);
					if(SecurityProjectUtils.userCanViewProject(user, appRootElements[1])) {
						chain.doFilter(request, response);
					} else {
						((HttpServletResponse)response).sendError(HttpServletResponse.SC_FORBIDDEN, " You are not allowed to access that resource ");;
					}
				}
			}
		} catch(Exception ex) {
			classLogger.error(Constants.STACKTRACE, ex);
		}
	}

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}
	
	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		// TODO Auto-generated method stub

	}


}
