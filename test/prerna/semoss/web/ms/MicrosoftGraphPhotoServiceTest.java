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
package prerna.semoss.web.ms;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;
import prerna.auth.User;
import prerna.collaboration.BrainPeopleUtils;
import prerna.collaboration.CollaborationDbUtils;
import prerna.util.Constants;
import prerna.util.Utility;

class MicrosoftGraphPhotoServiceTest {

	private static final String EMAIL = "someone@contoso.com";
	private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01 };

	private final MicrosoftGraphPhotoService service = new MicrosoftGraphPhotoService();

	@Test
	void answersNotFoundWhileCollaborationIsOff() {
		HttpServletRequest req = request(mock(User.class));
		try (MockedStatic<Utility> utility = mockStatic(Utility.class);
				MockedStatic<CollaborationDbUtils> collaboration = mockStatic(CollaborationDbUtils.class);
				MockedStatic<BrainPeopleUtils> people = mockStatic(BrainPeopleUtils.class)) {
			collaboration.when(CollaborationDbUtils::isInitalized).thenReturn(true);
			assertEquals(404, service.photo(req).getStatus());

			utility.when(Utility::isCollaborationDatabaseEnabled).thenReturn(true);
			collaboration.when(CollaborationDbUtils::isInitalized).thenReturn(false);
			assertEquals(404, service.photo(req).getStatus());

			people.verifyNoInteractions();
			verify(req, never()).getSession(false);
		}
	}

	@Test
	void needsASignedInUser() {
		try (MockedStatic<Utility> utility = mockStatic(Utility.class);
				MockedStatic<CollaborationDbUtils> collaboration = mockStatic(CollaborationDbUtils.class);
				MockedStatic<BrainPeopleUtils> people = mockStatic(BrainPeopleUtils.class)) {
			enableCollaboration(utility, collaboration);

			Response response = service.photo(request(null));

			assertEquals(401, response.getStatus());
			people.verifyNoInteractions();
		}
	}

	@Test
	void returnsThePhotoTheSignedInUserCanSeeForADay() throws Exception {
		User user = mock(User.class);
		try (MockedStatic<Utility> utility = mockStatic(Utility.class);
				MockedStatic<CollaborationDbUtils> collaboration = mockStatic(CollaborationDbUtils.class);
				MockedStatic<BrainPeopleUtils> people = mockStatic(BrainPeopleUtils.class)) {
			enableCollaboration(utility, collaboration);
			people.when(() -> BrainPeopleUtils.photo(user, EMAIL)).thenReturn(JPEG);

			Response response = service.photo(request(user));

			assertEquals(200, response.getStatus());
			assertEquals("image/jpeg", response.getMediaType().toString());
			assertArrayEquals(JPEG, (byte[]) response.getEntity());
			assertEquals("private, max-age=86400", response.getHeaderString("Cache-Control"));
		}
	}

	@Test
	void answersAMissingOrUnreadablePhotoAsNotFoundForAnHour() throws Exception {
		User user = mock(User.class);
		try (MockedStatic<Utility> utility = mockStatic(Utility.class);
				MockedStatic<CollaborationDbUtils> collaboration = mockStatic(CollaborationDbUtils.class);
				MockedStatic<BrainPeopleUtils> people = mockStatic(BrainPeopleUtils.class)) {
			enableCollaboration(utility, collaboration);

			for (Object outcome : new Object[] { null, new byte[0], new IllegalStateException("Graph returned 404") }) {
				if (outcome instanceof Exception e) {
					people.when(() -> BrainPeopleUtils.photo(any(), any())).thenThrow(e);
				} else {
					people.when(() -> BrainPeopleUtils.photo(any(), any())).thenReturn(outcome);
				}
				Response response = service.photo(request(user));
				assertEquals(404, response.getStatus());
				assertNull(response.getEntity());
				assertEquals("private, max-age=3600", response.getHeaderString("Cache-Control"));
			}
		}
	}

	private static void enableCollaboration(MockedStatic<Utility> utility,
			MockedStatic<CollaborationDbUtils> collaboration) {
		utility.when(Utility::isCollaborationDatabaseEnabled).thenReturn(true);
		collaboration.when(CollaborationDbUtils::isInitalized).thenReturn(true);
	}

	private static HttpServletRequest request(User user) {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getParameter("email")).thenReturn(EMAIL);
		if (user != null) {
			HttpSession session = mock(HttpSession.class);
			when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
			when(req.getSession(false)).thenReturn(session);
		}
		return req;
	}
}
