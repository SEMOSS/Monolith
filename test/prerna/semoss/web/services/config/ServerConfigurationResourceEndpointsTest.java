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
package prerna.semoss.web.services.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.PasswordRequirements;
import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.auth.utils.SecurityUserUtils;
import prerna.ds.py.PyUtils;
import prerna.io.connector.ConnectorScopeAccess;
import prerna.io.connector.ms.MicrosoftGraphUserLookup;
import prerna.reactor.cluster.VersionReactor;
import prerna.semoss.web.app.MonolithApplication;
import prerna.theme.AdminThemeUtils;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;
import prerna.util.Utility;
import prerna.web.conf.DBLoader;

class ServerConfigurationResourceEndpointsTest {

	private static final String CONTEXT = "/Monolith";

	private final ServerConfigurationResource resource = new ServerConfigurationResource();

	@Path("/things")
	@Produces("application/json")
	public static class ThingResource {

		@GET
		public Response list() {
			return null;
		}

		@POST
		@Path("save/")
		@Consumes("application/x-www-form-urlencoded")
		public Response save() {
			return null;
		}

		@DELETE
		@Path("{id}")
		@Produces("text/plain")
		public Response remove() {
			return null;
		}

		public Response helper() {
			return null;
		}
	}

	@Path("admin")
	public static class AdminThingResource {

		@GET
		@Path("/secrets")
		public Response secrets() {
			return null;
		}
	}

	public static class RootResource {

		@GET
		public Response root() {
			return null;
		}
	}

	@Test
	void listsEveryEndpointOfTheApplicationSortedByPathThenVerb() {
		try (MockedStatic<SecurityAdminUtils> admins = mockStatic(SecurityAdminUtils.class)) {
			JsonObject body = json(resource.listEndpoints(request(null), fixtureApplication()));

			assertEquals(CONTEXT, body.get("contextPath").getAsString());
			assertFalse(body.get("includesAdminResources").getAsBoolean());
			JsonArray endpoints = body.getAsJsonArray("endpoints");
			assertEquals(4, body.get("count").getAsInt());
			assertEquals(List.of("GET /", "GET /things", "POST /things/save", "DELETE /things/{id}"), routes(endpoints));

			JsonObject list = endpoints.get(1).getAsJsonObject();
			assertEquals("ThingResource", list.get("resource").getAsString());
			assertEquals("list", list.get("operation").getAsString());
			assertEquals(List.of("application/json"), strings(list.getAsJsonArray("produces")));
			assertFalse(list.has("consumes"));

			JsonObject save = endpoints.get(2).getAsJsonObject();
			assertEquals(List.of("application/x-www-form-urlencoded"), strings(save.getAsJsonArray("consumes")));
			assertEquals(List.of("text/plain"), strings(endpoints.get(3).getAsJsonObject().getAsJsonArray("produces")),
					"a method's own @Produces wins over its class's");
			assertFalse(endpoints.get(0).getAsJsonObject().has("produces"));
		}
	}

	@Test
	void listsAdminResourcesOnlyToAnAdmin() {
		User admin = mock(User.class);
		User user = mock(User.class);
		try (MockedStatic<SecurityAdminUtils> admins = mockStatic(SecurityAdminUtils.class)) {
			admins.when(() -> SecurityAdminUtils.userIsAdmin(admin)).thenReturn(true);
			admins.when(() -> SecurityAdminUtils.userIsAdmin(user)).thenReturn(null);

			JsonObject forUser = json(resource.listEndpoints(request(user), fixtureApplication()));
			assertFalse(forUser.get("includesAdminResources").getAsBoolean());
			assertFalse(routes(forUser.getAsJsonArray("endpoints")).contains("GET /admin/secrets"));

			JsonObject forAdmin = json(resource.listEndpoints(request(admin), fixtureApplication()));
			assertTrue(forAdmin.get("includesAdminResources").getAsBoolean());
			assertEquals(5, forAdmin.get("count").getAsInt());
			assertTrue(routes(forAdmin.getAsJsonArray("endpoints")).contains("GET /admin/secrets"));
		}
	}

