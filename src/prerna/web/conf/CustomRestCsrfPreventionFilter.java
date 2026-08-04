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
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import prerna.rpa.quartz.jobs.insight.RunPixelJobFromDB;

public class CustomRestCsrfPreventionFilter extends org.apache.catalina.filters.RestCsrfPreventionFilter {

	/**
	 * Set of paths to exclude from CSRF processing. Including openai endpoint,
	 * anthropic endpoint, and mcp endpoint
	 */
	private Set<String> excludedPaths = new HashSet<>();

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		// Get excluded paths parameter before calling parent init
		String excludedPathsParam = filterConfig.getInitParameter("excluded-paths");
		if (excludedPathsParam != null && !excludedPathsParam.trim().isEmpty()) {
			String[] paths = excludedPathsParam.split(",");
			for (String path : paths) {
				this.excludedPaths.add(path.trim());
			}
		}

		// Create a wrapper that excludes our custom parameter for excluded paths
		FilterConfig wrappedConfig = new FilterConfig() {
			@Override
			public String getFilterName() {
				return filterConfig.getFilterName();
			}

			@Override
			public ServletContext getServletContext() {
				return filterConfig.getServletContext();
			}

			@Override
			public String getInitParameter(String name) {
				if ("excluded-paths".equals(name)) {
					return null;
				}
				return filterConfig.getInitParameter(name);
			}

			@Override
			public Enumeration<String> getInitParameterNames() {
				return new Enumeration<String>() {
					private Enumeration<String> original = filterConfig.getInitParameterNames();
					private String next = null;

					@Override
					public boolean hasMoreElements() {
						if (next != null) {
							return true;
						}
						while (original.hasMoreElements()) {
							String param = original.nextElement();
							if (!"excluded-paths".equals(param)) {
								next = param;
								return true;
							}
						}
						return false;
					}

					@Override
					public String nextElement() {
						if (hasMoreElements()) {
							String result = next;
							next = null;
							return result;
						}
						throw new java.util.NoSuchElementException();
					}
				};
			}
		};

		// Call parent init with wrapped config
		super.init(wrappedConfig);
	}

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
			throws IOException, ServletException {
		HttpServletRequest httpRequest = (HttpServletRequest) arg0;

		// Check if current path should be excluded
		String path = httpRequest.getRequestURI().substring(httpRequest.getContextPath().length());
		boolean shouldExclude = excludedPaths.stream().anyMatch(excludedPath -> path.startsWith(excludedPath));

		if (shouldExclude) {
			// Skip CSRF processing for excluded paths
			arg2.doFilter(arg0, arg1);
			return;
		}

		HttpSession session = httpRequest.getSession();
		session.setAttribute("csrf", true);
		RunPixelJobFromDB.setFetchCsrf(true);
		super.doFilter(arg0, arg1, arg2);
	}
}