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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterAll;
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
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.User;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.engine.api.IEngine;
import prerna.util.AssetUtility;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.EngineUtility;
import prerna.util.Utility;

class ImageUploaderTest {

	private static final String BOUNDARY = "ImageUploaderTestBoundary";
	private static boolean setBaseFolder;

	@TempDir
	Path temp;

	private final ImageUploader uploader = new ImageUploader();
	private User user;
	private byte[] png;

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
	void makeImage() throws IOException {
		user = mock(User.class);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", out);
		png = out.toByteArray();
	}

	private record Part(String headers, byte[] content) {
	}

	private static Part image(String contentType, byte[] content) {
		return new Part("Content-Disposition: form-data; name=\"file\"; filename=\"logo.png\"\r\nContent-Type: "
				+ contentType, content);
	}

	private static Part field(String name, String value) {
		return new Part("Content-Disposition: form-data; name=\"" + name + "\"", value.getBytes(StandardCharsets.UTF_8));
	}

	private HttpServletRequest request(Part... parts) throws IOException {
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
		HttpServletRequest request = signedIn(user);
		when(request.getMethod()).thenReturn("POST");
		when(request.getContentType()).thenReturn("multipart/form-data; boundary=" + BOUNDARY);
		when(request.getContentLength()).thenReturn(body.length);
		when(request.getContentLengthLong()).thenReturn((long) body.length);
		when(request.getInputStream()).thenReturn(stream);
		return request;
	}

