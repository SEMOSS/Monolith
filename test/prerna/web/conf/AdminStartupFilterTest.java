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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import prerna.engine.api.IDatabaseEngine;
import prerna.engine.api.IRDBMSEngine;
import prerna.engine.api.IRawSelectWrapper;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.SystemEngineRegistry;

class AdminStartupFilterTest {

	@AfterEach
	void clearRedirect() {
		AdminStartupFilter.setSuccessfulRedirectUrl(null);
	}

	private static void run(boolean hasUser, HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws Exception {
		IRDBMSEngine securityDb = mock(IRDBMSEngine.class);
		IRawSelectWrapper wrapper = mock(IRawSelectWrapper.class);
		when(wrapper.hasNext()).thenReturn(hasUser);
		WrapperManager manager = mock(WrapperManager.class);
		when(manager.getRawWrapper(any(IDatabaseEngine.class), eq("SELECT * FROM SMSS_USER LIMIT 1")))
				.thenReturn(wrapper);
		try (MockedStatic<SystemEngineRegistry> registry = mockStatic(SystemEngineRegistry.class);
				MockedStatic<WrapperManager> managers = mockStatic(WrapperManager.class)) {
			registry.when(SystemEngineRegistry::getSecurityDb).thenReturn(securityDb);
			managers.when(WrapperManager::getInstance).thenReturn(manager);
			new AdminStartupFilter().doFilter(request, response, chain);
		}
		verify(wrapper).close();
	}

	@Test
	void passesTheAdminSetupWhileNoUserExists() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		run(false, request, response, chain);

		verify(chain).doFilter(request, response);
		verify(response, never()).sendError(anyInt(), anyString());
	}

	@Test
	void answersNotFoundOnceAUserExists() throws Exception {
		HttpServletResponse response = mock(HttpServletResponse.class);

		run(true, mock(HttpServletRequest.class), response, mock(FilterChain.class));

		verify(response).sendError(404, "Page Not Found");
		verify(response, never()).setHeader(eq("redirect"), anyString());
	}

	@Test
	void redirectsToTheEncodedLoginTargetOnceAUserExists() throws Exception {
		AdminStartupFilter.setSuccessfulRedirectUrl("https://app.example/semoss-ui/?a=1&b=<2>");
		HttpServletResponse response = mock(HttpServletResponse.class);

		run(true, mock(HttpServletRequest.class), response, mock(FilterChain.class));

		String encoded = "https://app.example/semoss-ui/?a=1&amp;b=&lt;2&gt;";
		verify(response).setHeader("redirect", encoded);
		verify(response).sendError(302, "Need to redirect to " + encoded);
	}

	@Test
	void stopsTheAdminSetupOnceAUserExists() throws Exception {
		FilterChain chain = mock(FilterChain.class);

		run(true, mock(HttpServletRequest.class), mock(HttpServletResponse.class), chain);
		AdminStartupFilter.setSuccessfulRedirectUrl("https://app.example/semoss-ui/");
		run(true, mock(HttpServletRequest.class), mock(HttpServletResponse.class), chain);

		verify(chain, never()).doFilter(any(), any());
	}

	@Test
	void refusesTheAdminSetupWhenTheUserCheckFails() throws Exception {
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);
		WrapperManager manager = mock(WrapperManager.class);
		when(manager.getRawWrapper(any(IDatabaseEngine.class), anyString()))
				.thenThrow(new IllegalStateException("security db unavailable"));
		try (MockedStatic<SystemEngineRegistry> registry = mockStatic(SystemEngineRegistry.class);
				MockedStatic<WrapperManager> managers = mockStatic(WrapperManager.class)) {
			registry.when(SystemEngineRegistry::getSecurityDb).thenReturn(mock(IRDBMSEngine.class));
			managers.when(WrapperManager::getInstance).thenReturn(manager);
			new AdminStartupFilter().doFilter(mock(HttpServletRequest.class), response, chain);
		}

		verify(response).sendError(503, "Unable to verify the initial admin setup");
		verify(chain, never()).doFilter(any(), any());
	}
}
