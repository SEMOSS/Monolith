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
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import prerna.auth.User;
import prerna.reactor.mgmt.MgmtUtil;
import prerna.util.Constants;
import prerna.util.Settings;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

public class MemoryCheckFilter implements Filter {

	private static final String NO_MORE_MEMORY = "/sessionCounterFail/";

	private static Boolean checkMem = null;
	private static String memProfileSettings = null;
	private static int memLimit = -1;

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
			throws IOException, ServletException {
		/*
		 * This beginning part is so we dont keep parsing RDF_Map.prop to see if the
		 * check memory is enabled or not So we will look to see if the Boolean (object
		 * class) is initiated If not, we will determine its value (true/false) from the
		 * isCheckMem() method If the check memory is false, then we continue down the
		 * filter chain
		 */

		if (checkMem == null) {
			isCheckMem();
		}

		if (!checkMem) {
			arg2.doFilter(arg0, arg1);
			return;
		}

		/*
		 * User has enabled the memory check filter Check to see if the user is logged
		 * in If yes.. nothing to do If not then try to see if we have memory If not
		 * move the uset to oom page
		 */

		ServletContext context = arg0.getServletContext();
		HttpSession session = ((HttpServletRequest) arg0).getSession(false);
		String fullUrl = WebUtility.cleanHttpResponse(((HttpServletRequest) arg0).getRequestURL().toString());
		User user = null;
		if (session != null) {
			user = (User) session.getAttribute(Constants.SESSION_USER);
		}

		if (user == null && !fullUrl.endsWith(NO_MORE_MEMORY) && !canLoadUser()) {
			// this will be the deployment name of the app
			String contextPath = context.getContextPath();

			// this will be the full path of the request
			// like http://localhost:8080/Monolith_Dev/api/engine/runPixel
			// we redirect to the index.html page where we have pushed the admin page
			String redirectUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length())
					+ NO_MORE_MEMORY;
			((HttpServletResponse) arg1).setHeader("redirect", redirectUrl);
			((HttpServletResponse) arg1).sendError(302);
			return;
		}

		arg2.doFilter(arg0, arg1);
	}

	/**
	 * Determine if we need to check memory
	 */
	private void isCheckMem() {
		String checkMemSettings = Utility.getDIHelperProperty(Settings.CHECK_MEM);
		if (checkMemSettings != null && !(checkMemSettings = checkMemSettings.trim()).isEmpty()) {
			boolean checkMem = Boolean.parseBoolean(checkMemSettings);
			if (checkMem) {
				String memLimitSettings = Utility.getDIHelperProperty(Settings.USER_MEM_LIMIT);
				String memProfileSetting = Utility.getDIHelperProperty(Settings.MEM_PROFILE_SETTINGS);

				if ((memLimitSettings != null && !(memLimitSettings = memLimitSettings.trim()).isEmpty())
						&& (memProfileSetting != null && !(memProfileSetting = memProfileSetting.trim()).isEmpty())) {
					int memLimit = Integer.parseInt(memLimitSettings);
					MemoryCheckFilter.memProfileSettings = memProfileSetting;
					MemoryCheckFilter.memLimit = memLimit;
				}
			}
			MemoryCheckFilter.checkMem = checkMem;
			return;
		}
		// default is false
		MemoryCheckFilter.checkMem = false;
	}

	/**
	 * Determine if we have enough memory to allow login
	 * 
	 * @return
	 */
	public synchronized boolean canLoadUser() {
		/**
		 * Example RDF_Map.prop options
		 * 
		 * <p>
		 * # MEMORY stuff
		 * <ol>
		 * <li>CHECK_MEM True</li>
		 * <li>MEM_PROFILE_SETTINGS CONSTANT</li>
		 * </ol>
		 * # specification in gigs
		 * <ol>
		 * <li>USER_MEM_LIMIT 2</li>
		 * <li>RESERVED_JAVA_MEM 12</li>
		 * </ol>
		 * </p>
		 */

		boolean canLoad = true;

		long freeMem = MgmtUtil.getFreeMemory();
		if (MemoryCheckFilter.memProfileSettings.equalsIgnoreCase(Settings.CONSTANT_MEM)) {
			canLoad = MemoryCheckFilter.memLimit < freeMem;
		}

		return canLoad;
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
