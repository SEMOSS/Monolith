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
import java.util.ArrayList;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import prerna.auth.User;
import prerna.auth.UserPermissionsMasterDB;
import prerna.util.Constants;

public class UserDBInsightFilter implements Filter {
	FilterConfig config;
	UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
	String engineAPIPath = "/e-";

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		ServletContext context = getFilterConfig().getServletContext();
		HttpSession session = ((HttpServletRequest)arg0).getSession(true);
		boolean securityEnabled = Boolean.parseBoolean(context.getInitParameter(Constants.SECURITY_ENABLED));
		
		if(securityEnabled) {
			String requestURI = ((HttpServletRequest) arg0).getRequestURI();
			if(requestURI.contains(engineAPIPath)) {
				String requestPathWithEngine = requestURI.substring(requestURI.indexOf(engineAPIPath) + engineAPIPath.length()); 
				String engineName = requestPathWithEngine.substring(0, requestPathWithEngine.indexOf("/"));
				
				User user = ((User) session.getAttribute(Constants.SESSION_USER));
				String userId = "";
				if(user!= null) {
					userId = user.getId();
				}
				ArrayList<String> userEngines = permissions.getUserAccessibleEngines(userId);
				if(!engineName.equals(Constants.LOCAL_MASTER_DB_NAME) && !userEngines.contains(engineName)) {
					HttpServletResponse response = (HttpServletResponse) arg1;
					response.addHeader("userId", userId);
					response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
					return;
				}
			}
		}
		arg2.doFilter(arg0, arg1);
	}

	@Override
	public void init(FilterConfig config) throws ServletException {
		setFilterConfig(config);
	}
	
	@Override
	public void destroy() {
		
	}
	
	public void setFilterConfig(FilterConfig config) {
		this.config = config;
	}

	public FilterConfig getFilterConfig() {
		return config;
	}
}