	@Test
	void walksTheResourcesTheApiApplicationRegisters() {
		User admin = mock(User.class);
		Application api = new MonolithApplication();
		try (MockedStatic<SecurityAdminUtils> admins = mockStatic(SecurityAdminUtils.class)) {
			admins.when(() -> SecurityAdminUtils.userIsAdmin(admin)).thenReturn(true);

			JsonArray anonymous = json(resource.listEndpoints(request(null), api)).getAsJsonArray("endpoints");
			JsonArray forAdmin = json(resource.listEndpoints(request(admin), api)).getAsJsonArray("endpoints");

			assertTrue(routes(anonymous).contains("GET /config/endpoints"));
			for (JsonElement endpoint : anonymous) {
				assertFalse(endpoint.getAsJsonObject().get("resource").getAsString().startsWith("Admin"),
						endpoint.toString());
			}
			assertTrue(resources(forAdmin).contains("AdminUserAuthorizationResource"));
			assertTrue(forAdmin.size() > anonymous.size());
			Set<String> registered = new HashSet<>();
			api.getClasses().forEach(c -> registered.add(c.getSimpleName()));
			assertTrue(registered.containsAll(resources(forAdmin)));
		}
	}

	@Test
	void fetchCsrfStartsASession() {
		HttpServletRequest req = request(null);

		Response response = resource.fetchCsrf(req, mock(HttpServletResponse.class));

		assertEquals(200, response.getStatus());
		assertTrue(jsonElement(response).getAsBoolean());
		verify(req).getSession(true);
	}

	@Test
	void anAnonymousCallerGetsTheConfigurationWithoutKeepingASession() {
		HttpSession session = mock(HttpSession.class);
		when(session.isNew()).thenReturn(true);
		when(session.getMaxInactiveInterval()).thenReturn(1800);
		HttpServletRequest req = configRequest(null, session);
		try (ConfigStatics statics = new ConfigStatics()) {
			Response response = resource.getServerConfig(req, mock(HttpServletResponse.class), mock(FilterChain.class));

			assertEquals(200, response.getStatus());
			assertEquals("private", response.getHeaderString("Cache-Control"));
			JsonObject config = json(response);
			assertEquals(30.0, config.get("timeout").getAsDouble());
			assertTrue(config.get("security").getAsBoolean());
			assertFalse(config.get("csrf").getAsBoolean());
			assertEquals(0, config.getAsJsonObject("logins").size());
			assertFalse(config.has("primaryLogin"));
			assertFalse(config.has("connectorAccess"), "which connectors work is only told to a signed in user");
			assertTrue(config.has("systemDate"));
			verify(session).invalidate();
		}
	}

	@Test
	void aSignedInUserKeepsTheirSessionAndSeesTheirLogins() {
		AccessToken token = new AccessToken();
		token.setProvider(AuthProvider.NATIVE);
		token.setId("jdoe");
		token.setName("J Doe");
		User user = new User();
		user.setAccessToken(token);
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		when(session.getAttribute("csrf")).thenReturn(true);
		when(session.getMaxInactiveInterval()).thenReturn(600);
		HttpServletRequest req = configRequest(session, session);
		try (ConfigStatics statics = new ConfigStatics()) {
			JsonObject config = json(
					resource.getServerConfig(req, mock(HttpServletResponse.class), mock(FilterChain.class)));

			assertEquals(10.0, config.get("timeout").getAsDouble());
			assertTrue(config.get("csrf").getAsBoolean());
			assertEquals("NATIVE", config.get("primaryLogin").getAsString());
			assertEquals("J Doe", config.getAsJsonObject("logins").get("NATIVE").getAsString());
			assertTrue(config.getAsJsonObject("connectorAccess").getAsJsonObject("outlook").get("mail").getAsBoolean());
			verify(session, never()).invalidate();
		}
	}

	@Test
	void tellsAnAnonymousBrowserToDropItsStaleSessionCookie() {
		HttpSession session = mock(HttpSession.class);
		HttpServletRequest req = configRequest(null, session);
		when(req.getCookies()).thenReturn(new Cookie[] { new Cookie("SMSSESSIONID", "stale"), new Cookie("other", "x") });
		try (ConfigStatics statics = new ConfigStatics()) {
			Response response = resource.getServerConfig(req, mock(HttpServletResponse.class), mock(FilterChain.class));

			List<String> cookies = new ArrayList<>();
			response.getHeaders().get("Set-Cookie").forEach(c -> cookies.add(c.toString()));
			assertEquals(2, cookies.size(), cookies.toString());
			for (String cookie : cookies) {
				assertTrue(cookie.startsWith("SMSSESSIONID=;"), cookie);
			}
			assertTrue(cookies.get(0).contains("path=" + CONTEXT), cookies.get(0));
			assertTrue(cookies.get(1).contains("path=/"), cookies.get(1));
		}
	}

