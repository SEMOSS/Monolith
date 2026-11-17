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
package prerna.upload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.engine.api.IEngine;
import prerna.io.connector.antivirus.VirusScannerUtils;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.project.api.IProject;
import prerna.util.AssetUtility;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.EngineUtility;
import prerna.util.Utility;
import prerna.util.git.GitRepoUtils;

class FileUploaderTest {

	private static final String BOUNDARY = "FileUploaderTestBoundary";
	private static boolean setBaseFolder;

	@TempDir
	Path temp;

	private final FileUploader uploader = new FileUploader();
	private String insightId;
	private Insight insight;
	private User user;
	private Path insightFolder;

	@BeforeAll
	static void defineBaseFolder(@TempDir Path base) {
		if (DIHelper.getInstance().getProperty(Constants.BASE_FOLDER) == null) {
			DIHelper.getInstance().getCoreProp().setProperty(Constants.BASE_FOLDER, base.toString());
			setBaseFolder = true;
		}
	}

	@AfterAll
	static void clearBaseFolder() {
		if (setBaseFolder) {
			DIHelper.getInstance().getCoreProp().remove(Constants.BASE_FOLDER);
		}
	}

	@BeforeEach
	void openInsight() {
		insightId = "upload-" + UUID.randomUUID();
		insightFolder = temp.resolve("insight");
		user = person();
		insight = mock(Insight.class);
		when(insight.getInsightId()).thenReturn(insightId);
		when(insight.getUser()).thenReturn(user);
		when(insight.getInsightFolder()).thenReturn(insightFolder.toString());
		InsightStore.getInstance().put(insight);
	}

	@AfterEach
	void closeInsight() {
		InsightStore.getInstance().remove(insightId);
	}

	private record Part(String headers, byte[] content) {
	}

	private static Part file(String fileName, String contentType, String content) {
		return new Part("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\nContent-Type: "
				+ contentType, content.getBytes(StandardCharsets.UTF_8));
	}

	private static Part field(String name, String value) {
		return new Part("Content-Disposition: form-data; name=\"" + name + "\"", value.getBytes(StandardCharsets.UTF_8));
	}

	private HttpServletRequest request(Part... parts) throws IOException {
		return requestAs(user, parts);
	}

