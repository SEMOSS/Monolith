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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.User;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.engine.api.IDatabaseEngine;
import prerna.engine.api.IEngine;
import prerna.util.Constants;
import prerna.util.Utility;

class DatabaseEngineResourceTest {

	private final User user = mock(User.class);

	/**
	 * Records the pixel each endpoint hands to {@link ResourceUtility#runPixel}
	 * while the rest of {@link ResourceUtility} runs for real.
	 */
	private static final class PixelCapture implements AutoCloseable {

		private final List<String> pixels = new ArrayList<>();
		private final MockedStatic<ResourceUtility> mock;

		private PixelCapture() {
			this.mock = Mockito.mockStatic(ResourceUtility.class, invocation -> {
				if (invocation.getMethod().getName().equals("runPixel")) {
					this.pixels.add(invocation.getArgument(1));
					return Response.ok().build();
				}
				return invocation.callRealMethod();
			});
		}

		@Override
		public void close() {
			this.mock.close();
		}
	}

	private static HttpServletRequest jsonRequest(String json) throws IOException {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getContentType()).thenReturn("application/json");
		when(request.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
		return request;
	}

	private HttpServletRequest signedInRequest() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(this.user);
		when(request.getSession(false)).thenReturn(session);
		return request;
	}

	private static String body(Response response) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		((StreamingOutput) response.getEntity()).write(out);
		return out.toString(StandardCharsets.UTF_8);
	}

	private static void assertError(Response response, int status, String message) throws IOException {
		assertEquals(status, response.getStatus());
		String body = body(response);
		assertTrue(body.contains("\"" + Constants.ERROR_MESSAGE + "\":\"" + message), body);
	}

	private static IDatabaseEngine database(IDatabaseEngine.DATABASE_TYPE type) {
		IDatabaseEngine engine = mock(IDatabaseEngine.class);
		when(engine.getDatabaseType()).thenReturn(type);
		return engine;
	}

	@Test
	void reloadsTheDatabase() {
		try (PixelCapture capture = new PixelCapture()) {
			new DatabaseEngineResource().reload(mock(HttpServletRequest.class), "db1");

			assertEquals(List.of("ReloadDatabase(database=\"db1\")"), capture.pixels);
		}
	}

	@Test
	void runsARawQueryThroughTheDatabase() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			Response response = new DatabaseEngineResource()
					.query(jsonRequest("{\"query\":\"SELECT * FROM T WHERE NAME = 'a\\\"b'\"}"), "db1");

			assertEquals(200, response.getStatus());
			assertEquals(List.of("Database(database=\"db1\") | Query(query=\"SELECT * FROM T WHERE NAME = 'a\\\"b'\")"),
					capture.pixels);
		}
	}

	@Test
	void requiresAQuery() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			DatabaseEngineResource resource = new DatabaseEngineResource();

			assertError(resource.query(jsonRequest("{}"), "db1"), 400, "Must pass in 'query'");
			assertError(resource.query(jsonRequest("{\"query\":\" \"}"), "db1"), 400, "Must pass in 'query'");
			assertError(resource.query(jsonRequest("{x"), "db1"), 400, "Invalid request body");
			assertTrue(capture.pixels.isEmpty());
		}
	}

	@Test
	void rejectsTheTypeLookupWithoutASignedInUser() throws IOException {
		HttpServletRequest noUser = mock(HttpServletRequest.class);
		when(noUser.getSession(false)).thenReturn(mock(HttpSession.class));

		assertError(new DatabaseEngineResource().getDatabaseType(mock(HttpServletRequest.class), "db1"), 401,
				"User session is invalid");
		assertError(new DatabaseEngineResource().getDatabaseType(noUser, "db1"), 401, "User session is invalid");
	}

	@Test
	void rejectsTheTypeLookupOfADatabaseTheUserCannotSee() throws IOException {
		try (MockedStatic<SecurityEngineUtils> security = Mockito.mockStatic(SecurityEngineUtils.class);
				MockedStatic<Utility> utility = Mockito.mockStatic(Utility.class)) {
			Response response = new DatabaseEngineResource().getDatabaseType(signedInRequest(), "db1");

			assertError(response, 401, "Database db1 does not exist or user does not have access");
			utility.verify(() -> Utility.getDatabase(any()), never());
		}
	}

	@Test
	void returnsTheDatabaseTypeAndSubtype() throws IOException {
		try (MockedStatic<SecurityEngineUtils> security = Mockito.mockStatic(SecurityEngineUtils.class);
				MockedStatic<Utility> utility = Mockito.mockStatic(Utility.class)) {
			security.when(() -> SecurityEngineUtils.userCanViewEngine(this.user, "db1")).thenReturn(true);
			security.when(() -> SecurityEngineUtils.getEngineTypeAndSubtype("db1"))
					.thenReturn(new Object[] { IEngine.CATALOG_TYPE.DATABASE, "POSTGRES" });
			IDatabaseEngine engine = database(IDatabaseEngine.DATABASE_TYPE.RDBMS);
			utility.when(() -> Utility.getDatabase("db1")).thenReturn(engine);

			Response response = new DatabaseEngineResource().getDatabaseType(signedInRequest(), "db1");

			assertEquals(200, response.getStatus());
			String body = body(response);
			assertTrue(body.contains("\"databaseType\":\"RDBMS\""), body);
			assertTrue(body.contains("\"subtype\":\"POSTGRES\""), body);
			assertTrue(body.contains("\"engineId\":\"db1\""), body);
		}
	}

	@Test
	void answersADiscoverableDatabaseWithoutASubtype() throws IOException {
		try (MockedStatic<SecurityEngineUtils> security = Mockito.mockStatic(SecurityEngineUtils.class);
				MockedStatic<Utility> utility = Mockito.mockStatic(Utility.class)) {
			security.when(() -> SecurityEngineUtils.engineIsDiscoverable("db1")).thenReturn(true);
			security.when(() -> SecurityEngineUtils.getEngineTypeAndSubtype("db1"))
					.thenThrow(new IllegalArgumentException("no row"));
			IDatabaseEngine engine = database(IDatabaseEngine.DATABASE_TYPE.JENA);
			utility.when(() -> Utility.getDatabase("db1")).thenReturn(engine);

			Response response = new DatabaseEngineResource().getDatabaseType(signedInRequest(), "db1");

			assertEquals(200, response.getStatus());
			String body = body(response);
			assertTrue(body.contains("\"databaseType\":\"JENA\""), body);
			assertFalse(body.contains("\"subtype\":\""), body);
		}
	}

	@Test
	void answers400WhenTheDatabaseCannotLoad() throws IOException {
		try (MockedStatic<SecurityEngineUtils> security = Mockito.mockStatic(SecurityEngineUtils.class);
				MockedStatic<Utility> utility = Mockito.mockStatic(Utility.class)) {
			security.when(() -> SecurityEngineUtils.userCanViewEngine(any(), any())).thenReturn(true);
			utility.when(() -> Utility.getDatabase("broken")).thenThrow(new IllegalStateException("smss missing"));

			assertError(new DatabaseEngineResource().getDatabaseType(signedInRequest(), "missing"), 400,
					"Could not load database with id missing");
			assertError(new DatabaseEngineResource().getDatabaseType(signedInRequest(), "broken"), 400,
					"Could not load database with id broken");
		}
	}
}
