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

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import prerna.engine.api.IDatabaseEngine;
import prerna.engine.api.IRDBMSEngine;
import prerna.engine.api.IRawSelectWrapper;
import prerna.query.querystruct.SelectQueryStruct;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.SystemEngineRegistry;
import prerna.util.Utility;

class NoUserExistsFilterTest {

	private static final String APP_URL = "http://localhost:8080/Monolith";

	private WrapperManager manager;
	private IRawSelectWrapper wrapper;

	@BeforeEach
	void resetUserDefined() throws Exception {
		Field field = NoUserExistsFilter.class.getDeclaredField("userDefined");
		field.setAccessible(true);
		field.set(null, false);
		wrapper = mock(IRawSelectWrapper.class);
		manager = mock(WrapperManager.class);
		when(manager.getRawWrapper(any(IDatabaseEngine.class), any(SelectQueryStruct.class))).thenReturn(wrapper);
	}

	private static HttpServletRequest request(String path) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRequestURL()).thenReturn(new StringBuffer(APP_URL + path));
		when(request.getPathInfo()).thenReturn(path.substring("/api".length()));
		return request;
	}

	private void run(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws Exception {
		try (MockedStatic<SystemEngineRegistry> registry = mockStatic(SystemEngineRegistry.class);
				MockedStatic<WrapperManager> managers = mockStatic(WrapperManager.class);
				MockedStatic<Utility> utility = mockStatic(Utility.class)) {
			registry.when(SystemEngineRegistry::getSecurityDb).thenReturn(mock(IRDBMSEngine.class));
			managers.when(WrapperManager::getInstance).thenReturn(manager);
			utility.when(Utility::getApplicationUrl).thenReturn(APP_URL);
			new NoUserExistsFilter().doFilter(request, response, chain);
		}
	}

	@Test
	void passesTheConfigEndpointsBeforeAnyUserExists() throws Exception {
		for (String path : new String[] { "/api/config", "/api/config/fetchCsrf" }) {
			HttpServletRequest request = request(path);
			HttpServletResponse response = mock(HttpServletResponse.class);
			FilterChain chain = mock(FilterChain.class);

			run(request, response, chain);

			verify(chain).doFilter(request, response);
		}
		verify(manager, never()).getRawWrapper(any(IDatabaseEngine.class), any(SelectQueryStruct.class));
	}

	@Test
	void redirectsToTheAdminSetupWhileNoUserExists() throws Exception {
		assumeTrue(System.getenv("SMSS_INITIAL_ADMIN") == null);
		when(wrapper.hasNext()).thenReturn(false);
		HttpServletRequest request = request("/api/auth/logins");
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		run(request, response, chain);

		verify(response).setHeader("redirect", APP_URL + "/setAdmin/");
		verify(response).sendError(302, "Need to redirect to " + APP_URL + "/setAdmin/");
		verify(chain, never()).doFilter(request, response);
		verify(wrapper).close();
	}

	@Test
	void redirectsAPathParameterThatEndsInConfigToTheAdminSetup() throws Exception {
		assumeTrue(System.getenv("SMSS_INITIAL_ADMIN") == null);
		when(wrapper.hasNext()).thenReturn(false);
		HttpServletRequest request = request("/api/engine/runPixel");
		when(request.getRequestURL()).thenReturn(new StringBuffer(APP_URL + "/api/engine/runPixel;config"));
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		run(request, response, chain);

		verify(response).sendError(302, "Need to redirect to " + APP_URL + "/setAdmin/");
		verify(chain, never()).doFilter(request, response);
	}

	@Test
	void stopsCheckingOnceAUserExists() throws Exception {
		when(wrapper.hasNext()).thenReturn(true);
		FilterChain chain = mock(FilterChain.class);

		run(request("/api/engine/runPixel"), mock(HttpServletResponse.class), chain);
		run(request("/api/engine/runPixel"), mock(HttpServletResponse.class), chain);

		verify(chain, times(2)).doFilter(any(), any());
		verify(manager, times(1)).getRawWrapper(any(IDatabaseEngine.class), any(SelectQueryStruct.class));
	}

	@Test
	void treatsAFailedUserQueryAsHavingAUser() throws Exception {
		when(manager.getRawWrapper(any(IDatabaseEngine.class), any(SelectQueryStruct.class)))
				.thenThrow(new IllegalStateException("security db is down"));
		HttpServletRequest request = request("/api/engine/runPixel");
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		run(request, response, chain);

		verify(chain).doFilter(request, response);
		verify(response, never()).sendError(anyInt(), anyString());
	}
}
