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
import static org.junit.jupiter.api.Assertions.assertNull;
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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.engine.api.IEngine;
import prerna.notifications.NotificationDbUtils;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.ThreadStore;
import prerna.project.api.IProject;
import prerna.reactor.IReactor;
import prerna.sablecc2.om.NounStore;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.VarStore;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.upload.CatalogImageUploader;
import prerna.util.AssetUtility;
import prerna.util.Constants;
import prerna.util.DefaultImageGeneratorUtil;
import prerna.util.EmailUtility;
import prerna.util.EngineUtility;
import prerna.util.NotificationConstants;
import prerna.util.Utility;

class ProjectResourceTest {

	private static final String CURRENT_SMSS = "PROJECT\tp1\nPROJECT_ALIAS\tPortal\nPROJECT_TYPE\tCODE\n"
			+ "DESCRIPTION\told\n";
	private static final byte[] PNG = new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };

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
	 * The static collaborators of the project endpoints, each mocked for one test,
	 * with the signed in user allowed through the alias lookup unchanged.
	 */
	private static final class Statics implements AutoCloseable {

		private final List<MockedStatic<?>> opened = new ArrayList<>();
		private final Map<String, Object> utilityValues = new HashMap<>();
		private final MockedStatic<SecurityProjectUtils> projects;
		private final MockedStatic<SecurityInsightUtils> insights;
		private final MockedStatic<SecurityAdminUtils> admin;
		private final MockedStatic<AbstractSecurityUtils> stockInsightImages;
		private final MockedStatic<ClusterUtil> cluster;
		private final MockedStatic<NotificationDbUtils> notifications;
		private final MockedStatic<EmailUtility> email;
		private final MockedStatic<EngineUtility> engines;
		private final MockedStatic<AssetUtility> assets;
		private final MockedStatic<DefaultImageGeneratorUtil> stock;
		private final MockedStatic<InsightStore> store;
		private final MockedStatic<Utility> utility;
		private final InsightStore insightStore = mock(InsightStore.class);

		private Statics() {
			try {
				this.projects = open(Mockito.mockStatic(SecurityProjectUtils.class));
				this.insights = open(Mockito.mockStatic(SecurityInsightUtils.class));
				this.admin = open(Mockito.mockStatic(SecurityAdminUtils.class));
				this.stockInsightImages = open(Mockito.mockStatic(AbstractSecurityUtils.class));
				this.cluster = open(Mockito.mockStatic(ClusterUtil.class));
				this.notifications = open(Mockito.mockStatic(NotificationDbUtils.class));
				this.email = open(Mockito.mockStatic(EmailUtility.class));
				this.engines = open(Mockito.mockStatic(EngineUtility.class));
				this.assets = open(Mockito.mockStatic(AssetUtility.class));
				this.stock = open(Mockito.mockStatic(DefaultImageGeneratorUtil.class));
				this.store = open(Mockito.mockStatic(InsightStore.class));
				this.store.when(InsightStore::getInstance).thenReturn(this.insightStore);
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
			Class.forName(EngineUtility.class.getName(), true, ProjectResourceTest.class.getClassLoader());
		}
	}

	@BeforeEach
	@AfterEach
	void resetTheCachedEmbedLogo() throws ReflectiveOperationException {
		Field logo = ProjectResource.class.getDeclaredField("defaultEmbedLogo");
		logo.setAccessible(true);
		logo.set(null, null);
		Field noLogo = ProjectResource.class.getDeclaredField("noLogo");
		noLogo.setAccessible(true);
		noLogo.set(null, false);
		ThreadStore.remove();
	}

	private HttpServletRequest signedInRequest() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpSession session = mock(HttpSession.class);
		when(session.getId()).thenReturn("session-1");
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(this.user);
		when(request.getSession(false)).thenReturn(session);
		when(request.getSession()).thenReturn(session);
		return request;
	}

	private static String body(Response response) throws IOException {
		return body((StreamingOutput) response.getEntity());
	}

	private static String body(StreamingOutput output) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		output.write(out);
		return out.toString(StandardCharsets.UTF_8);
	}

	private static void assertBody(Response response, int status, String fragment) throws IOException {
		assertEquals(status, response.getStatus());
		String body = body(response);
		assertTrue(body.contains(fragment), body);
	}

	private static Request freshRequest() {
		Request request = mock(Request.class);
		when(request.evaluatePreconditions(any(EntityTag.class))).thenReturn(null);
		return request;
	}

	private static IProject project(String name) {
		IProject project = mock(IProject.class);
		when(project.getProjectName()).thenReturn(name);
		return project;
	}

	private IProject projectWithSmss(Path smss) throws IOException {
		Files.writeString(smss, CURRENT_SMSS);
		Properties props = new Properties();
		props.put(Constants.PROJECT, "p1");
		props.put(Constants.PROJECT_ALIAS, "Portal");
		props.put(Constants.PROJECT_TYPE, "CODE");
		props.put("DESCRIPTION", "old");
		IProject project = project("Portal");
		when(project.getSmssFilePath()).thenReturn(smss.toString());
		when(project.getSmssProp()).thenReturn(props);
		return project;
	}

	private HttpServletRequest smssRequest(String smss) {
		HttpServletRequest request = signedInRequest();
		when(request.getParameter("smss")).thenReturn(smss);
		return request;
	}

	private Path projectBase(Statics s) throws IOException {
		Path base = Files.createDirectories(this.tempDir.resolve("Portal__p1"));
		Files.createDirectories(base.resolve("app_root/version/assets"));
		s.utilityValues.put("getProject", project("Portal"));
		s.engines.when(() -> EngineUtility.getSpecificEngineBaseFolder(IEngine.CATALOG_TYPE.PROJECT, "p1", "Portal"))
				.thenReturn(base.toString());
		return base;
	}

	private Insight insightWithFrames(String... frames) {
		Insight insight = mock(Insight.class);
		VarStore varStore = mock(VarStore.class);
		when(varStore.getFrameKeysCopy()).thenReturn(List.of(frames));
		when(insight.getVarStore()).thenReturn(varStore);
		when(insight.getInsightId()).thenReturn("i1");
		when(insight.getUser()).thenReturn(this.user);
		return insight;
	}

	private static void storeSessionInsight(Statics s, Insight insight) {
		when(s.insightStore.get("i1")).thenReturn(insight);
		when(s.insightStore.getInsightIDsForSession("session-1")).thenReturn(Set.of("i1"));
	}

	@Test
	void rejectsAnSmssUpdateForAnUnsafeProjectId() throws IOException {
		try (Statics s = new Statics()) {
			assertBody(new ProjectResource().updateSmssFile(smssRequest(CURRENT_SMSS), ".."), 400,
					"Invalid project id");
			s.projects.verify(() -> SecurityProjectUtils.userIsOwner(any(), anyString()), never());
		}
	}

	@Test
	void rejectsAnSmssUpdateWithoutASignedInUserOrOwnership() throws IOException {
		try (Statics s = new Statics()) {
			assertBody(new ProjectResource().updateSmssFile(mock(HttpServletRequest.class), "p1"), 401,
					"\"error\":\"User session is invalid\"");
			assertBody(new ProjectResource().updateSmssFile(smssRequest(CURRENT_SMSS), "p1"), 401,
					"User must be the owner to perform this function.");
			s.utility.verify(() -> Utility.getProject(anyString()), never());
		}
	}

	@Test
	void rejectsAnAdminSmssUpdateOfAMissingProject() throws IOException {
		try (Statics s = new Statics()) {
			s.admin.when(() -> SecurityAdminUtils.userIsAdmin(this.user)).thenReturn(true);

			assertBody(new ProjectResource().updateSmssFile(smssRequest(CURRENT_SMSS), "p1"), 401,
					"Project p1 does not exist.");
		}
	}

	@Test
	void answers400WhenTheProjectSmssFileIsMissing() throws IOException {
		try (Statics s = new Statics()) {
			s.projects.when(() -> SecurityProjectUtils.userIsOwner(this.user, "p1")).thenReturn(true);
			IProject project = project("Portal");
			when(project.getSmssFilePath()).thenReturn(this.tempDir.resolve("gone.smss").toString());
			s.utilityValues.put("getProject", project);

			assertBody(new ProjectResource().updateSmssFile(smssRequest(CURRENT_SMSS), "p1"), 400,
					"Could not find current project smss file");
		}
	}

	@Test
	void rejectsChangingTheProjectIdNameOrType() throws Exception {
		try (Statics s = new Statics()) {
			s.projects.when(() -> SecurityProjectUtils.userIsOwner(this.user, "p1")).thenReturn(true);
			Path smss = this.tempDir.resolve("p1.smss");
			IProject project = projectWithSmss(smss);
			s.utilityValues.put("getProject", project);

			for (String changed : new String[] { CURRENT_SMSS.replace("PROJECT\tp1", "PROJECT\tp2"),
					CURRENT_SMSS.replace("Portal", "Other"), CURRENT_SMSS.replace("CODE", "BLOCKS") }) {
				assertBody(new ProjectResource().updateSmssFile(smssRequest(changed), "p1"), 400,
						"The project id, project name, and project type cannot be changed");
			}
			assertEquals(CURRENT_SMSS, Files.readString(smss));
			verify(project, never()).close();
		}
	}

	@Test
	void rewritesTheProjectSmssAndNotifies() throws Exception {
		try (Statics s = new Statics()) {
			s.projects.when(() -> SecurityProjectUtils.userIsOwner(this.user, "p1")).thenReturn(true);
			Path smss = this.tempDir.resolve("p1.smss");
			IProject project = projectWithSmss(smss);
			s.utilityValues.put("getProject", project);
			s.utilityValues.put("isNotificationDatabaseEnabled", true);
			String updated = CURRENT_SMSS.replace("old", "new");

			assertBody(new ProjectResource().updateSmssFile(smssRequest(updated), "p1"), 200, "{\"success\":true}");

			assertEquals(updated, Files.readString(smss));
			verify(project).close();
			verify(project).open(smss.toString());
			s.cluster.verify(() -> ClusterUtil.pushProjectSmss("p1"));
			s.notifications.verify(() -> NotificationDbUtils.createNotification(eq(this.user), any(), any(), eq("p1"),
					eq(NotificationConstants.Type.SMSS_UPDATE), eq(NotificationConstants.APP_CATALOG), any(), any(),
					any(), any()));
			s.email.verify(() -> EmailUtility.sendSmssUpdateEmailNotification(this.user, "p1",
					EmailUtility.RESOURCE_TYPE.PROJECT));
		}
	}

	@Test
	void restoresThePreviousProjectSmssWhenTheProjectFailsToOpen() throws Exception {
		try (Statics s = new Statics()) {
			s.projects.when(() -> SecurityProjectUtils.userIsOwner(this.user, "p1")).thenReturn(true);
			Path smss = this.tempDir.resolve("p1.smss");
			IProject project = projectWithSmss(smss);
			s.utilityValues.put("getProject", project);
			int[] opens = new int[1];
			Mockito.doAnswer(i -> {
				if (opens[0]++ == 0) {
					throw new IllegalStateException("bad config");
				}
				return null;
			}).when(project).open(smss.toString());

			assertBody(new ProjectResource().updateSmssFile(smssRequest(CURRENT_SMSS + "X\ty\n"), "p1"), 400,
					"An error occurred initializing the new project details. Detailed message = bad config");
			assertEquals(2, opens[0]);
			assertEquals(CURRENT_SMSS, Files.readString(smss));
			s.cluster.verifyNoInteractions();
		}
	}

	@Test
	void reportsAProjectThatCannotBeRestored() throws Exception {
		try (Statics s = new Statics()) {
			s.projects.when(() -> SecurityProjectUtils.userIsOwner(this.user, "p1")).thenReturn(true);
			Path smss = this.tempDir.resolve("p1.smss");
			IProject project = projectWithSmss(smss);
			s.utilityValues.put("getProject", project);
			doThrow(new IllegalStateException("down")).when(project).open(smss.toString());

			assertBody(new ProjectResource().updateSmssFile(smssRequest(CURRENT_SMSS), "p1"), 400,
					"A fatal error occurred and could not revert the project to an operational state");
		}
	}

	@Test
	void rejectsAReactorCallWithoutASignedInUser() throws IOException {
		HttpServletRequest noUser = mock(HttpServletRequest.class);
		when(noUser.getSession(false)).thenReturn(mock(HttpSession.class));

		assertBody(new ProjectResource().runReactor(mock(HttpServletRequest.class), "p1", "Hello"), 401,
				"User session is invalid");
		assertBody(new ProjectResource().runReactor(noUser, "p1", "Hello"), 401, "User session is invalid");
	}

	@Test
	void rejectsAReactorCallOnAProjectTheUserCannotView() throws IOException {
		try (Statics s = new Statics()) {
			assertBody(new ProjectResource().runReactor(signedInRequest(), "p1", "Hello"), 401,
					"\"error\":\"Project p1 does not exist");
			s.utility.verify(() -> Utility.getProject(anyString()), never());
		}
	}

	@Test
	void answers400ForAnUnknownReactorOrABadBody() throws IOException {
		try (Statics s = new Statics()) {
			s.projects.when(() -> SecurityProjectUtils.userCanViewProject(this.user, "p1")).thenReturn(true);
			s.utilityValues.put("getProject", project("Portal"));
			HttpServletRequest unknown = signedInRequest();
			when(unknown.getReader()).thenReturn(new BufferedReader(new StringReader("{\"name\":\"x\"}")));
			HttpServletRequest badBody = signedInRequest();
			when(badBody.getReader()).thenReturn(new BufferedReader(new StringReader("[1,2]")));

			assertBody(new ProjectResource().runReactor(unknown, "p1", "Missing"), 400, "Could not find custom reactor");
			assertEquals(400, new ProjectResource().runReactor(badBody, "p1", "Hello").getStatus());
		}
	}

	@Test
	void runsTheProjectReactorWithTheBodyAsItsNouns() throws Exception {
		try (Statics s = new Statics()) {
			s.projects.when(() -> SecurityProjectUtils.userCanViewProject(this.user, "p1")).thenReturn(true);
			IReactor reactor = mock(IReactor.class);
			when(reactor.execute()).thenReturn(new NounMetadata("hi", PixelDataType.CONST_STRING));
			IProject project = project("Portal");
			when(project.getReactor("Hello")).thenReturn(reactor);
			s.utilityValues.put("getProject", project);
			HttpServletRequest request = signedInRequest();
			when(request.getReader()).thenReturn(new BufferedReader(new StringReader("{\"name\":[\"Ada\"]}")));

			Response response = new ProjectResource().runReactor(request, "p1", "Hello");

			assertEquals(200, response.getStatus());
			assertTrue(response.getHeaderString("Cache-Control").contains("no-store"));
			ArgumentCaptor<NounStore> nouns = ArgumentCaptor.forClass(NounStore.class);
			verify(reactor).setNounStore(nouns.capture());
			assertEquals("Ada", nouns.getValue().getNoun("name").get(0));
			ArgumentCaptor<Insight> insight = ArgumentCaptor.forClass(Insight.class);
			verify(reactor).setInsight(insight.capture());
			assertSame(this.user, insight.getValue().getUser());
			assertTrue(insight.getValue().getInsightId().startsWith("TempInsight_"));
			assertEquals("session-1", ThreadStore.getSessionId());
			assertTrue(body(response).contains("hi"));
		}
	}

	@Test
	void clearsTheThreadWhenTheProjectReactorFails() throws Exception {
		try (Statics s = new Statics()) {
			s.projects.when(() -> SecurityProjectUtils.userCanViewProject(this.user, "p1")).thenReturn(true);
			IReactor reactor = mock(IReactor.class);
			Object[] userDuringRun = new Object[1];
			when(reactor.execute()).thenAnswer(i -> {
				userDuringRun[0] = ThreadStore.getUser();
				throw new IllegalStateException("reactor failed");
			});
			IProject project = project("Portal");
			when(project.getReactor("Hello")).thenReturn(reactor);
			s.utilityValues.put("getProject", project);
			HttpServletRequest request = signedInRequest();
			when(request.getReader()).thenReturn(new BufferedReader(new StringReader("{}")));

			assertBody(new ProjectResource().runReactor(request, "p1", "Hello"), 400, "reactor failed");
			assertSame(this.user, userDuringRun[0]);
			assertNull(ThreadStore.getUser(), "a failed run leaves nothing on the thread");
		}
	}

	@Test
	void rejectsTheLandingPageForAnUnsafeIdOrWithoutAccess() throws IOException {
		try (Statics s = new Statics()) {
			assertBody(new ProjectResource().getProjectLandingPage(freshRequest(), signedInRequest(), "a/b"), 400,
					"Invalid project id");
			assertBody(new ProjectResource().getProjectLandingPage(freshRequest(), mock(HttpServletRequest.class),
					"p1"), 401, "User session is invalid");
			assertBody(new ProjectResource().getProjectLandingPage(freshRequest(), signedInRequest(), "p1"), 401,
					"Project p1 does not exist or user does not have access");
		}
	}

	@Test
	void checksAccessOnTheExactProjectIdItServes() throws IOException {
		try (Statics s = new Statics()) {
			s.projects.when(() -> SecurityProjectUtils.testUserProjectIdForAlias(this.user, "victim")).thenReturn("mine");
			s.projects.when(() -> SecurityProjectUtils.userCanViewProject(this.user, "mine")).thenReturn(true);

			assertBody(new ProjectResource().getProjectLandingPage(freshRequest(), signedInRequest(), "victim"), 401,
					"Project victim does not exist or user does not have access");
			s.projects.verify(() -> SecurityProjectUtils.testUserProjectIdForAlias(any(), anyString()), never());
		}
	}

	@Test
	void servesTheLandingPageWithAnEntityTag() throws IOException {
		try (Statics s = new Statics()) {
			s.projects.when(() -> SecurityProjectUtils.userCanViewProject(this.user, "p1")).thenReturn(true);
			Path base = projectBase(s);
			Files.writeString(base.resolve("app_root/version/assets/landing.html"), "<h1>Welcome</h1>");

			Response response = new ProjectResource().getProjectLandingPage(freshRequest(), signedInRequest(), "p1");

			assertEquals(200, response.getStatus());
			assertEquals("<h1>Welcome</h1>", response.getEntity());
			assertEquals(Integer.toString("<h1>Welcome</h1>".hashCode()), response.getEntityTag().getValue());
		}
	}

	@Test
	void answersNotModifiedForAnUnchangedLandingPage() throws IOException {
		try (Statics s = new Statics()) {
			s.projects.when(() -> SecurityProjectUtils.userCanViewProject(this.user, "p1")).thenReturn(true);
			Path base = projectBase(s);
			Files.writeString(base.resolve("app_root/version/assets/landing.html"), "<h1>Welcome</h1>");
			Request cached = mock(Request.class);
			when(cached.evaluatePreconditions(new EntityTag(Integer.toString("<h1>Welcome</h1>".hashCode()))))
					.thenReturn(Response.notModified());

			assertEquals(304, new ProjectResource().getProjectLandingPage(cached, signedInRequest(), "p1").getStatus());
		}
	}

	@Test
	void answers404WithoutALandingPage() throws IOException {
		try (Statics s = new Statics()) {
			s.projects.when(() -> SecurityProjectUtils.userCanViewProject(this.user, "p1")).thenReturn(true);
			projectBase(s);

			assertBody(new ProjectResource().getProjectLandingPage(freshRequest(), signedInRequest(), "p1"), 404,
					"No custom landing page found");
		}
	}

	@Test
	void servesAProjectAssetInsideTheAssetsFolder() throws IOException {
		try (Statics s = new Statics()) {
			s.projects.when(() -> SecurityProjectUtils.userCanViewProject(this.user, "p1")).thenReturn(true);
			Path base = projectBase(s);
			Files.writeString(base.resolve("app_root/version/assets/app.js"), "console.log(1);");

			Response response = new ProjectResource().downloadProjectAsset(freshRequest(), signedInRequest(), "p1",
					"app.js");

			assertEquals(200, response.getStatus());
			assertEquals("console.log(1);", response.getEntity());
			assertBody(new ProjectResource().downloadProjectAsset(freshRequest(), signedInRequest(), "p1",
					"missing.js"), 404, "No file found");
		}
	}

	@Test
	void refusesAnAssetPathOutsideTheAssetsFolder() throws IOException {
		try (Statics s = new Statics()) {
			s.projects.when(() -> SecurityProjectUtils.userCanViewProject(this.user, "p1")).thenReturn(true);
			Path base = projectBase(s);
			Files.writeString(base.resolve("app_root/version/secret.txt"), "top secret");

			for (String relPath : new String[] { "../secret.txt", "..\\secret.txt", "/etc/passwd" }) {
				assertBody(new ProjectResource().downloadProjectAsset(freshRequest(), signedInRequest(), "p1", relPath),
						400, "Invalid asset path");
			}
		}
	}

	@Test
	void rejectsAnAssetDownloadWithoutAccess() throws IOException {
		try (Statics s = new Statics()) {
			assertBody(new ProjectResource().downloadProjectAsset(freshRequest(), signedInRequest(), "..", "a.js"), 400,
					"Invalid project id");
			assertBody(new ProjectResource().downloadProjectAsset(freshRequest(), signedInRequest(), "p1", "a.js"), 401,
					"does not have access");
			s.utility.verify(() -> Utility.getProject(anyString()), never());
		}
	}

	@Test
	void answers404WhenNoEmbedLogoIsConfigured() throws IOException {
		try (Statics s = new Statics()) {
			s.projects.when(() -> SecurityProjectUtils.userCanViewProject(this.user, "p1")).thenReturn(true);
			s.utilityValues.put("getDIHelperProperty", "NONE");

			assertBody(new ProjectResource().getEmbedUrl(freshRequest(), signedInRequest(), "p1", null), 404,
					"no defualt logo");
		}
	}

	@Test
	void servesTheConfiguredEmbedLogo() throws IOException {
		try (Statics s = new Statics()) {
			s.projects.when(() -> SecurityProjectUtils.userCanViewProject(this.user, "p1")).thenReturn(true);
			Path embed = Files.createDirectories(this.tempDir.resolve("images/embed"));
			Files.writeString(embed.resolve("logo.svg"), "<svg/>");
			s.utilityValues.put("getDIHelperProperty", "logo.svg");
			s.utilityValues.put("getBaseFolder", this.tempDir.toString());

			Response response = new ProjectResource().getEmbedUrl(freshRequest(), signedInRequest(), "p1", "i1");

			assertEquals(200, response.getStatus());
			assertEquals("<svg/>", response.getEntity());
			assertEquals("image/svg+xml", response.getMediaType().toString());
		}
	}

	@Test
	void rejectsTheEmbedLogoWithoutAccess() throws IOException {
		try (Statics s = new Statics()) {
			assertBody(new ProjectResource().getEmbedUrl(freshRequest(), mock(HttpServletRequest.class), "p1", null),
					401, "User session is invalid");
			assertBody(new ProjectResource().getEmbedUrl(freshRequest(), signedInRequest(), "p1", null), 401,
					"does not have access");
		}
	}

	@Test
	void uploadsTheImageAsAProjectImageOnBothRoutes() {
		ServletContext context = mock(ServletContext.class);
		HttpServletRequest request = mock(HttpServletRequest.class);
		Response uploaded = Response.ok().build();
		try (MockedStatic<CatalogImageUploader> uploader = Mockito.mockStatic(CatalogImageUploader.class)) {
			uploader.when(() -> CatalogImageUploader.upload(context, request, "p1", true)).thenReturn(uploaded);

			assertSame(uploaded, new ProjectResource().uploadImage(context, request, "p1"));
			assertSame(uploaded, new ProjectResource().uploadProjectImage(context, request, "p1"));
		}
	}

	@Test
	void rejectsAProjectImageDownloadForAnUnsafeIdOrWithoutAccess() throws IOException {
		try (Statics s = new Statics()) {
			assertBody(new ProjectResource().downloadProjectImage(freshRequest(), signedInRequest(), ".."), 400,
					"Invalid project id");
			assertBody(new ProjectResource().downloadProjectImage(freshRequest(), mock(HttpServletRequest.class), "p1"),
					401, "User session is invalid");
			assertBody(new ProjectResource().downloadProjectImage(freshRequest(), signedInRequest(), "p1"), 401,
					"does not have access");
			s.stock.verifyNoInteractions();
		}
	}

	@Test
	void servesTheSystemBadgeForASystemProject() throws IOException {
		try (Statics s = new Statics()) {
			s.projects.when(() -> SecurityProjectUtils.projectIsDiscoverable("p1")).thenReturn(true);
			File badge = Files.writeString(this.tempDir.resolve("instance-managed-dark.svg"), "<svg/>").toFile();
			s.stock.when(() -> DefaultImageGeneratorUtil.getSystemProjectImage("p1", "dark")).thenReturn(badge);
			HttpServletRequest request = signedInRequest();
			when(request.getParameter("theme")).thenReturn("dark");

			Response response = new ProjectResource().downloadProjectImage(freshRequest(), request, "p1");

			assertEquals(200, response.getStatus());
			assertEquals(badge, response.getEntity());
			assertEquals("attachment; filename=p1_Image.svg", response.getHeaderString("Content-Disposition"));
			assertTrue(response.getHeaderString(HttpHeaders.CACHE_CONTROL).contains("max-age=300"));
			s.utility.verify(() -> Utility.getProject(anyString()), never());
		}
	}

	@Test
	void servesTheUploadedProjectImageOrTheStockImage() throws IOException {
		try (Statics s = new Statics()) {
			s.projects.when(() -> SecurityProjectUtils.userCanViewProject(this.user, "p1")).thenReturn(true);
			s.utilityValues.put("getProject", project("Portal"));
			Path version = Files.createDirectories(this.tempDir.resolve("version"));
			s.engines.when(() -> EngineUtility.getSpecificEngineVersionFolder(IEngine.CATALOG_TYPE.PROJECT, "p1",
					"Portal")).thenReturn(version.toString());
			File stockImage = Files.writeString(this.tempDir.resolve("stock.svg"), "<svg/>").toFile();
			s.stock.when(() -> DefaultImageGeneratorUtil.getStockImageForPath(anyString(), any()))
					.thenReturn(stockImage);

			Response stock = new ProjectResource().downloadProjectImage(freshRequest(), signedInRequest(), "p1");
			File uploaded = Files.write(version.resolve("image.png"), PNG).toFile();
			Response own = new ProjectResource().downloadProjectImage(freshRequest(), signedInRequest(), "p1");

			assertEquals(stockImage, stock.getEntity());
			assertEquals(uploaded, own.getEntity());
			assertEquals("image/png", own.getMediaType().toString());
		}
	}

	@Test
	void answers400WhenNoProjectImageExists() throws IOException {
		try (Statics s = new Statics()) {
			s.projects.when(() -> SecurityProjectUtils.userCanViewProject(this.user, "p1")).thenReturn(true);
			s.utilityValues.put("getProject", project("Portal"));
			s.engines.when(() -> EngineUtility.getSpecificEngineVersionFolder(any(), anyString(), anyString()))
					.thenReturn(this.tempDir.resolve("nowhere").toString());

			assertBody(new ProjectResource().downloadProjectImage(freshRequest(), signedInRequest(), "p1"), 400,
					"error sending image file");
		}
	}

	@Test
	void rejectsAnInsightImageDownloadForUnsafeIdsOrWithoutAccess() throws IOException {
		try (Statics s = new Statics()) {
			assertBody(new ProjectResource().downloadInsightImage(freshRequest(), signedInRequest(), "p1", "..", null),
					400, "Invalid project or insight id");
			assertBody(new ProjectResource().downloadInsightImage(freshRequest(), signedInRequest(), "a/b", "i1", null),
					400, "Invalid project or insight id");
			assertBody(new ProjectResource().downloadInsightImage(freshRequest(), mock(HttpServletRequest.class), "p1",
					"i1", null), 401, "User session is invalid");
			assertBody(new ProjectResource().downloadInsightImage(freshRequest(), signedInRequest(), "p1", "i1", null),
					401, "Insight does not exist or user does not have access to view");
		}
	}

	@Test
	void servesTheInsightImageForItsParams() throws IOException {
		try (Statics s = new Statics()) {
			s.insights.when(() -> SecurityInsightUtils.userCanViewInsight(this.user, "p1", "i1")).thenReturn(true);
			s.utilityValues.put("getProject", project("Portal"));
			Path version = Files.createDirectories(this.tempDir.resolve("version"));
			s.assets.when(() -> AssetUtility.getProjectVersionFolder("Portal", "p1")).thenReturn(version.toString());
			File plain = Files.write(Files.createDirectories(version.resolve("i1")).resolve("image.png"), PNG)
					.toFile();
			Path paramFolder = Files.createDirectories(version.resolve("i1/params/region-east"));
			File withParams = Files.write(paramFolder.resolve("image.png"), PNG).toFile();

			Response noParams = new ProjectResource().downloadInsightImage(freshRequest(), signedInRequest(), "p1", "i1",
					"undefined");
			Response params = new ProjectResource().downloadInsightImage(freshRequest(), signedInRequest(), "p1", "i1",
					"region-east");

			assertEquals(plain.getCanonicalFile(), ((File) noParams.getEntity()).getCanonicalFile());
			assertEquals(withParams.getCanonicalFile(), ((File) params.getEntity()).getCanonicalFile());
			assertEquals("image/png", params.getMediaType().toString());
			assertEquals("image-v2-" + withParams.lastModified(), params.getEntityTag().getValue());
		}
	}

	@Test
	void fallsBackToTheStockInsightImageOr404() throws IOException {
		try (Statics s = new Statics()) {
			s.insights.when(() -> SecurityInsightUtils.userCanViewInsight(this.user, "p1", "i1")).thenReturn(true);
			s.utilityValues.put("getProject", project("Portal"));
			s.assets.when(() -> AssetUtility.getProjectVersionFolder("Portal", "p1"))
					.thenReturn(Files.createDirectories(this.tempDir.resolve("version")).toString());

			assertBody(new ProjectResource().downloadInsightImage(freshRequest(), signedInRequest(), "p1", "i1", null),
					404, "Error sending image file");

			File stockImage = Files.writeString(this.tempDir.resolve("stock.svg"), "<svg/>").toFile();
			s.stockInsightImages.when(() -> AbstractSecurityUtils.getStockImage("p1", "i1")).thenReturn(stockImage);
			assertEquals(stockImage, new ProjectResource()
					.downloadInsightImage(freshRequest(), signedInRequest(), "p1", "i1", null).getEntity());
		}
	}

	@Test
	void keepsTraversalInInsightImageParamsInsideTheVersionFolder() throws IOException {
		try (Statics s = new Statics()) {
			s.insights.when(() -> SecurityInsightUtils.userCanViewInsight(this.user, "p1", "i1")).thenReturn(true);
			s.utilityValues.put("getProject", project("Portal"));
			Path version = Files.createDirectories(this.tempDir.resolve("version"));
			s.assets.when(() -> AssetUtility.getProjectVersionFolder("Portal", "p1")).thenReturn(version.toString());
			Files.write(this.tempDir.resolve("image.png"), PNG);

			assertBody(new ProjectResource().downloadInsightImage(freshRequest(), signedInRequest(), "p1", "i1",
					"../../.."), 404, "Error sending image file");
			s.stockInsightImages.verify(() -> AbstractSecurityUtils.getStockImage("p1", "i1"));
		}
	}

	@Test
	void answersNoSuchInsightForAnUnknownJdbcInsight() throws IOException {
		try (Statics s = new Statics()) {
			assertEquals("\"No such insight\"",
					body(new ProjectResource().getJDBCJsonOutput(null, "missing", "SELECT 1", signedInRequest(), null)));
			assertEquals("\"No such insight\"",
					body(new ProjectResource().getJDBCCSVOutput(null, "missing", "SELECT 1", signedInRequest(), null)));
		}
	}

	@Test
	void queriesTheOnlyFrameOfAJdbcInsight() throws IOException {
		try (Statics s = new Statics()) {
			Insight insight = insightWithFrames("FRAME_1");
			Map<String, Object> output = new HashMap<>();
			output.put("data", List.of(1));
			output.put("types", List.of("INT"));
			when(insight.query("SELECT * FROM FRAME_1", "FRAME_1")).thenReturn(output);
			storeSessionInsight(s, insight);

			String json = body(
					new ProjectResource().getJDBCJsonOutput(null, "i1", "SELECT * FROM FRAME_1", signedInRequest(), null));

			assertTrue(json.contains("\"INSIGHT_INSTANCE\":\"i1\""), json);
			assertTrue(json.contains("\"data\":[1]"), json);
			assertFalse(json.contains("types"), json);
		}
	}

	@Test
	void picksTheFrameNamedInTheFromClause() throws IOException {
		try (Statics s = new Statics()) {
			Insight insight = insightWithFrames("Sales", "Costs");
			when(insight.query(anyString(), eq("Costs"))).thenReturn(new HashMap<>(Map.of("frame", "costs")));
			when(insight.query(anyString(), eq(null))).thenReturn(new HashMap<>(Map.of("frame", "default")));
			storeSessionInsight(s, insight);

			String named = body(new ProjectResource().getJDBCJsonOutput(null, "i1",
					"select a from costs where b > 1", signedInRequest(), null));
			String unnamed = body(new ProjectResource().getJDBCJsonOutput(null, "i1", "select 1 from other",
					signedInRequest(), null));

			assertTrue(named.contains("\"frame\":\"costs\""), named);
			assertTrue(unnamed.contains("\"frame\":\"default\""), unnamed);
		}
	}

	@Test
	void readsTheJdbcSqlFromTheBodyWhenTheQueryHasNone() throws IOException {
		try (Statics s = new Statics()) {
			Insight insight = insightWithFrames("F");
			storeSessionInsight(s, insight);
			HttpServletRequest request = signedInRequest();
			when(request.getReader()).thenReturn(new BufferedReader(new StringReader("SELECT * FROM F")));

			String json = body(new ProjectResource().getJDBCJsonOutput(null, "i1", null, request, null));

			verify(insight).query("SELECT * FROM F", "F");
			assertEquals("\"Unable to generate output from sql: SELECT * FROM F\"", json);
		}
	}

	@Test
	void streamsTheCsvOfTheNamedFrameFromTheCacheFolder() throws IOException {
		try (Statics s = new Statics()) {
			Path cache = Files.createDirectories(this.tempDir.resolve("cache"));
			Path csv = Files.writeString(cache.resolve("out.csv"), "a\n1\n");
			s.utilityValues.put("getInsightCacheDir", cache.toString());
			Insight insight = insightWithFrames("Sales", "Costs");
			when(insight.queryCSV("SELECT * FROM Sales", "Sales")).thenReturn(csv.toString());
			storeSessionInsight(s, insight);

			String streamed = body(
					new ProjectResource().getJDBCCSVOutput(null, "i1", "SELECT * FROM Sales", signedInRequest(), null));

			assertEquals("a" + System.lineSeparator() + "1" + System.lineSeparator(), streamed);
			assertFalse(Files.exists(csv));
		}
	}

	@Test
	void refusesACsvOutsideTheCacheFolder() throws IOException {
		try (Statics s = new Statics()) {
			Path cache = Files.createDirectories(this.tempDir.resolve("cache"));
			Path outside = Files.writeString(this.tempDir.resolve("passwd"), "root");
			s.utilityValues.put("getInsightCacheDir", cache.toString());
			Insight insight = insightWithFrames("Sales", "Costs");
			when(insight.queryCSV(anyString(), any())).thenReturn(outside.toString());
			storeSessionInsight(s, insight);

			String streamed = body(
					new ProjectResource().getJDBCCSVOutput(null, "i1", "SELECT * FROM Sales", signedInRequest(), null));

			assertEquals("\"Unable to generate output from sql: SELECT * FROM Sales\"", streamed);
			assertTrue(Files.exists(outside));
		}
	}

	@Test
	void stripsTheInsightPrefixFromBinaryJdbcSql() {
		try (Statics s = new Statics()) {
			Insight insight = insightWithFrames("F");
			when(insight.getRdbmsId()).thenReturn("abc-1");
			storeSessionInsight(s, insight);

			new ProjectResource().getJDBCOutput(null, "i1", "SELECT * FROM \"abc_1\".F", null, signedInRequest(),
					null);

			verify(insight).query("SELECT * FROM F", null);
		}
	}
}
