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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.engine.api.IEngine;
import prerna.io.connector.secrets.ISecrets;
import prerna.io.connector.secrets.SecretsFactory;
import prerna.notifications.NotificationDbUtils;
import prerna.upload.CatalogImageUploader;
import prerna.util.Constants;
import prerna.util.DefaultImageGeneratorUtil;
import prerna.util.EmailUtility;
import prerna.util.EngineUtility;
import prerna.util.Utility;

class EngineRouteResourceTest {

	private static final String CURRENT_SMSS = "ENGINE\tdb1\nENGINE_ALIAS\tSales\n"
			+ "ENGINE_TYPE\tprerna.engine.impl.rdbms.RDBMSNativeEngine\nUSERNAME\tsa\nPASSWORD\tsecret\n";

	@TempDir
	Path tempDir;

	private final User user = mock(User.class);

	/**
	 * Mocks {@link Utility} so the named methods return the given values and every
	 * other method runs for real.
	 */
	private static MockedStatic<Utility> utility(Map<String, Object> values) {
		return Mockito.mockStatic(Utility.class, invocation -> {
			String name = invocation.getMethod().getName();
			if (values.containsKey(name)) {
				return values.get(name);
			}
			return invocation.callRealMethod();
		});
	}

	/**
	 * The static collaborators of the smss and image endpoints, each mocked for one
	 * test, with the signed in user allowed through the alias lookup unchanged.
	 */
	private static final class Statics implements AutoCloseable {

		private final List<MockedStatic<?>> opened = new ArrayList<>();
		private final Map<String, Object> utilityValues = new HashMap<>();
		private final MockedStatic<SecurityEngineUtils> security;
		private final MockedStatic<SecurityAdminUtils> admin;
		private final MockedStatic<ClusterUtil> cluster;
		private final MockedStatic<SecretsFactory> secrets;
		private final MockedStatic<NotificationDbUtils> notifications;
		private final MockedStatic<EmailUtility> email;
		private final MockedStatic<EngineUtility> engines;
		private final MockedStatic<DefaultImageGeneratorUtil> stock;
		private final MockedStatic<Utility> utility;

		private Statics() {
			try {
				this.security = open(Mockito.mockStatic(SecurityEngineUtils.class));
				this.admin = open(Mockito.mockStatic(SecurityAdminUtils.class));
				this.cluster = open(Mockito.mockStatic(ClusterUtil.class));
				this.secrets = open(Mockito.mockStatic(SecretsFactory.class));
				this.notifications = open(Mockito.mockStatic(NotificationDbUtils.class));
				this.email = open(Mockito.mockStatic(EmailUtility.class));
				this.engines = open(Mockito.mockStatic(EngineUtility.class));
				this.stock = open(Mockito.mockStatic(DefaultImageGeneratorUtil.class));
				this.utility = open(utility(this.utilityValues));
			} catch (RuntimeException e) {
				close();
				throw e;
			}
		}

		private <T> MockedStatic<T> open(MockedStatic<T> mock) {
			this.opened.add(mock);
			return mock;
		}

		@Override
		public void close() {
			for (int i = this.opened.size() - 1; i >= 0; i--) {
				this.opened.get(i).close();
			}
			this.opened.clear();
		}
	}