	@Test
	void readsTheStaticConfigurationOnce() {
		try (ConfigStatics statics = new ConfigStatics()) {
			for (int i = 0; i < 3; i++) {
				HttpSession session = mock(HttpSession.class);
				resource.getServerConfig(configRequest(null, session), mock(HttpServletResponse.class),
						mock(FilterChain.class));
			}
			statics.py.verify(PyUtils::pyEnabled, atMost(1));
		}
	}

	private static Application fixtureApplication() {
		return new Application() {
			@Override
			public Set<Class<?>> getClasses() {
				return Set.of(ThingResource.class, AdminThingResource.class, RootResource.class);
			}
		};
	}

	/**
	 * Every setting the configuration endpoint reads, answered with defaults, and
	 * the connector access a signed in user is told about.
	 */
	private static final class ConfigStatics implements AutoCloseable {

		private final List<MockedStatic<?>> opened = new ArrayList<>();
		private final MockedStatic<PyUtils> py;

		private ConfigStatics() {
			opened.add(mockStatic(Utility.class, invocation -> switch (invocation.getMethod().getName()) {
			case "getCurrentZonedDateTimeUTC" -> ZonedDateTime.now(ZoneOffset.UTC);
			case "getSameSiteCookieValue" -> "Strict";
			default -> RETURNS_DEFAULTS.answer(invocation);
			}));
			py = mockStatic(PyUtils.class);
			opened.add(py);
			opened.add(mockStatic(AbstractSecurityUtils.class));
			MockedStatic<DBLoader> loader = mockStatic(DBLoader.class);
			loader.when(DBLoader::getSessionIdKey).thenReturn("SMSSESSIONID");
			opened.add(loader);
			opened.add(mockStatic(VersionReactor.class));
			MockedStatic<SocialPropertiesUtil> social = mockStatic(SocialPropertiesUtil.class);
			social.when(SocialPropertiesUtil::getInstance).thenReturn(mock(SocialPropertiesUtil.class));
			opened.add(social);
			opened.add(mockStatic(MicrosoftGraphUserLookup.class));
			opened.add(mockStatic(PasswordRequirements.class));
			MockedStatic<ConnectorScopeAccess> connectors = mockStatic(ConnectorScopeAccess.class);
			connectors.when(ConnectorScopeAccess::getConnectorAccess).thenReturn(Map.of("outlook", Map.of("mail", true)));
			opened.add(connectors);
			opened.add(mockStatic(AdminThemeUtils.class));
			opened.add(mockStatic(SecurityEngineUtils.class));
			opened.add(mockStatic(SecurityProjectUtils.class));
			opened.add(mockStatic(SecurityInsightUtils.class));
			opened.add(mockStatic(SecurityUserUtils.class));
		}

		@Override
		public void close() {
			for (int i = opened.size() - 1; i >= 0; i--) {
				opened.get(i).close();
			}
		}
	}

	private static HttpServletRequest configRequest(HttpSession existing, HttpSession created) {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getContextPath()).thenReturn(CONTEXT);
		when(req.getSession(false)).thenReturn(existing);
		when(req.getSession()).thenReturn(created);
		when(req.getSession(true)).thenReturn(created);
		return req;
	}

	private static HttpServletRequest request(User user) {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getContextPath()).thenReturn(CONTEXT);
		if (user != null) {
			HttpSession session = mock(HttpSession.class);
			when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
			when(req.getSession(false)).thenReturn(session);
		}
		return req;
	}

	private static List<String> routes(JsonArray endpoints) {
		List<String> routes = new ArrayList<>();
		for (JsonElement endpoint : endpoints) {
			JsonObject e = endpoint.getAsJsonObject();
			routes.add(e.get("method").getAsString() + " " + e.get("path").getAsString());
		}
		return routes;
	}

	private static Set<String> resources(JsonArray endpoints) {
		Set<String> resources = new TreeSet<>();
		for (JsonElement endpoint : endpoints) {
			resources.add(endpoint.getAsJsonObject().get("resource").getAsString());
		}
		return resources;
	}

	private static List<String> strings(JsonArray array) {
		List<String> values = new ArrayList<>();
		array.forEach(v -> values.add(v.getAsString()));
		return values;
	}

	private static JsonElement jsonElement(Response response) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			((StreamingOutput) response.getEntity()).write(out);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return JsonParser.parseString(out.toString(StandardCharsets.UTF_8));
	}

	private static JsonObject json(Response response) {
		return jsonElement(response).getAsJsonObject();
	}
}
