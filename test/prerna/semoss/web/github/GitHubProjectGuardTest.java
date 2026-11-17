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
package prerna.semoss.web.github;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.User;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.util.Constants;

class GitHubProjectGuardTest {

	@Test
	void asksForAProjectIdBeforeLookingAtTheSession() {
		HttpServletRequest req = request(null);
		for (String projectId : new String[] { null, "", "  " }) {
			WebApplicationException error = assertThrows(WebApplicationException.class,
					() -> GitHubProjectGuard.requireProjectOwner(req, projectId));
			assertEquals(400, error.getResponse().getStatus());
			assertEquals("projectId is required", reason(error));
		}
	}

	@Test
	void turnsAwayACallerWithoutASignedInUser() {
		WebApplicationException noSession = assertThrows(WebApplicationException.class,
				() -> GitHubProjectGuard.requireProjectOwner(request(null), "p1"));
		assertEquals(401, noSession.getResponse().getStatus());

		HttpSession empty = mock(HttpSession.class);
		WebApplicationException noUser = assertThrows(WebApplicationException.class,
				() -> GitHubProjectGuard.requireProjectOwner(request(empty), "p1"));
		assertEquals(401, noUser.getResponse().getStatus());
		assertEquals("login required", reason(noUser));
	}

	@Test
	void letsOnlyTheOwnerOfThatProjectThrough() {
		User user = mock(User.class);
		HttpServletRequest req = request(session(user));
		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class)) {
			projects.when(() -> SecurityProjectUtils.userIsOwner(user, "mine")).thenReturn(true);

			assertDoesNotThrow(() -> GitHubProjectGuard.requireProjectOwner(req, "mine"));
			WebApplicationException error = assertThrows(WebApplicationException.class,
					() -> GitHubProjectGuard.requireProjectOwner(req, "theirs"));
			assertEquals(403, error.getResponse().getStatus());
			assertEquals("owner access to this project is required", reason(error));
		}
	}

	@Test
	void doesNotAskAboutOwnershipWithoutAUser() {
		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class)) {
			assertThrows(WebApplicationException.class, () -> GitHubProjectGuard.requireProjectOwner(request(null), "p1"));
			projects.verify(() -> SecurityProjectUtils.userIsOwner(any(), any()), never());
		}
	}

	private static HttpSession session(User user) {
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		return session;
	}

	private static HttpServletRequest request(HttpSession session) {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getSession(false)).thenReturn(session);
		return req;
	}

	private static String reason(WebApplicationException error) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			((StreamingOutput) error.getResponse().getEntity()).write(out);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		JsonObject body = JsonParser.parseString(out.toString(StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals("error", body.get("status").getAsString());
		return body.get("reason").getAsString();
	}
}
