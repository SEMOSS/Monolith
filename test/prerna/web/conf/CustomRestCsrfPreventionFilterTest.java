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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

class CustomRestCsrfPreventionFilterTest {

	private static final String TOKEN_HEADER = "X-CSRF-Token";
	private static final String NONCE_ATTRIBUTE = "org.apache.catalina.filters.CSRF_REST_NONCE";
	private static final String EXCLUDED = " /api/model/openai , /api/ext,/api/model/anthropic ";

	private static CustomRestCsrfPreventionFilter filter(String excludedPaths) throws Exception {
		Map<String, String> params = new TreeMap<>();
		params.put("denyStatus", "403");
		params.put("randomClass", "java.security.SecureRandom");
		if (excludedPaths != null) {
			params.put("excluded-paths", excludedPaths);
		}
		FilterConfig config = mock(FilterConfig.class);
		when(config.getServletContext()).thenReturn(mock(ServletContext.class));
		when(config.getInitParameterNames()).thenAnswer(i -> Collections.enumeration(params.keySet()));
		params.forEach((k, v) -> when(config.getInitParameter(k)).thenReturn(v));
		CustomRestCsrfPreventionFilter filter = new CustomRestCsrfPreventionFilter();
		filter.init(config);
		return filter;
	}

	private static HttpSession session(Map<String, Object> attributes) {
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(anyString())).thenAnswer(i -> attributes.get(i.getArgument(0)));
		doAnswer(i -> attributes.put(i.getArgument(0), i.getArgument(1))).when(session).setAttribute(anyString(),
				any());
		return session;
	}

	private static HttpServletRequest request(String method, String path, HttpSession session) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn(method);
		when(request.getContextPath()).thenReturn("/Monolith");
		when(request.getRequestURI()).thenReturn("/Monolith" + path);
		when(request.getServletPath()).thenReturn(path);
		when(request.getSession()).thenReturn(session);
		when(request.getSession(anyBoolean())).thenReturn(session);
		return request;
	}

	@Test
	void skipsCsrfForAnExcludedPrefix() throws Exception {
		CustomRestCsrfPreventionFilter filter = filter(EXCLUDED);
		for (String path : new String[] { "/api/model/openai/v1/chat/completions", "/api/ext/mcp/toolbox",
				"/api/model/anthropic/v1/messages" }) {
			HttpServletRequest request = request("POST", path, null);
			HttpServletResponse response = mock(HttpServletResponse.class);
			FilterChain chain = mock(FilterChain.class);

			filter.doFilter(request, response, chain);

			verify(chain).doFilter(request, response);
			verify(request, never()).getSession();
			verify(response, never()).sendError(anyInt(), anyString());
		}
	}

	@Test
	void appliesCsrfToAPathThatOnlyLooksExcludedInTheRawUri() throws Exception {
		// Tomcat strips ;params and resolves .. before mapping, so this is served as runPixel
		HttpServletRequest dotDot = request("POST", "/api/engine/runPixel", session(new HashMap<>()));
		when(dotDot.getRequestURI()).thenReturn("/Monolith/api/ext/..;/engine/runPixel");
		when(dotDot.getServletPath()).thenReturn("/api");
		when(dotDot.getPathInfo()).thenReturn("/engine/runPixel");
		HttpServletRequest lookalike = request("POST", "/api/extanything", session(new HashMap<>()));
		when(lookalike.getServletPath()).thenReturn("/api");
		when(lookalike.getPathInfo()).thenReturn("/extanything");
		CustomRestCsrfPreventionFilter filter = filter(EXCLUDED);
		for (HttpServletRequest request : new HttpServletRequest[] { dotDot, lookalike }) {
			HttpServletResponse response = mock(HttpServletResponse.class);
			FilterChain chain = mock(FilterChain.class);

			filter.doFilter(request, response, chain);

			verify(response).sendError(eq(403), anyString());
			verify(chain, never()).doFilter(request, response);
		}
	}

	@Test
	void skipsCsrfForAnExcludedPathMappedUnderTheApiServlet() throws Exception {
		HttpServletRequest request = request("POST", "/api/ext/mcp/toolbox", null);
		when(request.getServletPath()).thenReturn("/api");
		when(request.getPathInfo()).thenReturn("/ext/mcp/toolbox");
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		filter(EXCLUDED).doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		verify(response, never()).sendError(anyInt(), anyString());
	}

	@Test
	void ignoresABlankExcludedPath() throws Exception {
		HttpServletRequest request = request("POST", "/api/engine/runPixel", session(new HashMap<>()));
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		filter("/api/ext, ,").doFilter(request, response, chain);

		verify(response).sendError(eq(403), anyString());
		verify(chain, never()).doFilter(request, response);
	}

	@Test
	void deniesAStateChangingRequestWithoutAToken() throws Exception {
		Map<String, Object> attributes = new HashMap<>();
		HttpServletRequest request = request("POST", "/api/engine/runPixel", session(attributes));
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		filter(EXCLUDED).doFilter(request, response, chain);

		verify(response).sendError(eq(403), anyString());
		verify(response).setHeader(TOKEN_HEADER, "Required");
		verify(chain, never()).doFilter(request, response);
		assertEquals(Boolean.TRUE, attributes.get("csrf"));
	}

	@Test
	void handsOutATokenOnFetchAndAcceptsItOnTheNextPost() throws Exception {
		CustomRestCsrfPreventionFilter filter = filter(null);
		Map<String, Object> attributes = new HashMap<>();
		HttpSession session = session(attributes);

		HttpServletRequest fetch = request("GET", "/api/config/fetchCsrf", session);
		when(fetch.getHeader(TOKEN_HEADER)).thenReturn("Fetch");
		HttpServletResponse fetchResponse = mock(HttpServletResponse.class);
		FilterChain fetchChain = mock(FilterChain.class);
		filter.doFilter(fetch, fetchResponse, fetchChain);

		verify(fetchChain).doFilter(fetch, fetchResponse);
		ArgumentCaptor<String> nonce = ArgumentCaptor.forClass(String.class);
		verify(fetchResponse).setHeader(eq(TOKEN_HEADER), nonce.capture());
		assertNotNull(nonce.getValue());
		assertFalse(nonce.getValue().isEmpty());
		assertEquals(nonce.getValue(), attributes.get(NONCE_ATTRIBUTE));

		HttpServletRequest post = request("POST", "/api/engine/runPixel", session);
		when(post.getHeader(TOKEN_HEADER)).thenReturn(nonce.getValue());
		HttpServletResponse postResponse = mock(HttpServletResponse.class);
		FilterChain postChain = mock(FilterChain.class);
		filter.doFilter(post, postResponse, postChain);

		verify(postChain).doFilter(post, postResponse);
		verify(postResponse, never()).sendError(anyInt(), anyString());
	}

	@Test
	void deniesAPostWithAnotherSessionsToken() throws Exception {
		Map<String, Object> attributes = new HashMap<>();
		attributes.put(NONCE_ATTRIBUTE, "the-real-nonce");
		HttpServletRequest request = request("POST", "/api/engine/runPixel", session(attributes));
		when(request.getHeader(TOKEN_HEADER)).thenReturn("a-guessed-nonce");
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		filter(EXCLUDED).doFilter(request, response, chain);

		verify(response).sendError(eq(403), anyString());
		verify(chain, never()).doFilter(request, response);
	}

	@Test
	void passesASafeMethodWithoutAToken() throws Exception {
		HttpServletRequest request = request("GET", "/api/auth/logins", session(new HashMap<>()));
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		filter(EXCLUDED).doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		verify(response, never()).sendError(anyInt(), anyString());
	}
}