	private static HttpServletRequest signedIn(User user) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		when(request.getSession(false)).thenReturn(session);
		return request;
	}

	private ServletContext context() {
		ServletContext context = mock(ServletContext.class);
		when(context.getInitParameter(AbstractUploader.TEMP_FILE_UPLOAD_KEY)).thenReturn(temp.resolve("spool").toString());
		return context;
	}

	private static Map<String, String> body(Response response) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		((StreamingOutput) response.getEntity()).write(out);
		return new Gson().fromJson(out.toString(StandardCharsets.UTF_8), new TypeToken<Map<String, String>>() {
		}.getType());
	}

	private static String error(Response response) throws IOException {
		return body(response).get(Constants.ERROR_MESSAGE);
	}

	private MockedStatic<SecurityEngineUtils> engineSecurity(boolean canEdit) {
		MockedStatic<SecurityEngineUtils> security = mockStatic(SecurityEngineUtils.class);
		security.when(() -> SecurityEngineUtils.userCanEditEngine(user, "e1")).thenReturn(canEdit);
		security.when(() -> SecurityEngineUtils.getEngineAliasForId("e1")).thenReturn("Model");
		security.when(() -> SecurityEngineUtils.getEngineTypeAndSubtype("e1"))
				.thenReturn(new Object[] { IEngine.CATALOG_TYPE.MODEL, "OPEN_AI" });
		return security;
	}

	private MockedStatic<EngineUtility> engineFolders(Path version) {
		MockedStatic<EngineUtility> engines = mockStatic(EngineUtility.class);
		engines.when(() -> EngineUtility.getCouchSelector(IEngine.CATALOG_TYPE.MODEL)).thenReturn("model");
		engines.when(() -> EngineUtility.getLocalEngineImageDirectory(IEngine.CATALOG_TYPE.MODEL))
				.thenReturn(temp.resolve("images").toString());
		engines.when(() -> EngineUtility.getSpecificEngineVersionFolder(IEngine.CATALOG_TYPE.MODEL, "Model__e1"))
				.thenReturn(version.toString());
		return engines;
	}

	private static MockedStatic<Utility> noEngineLoad() {
		return mockStatic(Utility.class,
				inv -> "getEngine".equals(inv.getMethod().getName()) ? null : inv.callRealMethod());
	}

	private MockedStatic<SecurityProjectUtils> projectSecurity(boolean canEdit) {
		MockedStatic<SecurityProjectUtils> security = mockStatic(SecurityProjectUtils.class);
		security.when(() -> SecurityProjectUtils.userCanEditProject(user, "p1")).thenReturn(canEdit);
		security.when(() -> SecurityProjectUtils.getProjectAliasForId("p1")).thenReturn("Proj");
		return security;
	}

	private MockedStatic<AssetUtility> projectVersion(Path version) {
		MockedStatic<AssetUtility> assets = mockStatic(AssetUtility.class);
		assets.when(() -> AssetUtility.getProjectVersionFolder("Proj", "p1")).thenReturn(version.toString());
		return assets;
	}

	@Test
	void everyEndpointRejectsARequestWithoutASession() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getParameter(anyString())).thenReturn("id1");

		assertEquals("User session is invalid", error(uploader.uploadEngineImage(context(), request)));
		assertEquals("User session is invalid", error(uploader.deleteEngineImage(request)));
		assertEquals("User session is invalid", error(uploader.uploadProjectImage(context(), request)));
		assertEquals("User session is invalid", error(uploader.deleteProjectImage(request)));
		assertEquals("User session is invalid", error(uploader.uploadInsightImage(context(), request)));
		assertEquals("User session is invalid", error(uploader.deleteInsightImage(request)));
		assertEquals("User session is invalid", error(uploader.uploadDatabaseImage(context(), request)));
		assertEquals("User session is invalid", error(uploader.deleteDatabaseImage(request)));
	}

	@Test
	void everyEndpointRejectsASessionWithoutAUser() throws Exception {
		HttpServletRequest request = signedIn(null);
		when(request.getParameter(anyString())).thenReturn("id1");

		assertEquals(400, uploader.uploadEngineImage(context(), request).getStatus());
		assertEquals(400, uploader.deleteEngineImage(request).getStatus());
		assertEquals(400, uploader.uploadProjectImage(context(), request).getStatus());
		assertEquals(400, uploader.deleteProjectImage(request).getStatus());
		assertEquals(400, uploader.uploadInsightImage(context(), request).getStatus());
		assertEquals(400, uploader.deleteInsightImage(request).getStatus());
		assertEquals(400, uploader.uploadDatabaseImage(context(), request).getStatus());
		assertEquals(400, uploader.deleteDatabaseImage(request).getStatus());
	}

	@Test
	void uploadEngineImageRequiresTheFileAndTheEngineId() throws Exception {
		Response noFile = uploader.uploadEngineImage(context(), request(field("engineId", "e1")));
		Response noId = uploader.uploadEngineImage(context(), request(image("image/png", png)));

		assertEquals("Could not find the file to upload for the insight in the request", error(noFile));
		assertEquals("Need to pass the proper engine id to upload the image", error(noId));
	}

	@Test
	void uploadEngineImageRequiresEditAccess() throws Exception {
		try (MockedStatic<SecurityEngineUtils> security = engineSecurity(false)) {
			Response response = uploader.uploadEngineImage(context(),
					request(field("engineId", "e1"), image("image/png", png)));

			assertEquals(400, response.getStatus());
			assertEquals("User does not have access to this engine or the engine id does not exist", error(response));
		}
	}

	@Test
	void uploadEngineImageReplacesTheImageInTheVersionFolder() throws Exception {
		Path version = Files.createDirectories(temp.resolve("model").resolve("version"));
		Files.writeString(version.resolve("image.jpeg"), "old");
		Files.writeString(version.resolve("notes.txt"), "keep");
		try (MockedStatic<SecurityEngineUtils> security = engineSecurity(true);
				MockedStatic<EngineUtility> engines = engineFolders(version);
				MockedStatic<Utility> utility = noEngineLoad();
				MockedStatic<ClusterUtil> cluster = mockStatic(ClusterUtil.class)) {
			Response response = uploader.uploadEngineImage(context(),
					request(field("engineId", "e1"), image("image/png", png)));

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("message", "Successfully updated engine image", "engine_id", "e1", "engine_name",
					"Model"), body(response));
			assertArrayEquals(png, Files.readAllBytes(version.resolve("image.png")));
			assertFalse(Files.exists(version.resolve("image.jpeg")));
			assertTrue(Files.exists(version.resolve("notes.txt")));
			utility.verify(() -> Utility.getEngine("e1", IEngine.CATALOG_TYPE.MODEL, true));
			cluster.verify(() -> ClusterUtil.copyLocalFileToEngineCloudFolder(eq("e1"), eq(IEngine.CATALOG_TYPE.MODEL),
					anyString()));
		}
	}

	@Test
	void deleteEngineImageRejectsAnIdThatIsNotOnePathSegment() throws Exception {
		HttpServletRequest request = signedIn(user);
		when(request.getParameter("engineId")).thenReturn("..");

		Response response = uploader.deleteEngineImage(request);

		assertEquals(400, response.getStatus());
		assertEquals("Need to pass the proper engine id to remove the image", error(response));
	}

	@Test
	void deleteEngineImageRequiresEditAccess() throws Exception {
		HttpServletRequest request = signedIn(user);
		when(request.getParameter("engineId")).thenReturn("e1");
		try (MockedStatic<SecurityEngineUtils> security = engineSecurity(false)) {
			assertEquals(400, uploader.deleteEngineImage(request).getStatus());
		}
	}

	@Test
	void deleteEngineImageRemovesTheVersionFolderImages() throws Exception {
		Path version = Files.createDirectories(temp.resolve("model").resolve("version"));
		Files.writeString(version.resolve("image.png"), "old");
		Files.writeString(version.resolve("model.smss"), "keep");
		HttpServletRequest request = signedIn(user);
		when(request.getParameter("engineId")).thenReturn("e1");
		try (MockedStatic<SecurityEngineUtils> security = engineSecurity(true);
				MockedStatic<EngineUtility> engines = engineFolders(version);
				MockedStatic<Utility> utility = noEngineLoad()) {
			Response response = uploader.deleteEngineImage(request);

			assertEquals(200, response.getStatus());
			assertEquals("Successfully deleted engine image", body(response).get("message"));
			assertFalse(Files.exists(version.resolve("image.png")));
			assertTrue(Files.exists(version.resolve("model.smss")));
		}
	}

	@Test
	void uploadProjectImageRequiresTheFileAndTheProjectId() throws Exception {
		Response noFile = uploader.uploadProjectImage(context(), request(field("projectId", "p1")));
		Response noId = uploader.uploadProjectImage(context(), request(image("image/png", png)));

		assertEquals("Could not find the file to upload for the insight in the request", error(noFile));
		assertEquals("Need to pass the proper project id to upload the image", error(noId));
	}

	@Test
	void uploadProjectImageRejectsAProjectTheUserCannotEdit() throws Exception {
		try (MockedStatic<SecurityProjectUtils> security = projectSecurity(false)) {
			Response response = uploader.uploadProjectImage(context(),
					request(field("projectId", "p1"), image("image/png", png)));

			assertEquals("User does not have access to edit this project", error(response));
		}
	}

	@Test
	void uploadProjectImageReplacesTheImageInTheProjectVersionFolder() throws Exception {
		Path version = Files.createDirectories(temp.resolve("project").resolve("version"));
		Files.writeString(version.resolve("image.svg"), "old");
		try (MockedStatic<SecurityProjectUtils> security = projectSecurity(true);
				MockedStatic<AssetUtility> assets = projectVersion(version);
				MockedStatic<EngineUtility> engines = mockStatic(EngineUtility.class)) {
			Response response = uploader.uploadProjectImage(context(),
					request(field("projectId", "p1"), image("image/png", png)));

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("message", "Successfully updated project image", "app_id", "p1", "app_name", "Proj"),
					body(response));
			assertArrayEquals(png, Files.readAllBytes(version.resolve("image.png")));
			assertFalse(Files.exists(version.resolve("image.svg")));
		}
	}

	@Test
	void deleteProjectImageRejectsAnIdThatIsNotOnePathSegment() throws Exception {
		HttpServletRequest request = signedIn(user);
		when(request.getParameter("projectId")).thenReturn("a/b");

		assertEquals("Need to pass the proper project id to remove the image",
				error(uploader.deleteProjectImage(request)));
	}

	@Test
	void deleteProjectImageRemovesTheProjectImage() throws Exception {
		Path version = Files.createDirectories(temp.resolve("project").resolve("version"));
		Files.writeString(version.resolve("image.gif"), "old");
		Files.writeString(version.resolve("project.smss"), "keep");
		HttpServletRequest request = signedIn(user);
		when(request.getParameter("projectId")).thenReturn("p1");
		try (MockedStatic<SecurityProjectUtils> security = projectSecurity(true);
				MockedStatic<AssetUtility> assets = projectVersion(version);
				MockedStatic<EngineUtility> engines = mockStatic(EngineUtility.class)) {
			Response response = uploader.deleteProjectImage(request);

			assertEquals(200, response.getStatus());
			assertEquals("Proj", body(response).get("project_name"));
			assertFalse(Files.exists(version.resolve("image.gif")));
			assertTrue(Files.exists(version.resolve("project.smss")));
		}
	}

	@Test
	void deleteProjectImageRequiresEditAccess() throws Exception {
		HttpServletRequest request = signedIn(user);
		when(request.getParameter("projectId")).thenReturn("p1");
		try (MockedStatic<SecurityProjectUtils> security = projectSecurity(false);
				MockedStatic<EngineUtility> engines = mockStatic(EngineUtility.class)) {
			Response response = uploader.deleteProjectImage(request);

			assertEquals("User does not have access to this project or the project id does not exist",
					error(response));
		}
	}

	@Test
	void uploadInsightImageRequiresBothIdsAndEditAccess() throws Exception {
		Response missing = uploader.uploadInsightImage(context(),
				request(field("projectId", "p1"), image("image/png", png)));
		assertEquals("Need to pass the proper project and insight ids to upload the image", error(missing));

		try (MockedStatic<SecurityProjectUtils> security = projectSecurity(true);
				MockedStatic<SecurityInsightUtils> insights = mockStatic(SecurityInsightUtils.class)) {
			Response denied = uploader.uploadInsightImage(context(),
					request(field("projectId", "p1"), field("insightId", "i1"), image("image/png", png)));

			assertEquals("User does not have access to edit this insight within the project", error(denied));
		}
	}

	@Test
	void uploadInsightImageWritesIntoTheInsightFolderOfTheProject() throws Exception {
		Path version = Files.createDirectories(temp.resolve("project").resolve("version"));
		Files.createDirectories(version.resolve("i1"));
		Files.writeString(version.resolve("i1").resolve("image.jpg"), "old");
		try (MockedStatic<SecurityProjectUtils> security = projectSecurity(true);
				MockedStatic<SecurityInsightUtils> insights = mockStatic(SecurityInsightUtils.class);
				MockedStatic<AssetUtility> assets = projectVersion(version)) {
			insights.when(() -> SecurityInsightUtils.userCanEditInsight(user, "p1", "i1")).thenReturn(true);

			Response response = uploader.uploadInsightImage(context(),
					request(field("projectId", "p1"), field("insightId", "i1"), image("image/png", png)));

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("project_id", "p1", "project_name", "Proj", "insight_id", "i1", "message",
					"Successfully updated insight image"), body(response));
			assertArrayEquals(png, Files.readAllBytes(version.resolve("i1").resolve("image.png")));
			assertFalse(Files.exists(version.resolve("i1").resolve("image.jpg")));
		}
	}

	@Test
	void deleteInsightImageRejectsIdsThatAreNotOnePathSegment() throws Exception {
		HttpServletRequest badProject = signedIn(user);
		when(badProject.getParameter("projectId")).thenReturn("..");
		when(badProject.getParameter("insightId")).thenReturn("i1");
		HttpServletRequest badInsight = signedIn(user);
		when(badInsight.getParameter("projectId")).thenReturn("p1");
		when(badInsight.getParameter("insightId")).thenReturn("../i1");

		assertEquals("Need to pass the proper project id to remove the image",
				error(uploader.deleteInsightImage(badProject)));
		assertEquals("Need to pass the proper insight id to remove the image",
				error(uploader.deleteInsightImage(badInsight)));
	}

	@Test
	void deleteInsightImageReportsAnInsightWithoutACustomImage() throws Exception {
		Path version = Files.createDirectories(temp.resolve("project").resolve("version"));
		HttpServletRequest request = signedIn(user);
		when(request.getParameter("projectId")).thenReturn("p1");
		when(request.getParameter("insightId")).thenReturn("i1");
		try (MockedStatic<SecurityProjectUtils> security = projectSecurity(true);
				MockedStatic<SecurityInsightUtils> insights = mockStatic(SecurityInsightUtils.class);
				MockedStatic<AssetUtility> assets = projectVersion(version)) {
			insights.when(() -> SecurityInsightUtils.userCanEditInsight(user, "p1", "i1")).thenReturn(true);

			Response response = uploader.deleteInsightImage(request);

			assertEquals(400, response.getStatus());
			assertEquals("You do not have a custom insight image to delete", error(response));
		}
	}

	@Test
	void deleteInsightImageRemovesTheInsightImage() throws Exception {
		Path version = Files.createDirectories(temp.resolve("project").resolve("version"));
		Path insightFolder = Files.createDirectories(version.resolve("i1"));
		Files.writeString(insightFolder.resolve("image.png"), "old");
		HttpServletRequest request = signedIn(user);
		when(request.getParameter("projectId")).thenReturn("p1");
		when(request.getParameter("insightId")).thenReturn("i1");
		try (MockedStatic<SecurityProjectUtils> security = projectSecurity(true);
				MockedStatic<SecurityInsightUtils> insights = mockStatic(SecurityInsightUtils.class);
				MockedStatic<AssetUtility> assets = projectVersion(version)) {
			insights.when(() -> SecurityInsightUtils.userCanEditInsight(user, "p1", "i1")).thenReturn(true);

			Response response = uploader.deleteInsightImage(request);

			assertEquals(200, response.getStatus());
			assertFalse(Files.exists(insightFolder.resolve("image.png")));
		}
	}

	@Test
	void uploadDatabaseImageAcceptsTheDatabaseIdField() throws Exception {
		Path version = Files.createDirectories(temp.resolve("model").resolve("version"));
		try (MockedStatic<SecurityEngineUtils> security = engineSecurity(true);
				MockedStatic<EngineUtility> engines = engineFolders(version);
				MockedStatic<Utility> utility = noEngineLoad();
				MockedStatic<ClusterUtil> cluster = mockStatic(ClusterUtil.class)) {
			Response response = uploader.uploadDatabaseImage(context(),
					request(field("databaseId", "e1"), image("image/png", png)));

			assertEquals(200, response.getStatus());
			assertEquals("e1", body(response).get("database_id"));
			assertEquals("Model", body(response).get("database_name"));
			assertArrayEquals(png, Files.readAllBytes(version.resolve("image.png")));
		}
	}

	@Test
	void engineAndDatabaseUploadsNameTheImageByItsDecodedFormatNotItsContentType() throws Exception {
		Path version = Files.createDirectories(temp.resolve("model").resolve("version"));
		String traversal = "image/x\\..\\..\\..\\escaped";
		try (MockedStatic<SecurityEngineUtils> security = engineSecurity(true);
				MockedStatic<EngineUtility> engines = engineFolders(version);
				MockedStatic<Utility> utility = noEngineLoad();
				MockedStatic<ClusterUtil> cluster = mockStatic(ClusterUtil.class)) {
			assertEquals(200, uploader
					.uploadEngineImage(context(), request(field("engineId", "e1"), image(traversal, png))).getStatus());
			assertArrayEquals(png, Files.readAllBytes(version.resolve("image.png")));
			Files.delete(version.resolve("image.png"));

			assertEquals(200, uploader
					.uploadDatabaseImage(context(), request(field("databaseId", "e1"), image(traversal, png)))
					.getStatus());
			assertArrayEquals(png, Files.readAllBytes(version.resolve("image.png")));
		}
		try (Stream<Path> files = Files.walk(temp)) {
			assertTrue(files.noneMatch(f -> f.getFileName().toString().contains("escaped")));
		}
	}

	@Test
	void projectAndInsightUploadsNameTheImageByItsDecodedFormatNotItsContentType() throws Exception {
		Path version = Files.createDirectories(temp.resolve("project").resolve("version"));
		Files.createDirectories(version.resolve("i1"));
		String traversal = "image/x\\..\\..\\..\\escaped";
		try (MockedStatic<SecurityProjectUtils> security = projectSecurity(true);
				MockedStatic<SecurityInsightUtils> insights = mockStatic(SecurityInsightUtils.class);
				MockedStatic<AssetUtility> assets = projectVersion(version);
				MockedStatic<EngineUtility> engines = mockStatic(EngineUtility.class)) {
			insights.when(() -> SecurityInsightUtils.userCanEditInsight(user, "p1", "i1")).thenReturn(true);

			assertEquals(200, uploader
					.uploadProjectImage(context(), request(field("projectId", "p1"), image(traversal, png)))
					.getStatus());
			assertEquals(200, uploader.uploadInsightImage(context(),
					request(field("projectId", "p1"), field("insightId", "i1"), image(traversal, png))).getStatus());
		}
		assertArrayEquals(png, Files.readAllBytes(version.resolve("image.png")));
		assertArrayEquals(png, Files.readAllBytes(version.resolve("i1").resolve("image.png")));
		try (Stream<Path> files = Files.walk(temp)) {
			assertTrue(files.noneMatch(f -> f.getFileName().toString().contains("escaped")));
		}
	}

	@Test
	void uploadProjectImageRefusesAFileThatIsNotAPngJpegOrGifAndKeepsTheOldImage() throws Exception {
		Path version = Files.createDirectories(temp.resolve("project").resolve("version"));
		Files.write(version.resolve("image.png"), png);
		byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
		byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
				.getBytes(StandardCharsets.UTF_8);
		try (MockedStatic<SecurityProjectUtils> security = projectSecurity(true);
				MockedStatic<AssetUtility> assets = projectVersion(version);
				MockedStatic<EngineUtility> engines = mockStatic(EngineUtility.class)) {
			for (Part part : new Part[] { image("text/html", html), image("image/svg+xml", svg),
					image("image/png", html) }) {
				Response response = uploader.uploadProjectImage(context(), request(field("projectId", "p1"), part));

				assertEquals(415, response.getStatus());
				assertEquals("Supported image formats are PNG, JPEG, and GIF", error(response));
			}
		}
		assertArrayEquals(png, Files.readAllBytes(version.resolve("image.png")));
		try (Stream<Path> files = Files.list(version)) {
			assertEquals(1, files.count());
		}
	}

	@Test
	void deleteDatabaseImageFallsBackToTheDatabaseIdParameter() throws Exception {
		Path version = Files.createDirectories(temp.resolve("model").resolve("version"));
		Files.writeString(version.resolve("image.png"), "old");
		HttpServletRequest request = signedIn(user);
		when(request.getParameter("databaseId")).thenReturn("e1");
		try (MockedStatic<SecurityEngineUtils> security = engineSecurity(true);
				MockedStatic<EngineUtility> engines = engineFolders(version);
				MockedStatic<Utility> utility = noEngineLoad()) {
			Response response = uploader.deleteDatabaseImage(request);

			assertEquals(200, response.getStatus());
			assertEquals("Successfully deleted database image", body(response).get("message"));
			assertFalse(Files.exists(version.resolve("image.png")));
		}
	}

	@Test
	void deleteDatabaseImageRequiresEditAccess() throws Exception {
		HttpServletRequest request = signedIn(user);
		when(request.getParameter("engineId")).thenReturn("e1");
		try (MockedStatic<SecurityEngineUtils> security = engineSecurity(false);
				MockedStatic<Utility> utility = noEngineLoad()) {
			assertEquals(400, uploader.deleteDatabaseImage(request).getStatus());
			utility.verify(() -> Utility.getEngine(anyString(), eq(IEngine.CATALOG_TYPE.MODEL), eq(true)), never());
		}
	}
}