	@BeforeAll
	static void initializeEngineUtilityWithABaseFolder(@TempDir Path base) throws ClassNotFoundException {
		try (MockedStatic<Utility> u = utility(Map.of("getBaseFolder", base.toString()))) {
			Class.forName(EngineUtility.class.getName(), true, EngineRouteResourceTest.class.getClassLoader());
		}
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

	private static void assertBody(Response response, int status, String fragment) throws IOException {
		assertEquals(status, response.getStatus());
		String body = body(response);
		assertTrue(body.contains(fragment), body);
	}

	private IEngine engineWithSmss(Path smss) throws IOException {
		Files.writeString(smss, CURRENT_SMSS);
		Properties props = new Properties();
		props.put(Constants.ENGINE, "db1");
		props.put(Constants.ENGINE_ALIAS, "Sales");
		props.put(Constants.ENGINE_TYPE, "prerna.engine.impl.rdbms.RDBMSNativeEngine");
		props.put("USERNAME", "sa");
		props.put("PASSWORD", "secret");
		IEngine engine = mock(IEngine.class);
		when(engine.getSmssFilePath()).thenReturn(smss.toString());
		when(engine.getSmssProp()).thenReturn(props);
		when(engine.getCatalogType()).thenReturn(IEngine.CATALOG_TYPE.DATABASE);
		when(engine.getEngineId()).thenReturn("db1");
		when(engine.getEngineName()).thenReturn("Sales");
		return engine;
	}

	private HttpServletRequest smssRequest(String smss) {
		HttpServletRequest request = signedInRequest();
		when(request.getParameter("smss")).thenReturn(smss);
		return request;
	}

	private static Request freshRequest() {
		Request request = mock(Request.class);
		when(request.evaluatePreconditions(any(EntityTag.class))).thenReturn(null);
		return request;
	}

	@Test
	void appendsOnlyPresentValuesAsJson() {
		Map<String, Object> body = new HashMap<>();
		body.put("limit", 5L);
		body.put("empty", "");
		body.put("missing", null);
		body.put("map", Map.of("k", "v<w>"));
		body.put("flag", false);
		StringBuilder pixel = new StringBuilder("X(a=1");

		for (String key : new String[] { "limit", "empty", "missing", "absent", "map", "flag" }) {
			EngineRouteResource.appendIfPresent(pixel, body, key);
		}

		assertEquals("X(a=1, limit=5, map={\"k\":\"v<w>\"}, flag=false", pixel.toString());
	}

	@Test
	void buildsTheErrorEnvelope() throws IOException {
		Response response = EngineRouteResource.error("nope", 418);

		assertEquals(418, response.getStatus());
		assertEquals("{\"" + Constants.ERROR_MESSAGE + "\":\"nope\"}", body(response));
	}

	@Test
	void rejectsTheTypeLookupWithoutASignedInUser() throws IOException {
		assertBody(new EngineRouteResource().getEngineType(mock(HttpServletRequest.class), "db1"), 401,
				"User session is invalid");
	}

	@Test
	void rejectsTheTypeLookupOfAHiddenEngine() throws IOException {
		try (Statics s = new Statics()) {
			Response response = new EngineRouteResource().getEngineType(signedInRequest(), "db1");

			assertBody(response, 401, "Engine db1 does not exist or user does not have access");
			s.security.verify(() -> SecurityEngineUtils.getEngineTypeAndSubtype(any()), never());
		}
	}

	@Test
	void checksAccessOnTheExactEngineIdItServes() throws IOException {
		try (Statics s = new Statics();
				MockedStatic<SecurityQueryUtils> query = Mockito.mockStatic(SecurityQueryUtils.class)) {
			query.when(() -> SecurityQueryUtils.testUserEngineIdForAlias(any(), eq("victim"))).thenReturn("mine");
			s.security.when(() -> SecurityEngineUtils.userCanViewEngine(any(), eq("mine"))).thenReturn(true);

			Response response = new EngineRouteResource().getEngineType(signedInRequest(), "victim");

			assertBody(response, 401, "Engine victim does not exist or user does not have access");
			query.verify(() -> SecurityQueryUtils.testUserEngineIdForAlias(any(), anyString()), never());
			s.security.verify(() -> SecurityEngineUtils.getEngineTypeAndSubtype(any()), never());
		}
	}

	@Test
	void returnsTheTypeOfAViewableOrDiscoverableEngine() throws IOException {
		try (Statics s = new Statics()) {
			s.security.when(() -> SecurityEngineUtils.userCanViewEngine(this.user, "db1")).thenReturn(true);
			s.security.when(() -> SecurityEngineUtils.engineIsDiscoverable("m1")).thenReturn(true);
			s.security.when(() -> SecurityEngineUtils.getEngineTypeAndSubtype("db1"))
					.thenReturn(new Object[] { IEngine.CATALOG_TYPE.DATABASE, "H2_DB" });
			s.security.when(() -> SecurityEngineUtils.getEngineTypeAndSubtype("m1"))
					.thenReturn(new Object[] { IEngine.CATALOG_TYPE.MODEL, null });

			String database = body(new EngineRouteResource().getEngineType(signedInRequest(), "db1"));
			String model = body(new EngineRouteResource().getEngineType(signedInRequest(), "m1"));

			assertTrue(database.contains("\"engineType\":\"DATABASE\"") && database.contains("\"engineId\":\"db1\""),
					database);
			assertTrue(model.contains("\"engineType\":\"MODEL\""), model);
		}
	}

	@Test
	void answers400ForAnEngineWithoutAType() throws IOException {
		try (Statics s = new Statics()) {
			s.security.when(() -> SecurityEngineUtils.engineIsDiscoverable("db1")).thenReturn(true);
			s.security.when(() -> SecurityEngineUtils.getEngineTypeAndSubtype("db1"))
					.thenThrow(new IllegalArgumentException("no row"));

			assertBody(new EngineRouteResource().getEngineType(signedInRequest(), "db1"), 400,
					"Unknown engine with id db1");
		}
	}

	@Test
	void rejectsAnSmssUpdateForAnUnsafeEngineId() throws IOException {
		try (Statics s = new Statics()) {
			for (String id : new String[] { "..", "." }) {
				assertBody(new EngineRouteResource().updateSmssFile(smssRequest(CURRENT_SMSS), id), 400,
						"Invalid engine id");
			}
			s.security.verifyNoInteractions();
		}
	}

	@Test
	void rejectsAnSmssUpdateWithoutASignedInUser() throws IOException {
		Response response = new EngineRouteResource().updateSmssFile(mock(HttpServletRequest.class), "db1");

		assertBody(response, 401, "\"error\":\"User session is invalid\"");
	}

	@Test
	void rejectsAnSmssUpdateFromANonOwner() throws IOException {
		try (Statics s = new Statics()) {
			Response response = new EngineRouteResource().updateSmssFile(smssRequest(CURRENT_SMSS), "db1");

			assertBody(response, 401, "User must be the owner to perform this function.");
			s.utility.verify(() -> Utility.getEngine(anyString()), never());
		}
	}

	@Test
	void rejectsAnAdminSmssUpdateOfAMissingEngine() throws IOException {
		try (Statics s = new Statics()) {
			s.admin.when(() -> SecurityAdminUtils.userIsAdmin(this.user)).thenReturn(true);

			assertBody(new EngineRouteResource().updateSmssFile(smssRequest(CURRENT_SMSS), "db1"), 401,
					"Engine db1 does not exist.");
			s.security.verify(() -> SecurityEngineUtils.userIsOwner(any(), anyString()), never());
		}
	}

	@Test
	void answers400WhenTheSmssFileIsMissing() throws IOException {
		try (Statics s = new Statics()) {
			s.security.when(() -> SecurityEngineUtils.userIsOwner(this.user, "db1")).thenReturn(true);
			IEngine engine = mock(IEngine.class);
			when(engine.getSmssFilePath()).thenReturn(this.tempDir.resolve("gone.smss").toString());
			s.utilityValues.put("getEngine", engine);

			assertBody(new EngineRouteResource().updateSmssFile(smssRequest(CURRENT_SMSS), "db1"), 400,
					"Could not find current engie smss file");
		}
	}

	@Test
	void rejectsChangingTheEngineIdNameOrType() throws Exception {
		try (Statics s = new Statics()) {
			s.security.when(() -> SecurityEngineUtils.userIsOwner(this.user, "db1")).thenReturn(true);
			Path smss = this.tempDir.resolve("db1.smss");
			IEngine engine = engineWithSmss(smss);
			s.utilityValues.put("getEngine", engine);

			for (String changed : new String[] { CURRENT_SMSS.replace("ENGINE\tdb1", "ENGINE\tdb2"),
					CURRENT_SMSS.replace("Sales", "Marketing"),
					CURRENT_SMSS.replace("RDBMSNativeEngine", "H2EmbeddedServerEngine") }) {
				assertBody(new EngineRouteResource().updateSmssFile(smssRequest(changed), "db1"), 400,
						"The engine id, engine name, and engine type cannot be changed");
			}
			assertEquals(CURRENT_SMSS, Files.readString(smss));
			verify(engine, never()).close();
		}
	}

	@Test
	void rewritesTheSmssAndReopensTheEngine() throws Exception {
		try (Statics s = new Statics()) {
			s.security.when(() -> SecurityEngineUtils.userIsOwner(this.user, "db1")).thenReturn(true);
			Path smss = this.tempDir.resolve("db1.smss");
			IEngine engine = engineWithSmss(smss);
			s.utilityValues.put("getEngine", engine);
			s.utilityValues.put("isNotificationDatabaseEnabled", false);
			String updated = CURRENT_SMSS.replace("USERNAME\tsa", "USERNAME\tadmin").replace("secret",
					Constants.SENSITIVE_INFO_MASK);

			Response response = new EngineRouteResource().updateSmssFile(smssRequest(updated), "db1");

			assertBody(response, 200, "{\"success\":true}");
			assertEquals(CURRENT_SMSS.replace("USERNAME\tsa", "USERNAME\tadmin"), Files.readString(smss));
			verify(engine).close();
			verify(engine).open(smss.toString());
			s.cluster.verify(() -> ClusterUtil.pushEngineSmss("db1", IEngine.CATALOG_TYPE.DATABASE));
			s.notifications.verifyNoInteractions();
		}
	}

	@Test
	void notifiesTheUserOfAnSmssUpdate() throws Exception {
		try (Statics s = new Statics()) {
			s.admin.when(() -> SecurityAdminUtils.userIsAdmin(this.user)).thenReturn(true);
			s.security.when(() -> SecurityEngineUtils.engineExists("db1")).thenReturn(true);
			s.security.when(() -> SecurityEngineUtils.getEngineType("db1")).thenReturn(IEngine.CATALOG_TYPE.DATABASE);
			IEngine engine = engineWithSmss(this.tempDir.resolve("db1.smss"));
			s.utilityValues.put("getEngine", engine);
			s.utilityValues.put("isNotificationDatabaseEnabled", true);

			assertEquals(200, new EngineRouteResource().updateSmssFile(smssRequest(CURRENT_SMSS), "db1").getStatus());

			s.notifications.verify(() -> NotificationDbUtils.createNotification(eq(this.user), any(), any(),
					eq("db1"), any(), eq("database"), any(), any(), any(), any()));
			s.email.verify(() -> EmailUtility.sendSmssUpdateEmailNotification(this.user, "db1",
					EmailUtility.RESOURCE_TYPE.ENGINE));
		}
	}

	@Test
	void restoresThePreviousSmssWhenTheEngineFailsToOpen() throws Exception {
		try (Statics s = new Statics()) {
			s.security.when(() -> SecurityEngineUtils.userIsOwner(this.user, "db1")).thenReturn(true);
			Path smss = this.tempDir.resolve("db1.smss");
			IEngine engine = engineWithSmss(smss);
			s.utilityValues.put("getEngine", engine);
			String[] contentAtFirstOpen = new String[1];
			int[] opens = new int[1];
			Mockito.doAnswer(i -> {
				if (opens[0]++ == 0) {
					contentAtFirstOpen[0] = Files.readString(smss);
					throw new IllegalStateException("bad url");
				}
				return null;
			}).when(engine).open(smss.toString());
			String updated = CURRENT_SMSS + "CONNECTION_URL\tjdbc:broken\n";

			Response response = new EngineRouteResource().updateSmssFile(smssRequest(updated), "db1");

			assertBody(response, 400, "An error occurred initializing the new engine details. Detailed message = bad url");
			assertEquals(updated, contentAtFirstOpen[0]);
			assertEquals(2, opens[0]);
			assertEquals(CURRENT_SMSS, Files.readString(smss));
			s.cluster.verifyNoInteractions();
		}
	}

	@Test
	void reportsAnEngineThatCannotBeRestored() throws Exception {
		try (Statics s = new Statics()) {
			s.security.when(() -> SecurityEngineUtils.userIsOwner(this.user, "db1")).thenReturn(true);
			Path smss = this.tempDir.resolve("db1.smss");
			IEngine engine = engineWithSmss(smss);
			s.utilityValues.put("getEngine", engine);
			doThrow(new IllegalStateException("down")).when(engine).open(smss.toString());

			assertBody(new EngineRouteResource().updateSmssFile(smssRequest(CURRENT_SMSS), "db1"), 400,
					"A fatal error occurred and could not revert the engine to an operational state");
			assertEquals(CURRENT_SMSS, Files.readString(smss));
		}
	}

	@Test
	void writesUpdatedValuesToTheSecretStoreInsteadOfTheFile() throws Exception {
		try (Statics s = new Statics()) {
			s.security.when(() -> SecurityEngineUtils.userIsOwner(this.user, "db1")).thenReturn(true);
			Path smss = this.tempDir.resolve("db1.smss");
			IEngine engine = engineWithSmss(smss);
			s.utilityValues.put("getEngine", engine);
			ISecrets store = mock(ISecrets.class);
			when(store.getEngineSecrets(IEngine.CATALOG_TYPE.DATABASE, "db1", "Sales"))
					.thenReturn(Map.of("PASSWORD", "vaulted"));
			s.secrets.when(SecretsFactory::getSecretConnector).thenReturn(store);
			String updated = CURRENT_SMSS.replace("secret", Constants.SENSITIVE_INFO_MASK);

			Response response = new EngineRouteResource().updateSmssFile(smssRequest(updated), "db1");

			assertBody(response, 200, "{\"success\":true}");
			@SuppressWarnings("unchecked")
			ArgumentCaptor<Map<String, Object>> written = ArgumentCaptor.forClass(Map.class);
			verify(store).writeEngineSecrets(eq(IEngine.CATALOG_TYPE.DATABASE), eq("db1"), eq("Sales"),
					written.capture());
			assertEquals("vaulted", written.getValue().get("PASSWORD"));
			assertEquals("sa", written.getValue().get("USERNAME"));
			assertEquals(CURRENT_SMSS, Files.readString(smss));
			verify(engine).close();
			verify(engine).open(smss.toString());
			s.cluster.verify(() -> ClusterUtil.publishEngineClose("db1"));
			s.cluster.verify(() -> ClusterUtil.pushEngineSmss(anyString(), any()), never());
		}
	}

	@Test
	void answers400WhenTheSecretStoreRejectsTheUpdate() throws Exception {
		try (Statics s = new Statics()) {
			s.security.when(() -> SecurityEngineUtils.userIsOwner(this.user, "db1")).thenReturn(true);
			IEngine engine = engineWithSmss(this.tempDir.resolve("db1.smss"));
			s.utilityValues.put("getEngine", engine);
			ISecrets store = mock(ISecrets.class);
			when(store.writeEngineSecrets(any(), any(), any(), any())).thenThrow(new IllegalStateException("sealed"));
			s.secrets.when(SecretsFactory::getSecretConnector).thenReturn(store);

			assertBody(new EngineRouteResource().updateSmssFile(smssRequest(CURRENT_SMSS), "db1"), 400,
					"Detailed message = sealed");
			verify(engine, never()).close();
		}
	}

	@Test
	void uploadsTheImageAsAnEngineImage() {
		ServletContext context = mock(ServletContext.class);
		HttpServletRequest request = mock(HttpServletRequest.class);
		Response uploaded = Response.ok().build();
		try (MockedStatic<CatalogImageUploader> uploader = Mockito.mockStatic(CatalogImageUploader.class)) {
			uploader.when(() -> CatalogImageUploader.upload(context, request, "db1", false)).thenReturn(uploaded);

			assertSame(uploaded, new EngineRouteResource().uploadImage(context, request, "db1"));
		}
	}

	@Test
	void rejectsAnImageDownloadForAnUnsafeOrUnknownEngine() throws IOException {
		try (Statics s = new Statics()) {
			s.security.when(() -> SecurityEngineUtils.getEngineTypeAndSubtype("db1"))
					.thenThrow(new IllegalArgumentException("no row"));

			assertBody(new EngineRouteResource().imageDownload(freshRequest(), signedInRequest(), ".."), 400,
					"Invalid engine id");
			assertBody(new EngineRouteResource().imageDownload(freshRequest(), mock(HttpServletRequest.class), "db1"),
					401, "User session is invalid");
			assertBody(new EngineRouteResource().imageDownload(freshRequest(), signedInRequest(), "db1"), 400,
					"Unknown engine with id db1");
		}
	}

	@Test
	void rejectsAnImageDownloadOfAHiddenEngine() throws IOException {
		try (Statics s = new Statics()) {
			s.security.when(() -> SecurityEngineUtils.getEngineTypeAndSubtype("db1"))
					.thenReturn(new Object[] { IEngine.CATALOG_TYPE.DATABASE, null });

			assertBody(new EngineRouteResource().imageDownload(freshRequest(), signedInRequest(), "db1"), 401,
					"Engine db1 does not exist or user does not have access");
			s.engines.verifyNoInteractions();
		}
	}

	@Test
	void downloadsTheEnginesOwnImageWithBrowserCaching() throws Exception {
		try (Statics s = new Statics()) {
			s.security.when(() -> SecurityEngineUtils.getEngineTypeAndSubtype("db1"))
					.thenReturn(new Object[] { IEngine.CATALOG_TYPE.DATABASE, null });
			s.security.when(() -> SecurityEngineUtils.userCanViewEngine(this.user, "db1")).thenReturn(true);
			s.security.when(() -> SecurityEngineUtils.getEngineAliasForId("db1")).thenReturn("Sales");
			Path version = Files.createDirectories(this.tempDir.resolve("version"));
			File image = Files.write(version.resolve("image.png"), new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D,
					0x0A, 0x1A, 0x0A }).toFile();
			s.engines.when(() -> EngineUtility.getSpecificEngineVersionFolder(IEngine.CATALOG_TYPE.DATABASE,
					"Sales__db1")).thenReturn(version.toString());

			Response response = new EngineRouteResource().imageDownload(freshRequest(), signedInRequest(), "db1");

			assertEquals(200, response.getStatus());
			assertEquals(image, response.getEntity());
			assertEquals("attachment; filename=db1_Image.png", response.getHeaderString("Content-Disposition"));
			assertTrue(response.getEntityTag().getValue().startsWith("image-v2-"));
			assertTrue(response.getHeaderString(HttpHeaders.CACHE_CONTROL).contains("private"));
			assertEquals("image/png", response.getMediaType().toString());
			s.stock.verifyNoInteractions();
		}
	}

