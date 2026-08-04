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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.engine.api.IDatabaseEngine;
import prerna.engine.api.IRawSelectWrapper;
import prerna.query.querystruct.SelectQueryStruct;
import prerna.query.querystruct.selectors.QueryColumnSelector;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.SystemEngineRegistry;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

public class NoUserExistsFilter implements Filter {

	private static final Logger classLogger = LogManager.getLogger(NoUserExistsFilter.class);

	private static final String SMSS_INITIAL_ADMIN = "SMSS_INITIAL_ADMIN";
	private static final String SET_ADMIN_HTML = "/setAdmin/";

	private static boolean userDefined = false;

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
			throws IOException, ServletException {
		// i do not want to run this query for every single call
		// just gets annoying
		if (!NoUserExistsFilter.userDefined) {
			// this will be the full path of the request
			// like http://localhost:8080/Monolith_Dev/api/engine/runPixel
			String fullUrl = WebUtility.cleanHttpResponse(((HttpServletRequest) arg0).getRequestURL().toString());
			if (!ResourceUtility.canAccessWithoutUsers(fullUrl)) {
				boolean hasUser = hasUser();
				// no users at all registered, we need to send to the admin page
				if (!hasUser) {
					if (System.getenv(SMSS_INITIAL_ADMIN) != null) {
						// set initial admin id via env
						setInitialAdminViaEnv(((HttpServletRequest) arg0));
					} else {
						// we redirect to the index.html page where we have pushed the admin page
						String redirectUrl = Utility.getApplicationUrl() + SET_ADMIN_HTML;
						((HttpServletResponse) arg1).setHeader("redirect", redirectUrl);
						((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + redirectUrl);
						return;
					}
				} else {
					// set boolean so we dont keep querying all the time
					NoUserExistsFilter.userDefined = true;
				}
			}
		}

		arg2.doFilter(arg0, arg1);
	}

	/**
	 * Check again that initial user does not exist and create from env
	 */
	private synchronized void setInitialAdminViaEnv(HttpServletRequest request) {
		boolean hasUser = hasUser();
		if (!hasUser) {
			// if there a env var for initial admin
			// set the admin so we are done
			String id = System.getenv(SMSS_INITIAL_ADMIN);
			SecurityUpdateUtils.registerUser(id, null, null, null, null, null, null, null, true, true, true, null, null,
					null, null);
			classLogger.info("The initial admin has been defined via an environment variable");
			// set boolean so we dont keep querying all the time
			NoUserExistsFilter.userDefined = true;
		}
	}

	/**
	 * 
	 * @return
	 */
	private static boolean hasUser() {
		boolean hasUser = true;
		IDatabaseEngine engine = SystemEngineRegistry.getSecurityDb();
		SelectQueryStruct qs = new SelectQueryStruct();
		qs.addSelector(new QueryColumnSelector("SMSS_USER__ID"));
		qs.setLimit(1);
		try (IRawSelectWrapper wrapper = WrapperManager.getInstance().getRawWrapper(engine, qs)) {
			hasUser = wrapper.hasNext();
		} catch (Exception e) {
			classLogger.error(
					"An error occurred querying against the security db to determine if an initial user has been set",
					e);
		}

		return hasUser;
	}

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		// TODO Auto-generated method stub

	}
}
