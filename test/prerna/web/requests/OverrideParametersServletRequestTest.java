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
package prerna.web.requests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;

class OverrideParametersServletRequestTest {

	@Test
	void answersParametersFromTheOverrideMap() {
		HttpServletRequest original = mock(HttpServletRequest.class);
		when(original.getParameter("expression")).thenReturn("Original();");
		OverrideParametersServletRequest wrapper = new OverrideParametersServletRequest(original);

		wrapper.setParameters(Map.of("insightId", "new", "expression", "Override();"));

		assertEquals("new", wrapper.getParameter("insightId"));
		assertEquals("Override();", wrapper.getParameter("expression"));
	}

	@Test
	void hidesParametersOfTheWrappedRequestThatAreNotOverridden() {
		HttpServletRequest original = mock(HttpServletRequest.class);
		when(original.getParameter("tz")).thenReturn("UTC");
		OverrideParametersServletRequest wrapper = new OverrideParametersServletRequest(original);

		assertNull(wrapper.getParameter("tz"), "a wrapper with no overrides answers no parameters");
		wrapper.setParameters(Map.of("insightId", "new"));
		assertNull(wrapper.getParameter("tz"));
	}

	@Test
	void delegatesEverythingElseToTheWrappedRequest() {
		HttpServletRequest original = mock(HttpServletRequest.class);
		when(original.getMethod()).thenReturn("POST");
		when(original.getRequestURI()).thenReturn("/Monolith/api/engine/runPixel");
		OverrideParametersServletRequest wrapper = new OverrideParametersServletRequest(original);

		assertEquals("POST", wrapper.getMethod());
		assertEquals("/Monolith/api/engine/runPixel", wrapper.getRequestURI());
	}
}