	@Test
	void fallsBackToTheStockImageForTheTheme() throws Exception {
		try (Statics s = new Statics()) {
			s.security.when(() -> SecurityEngineUtils.getEngineTypeAndSubtype("m1"))
					.thenReturn(new Object[] { IEngine.CATALOG_TYPE.MODEL, null });
			s.security.when(() -> SecurityEngineUtils.engineIsDiscoverable("m1")).thenReturn(true);
			Path version = Files.createDirectories(this.tempDir.resolve("empty"));
			File stockImage = Files.writeString(this.tempDir.resolve("stock.svg"), "<svg/>").toFile();
			s.engines.when(() -> EngineUtility.getSpecificEngineVersionFolder(any(), anyString()))
					.thenReturn(version.toString());
			s.stock.when(() -> DefaultImageGeneratorUtil.getStockImageForPath(version + "/image.png", "dark"))
					.thenReturn(stockImage);
			HttpServletRequest request = signedInRequest();
			when(request.getParameter("theme")).thenReturn("dark");

			Response response = new EngineRouteResource().imageDownload(freshRequest(), request, "m1");

			assertEquals(200, response.getStatus());
			assertEquals(stockImage, response.getEntity());
			assertEquals("attachment; filename=m1_Image.svg", response.getHeaderString("Content-Disposition"));
		}
	}

	@Test
	void answers400WhenNoImageExists() throws Exception {
		try (Statics s = new Statics()) {
			s.security.when(() -> SecurityEngineUtils.getEngineTypeAndSubtype("db1"))
					.thenReturn(new Object[] { IEngine.CATALOG_TYPE.DATABASE, null });
			s.security.when(() -> SecurityEngineUtils.userCanViewEngine(this.user, "db1")).thenReturn(true);
			s.engines.when(() -> EngineUtility.getSpecificEngineVersionFolder(any(), anyString()))
					.thenReturn(this.tempDir.resolve("nowhere").toString());

			assertBody(new EngineRouteResource().imageDownload(freshRequest(), signedInRequest(), "db1"), 400,
					"Error sending image file");
		}
	}
}