	private static HttpServletRequest requestAs(User caller, Part... parts) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (Part part : parts) {
			out.writeBytes(("--" + BOUNDARY + "\r\n" + part.headers() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
			out.writeBytes(part.content());
			out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
		}
		out.writeBytes(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
		byte[] body = out.toByteArray();
		ByteArrayInputStream in = new ByteArrayInputStream(body);
		ServletInputStream stream = new ServletInputStream() {
			@Override
			public int read() {
				return in.read();
			}

			@Override
			public int read(byte[] b, int off, int len) {
				return in.read(b, off, len);
			}

			@Override
			public boolean isFinished() {
				return in.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener listener) {
			}
		};
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpSession session = mock(HttpSession.class);
		when(session.getId()).thenReturn("session-1");
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(caller);
		when(request.getSession()).thenReturn(session);
		when(request.getSession(false)).thenReturn(caller == null ? null : session);
		when(request.getMethod()).thenReturn("POST");
		when(request.getContentType()).thenReturn("multipart/form-data; boundary=" + BOUNDARY);
		when(request.getContentLength()).thenReturn(body.length);
		when(request.getContentLengthLong()).thenReturn((long) body.length);
		when(request.getInputStream()).thenReturn(stream);
		return request;
	}

	private ServletContext context() {
		ServletContext context = mock(ServletContext.class);
		when(context.getInitParameter(AbstractUploader.TEMP_FILE_UPLOAD_KEY)).thenReturn(temp.resolve("spool").toString());
		return context;
	}

	private static String body(Response response) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		((StreamingOutput) response.getEntity()).write(out);
		return out.toString(StandardCharsets.UTF_8);
	}

	private static List<Map<String, String>> files(Response response) throws IOException {
		assertEquals(200, response.getStatus(), () -> safeBody(response));
		return new Gson().fromJson(body(response), new TypeToken<List<Map<String, String>>>() {
		}.getType());
	}

	private static String safeBody(Response response) {
		try {
			return body(response);
		} catch (IOException e) {
			return e.toString();
		}
	}

	private static String error(Response response) throws IOException {
		Map<String, String> map = new Gson().fromJson(body(response), new TypeToken<Map<String, String>>() {
		}.getType());
		return map.get(Constants.ERROR_MESSAGE);
	}

	private Response baseUpload(HttpServletRequest request, String path, String projectId, String engineId,
			boolean userSpace) {
		return uploader.baseUpload(context(), request, insightId, path, projectId, engineId, userSpace);
	}

	private static MultivaluedMap<String, String> form(String type, String headers) {
		MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
		form.putSingle("uploadType", type);
		form.putSingle("userHeaders", headers);
		return form;
	}

	@Test
	void headerCheckFlagsDuplicateAndIllegalCsvHeaders() throws Exception {
		Response response = uploader.checkUserDefinedHeaders(form("csv", "{\"a.csv\":[\"Name\",\"NAME\",\"x+y\",\"ok\"]}"));

		assertEquals(200, response.getStatus());
		Map<String, Map<String, String>> invalid = new Gson().fromJson(body(response),
				new TypeToken<Map<String, Map<String, String>>>() {
				}.getType());
		assertEquals(Map.of("a.csv", Map.of("Name", "Cannot have duplicate header names", "NAME",
				"Cannot have duplicate header names", "x+y", "Header names cannot contain +%@;")), invalid);
	}

	@Test
	void headerCheckKeepsOneEntryPerExcelWorkbook() throws Exception {
		Response response = uploader
				.checkUserDefinedHeaders(form("excel", "[{\"Sheet1\":[\"a\",\"a\"]},{\"Sheet1\":[\"b\"]}]"));

		assertEquals(200, response.getStatus());
		List<Map<String, Map<String, String>>> invalid = new Gson().fromJson(body(response),
				new TypeToken<List<Map<String, Map<String, String>>>>() {
				}.getType());
		assertEquals(List.of(Map.of("Sheet1", Map.of("a", "Cannot have duplicate header names")), Map.of()), invalid);
	}

	@Test
	void headerCheckRejectsHeadersThatAreNotJson() throws Exception {
		Response csv = uploader.checkUserDefinedHeaders(form("csv", "not json"));
		Response excel = uploader.checkUserDefinedHeaders(form("excel", "{\"a\":1}"));

		assertEquals(400, csv.getStatus());
		assertTrue(error(csv).startsWith("Invalid format passed for user defined headers"));
		assertEquals(400, excel.getStatus());
	}

	@Test
	void baseUploadRejectsAnInsightThatIsNotOpen() throws Exception {
		Response response = uploader.baseUpload(context(), request(file("a.csv", "text/csv", "x")), "not-open", null,
				null, null, false);

		assertEquals(400, response.getStatus());
		assertEquals("Session could not be validated in order to upload files", error(response));
	}

	@Test
	void baseUploadRejectsAnInsightWithoutAUser() throws Exception {
		when(insight.getUser()).thenReturn(null);

		Response response = baseUpload(request(file("a.csv", "text/csv", "x")), null, null, null, false);

		assertEquals(400, response.getStatus());
	}

	/** A mocked user whose same-person check is the real one. */
	private static User person() {
		User person = mock(User.class);
		when(person.isSamePerson(any())).thenCallRealMethod();
		return person;
	}

	private static User withLogin(User user, AuthProvider type, String id) {
		AccessToken token = new AccessToken();
		token.setProvider(type);
		token.setId(id);
		when(user.getLogins()).thenReturn(List.of(type));
		when(user.getAccessToken(type)).thenReturn(token);
		return user;
	}

	@Test
	void baseUploadRefusesAnotherUsersInsight() throws Exception {
		withLogin(user, AuthProvider.NATIVE, "jdoe");
		User other = withLogin(person(), AuthProvider.NATIVE, "asmith");
		User sameIdOtherType = withLogin(person(), AuthProvider.MICROSOFT, "jdoe");

		for (User caller : new User[] { other, sameIdOtherType, null }) {
			Response response = uploader.baseUpload(context(), requestAs(caller, file("a.csv", "text/csv", "x")),
					insightId, null, null, null, false);

			assertEquals(400, response.getStatus());
			assertEquals("Session could not be validated in order to upload files", error(response));
		}
		assertFalse(Files.exists(insightFolder.resolve("a.csv")));
	}

	@Test
	void everyAssetUploadRefusesAnotherUsersInsight() throws Exception {
		User other = person();
		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class);
				MockedStatic<SecurityEngineUtils> engines = mockStatic(SecurityEngineUtils.class)) {
			projects.when(() -> SecurityProjectUtils.userCanEditProject(user, "p1")).thenReturn(true);
			engines.when(() -> SecurityEngineUtils.userCanEditEngine(user, "e1")).thenReturn(true);

			Response[] responses = {
					uploader.userAssetsUpload(context(), requestAs(other, file("a.csv", "text/csv", "x")), insightId,
							null),
					uploader.projectAssetsUpload(context(), requestAs(other, file("a.csv", "text/csv", "x")),
							insightId, null, "p1"),
					uploader.engineAssetsUpload(context(), requestAs(other, file("a.csv", "text/csv", "x")), insightId,
							null, "e1") };

			for (Response response : responses) {
				assertEquals(400, response.getStatus());
				assertEquals("Session could not be validated in order to upload files", error(response));
			}
			projects.verify(() -> SecurityProjectUtils.userCanEditProject(any(), anyString()), never());
			engines.verify(() -> SecurityEngineUtils.userCanEditEngine(any(), anyString()), never());
		}
	}

	@Test
	void baseUploadChecksTheCallersOwnRights() throws Exception {
		withLogin(user, AuthProvider.NATIVE, "jdoe");
		User caller = withLogin(person(), AuthProvider.NATIVE, "jdoe");
		try (MockedStatic<AbstractSecurityUtils> security = mockStatic(AbstractSecurityUtils.class);
				MockedStatic<SecurityQueryUtils> query = mockStatic(SecurityQueryUtils.class)) {
			security.when(AbstractSecurityUtils::adminSetPublisher).thenReturn(true);
			query.when(() -> SecurityQueryUtils.userIsPublisher(caller)).thenReturn(true);

			files(uploader.baseUpload(context(), requestAs(caller, file("a.csv", "text/csv", "x")), insightId, null,
					null, null, false));

			query.verify(() -> SecurityQueryUtils.userIsPublisher(user), never());
		}
		assertTrue(Files.exists(insightFolder.resolve("a.csv")));
	}

	@Test
	void baseUploadWritesTheFileIntoTheInsightFolder() throws Exception {
		List<Map<String, String>> saved = files(
				baseUpload(request(file("data.csv", "text/csv", "id,name\n1,a\n")), null, null, null, false));

		assertEquals(List.of(Map.of("fileName", "data.csv", "fileLocation", "/data.csv")), saved);
		assertEquals("id,name\n1,a\n", Files.readString(insightFolder.resolve("data.csv")));
	}

	@Test
	void baseUploadKeepsOnlyTheBaseNameOfAClientPath() throws Exception {
		List<Map<String, String>> saved = files(baseUpload(
				request(file("../../escape.csv", "text/csv", "a"), file("..\\..\\win.csv", "text/csv", "b")), null, null,
				null, false));

		assertEquals("escape.csv", saved.get(0).get("fileName"));
		assertEquals("win.csv", saved.get(1).get("fileName"));
		assertTrue(Files.exists(insightFolder.resolve("escape.csv")));
		assertTrue(Files.exists(insightFolder.resolve("win.csv")));
		assertFalse(Files.exists(temp.getParent().resolve("escape.csv")));
	}

	@Test
	void baseUploadGivesARepeatedNameAUniqueSuffix() throws Exception {
		files(baseUpload(request(file("data.csv", "text/csv", "first")), null, null, null, false));
		List<Map<String, String>> saved = files(
				baseUpload(request(file("data.csv", "text/csv", "second")), null, null, null, false));

		assertEquals("data (2).csv", saved.get(0).get("fileName"));
		assertEquals("first", Files.readString(insightFolder.resolve("data.csv")));
		assertEquals("second", Files.readString(insightFolder.resolve("data (2).csv")));
	}

	@Test
	void baseUploadWritesIntoARelativeSubfolder() throws Exception {
		List<Map<String, String>> saved = files(
				baseUpload(request(file("a.csv", "text/csv", "x")), "sub/dir", null, null, false));

		assertEquals("/sub/dir/a.csv", saved.get(0).get("fileLocation"));
		assertTrue(Files.exists(insightFolder.resolve("sub").resolve("dir").resolve("a.csv")));
	}

	@Test
	void baseUploadRejectsARelativePathThatLeavesTheAssetFolder() throws Exception {
		Response response = baseUpload(request(file("a.csv", "text/csv", "x")), "../outside", null, null, false);

		assertEquals(400, response.getStatus());
		assertFalse(Files.exists(temp.resolve("outside")));
	}

	@Test
	void baseUploadSavesPastedTextAsAnAutoGeneratedFile() throws Exception {
		List<Map<String, String>> saved = files(
				baseUpload(request(field("file", "pasted,values\n1,2\n")), null, null, null, false));

		assertEquals("AutoGeneratedFile.txt", saved.get(0).get("fileName"));
		assertEquals("pasted,values\n1,2\n", Files.readString(insightFolder.resolve("AutoGeneratedFile.txt")));
	}

	@Test
	void baseUploadIgnoresPartsNotNamedFile() throws Exception {
		Part other = new Part("Content-Disposition: form-data; name=\"attachment\"; filename=\"skip.csv\"",
				"x".getBytes(StandardCharsets.UTF_8));

		List<Map<String, String>> saved = files(
				baseUpload(request(other, field("note", "hi"), file("keep.csv", "text/csv", "y")), null, null, null,
						false));

		assertEquals(1, saved.size());
		assertEquals("keep.csv", saved.get(0).get("fileName"));
		assertFalse(Files.exists(insightFolder.resolve("skip.csv")));
	}

	@Test
	void baseUploadAddsAnExtensionFromTheContentType() throws Exception {
		List<Map<String, String>> saved = files(
				baseUpload(request(file("notes", "text/csv", "a,b")), null, null, null, false));

		assertEquals("notes.csv", saved.get(0).get("fileName"));
	}

	@Test
	void baseUploadRejectsATraversalProjectOrEngineId() throws Exception {
		Response project = baseUpload(request(file("a.csv", "text/csv", "x")), null, "..", null, false);
		Response engine = baseUpload(request(file("a.csv", "text/csv", "x")), null, null, "a/b", false);

		assertEquals(400, project.getStatus());
		assertEquals("Invalid project or engine id", error(project));
		assertEquals(400, engine.getStatus());
	}

	@Test
	void baseUploadRequiresPublisherRightsWhenTheAdminRestrictsPublishing() throws Exception {
		try (MockedStatic<AbstractSecurityUtils> security = mockStatic(AbstractSecurityUtils.class);
				MockedStatic<SecurityQueryUtils> query = mockStatic(SecurityQueryUtils.class)) {
			security.when(AbstractSecurityUtils::adminSetPublisher).thenReturn(true);
			query.when(() -> SecurityQueryUtils.userIsPublisher(user)).thenReturn(false);

			Response response = baseUpload(request(file("a.csv", "text/csv", "x")), null, null, null, false);

			assertEquals(400, response.getStatus());
			assertTrue(error(response).startsWith("User does not have permission to publish data"));
		}
		assertFalse(Files.exists(insightFolder.resolve("a.csv")));
	}

	@Test
	void baseUploadRequiresEditAccessToASavedInsight() throws Exception {
		when(insight.isSavedInsight()).thenReturn(true);
		when(insight.getProjectId()).thenReturn("p1");
		when(insight.getRdbmsId()).thenReturn("i1");
		try (MockedStatic<SecurityInsightUtils> security = mockStatic(SecurityInsightUtils.class)) {
			security.when(() -> SecurityInsightUtils.userCanEditInsight(user, "p1", "i1")).thenReturn(false);

			Response response = baseUpload(request(file("a.csv", "text/csv", "x")), null, null, null, false);

			assertEquals(400, response.getStatus());
			assertEquals("User does not edit access for this insight", error(response));
		}
	}

	@Test
	void baseUploadRequiresEditAccessToTheTargetProjectOrEngine() throws Exception {
		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class);
				MockedStatic<SecurityEngineUtils> engines = mockStatic(SecurityEngineUtils.class)) {
			Response project = baseUpload(request(file("a.csv", "text/csv", "x")), null, "p1", null, false);
			Response engine = baseUpload(request(file("a.csv", "text/csv", "x")), null, null, "e1", false);

			assertEquals(400, project.getStatus());
			assertEquals("User does not have permission for this project.", error(project));
			assertEquals(400, engine.getStatus());
			assertEquals("User does not have permission for this engine.", error(engine));
		}
	}

	@Test
	void baseUploadRejectsAProjectAndAnEngineTogether() throws Exception {
		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class);
				MockedStatic<SecurityEngineUtils> engines = mockStatic(SecurityEngineUtils.class)) {
			projects.when(() -> SecurityProjectUtils.userCanEditProject(user, "p1")).thenReturn(true);
			engines.when(() -> SecurityEngineUtils.userCanEditEngine(user, "e1")).thenReturn(true);

			Response response = baseUpload(request(file("a.csv", "text/csv", "x")), null, "p1", "e1", false);

			assertEquals(422, response.getStatus());
		}
	}

	@Test
	void baseUploadWritesIntoTheProjectAppRootAndSyncsIt() throws Exception {
		Path appRoot = temp.resolve("project").resolve("app_root");
		IProject project = mock(IProject.class);
		when(project.getEngineId()).thenReturn("p1");
		when(project.getEngineName()).thenReturn("Proj");
		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class);
				MockedStatic<Utility> utility = mockStatic(Utility.class,
						inv -> "getProject".equals(inv.getMethod().getName()) ? project : inv.callRealMethod());
				MockedStatic<EngineUtility> engines = mockStatic(EngineUtility.class);
				MockedStatic<ClusterUtil> cluster = mockStatic(ClusterUtil.class)) {
			projects.when(() -> SecurityProjectUtils.userCanEditProject(user, "p1")).thenReturn(true);
			engines.when(() -> EngineUtility.getSpecificEngineAppRootFolder(IEngine.CATALOG_TYPE.PROJECT, "p1", "Proj"))
					.thenReturn(appRoot.toString());

			List<Map<String, String>> saved = files(
					baseUpload(request(file("a.csv", "text/csv", "x")), null, "p1", null, false));

			assertEquals("a.csv", saved.get(0).get("fileName"));
			assertTrue(Files.exists(appRoot.resolve("a.csv")));
			cluster.verify(() -> ClusterUtil.pushProjectFolder(project, appRoot.toString()));
		}
	}

	@Test
	void baseUploadWritesIntoTheUsersAssetAppRootForTheUserProject() throws Exception {
		Path appRoot = temp.resolve("user").resolve("app_root");
		when(user.getPrimaryLogin()).thenReturn(AuthProvider.NATIVE);
		when(user.getAssetProjectId(AuthProvider.NATIVE)).thenReturn("asset-1");
		try (MockedStatic<AssetUtility> assets = mockStatic(AssetUtility.class);
				MockedStatic<ClusterUtil> cluster = mockStatic(ClusterUtil.class)) {
			assets.when(() -> AssetUtility.getUserAssetAppRootFolder("Asset", "asset-1")).thenReturn(appRoot.toString());

			files(baseUpload(request(file("a.csv", "text/csv", "x")), null, "user", null, false));

			assertTrue(Files.exists(appRoot.resolve("a.csv")));
			cluster.verify(() -> ClusterUtil.pushUserAsset("asset-1"));
		}
	}

	@Test
	void baseUploadWritesIntoTheEngineFolderAndSyncsIt() throws Exception {
		Path engineFolder = temp.resolve("engine");
		IEngine engine = mock(IEngine.class);
		when(engine.getEngineId()).thenReturn("e1");
		when(engine.getEngineName()).thenReturn("Model");
		when(engine.getCatalogType()).thenReturn(IEngine.CATALOG_TYPE.MODEL);
		try (MockedStatic<SecurityEngineUtils> security = mockStatic(SecurityEngineUtils.class);
				MockedStatic<Utility> utility = mockStatic(Utility.class,
						inv -> "getEngine".equals(inv.getMethod().getName()) ? engine : inv.callRealMethod());
				MockedStatic<EngineUtility> engines = mockStatic(EngineUtility.class);
				MockedStatic<ClusterUtil> cluster = mockStatic(ClusterUtil.class)) {
			security.when(() -> SecurityEngineUtils.userCanEditEngine(user, "e1")).thenReturn(true);
			engines.when(() -> EngineUtility.getSpecificEngineBaseFolder(IEngine.CATALOG_TYPE.MODEL, "e1", "Model"))
					.thenReturn(engineFolder.toString());

			files(baseUpload(request(file("a.csv", "text/csv", "x")), "inputs", null, "e1", false));

			Path written = engineFolder.resolve("inputs");
			assertTrue(Files.exists(written.resolve("a.csv")));
			cluster.verify(() -> ClusterUtil.pushEngineFolder(engine, written.toRealPath().toString()));
		}
	}

	@Test
	void baseUploadSyncsARoomInsightAfterWriting() throws Exception {
		when(insight.getRoomId()).thenReturn("room-1");
		try (MockedStatic<ClusterUtil> cluster = mockStatic(ClusterUtil.class)) {
			files(baseUpload(request(file("a.csv", "text/csv", "x")), null, null, null, false));

			cluster.verify(() -> ClusterUtil.pushRoom("room-1"));
		}
	}

	@Test
	void baseUploadRejectsAFileTheVirusScannerFlags() throws Exception {
		try (MockedStatic<Utility> utility = mockStatic(Utility.class,
				inv -> "isVirusScanningEnabled".equals(inv.getMethod().getName()) ? true : inv.callRealMethod());
				MockedStatic<VirusScannerUtils> scanner = mockStatic(VirusScannerUtils.class)) {
			scanner.when(() -> VirusScannerUtils.getViruses(eq("bad.exe"), any()))
					.thenReturn(Map.of("bad.exe", List.of("Eicar"), "other", List.of("Trojan")));

			Response response = baseUpload(request(file("bad.exe", "application/octet-stream", "x")), null, null, null,
					false);

			assertEquals(400, response.getStatus());
			assertEquals("Detected 2 viruses. If you believe this is an error, please contact an administrator.",
					error(response));
		}
		assertFalse(Files.exists(insightFolder.resolve("bad.exe")));
	}

	@Test
	void userAssetsUploadWritesIntoTheUsersAssetFolder() throws Exception {
		Path assetFolder = temp.resolve("assets");
		IProject assetProject = mock(IProject.class);
		when(assetProject.getEngineId()).thenReturn("asset-1");
		when(assetProject.getEngineName()).thenReturn("Asset");
		when(user.getAssetProject()).thenReturn(assetProject);
		try (MockedStatic<AssetUtility> assets = mockStatic(AssetUtility.class)) {
			assets.when(() -> AssetUtility.getUserAssetFolder("Asset", "asset-1")).thenReturn(assetFolder.toString());

			List<Map<String, String>> saved = files(
					uploader.userAssetsUpload(context(), request(file("a.csv", "text/csv", "x")), insightId, "docs"));

			assertEquals("/docs/a.csv", saved.get(0).get("fileLocation"));
			assertTrue(Files.exists(assetFolder.resolve("docs").resolve("a.csv")));
		}
	}

	@Test
	void userAssetsUploadRejectsAnInsightThatIsNotOpen() throws Exception {
		Response response = uploader.userAssetsUpload(context(), request(file("a.csv", "text/csv", "x")), "nope", null);

		assertEquals(400, response.getStatus());
	}

	@Test
	void projectAssetsUploadRequiresAProjectId() throws Exception {
		Response missing = uploader.projectAssetsUpload(context(), request(file("a.csv", "text/csv", "x")), insightId,
				null, null);
		Response blank = uploader.projectAssetsUpload(context(), request(file("a.csv", "text/csv", "x")), insightId,
				null, "   ");

		assertEquals(400, missing.getStatus());
		assertEquals("Must provide a project id.", error(missing));
		assertEquals(400, blank.getStatus());
	}

	@Test
	void projectAssetsUploadRequiresEditAccess() throws Exception {
		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class)) {
			Response response = uploader.projectAssetsUpload(context(), request(file("a.csv", "text/csv", "x")),
					insightId, null, "p1");

			assertEquals(400, response.getStatus());
			assertEquals("User does not have permission for this project.", error(response));
		}
	}

	@Test
	void projectAssetsUploadWritesCommitsAndSyncsTheAssets() throws Exception {
		Path assetsFolder = temp.resolve("version").resolve("assets");
		Path versionFolder = temp.resolve("version");
		IProject project = mock(IProject.class);
		when(project.getEngineId()).thenReturn("p1");
		when(project.getEngineName()).thenReturn("Proj");
		when(project.getCatalogType()).thenReturn(IEngine.CATALOG_TYPE.PROJECT);
		AccessToken token = new AccessToken();
		token.setUsername("jdoe");
		token.setEmail("jdoe@example.com");
		when(user.getPrimaryLogin()).thenReturn(AuthProvider.NATIVE);
		when(user.getAccessToken(AuthProvider.NATIVE)).thenReturn(token);
		List<List<String>> added = new ArrayList<>();
		try (MockedStatic<SecurityProjectUtils> projects = mockStatic(SecurityProjectUtils.class);
				MockedStatic<Utility> utility = mockStatic(Utility.class,
						inv -> "getProject".equals(inv.getMethod().getName()) ? project : inv.callRealMethod());
				MockedStatic<EngineUtility> engines = mockStatic(EngineUtility.class);
				MockedStatic<GitRepoUtils> git = mockStatic(GitRepoUtils.class);
				MockedStatic<ClusterUtil> cluster = mockStatic(ClusterUtil.class)) {
			projects.when(() -> SecurityProjectUtils.userCanEditProject(user, "p1")).thenReturn(true);
			engines.when(() -> EngineUtility.getSpecificEngineAssetsFolder(IEngine.CATALOG_TYPE.PROJECT, "p1", "Proj"))
					.thenReturn(assetsFolder.toString());
			engines.when(() -> EngineUtility.getSpecificEngineVersionFolder(IEngine.CATALOG_TYPE.PROJECT, "p1", "Proj"))
					.thenReturn(versionFolder.toString());
			git.when(() -> GitRepoUtils.addSpecificFiles(anyString(), anyList())).thenAnswer(inv -> {
				added.add(inv.getArgument(1));
				return null;
			});

			files(uploader.projectAssetsUpload(context(),
					request(file("a.csv", "text/csv", "x"), file("b.csv", "text/csv", "y")), insightId, "/docs/",
					"p1"));

			Path written = assetsFolder.resolve("docs");
			assertTrue(Files.exists(written.resolve("a.csv")));
			assertEquals(List.of(List.of("assets/docs/a.csv", "assets/docs/b.csv")), added);
			git.verify(() -> GitRepoUtils.commitAddedFiles(versionFolder.toString(),
					"add: uploaded assets/docs/a.csv, assets/docs/b.csv", "jdoe", "jdoe@example.com"));
			cluster.verify(() -> ClusterUtil.pushProjectFolder(project, written.toRealPath().toString()));
		}
	}

	@Test
	void engineAssetsUploadRequiresAnEngineIdAndEditAccess() throws Exception {
		try (MockedStatic<SecurityEngineUtils> security = mockStatic(SecurityEngineUtils.class)) {
			Response missing = uploader.engineAssetsUpload(context(), request(file("a.csv", "text/csv", "x")),
					insightId, null, null);
			Response denied = uploader.engineAssetsUpload(context(), request(file("a.csv", "text/csv", "x")),
					insightId, null, "e1");

			assertEquals(400, missing.getStatus());
			assertEquals("Must provide an engine id.", error(missing));
			assertEquals(400, denied.getStatus());
			assertEquals("User does not have permission for this engine.", error(denied));
		}
	}

	@Test
	void engineAssetsUploadWritesIntoTheEngineAssetsAndKeepsItWhenGitFails() throws Exception {
		Path assetsFolder = temp.resolve("engine").resolve("assets");
		IEngine engine = mock(IEngine.class);
		when(engine.getEngineId()).thenReturn("e1");
		when(engine.getEngineName()).thenReturn("Model");
		when(engine.getCatalogType()).thenReturn(IEngine.CATALOG_TYPE.MODEL);
		try (MockedStatic<SecurityEngineUtils> security = mockStatic(SecurityEngineUtils.class);
				MockedStatic<Utility> utility = mockStatic(Utility.class,
						inv -> "getEngine".equals(inv.getMethod().getName()) ? engine : inv.callRealMethod());
				MockedStatic<EngineUtility> engines = mockStatic(EngineUtility.class);
				MockedStatic<GitRepoUtils> git = mockStatic(GitRepoUtils.class);
				MockedStatic<ClusterUtil> cluster = mockStatic(ClusterUtil.class)) {
			security.when(() -> SecurityEngineUtils.userCanEditEngine(user, "e1")).thenReturn(true);
			engines.when(() -> EngineUtility.getSpecificEngineAssetsFolder(IEngine.CATALOG_TYPE.MODEL, "e1", "Model"))
					.thenReturn(assetsFolder.toString());
			git.when(() -> GitRepoUtils.addSpecificFiles(anyString(), anyList()))
					.thenThrow(new IllegalStateException("no repo"));

			List<Map<String, String>> saved = files(uploader.engineAssetsUpload(context(),
					request(file("a.csv", "text/csv", "x")), insightId, null, "e1"));

			assertEquals("/a.csv", saved.get(0).get("fileLocation"));
			assertTrue(Files.exists(assetsFolder.resolve("a.csv")));
			git.verify(() -> GitRepoUtils.commitAddedFiles(anyString(), anyString(), anyString(), anyString()),
					never());
			cluster.verify(() -> ClusterUtil.pushEngineFolder(engine, assetsFolder.toString()));
		}
	}
}
