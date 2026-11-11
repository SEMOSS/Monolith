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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.google.gson.Gson;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.theme.AbstractThemeUtils;
import prerna.theme.AdminThemeUtils;
import prerna.util.Constants;

class AdminThemeResourceTest {

	private static User user() {
		User user = new User();
		AccessToken token = new AccessToken();
		token.setProvider(AuthProvider.NATIVE);
		token.setId("admin");
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

	private static Object error(Response response) throws IOException {
		return new Gson().fromJson(body(response), Map.class).get("error");
	}

	private static MultivaluedMap<String, String> form(String... pairs) {
		MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			form.add(pairs[i], pairs[i + 1]);
		}
		return form;
	}

	private static final class Themes implements AutoCloseable {
		final MockedStatic<AbstractThemeUtils> init = mockStatic(AbstractThemeUtils.class);
		final MockedStatic<AdminThemeUtils> admin = mockStatic(AdminThemeUtils.class);
		final AdminThemeUtils utils = mock(AdminThemeUtils.class);

		Themes(boolean initialized, User adminUser) {
			init.when(AbstractThemeUtils::isInitalized).thenReturn(initialized);
			admin.when(() -> AdminThemeUtils.getInstance(any())).thenReturn(null);
			if (adminUser != null) {
				admin.when(() -> AdminThemeUtils.getInstance(adminUser)).thenReturn(utils);
			}
		}

		@Override
		public void close() {
			admin.close();
			init.close();
		}
	}

	private static List<Function<HttpServletRequest, Response>> adminEndpoints() {
		AdminThemeResource resource = new AdminThemeResource();
		return List.of(request -> resource.getAdminThemes(request, 10, 0),
				request -> resource.createAdminTheme(request, form("name", "n", "json", "{}")),
				request -> resource.editAdminTheme(request, form("id", "t1")),
				request -> resource.deleteAdminTheme(request, form("id", "t1")),
				request -> resource.setActiveAdminTheme(request, form("id", "t1")),
				resource::setAllAdminThemesInactive);
	}

	@Test
	void everyEndpointAnswers400WhenTheThemeDatabaseIsMissing() throws Exception {
		try (Themes themes = new Themes(false, user())) {
			Response active = new AdminThemeResource().getActiveAdminTheme(request(null));
			assertEquals(400, active.getStatus());
			assertEquals("Theming database was not found to perform these operations", error(active));
			for (Function<HttpServletRequest, Response> endpoint : adminEndpoints()) {
				Response response = endpoint.apply(request(user()));
				assertEquals(400, response.getStatus());
				assertEquals("Theming database was not found to perform these operations", error(response));
			}
			verifyNoInteractions(themes.utils);
		}
	}

	@Test
	void everyAdminEndpointAnswers401WithoutASignedInUser() throws Exception {
		try (Themes themes = new Themes(true, null)) {
			for (Function<HttpServletRequest, Response> endpoint : adminEndpoints()) {
				assertEquals(401, endpoint.apply(mock(HttpServletRequest.class)).getStatus());
				assertEquals(401, endpoint.apply(request(null)).getStatus());
			}
		}
	}

	@Test
	void everyAdminEndpointRejectsANonAdminWithoutTouchingThemes() throws Exception {
		try (Themes themes = new Themes(true, null)) {
			for (Function<HttpServletRequest, Response> endpoint : adminEndpoints()) {
				Response response = endpoint.apply(request(user()));
				assertEquals("User is not an admin", error(response));
			}
			verifyNoInteractions(themes.utils);
		}
	}

	@Test
	void anyoneCanReadTheActiveTheme() throws Exception {
		try (Themes themes = new Themes(true, null)) {
			themes.admin.when(AdminThemeUtils::getActiveAdminTheme).thenReturn(Map.of("name", "dark"));
			Response response = new AdminThemeResource().getActiveAdminTheme(mock(HttpServletRequest.class));

			assertEquals(200, response.getStatus());
			assertEquals("{\"name\":\"dark\"}", body(response));
		}
	}

	@Test
	void listsThemesForAnAdminWithThePaging() throws Exception {
		User user = user();
		try (Themes themes = new Themes(true, user)) {
			when(themes.utils.getAdminThemes(5, 10)).thenReturn(List.of(Map.of("id", "t1")));
			Response response = new AdminThemeResource().getAdminThemes(request(user), 5, 10);

			assertEquals(200, response.getStatus());
			assertEquals("[{\"id\":\"t1\"}]", body(response));
		}
	}

	@Test
	void createsAThemeAndAnswersWhetherItWasCreated() throws Exception {
		User user = user();
		try (Themes themes = new Themes(true, user)) {
			when(themes.utils.createAdminTheme("Dark", "{\"a\":1}", true)).thenReturn("t1");
			Response created = new AdminThemeResource().createAdminTheme(request(user),
					form("name", "Dark", "json", "{\"a\":1}", "isActive", "true"));
			assertEquals(200, created.getStatus());
			assertEquals("true", body(created));

			Response failed = new AdminThemeResource().createAdminTheme(request(user),
					form("name", "Light", "json", "{}"));
			assertEquals(400, failed.getStatus());
			assertEquals("false", body(failed));
			verify(themes.utils).createAdminTheme("Light", "{}", false);
		}
	}

	@Test
	void editsATheme() throws Exception {
		User user = user();
		try (Themes themes = new Themes(true, user)) {
			when(themes.utils.editAdminTheme("t1", "Dark", "{}", false)).thenReturn(true);
			Response edited = new AdminThemeResource().editAdminTheme(request(user),
					form("id", "t1", "name", "Dark", "json", "{}", "isActive", "false"));
			assertEquals(200, edited.getStatus());
			assertEquals("true", body(edited));

			Response failed = new AdminThemeResource().editAdminTheme(request(user), form("id", "t2"));
			assertEquals(400, failed.getStatus());
			assertEquals("false", body(failed));
		}
	}

	@Test
	void deletesATheme() throws Exception {
		User user = user();
		try (Themes themes = new Themes(true, user)) {
			when(themes.utils.deleteAdminTheme("t1")).thenReturn(true);
			assertEquals(200, new AdminThemeResource().deleteAdminTheme(request(user), form("id", "t1")).getStatus());
			assertEquals(400, new AdminThemeResource().deleteAdminTheme(request(user), form("id", "t2")).getStatus());
		}
	}

	@Test
	void setsTheActiveTheme() throws Exception {
		User user = user();
		try (Themes themes = new Themes(true, user)) {
			when(themes.utils.setActiveTheme("t1")).thenReturn(true);
			assertEquals(200,
					new AdminThemeResource().setActiveAdminTheme(request(user), form("id", "t1")).getStatus());
			assertEquals(400,
					new AdminThemeResource().setActiveAdminTheme(request(user), form("id", "t2")).getStatus());
		}
	}

	@Test
	void setsAllThemesInactive() throws Exception {
		User user = user();
		try (Themes themes = new Themes(true, user)) {
			when(themes.utils.setAllThemesInactive()).thenReturn(true, false);
			assertEquals(200, new AdminThemeResource().setAllAdminThemesInactive(request(user)).getStatus());
			assertEquals(400, new AdminThemeResource().setAllAdminThemesInactive(request(user)).getStatus());
		}
	}
}
