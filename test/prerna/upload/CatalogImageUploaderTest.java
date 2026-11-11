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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.apache.commons.fileupload2.core.DiskFileItem;
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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.User;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.engine.api.IEngine;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.EngineUtility;
import prerna.util.Utility;

class CatalogImageUploaderTest {

	private static final String BOUNDARY = "CatalogImageTestBoundary";
	private static boolean setBaseFolder;

	@TempDir
	Path temp;

	private User user;
	private Path spool;

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
	void prepare() {
		user = mock(User.class);
		spool = temp.resolve("spool");
	}

	private record Part(String headers, byte[] content) {
	}

	private static Part file(byte[] content) {
		return new Part("Content-Disposition: form-data; name=\"file\"; filename=\"pic\"\r\nContent-Type: image/x-test",
				content);
	}

	private static byte[] image(String format, int width, int height, boolean noisy) throws IOException {
		BufferedImage image = new BufferedImage(width, height,
				noisy ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_BYTE_BINARY);
		if (noisy) {
			Random random = new Random(7);
			for (int x = 0; x < width; x++) {
				for (int y = 0; y < height; y++) {
					image.setRGB(x, y, random.nextInt());
				}
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertTrue(ImageIO.write(image, format, out));
		return out.toByteArray();
	}

	private HttpServletRequest request(User signedIn, Part... parts) throws IOException {
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
		if (signedIn != null) {
			HttpSession session = mock(HttpSession.class);
			when(session.getAttribute(Constants.SESSION_USER)).thenReturn(signedIn);
			when(request.getSession(false)).thenReturn(session);
		}
		when(request.getMethod()).thenReturn("POST");
		when(request.getContextPath()).thenReturn("/Monolith");
		when(request.getContentType()).thenReturn("multipart/form-data; boundary=" + BOUNDARY);
		when(request.getContentLength()).thenReturn(body.length);
		when(request.getContentLengthLong()).thenReturn((long) body.length);
		when(request.getInputStream()).thenReturn(stream);
		return request;
	}

	private ServletContext context() {
		ServletContext context = mock(ServletContext.class);
		when(context.getInitParameter(AbstractUploader.TEMP_FILE_UPLOAD_KEY)).thenReturn(spool.toString());
		return context;
	}

	private DiskFileItem item(byte[] content) throws Exception {
		List<DiskFileItem> items = AbstractUploader.createUploadHandler(context())
				.parseRequest(request(null, file(content)));
		return items.get(0);
	}

	private static Map<String, String> body(Response response) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		((StreamingOutput) response.getEntity()).write(out);
		return new Gson().fromJson(out.toString(StandardCharsets.UTF_8), new TypeToken<Map<String, String>>() {
		}.getType());
	}

	private static Response rejection(DiskFileItem item) {
		return assertThrows(WebApplicationException.class, () -> CatalogImageUploader.validateImage(item))
				.getResponse();
	}

	private MockedStatic<SecurityProjectUtils> projectAccess(boolean canEdit) {
		MockedStatic<SecurityProjectUtils> security = mockStatic(SecurityProjectUtils.class);
		security.when(() -> SecurityProjectUtils.userCanEditProject(user, "p1")).thenReturn(canEdit);
		security.when(() -> SecurityProjectUtils.getProjectAliasForId("p1")).thenReturn("Proj");
		return security;
	}

	private static List<Path> listing(Path folder) throws IOException {
		if (!Files.exists(folder)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(folder)) {
			return files.toList();
		}
	}

	@Test
	void validateImageNamesTheDecodedFormat() throws Exception {
		assertEquals("png", CatalogImageUploader.validateImage(item(image("png", 4, 4, true))));
		assertEquals("jpeg", CatalogImageUploader.validateImage(item(image("jpg", 4, 4, true))));
		assertEquals("gif", CatalogImageUploader.validateImage(item(image("gif", 4, 4, true))));
	}

	@Test
	void validateImageRejectsAnEmptyFile() throws Exception {
		Response response = rejection(item(new byte[0]));

		assertEquals(400, response.getStatus());
		assertEquals("Image file is empty", body(response).get(Constants.ERROR_MESSAGE));
	}

	@Test
	void validateImageRejectsFormatsOutsidePngJpegAndGif() throws Exception {
		Response bmp = rejection(item(image("bmp", 4, 4, true)));
		Response svg = rejection(
				item("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>x()</script></svg>".getBytes(StandardCharsets.UTF_8)));

		assertEquals(415, bmp.getStatus());
		assertEquals(415, svg.getStatus());
		assertEquals("Supported image formats are PNG, JPEG, and GIF", body(svg).get(Constants.ERROR_MESSAGE));
	}

	@Test
	void validateImageRejectsMoreThanTwentyFiveMillionPixels() throws Exception {
		Response response = rejection(item(image("png", 5001, 5000, false)));

		assertEquals(400, response.getStatus());
		assertEquals("Image must not exceed 25 million pixels", body(response).get(Constants.ERROR_MESSAGE));
	}

	@Test
	void validateImageRejectsTruncatedImageData() throws Exception {
		byte[] png = image("png", 64, 64, true);

		Response response = rejection(item(Arrays.copyOf(png, png.length / 2)));

		assertEquals(400, response.getStatus());
		assertEquals("Invalid image data", body(response).get(Constants.ERROR_MESSAGE));
	}

	@Test
	void replaceImageSwapsInTheNewFormatAndRemovesTheOthers() throws Exception {
		Path folder = Files.createDirectories(temp.resolve("version"));
		Files.writeString(folder.resolve("image.png"), "old png");
		Files.writeString(folder.resolve("image.svg"), "old svg");
		Files.writeString(folder.resolve("image.txt"), "not an image");
		Files.writeString(folder.resolve("other.png"), "other basename");
		byte[] jpeg = image("jpg", 4, 4, true);

		List<Path> removed = CatalogImageUploader.replaceImage(folder, "image", item(jpeg), "jpeg");

		assertArrayEquals(jpeg, Files.readAllBytes(folder.resolve("image.jpeg")));
		assertEquals(List.of(folder.resolve("image.png"), folder.resolve("image.svg")), removed);
		assertTrue(Files.exists(folder.resolve("image.txt")));
		assertTrue(Files.exists(folder.resolve("other.png")));
		assertFalse(listing(folder).stream().anyMatch(p -> p.getFileName().toString().endsWith(".tmp")));
	}

	@Test
	void replaceImageOverwritesTheSameFormatInPlace() throws Exception {
		Path folder = Files.createDirectories(temp.resolve("version"));
		Files.writeString(folder.resolve("p1.png"), "old");
		byte[] png = image("png", 4, 4, true);

		List<Path> removed = CatalogImageUploader.replaceImage(folder, "p1", item(png), "png");

		assertEquals(List.of(), removed);
		assertArrayEquals(png, Files.readAllBytes(folder.resolve("p1.png")));
	}

	@Test
	void uploadRequiresASignedInUser() throws Exception {
		Response response = CatalogImageUploader.upload(context(), request(null, file(image("png", 4, 4, true))), "p1",
				true);

		assertEquals(401, response.getStatus());
	}

	@Test
	void uploadRejectsAnIdThatIsNotOnePathSegment() throws Exception {
		Response response = CatalogImageUploader.upload(context(), request(user, file(image("png", 4, 4, true))), "..",
				false);

		assertEquals(400, response.getStatus());
		assertEquals("Invalid engine id", body(response).get(Constants.ERROR_MESSAGE));
	}

	@Test
	void uploadRequiresEditAccessBeforeReadingTheBody() throws Exception {
		HttpServletRequest request = request(user, file(image("png", 4, 4, true)));
		try (MockedStatic<SecurityProjectUtils> security = projectAccess(false)) {
			Response response = CatalogImageUploader.upload(context(), request, "p1", true);

			assertEquals(403, response.getStatus());
		}
		assertFalse(request.getInputStream().isFinished());
	}

	@Test
	void uploadRequiresAMultipartBody() throws Exception {
		HttpServletRequest request = request(user, file(image("png", 4, 4, true)));
		when(request.getContentType()).thenReturn("application/json");
		try (MockedStatic<SecurityProjectUtils> security = projectAccess(true)) {
			assertEquals(415, CatalogImageUploader.upload(context(), request, "p1", true).getStatus());
		}
	}

	@Test
	void uploadAcceptsExactlyOneFilePart() throws Exception {
		Part form = new Part("Content-Disposition: form-data; name=\"file\"", "text".getBytes(StandardCharsets.UTF_8));
		byte[] png = image("png", 4, 4, true);
		try (MockedStatic<SecurityProjectUtils> security = projectAccess(true)) {
			Response formField = CatalogImageUploader.upload(context(), request(user, form), "p1", true);
			Response twoFiles = CatalogImageUploader.upload(context(), request(user, file(png), file(png)), "p1", true);

			assertEquals(400, formField.getStatus());
			assertEquals("Provide exactly one image in the 'file' field", body(formField).get(Constants.ERROR_MESSAGE));
			assertEquals(413, twoFiles.getStatus());
		}
	}

	@Test
	void uploadStoresAProjectImageAndRemovesTheSpooledPart() throws Exception {
		Path version = temp.resolve("project").resolve("version");
		byte[] png = image("png", 120, 120, true);
		try (MockedStatic<SecurityProjectUtils> security = projectAccess(true);
				MockedStatic<Utility> utility = mockStatic(Utility.class,
						inv -> "getProject".equals(inv.getMethod().getName()) ? null : inv.callRealMethod());
				MockedStatic<EngineUtility> engines = mockStatic(EngineUtility.class)) {
			engines.when(() -> EngineUtility.getSpecificEngineVersionFolder(IEngine.CATALOG_TYPE.PROJECT, "p1", "Proj"))
					.thenReturn(version.toString());

			Response response = CatalogImageUploader.upload(context(), request(user, file(png)), "p1", true);

			assertEquals(200, response.getStatus());
			assertEquals(Map.of("message", "Successfully updated image", "id", "p1", "name", "Proj", "imageUrl",
					"/Monolith/api/project-p1/projectImage/download", "contentType", "image/png"), body(response));
			assertArrayEquals(png, Files.readAllBytes(version.resolve("image.png")));
			utility.verify(() -> Utility.getProject("p1"));
		}
		assertEquals(List.of(), listing(spool));
	}

	@Test
	void uploadStoresAnEngineImageUnderItsCatalogType() throws Exception {
		Path version = temp.resolve("model").resolve("version");
		Files.createDirectories(version);
		Files.writeString(version.resolve("image.gif"), "old");
		byte[] jpeg = image("jpg", 8, 8, true);
		try (MockedStatic<SecurityEngineUtils> security = mockStatic(SecurityEngineUtils.class);
				MockedStatic<Utility> utility = mockStatic(Utility.class,
						inv -> "getEngine".equals(inv.getMethod().getName()) ? null : inv.callRealMethod());
				MockedStatic<EngineUtility> engines = mockStatic(EngineUtility.class)) {
			security.when(() -> SecurityEngineUtils.userCanEditEngine(user, "e1")).thenReturn(true);
			security.when(() -> SecurityEngineUtils.getEngineType("e1")).thenReturn(IEngine.CATALOG_TYPE.MODEL);
			security.when(() -> SecurityEngineUtils.getEngineAliasForId("e1")).thenReturn("Model");
			engines.when(() -> EngineUtility.getSpecificEngineVersionFolder(IEngine.CATALOG_TYPE.MODEL, "e1", "Model"))
					.thenReturn(version.toString());

			Response response = CatalogImageUploader.upload(context(), request(user, file(jpeg)), "e1", false);

			assertEquals(200, response.getStatus());
			assertEquals("/Monolith/api/e-e1/image/download", body(response).get("imageUrl"));
			assertEquals("image/jpeg", body(response).get("contentType"));
			assertArrayEquals(jpeg, Files.readAllBytes(version.resolve("image.jpeg")));
			assertFalse(Files.exists(version.resolve("image.gif")));
			utility.verify(() -> Utility.getEngine("e1", IEngine.CATALOG_TYPE.MODEL, true));
		}
	}
}
