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
package prerna.semoss.web.services.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.graph.utility.MsGraphUtility;
import prerna.io.connector.ms.MicrosoftGraphUserLookup;
import prerna.util.Constants;

class AuthorizationResourceTest {

	private static User user() {
		User user = new User();
		AccessToken token = new AccessToken();
		token.setProvider(AuthProvider.NATIVE);
		token.setId("jdoe");
		user.setAccessToken(token);
		return user;
	}

	private static HttpServletRequest request(User user) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		when(request.getSession(false)).thenReturn(session);
		return request;
	}

	private static String body(Response response) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		((StreamingOutput) response.getEntity()).write(out);
		return out.toString(StandardCharsets.UTF_8);
	}

	private static List<String> ids(Response response) throws IOException {
		List<Map<String, Object>> users = new Gson().fromJson(body(response),
				new TypeToken<List<Map<String, Object>>>() {
				}.getType());
		List<String> ids = new ArrayList<>();
		for (Map<String, Object> user : users) {
			ids.add((String) user.get("id"));
		}
		return ids;
	}

	private static List<Map<String, Object>> users(String... ids) {
		List<Map<String, Object>> users = new ArrayList<>();
		for (String id : ids) {
			users.add(Map.of("id", id));
		}
		return users;
	}

	private static Response searchDatabase(String term, long limit, long offset) {
		try (MockedStatic<MicrosoftGraphUserLookup> lookup = mockStatic(MicrosoftGraphUserLookup.class);
				MockedStatic<SecurityQueryUtils> query = mockStatic(SecurityQueryUtils.class)) {
			lookup.when(() -> MicrosoftGraphUserLookup.useDirectory(any())).thenReturn(false);
			query.when(() -> SecurityQueryUtils.searchForUser(term == null ? "" : term))
					.thenReturn(users("a", "b", "c", "d", "e"));
			return new AuthorizationResource().searchForUser(request(user()), term, limit, offset, null);
		}
	}

	@Test
	void rejectsARequestWithoutASessionWith401() throws Exception {
		Response response = new AuthorizationResource().searchForUser(mock(HttpServletRequest.class), "jo", 10, 0,
				null);
		assertEquals(401, response.getStatus());
		assertEquals("User session is invalid", new Gson().fromJson(body(response), Map.class)
				.get(Constants.ERROR_MESSAGE));
	}

	@Test
	void rejectsASessionWithoutAUserWith401() {
		assertEquals(401, new AuthorizationResource().searchForUser(request(null), "jo", 10, 0, null).getStatus());
	}

	@Test
	void pagesTheSecurityDatabaseResults() throws Exception {
		Response response = searchDatabase("jo", 2, 1);
		assertEquals(200, response.getStatus());
		assertEquals(List.of("b", "c"), ids(response));
	}

	@Test
	void returnsEverythingFromTheOffsetWithoutALimit() throws Exception {
		assertEquals(List.of("d", "e"), ids(searchDatabase("jo", 0, 3)));
		assertEquals(List.of("a", "b", "c", "d", "e"), ids(searchDatabase("jo", -1, 0)));
	}

	@Test
	void clampsAnOffsetPastTheEndAndANegativeOffset() throws Exception {
		assertEquals(List.of(), ids(searchDatabase("jo", 2, 10)));
		assertEquals(List.of("a", "b"), ids(searchDatabase("jo", 2, -4)));
	}

	@Test
	void clampsALimitPastTheEnd() throws Exception {
		assertEquals(List.of("d", "e"), ids(searchDatabase("jo", Long.MAX_VALUE, 3)));
	}

	@Test
	void searchesForEveryoneWhenNoTermIsSent() throws Exception {
		assertEquals(5, ids(searchDatabase(null, 0, 0)).size());
	}

	@Test
	void answers500WhenTheSecurityDatabaseSearchFails() throws Exception {
		try (MockedStatic<MicrosoftGraphUserLookup> lookup = mockStatic(MicrosoftGraphUserLookup.class);
				MockedStatic<SecurityQueryUtils> query = mockStatic(SecurityQueryUtils.class)) {
			lookup.when(() -> MicrosoftGraphUserLookup.useDirectory(any())).thenReturn(false);
			query.when(() -> SecurityQueryUtils.searchForUser(anyString()))
					.thenThrow(new IllegalStateException("db down"));
			Response response = new AuthorizationResource().searchForUser(request(user()), "jo", 10, 0, null);

			assertEquals(500, response.getStatus());
			assertEquals("db down", new Gson().fromJson(body(response), Map.class).get(Constants.ERROR_MESSAGE));
		}
	}

	@Test
	void searchesTheDirectoryWhenItIsUsedAndPassesThePaging() throws Exception {
		User user = user();
		HttpServletRequest request = request(user);
		try (MockedStatic<MicrosoftGraphUserLookup> lookup = mockStatic(MicrosoftGraphUserLookup.class);
				MockedStatic<MsGraphUtility> graph = mockStatic(MsGraphUtility.class)) {
			lookup.when(() -> MicrosoftGraphUserLookup.useDirectory("true")).thenReturn(true);
			graph.when(() -> MsGraphUtility.searchDirectory(request, user, "jo", 25L, 50L)).thenReturn(users("ms1"));
			Response response = new AuthorizationResource().searchForUser(request, "jo", 25, 50, "true");

			assertEquals(200, response.getStatus());
			assertEquals(List.of("ms1"), ids(response));
		}
	}

	@Test
	void answers400WhenTheDirectoryNeedsAMicrosoftLogin() throws Exception {
		try (MockedStatic<MicrosoftGraphUserLookup> lookup = mockStatic(MicrosoftGraphUserLookup.class);
				MockedStatic<MsGraphUtility> graph = mockStatic(MsGraphUtility.class)) {
			lookup.when(() -> MicrosoftGraphUserLookup.useDirectory(any())).thenReturn(true);
			graph.when(() -> MsGraphUtility.searchDirectory(any(), any(), anyString(), anyLong(), anyLong()))
					.thenThrow(new IllegalAccessException("Sign in to Microsoft to search the directory"));
			Response response = new AuthorizationResource().searchForUser(request(user()), "jo", 10, 0, null);

			assertEquals(400, response.getStatus());
			assertEquals("Sign in to Microsoft to search the directory",
					new Gson().fromJson(body(response), Map.class).get(Constants.ERROR_MESSAGE));
		}
	}

	@Test
	void answers500WhenTheDirectorySearchFails() throws Exception {
		try (MockedStatic<MicrosoftGraphUserLookup> lookup = mockStatic(MicrosoftGraphUserLookup.class);
				MockedStatic<MsGraphUtility> graph = mockStatic(MsGraphUtility.class)) {
			lookup.when(() -> MicrosoftGraphUserLookup.useDirectory(any())).thenReturn(true);
			graph.when(() -> MsGraphUtility.searchDirectory(any(), any(), anyString(), anyLong(), anyLong()))
					.thenThrow(new IllegalStateException("graph down"));
			assertEquals(500, new AuthorizationResource().searchForUser(request(user()), "jo", 10, 0, null)
					.getStatus());
		}
	}
}
