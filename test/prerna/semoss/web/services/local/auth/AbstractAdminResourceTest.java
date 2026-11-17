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
package prerna.semoss.web.services.local.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import jakarta.servlet.http.HttpServletRequest;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;

class AbstractAdminResourceTest {

	private static User signedIn(String id) {
		AccessToken token = new AccessToken();
		token.setProvider(AuthProvider.NATIVE);
		token.setId(id);
		User user = new User();
		user.setAccessToken(token);
		return user;
	}

	@Test
	void returnsTheAdminUtilitiesForAnAdmin() throws Exception {
		User admin = signedIn("admin1");
		SecurityAdminUtils adminUtils = Mockito.mock(SecurityAdminUtils.class);
		try (MockedStatic<SecurityAdminUtils> statics = Mockito.mockStatic(SecurityAdminUtils.class)) {
			statics.when(() -> SecurityAdminUtils.getInstance(admin)).thenReturn(adminUtils);

			assertSame(adminUtils,
					new AbstractAdminResource().performAdminCheck(Mockito.mock(HttpServletRequest.class), admin));
		}
	}

	@Test
	void rejectsANonAdminWithIllegalAccessException() {
		User user = signedIn("jdoe");
		try (MockedStatic<SecurityAdminUtils> statics = Mockito.mockStatic(SecurityAdminUtils.class)) {
			statics.when(() -> SecurityAdminUtils.getInstance(user)).thenReturn(null);

			IllegalAccessException e = assertThrows(IllegalAccessException.class,
					() -> new AbstractAdminResource().performAdminCheck(Mockito.mock(HttpServletRequest.class), user));
			assertEquals("User is not an admin", e.getMessage());
		}
	}

	@Test
	void rejectsAMissingUser() {
		try (MockedStatic<SecurityAdminUtils> statics = Mockito.mockStatic(SecurityAdminUtils.class,
				Mockito.CALLS_REAL_METHODS)) {
			assertThrows(IllegalAccessException.class,
					() -> new AbstractAdminResource().performAdminCheck(Mockito.mock(HttpServletRequest.class), null));
			statics.verify(() -> SecurityAdminUtils.getInstance(null));
		}
	}
}
