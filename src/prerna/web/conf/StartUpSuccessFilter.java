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
import prerna.om.ThreadStore;
import prerna.web.services.util.WebUtility;

public class StartUpSuccessFilter implements Filter {

	private static final String FAIL_HTML = "/startUpFail/";

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
			throws IOException, ServletException {
		ServletContext context = arg0.getServletContext();
		ThreadStore.remove();
		try {
			filterRequest(context, arg0, arg1, arg2);
		} finally {
			ThreadStore.remove();
		}
	}

	/**
	 * Sends a request on, or to the start up failure page when the server did not
	 * start. The thread carries nothing from the request it served before, and
	 * {@link #doFilter} clears what this request put on it once the request ends.
	 *
	 * @param context  the servlet context
	 * @param request  the request
	 * @param response the response
	 * @param chain    the rest of the filters
	 * @throws IOException      when the request or response cannot be read or
	 *                          written
	 * @throws ServletException when a later filter fails
	 */
	private void filterRequest(ServletContext context, ServletRequest request, ServletResponse response,
			FilterChain chain) throws IOException, ServletException {
		// never redirect health/liveness/readiness probes - they must stay reachable
		// so orchestrators can observe status precisely when startup has failed
		if (request instanceof HttpServletRequest) {
			String servletPath = ((HttpServletRequest) request).getServletPath();
			if (servletPath != null && servletPath.startsWith("/health")) {
				chain.doFilter(request, response);
				return;
			}
		}

		WebUtility.loggingContext(request);
		if (!DBLoader.isStartupSuccess()) {
			// this will be the deployment name of the app
			String contextPath = context.getContextPath();

			// this will be the full path of the request
			// like http://localhost:8080/Monolith_Dev/api/engine/runPixel
			String fullUrl = WebUtility.cleanHttpResponse(((HttpServletRequest) request).getRequestURL().toString());

			if (!fullUrl.endsWith(FAIL_HTML)) {
				// we redirect to the index.html page where we have pushed the admin page
				String redirectUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length())
						+ FAIL_HTML;
				((HttpServletResponse) response).setHeader("redirect", redirectUrl);
				((HttpServletResponse) response).sendError(302, "Redirect required");
				return;
			}
		}

		chain.doFilter(request, response);
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