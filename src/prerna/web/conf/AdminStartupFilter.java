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
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.owasp.encoder.Encode;

import prerna.engine.api.IRDBMSEngine;
import prerna.engine.api.IRawSelectWrapper;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.SystemEngineRegistry;

public class AdminStartupFilter implements Filter {

	private static final Logger classLogger = LogManager.getLogger(AdminStartupFilter.class);

	private static String initialRedirect;

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
			throws IOException, ServletException {
		IRDBMSEngine engine = SystemEngineRegistry.getSecurityDb();
		String q = "SELECT * FROM SMSS_USER LIMIT 1";
		try (IRawSelectWrapper wrapper = WrapperManager.getInstance().getRawWrapper(engine, q)) {
			boolean hasUser = wrapper.hasNext();
			// if there are users, redirect to the main semoss page
			// we do not want to allow the person to make any admin requests
			if (hasUser) {
				if (initialRedirect != null) {
					String encodedRedirectUrl = Encode.forHtml(initialRedirect);
					((HttpServletResponse) arg1).setHeader("redirect", encodedRedirectUrl);
					((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + encodedRedirectUrl);
				} else {
					((HttpServletResponse) arg1).sendError(404, "Page Not Found");
				}
			}
		} catch (Exception e) {
			classLogger.error("Error checking whether an initial admin user exists during startup filtering", e);
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

	public static void setSuccessfulRedirectUrl(String initialRedirect) {
		AdminStartupFilter.initialRedirect = initialRedirect;
	}

}
