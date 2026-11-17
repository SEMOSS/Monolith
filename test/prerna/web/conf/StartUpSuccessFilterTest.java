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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class StartUpSuccessFilterTest {

	private static HttpServletRequest request(String servletPath, String url) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		ServletContext context = mock(ServletContext.class);
		when(context.getContextPath()).thenReturn("/Monolith");
		when(request.getServletContext()).thenReturn(context);
		when(request.getServletPath()).thenReturn(servletPath);
		when(request.getRequestURL()).thenReturn(new StringBuffer(url));
		when(request.getContextPath()).thenReturn("/Monolith");
		when(request.getMethod()).thenReturn("GET");
		when(request.getRequestURI()).thenReturn(url.substring(url.indexOf("/Monolith")));
		return request;
	}

	private static void run(boolean startupSuccess, HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws Exception {
		try (MockedStatic<DBLoader> loader = mockStatic(DBLoader.class)) {
			loader.when(DBLoader::isStartupSuccess).thenReturn(startupSuccess);
			new StartUpSuccessFilter().doFilter(request, response, chain);
		}
	}

	@Test
	void passesHealthProbesAfterAFailedStartup() throws Exception {
		HttpServletRequest request = request("/health", "http://localhost:8080/Monolith/health/ready");
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		run(false, request, response, chain);

		verify(chain).doFilter(request, response);
		verify(response, never()).sendError(anyInt(), anyString());
	}

	@Test
	void passesRequestsAfterASuccessfulStartup() throws Exception {
		HttpServletRequest request = request("/api", "http://localhost:8080/Monolith/api/engine/runPixel");
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		run(true, request, response, chain);

		verify(chain).doFilter(request, response);
		verify(response, never()).sendError(anyInt(), anyString());
	}

	@Test
	void sendsRequestsToTheFailurePageAfterAFailedStartup() throws Exception {
		HttpServletRequest request = request("/api", "http://localhost:8080/Monolith/api/engine/runPixel");
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		run(false, request, response, chain);

		verify(response).setHeader("redirect", "http://localhost:8080/Monolith/startUpFail/");
		verify(response).sendError(302, "Redirect required");
		verify(chain, never()).doFilter(request, response);
	}

	@Test
	void showsTheFailurePageAfterAFailedStartup() throws Exception {
		HttpServletRequest request = request("/startUpFail/", "http://localhost:8080/Monolith/startUpFail/");
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		run(false, request, response, chain);

		verify(chain).doFilter(request, response);
		verify(response, never()).sendError(anyInt(), anyString());
	}
}
