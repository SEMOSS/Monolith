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
package prerna.graph.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.io.connector.ms.MicrosoftGraphUserLookup;
import prerna.io.connector.ms.MicrosoftGraphUserLookup.UserPage;

class MsGraphUtilityTest {

	private final Map<String, Object> attributes = new HashMap<>();
	private HttpServletRequest request;
	private User user;
	private MockedStatic<MicrosoftGraphUserLookup> graph;

	@BeforeEach
	void prepare() {
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(anyString())).thenAnswer(inv -> attributes.get(inv.<String>getArgument(0)));
		doAnswer(inv -> attributes.put(inv.getArgument(0), inv.getArgument(1))).when(session)
				.setAttribute(anyString(), any());
		request = mock(HttpServletRequest.class);
		when(request.getSession()).thenReturn(session);
		user = mock(User.class);
		graph = mockStatic(MicrosoftGraphUserLookup.class);
	}

	@AfterEach
	void release() {
		graph.close();
	}

	private static Map<String, Object> person(String id, String email) {
		Map<String, Object> person = new HashMap<>();
		if (id != null) {
			person.put("id", id);
		}
		if (email != null) {
			person.put("email", email);
		}
		return person;
	}

	private static UserPage page(String nextLink, String... ids) {
		List<Map<String, Object>> users = new ArrayList<>();
		List<Map<String, Object>> graphUsers = new ArrayList<>();
		for (String id : ids) {
			users.add(person(id, id + "@example.com"));
			Map<String, Object> graphUser = new HashMap<>();
			graphUser.put("id", "graph-" + id);
			graphUser.put("displayName", "Name " + id);
			graphUsers.add(graphUser);
		}
		return new UserPage(users, graphUsers, nextLink);
	}

	private void graphReturns(String nextLink, UserPage page) {
		if (nextLink == null) {
			graph.when(() -> MicrosoftGraphUserLookup.searchUsers(any(), any(), isNull())).thenReturn(page);
		} else {
			graph.when(() -> MicrosoftGraphUserLookup.searchUsers(any(), any(), eq(nextLink)))
					.thenReturn(page);
		}
	}

	private static List<Object> ids(List<Map<String, Object>> users) {
		List<Object> ids = new ArrayList<>();
		for (Map<String, Object> user : users) {
			ids.add(user.get("id"));
		}
		return ids;
	}

	@Test
	void projectSearchLeavesOutUsersWhoAlreadyHaveAccess() throws Exception {
		graphReturns(null, page(null, "u1", "u2", "u3"));
		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class)) {
			projects.when(() -> SecurityProjectUtils.getProjectUsers(user, "p1", "jo", "", -1, -1))
					.thenReturn(List.of(person("u1", null), person(null, " U2@Example.COM ")));

			List<Map<String, Object>> found = MsGraphUtility.getProjectUsers(request, user, "p1", "jo", 10, 0, false);

			assertEquals(List.of("u3"), ids(found));
			assertEquals("Name u3", found.get(0).get("displayName"));
			assertEquals("u3@example.com", found.get(0).get("email"));
		}
	}

	@Test
	void adminSearchesUseTheAdminUserLists() throws Exception {
		graphReturns(null, page(null, "u1", "u2"));
		SecurityAdminUtils admin = mock(SecurityAdminUtils.class);
		when(admin.getProjectUsers("p1", "jo", "", -1, -1)).thenReturn(List.of(person("u1", null)));
		when(admin.getEngineUsers("e1", "jo", "", -1, -1)).thenReturn(List.of(person("u2", null)));
		try (MockedStatic<SecurityAdminUtils> admins = mockStatic(SecurityAdminUtils.class);
				MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class);
				MockedStatic<SecurityEngineUtils> engines = mockStatic(SecurityEngineUtils.class)) {
			admins.when(() -> SecurityAdminUtils.getInstance(user)).thenReturn(admin);

			assertEquals(List.of("u2"), ids(MsGraphUtility.getProjectUsers(request, user, "p1", "jo", 10, 0, true)));
			assertEquals(List.of("u1"), ids(MsGraphUtility.getEngineUsers(request, user, "e1", "jo", 10, 0, true)));
			projects.verify(() -> SecurityProjectUtils.getProjectUsers(any(), anyString(), anyString(), anyString(),
					anyLong(), anyLong()), never());
			engines.verify(() -> SecurityEngineUtils.getEngineUsers(any(), anyString(), anyString(), anyString(),
					anyLong(), anyLong()), never());
		}
	}

	@Test
	void engineSearchLeavesOutUsersWhoAlreadyHaveAccess() throws Exception {
		graphReturns(null, page(null, "u1", "u2"));
		try (MockedStatic<SecurityEngineUtils> engines = mockStatic(SecurityEngineUtils.class)) {
			engines.when(() -> SecurityEngineUtils.getEngineUsers(user, "e1", "jo", "", -1, -1))
					.thenReturn(List.of(person("u2", null)));

			assertEquals(List.of("u1"), ids(MsGraphUtility.getEngineUsers(request, user, "e1", "jo", 10, 0, false)));
		}
	}

	@Test
	void groupSearchMatchesMembersByTheirUseridKey() throws Exception {
		graphReturns(null, page(null, "u1", "u2"));
		Map<String, Object> member = new HashMap<>();
		member.put("userid", "u1");

		List<Map<String, Object>> found = MsGraphUtility.getGroupUsers(request, user, "g1", List.of(member), "jo", 10,
				0);

		assertEquals(List.of("u2"), ids(found));
	}

	@Test
	void fetchesGraphPagesUntilThePageIsFullAndKeepsTheRestForTheNextCall() throws Exception {
		graphReturns(null, page("n1", "u1", "u2"));
		graphReturns("n1", page("n2", "u3", "u4"));
		graphReturns("n2", page(null, "u5"));

		List<Map<String, Object>> first = MsGraphUtility.getGroupUsers(request, user, "g1", List.of(), "a", 3, 0);
		List<Map<String, Object>> second = MsGraphUtility.getGroupUsers(request, user, "g1", List.of(), "a", 3, 3);
		List<Map<String, Object>> third = MsGraphUtility.getGroupUsers(request, user, "g1", List.of(), "a", 3, 6);

		assertEquals(List.of("u1", "u2", "u3"), ids(first));
		assertEquals(List.of("u4", "u5"), ids(second));
		assertEquals(List.of(), third);
		graph.verify(() -> MicrosoftGraphUserLookup.searchUsers(any(), any(), any()), times(3));
	}

	@Test
	void offsetZeroStartsTheSearchOver() throws Exception {
		graphReturns(null, page("n1", "u1", "u2"));

		MsGraphUtility.getGroupUsers(request, user, "g1", List.of(), "a", 1, 0);
		List<Map<String, Object>> again = MsGraphUtility.getGroupUsers(request, user, "g1", List.of(), "a", 1, 0);

		assertEquals(List.of("u1"), ids(again));
		graph.verify(() -> MicrosoftGraphUserLookup.searchUsers(any(), any(), isNull()), times(2));
	}

	@Test
	void aContinuationWithoutStoredStateStartsANewSearch() throws Exception {
		graphReturns(null, page(null, "u1"));

		List<Map<String, Object>> found = MsGraphUtility.getGroupUsers(request, user, "g1", List.of(), "a", 5, 20);

		assertEquals(List.of("u1"), ids(found));
	}

	@Test
	void withoutALimitReturnsTheNextGraphPageThatHasMatches() throws Exception {
		graphReturns(null, page("n1", "u1"));
		graphReturns("n1", page("n2", "u2", "u3"));
		Map<String, Object> member = new HashMap<>();
		member.put("userid", "u1");

		List<Map<String, Object>> found = MsGraphUtility.getGroupUsers(request, user, "g1", List.of(member), "a", 0, 0);

		assertEquals(List.of("u2", "u3"), ids(found));
		graph.verify(() -> MicrosoftGraphUserLookup.searchUsers(any(), any(), eq("n2")),
				never());
	}

	@Test
	void keepsEachSearchInItsOwnSessionAttribute() throws Exception {
		graphReturns(null, page(null, "u1"));
		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class);
				MockedStatic<SecurityEngineUtils> engines = mockStatic(SecurityEngineUtils.class)) {
			projects.when(() -> SecurityProjectUtils.getProjectUsers(any(), anyString(), anyString(), anyString(),
					anyLong(), anyLong())).thenReturn(List.of());
			engines.when(() -> SecurityEngineUtils.getEngineUsers(any(), anyString(), anyString(), anyString(),
					anyLong(), anyLong())).thenReturn(List.of());

			MsGraphUtility.getProjectUsers(request, user, "p1", "a", 5, 0, false);
			MsGraphUtility.getEngineUsers(request, user, "e1", "a", 5, 0, false);
			MsGraphUtility.getGroupUsers(request, user, "g1", List.of(), "a", 5, 0);
		}

		assertEquals(Set.of("nld_p_p1_a", "nld_e_e1_a", "nld_g_g1_a"), attributes.keySet());
	}

	@Test
	void directorySearchMarksUsersWhoAlreadyHaveAnAccount() throws Exception {
		List<Map<String, Object>> users = new ArrayList<>(page(null, "u1", "u2").getUsers());
		users.add(person(null, "noid@example.com"));
		List<Map<String, Object>> graphUsers = new ArrayList<>(page(null, "u1", "u2").getGraphUsers());
		graphUsers.add(new HashMap<>());
		graphReturns(null, new UserPage(users, graphUsers, null));
		graph.when(() -> MicrosoftGraphUserLookup.findExistingUserIds(any())).thenReturn(Set.of("u1"));
		try (MockedStatic<User> accounts = mockStatic(User.class)) {
			accounts.when(() -> User.getSingleLogginName(user)).thenReturn("me");

			List<Map<String, Object>> found = MsGraphUtility.searchDirectory(request, user, "a", 10, 0);

			assertEquals(3, found.size());
			assertEquals(true, found.get(0).get(MsGraphUtility.HAS_ACCOUNT_KEY));
			assertEquals(false, found.get(1).get(MsGraphUtility.HAS_ACCOUNT_KEY));
			assertEquals(false, found.get(2).get(MsGraphUtility.HAS_ACCOUNT_KEY));
		}
		assertEquals(Set.of("nld_u_me_a"), attributes.keySet());
	}

	@Test
	void aGraphFailureBecomesAFriendlyError() {
		graph.when(() -> MicrosoftGraphUserLookup.searchUsers(any(), any(), any()))
				.thenThrow(new RuntimeException("503 from graph"));

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> MsGraphUtility.getGroupUsers(request, user, "g1", List.of(), "a", 5, 0));

		assertEquals("Could not search your organization's directory. Try again.", error.getMessage());
	}

	@Test
	void aMissingMicrosoftLoginIsRethrownAsIs() {
		IllegalAccessException denied = new IllegalAccessException("Sign in to Microsoft");
		graph.when(() -> MicrosoftGraphUserLookup.searchUsers(any(), any(), any())).thenThrow(denied);

		IllegalAccessException thrown = assertThrows(IllegalAccessException.class,
				() -> MsGraphUtility.getGroupUsers(request, user, "g1", List.of(), "a", 5, 0));

		assertSame(denied, thrown);
	}
}
