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
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.commons.fileupload2.core.DiskFileItem;
import org.apache.commons.fileupload2.core.FileUploadSizeException;
import org.apache.commons.fileupload2.jakarta.servlet6.JakartaServletDiskFileUpload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;

class AbstractUploaderTest {

	private static final String BOUNDARY = "UploaderTestBoundary";

	@TempDir
	Path temp;

	private final AbstractUploader uploader = new AbstractUploader() {
		private static final long serialVersionUID = 1L;
	};

	private record Part(String headers, byte[] content) {
	}

	private static Part part(String headers, String content) {
		return new Part(headers, content.getBytes(StandardCharsets.UTF_8));
	}

	private static byte[] multipart(Part... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (Part part : parts) {
			out.writeBytes(("--" + BOUNDARY + "\r\n" + part.headers() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
			out.writeBytes(part.content());
			out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
		}
		out.writeBytes(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
		return out.toByteArray();
	}

	private static HttpServletRequest request(byte[] body) throws IOException {
		HttpServletRequest request = mock(HttpServletRequest.class);
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
		when(request.getMethod()).thenReturn("POST");
		when(request.getContentType()).thenReturn("multipart/form-data; boundary=" + BOUNDARY);
		when(request.getContentLength()).thenReturn(body.length);
		when(request.getContentLengthLong()).thenReturn((long) body.length);
		when(request.getInputStream()).thenReturn(stream);
		return request;
	}

	private ServletContext context(Path tempUploads) {
		ServletContext context = mock(ServletContext.class);
		when(context.getInitParameter(AbstractUploader.TEMP_FILE_UPLOAD_KEY))
				.thenReturn(tempUploads == null ? null : tempUploads.toString());
		return context;
	}

	private DiskFileItem parseOne(String headers, byte[] content) throws Exception {
		JakartaServletDiskFileUpload upload = AbstractUploader.createUploadHandler(context(temp.resolve("tmp")));
		List<DiskFileItem> items = upload.parseRequest(request(multipart(new Part(headers, content))));
		assertEquals(1, items.size());
		return items.get(0);
	}

	@Test
	void normalizeAndCreatePathCreatesTheFolderWithATrailingSeparator() {
		String created = AbstractUploader.normalizeAndCreatePath(temp.resolve("a").resolve("b").toString());

		assertTrue(created.endsWith(AbstractUploader.DIR_SEPARATOR));
		assertTrue(Files.isDirectory(temp.resolve("a").resolve("b")));
	}

	@Test
	void normalizeAndCreatePathResolvesDotSegmentsBeforeCreating() {
		String created = AbstractUploader.normalizeAndCreatePath(temp + "/x/../y");

		assertEquals(temp.resolve("y") + AbstractUploader.DIR_SEPARATOR, created);
		assertTrue(Files.isDirectory(temp.resolve("y")));
		assertFalse(Files.exists(temp.resolve("x")));
	}

	@Test
	void normalizeAndCreatePathRejectsAPathThatClimbsAboveItsRoot() {
		assertThrows(IllegalArgumentException.class, () -> AbstractUploader.normalizeAndCreatePath("../outside"));
	}

	@Test
	void convertToStringReadsTheWholeReader() throws Exception {
		String text = "line one\nline two\n" + "x".repeat(10_000);

		assertEquals(text, AbstractUploader.convertToString(new StringReader(text)));
	}

	@Test
	void writeFileStoresDetectedTextAsUtf8() throws Exception {
		byte[] utf16 = "\uFEFFid,name\n1,caf\u00e9\n".getBytes(StandardCharsets.UTF_16LE);
		DiskFileItem item = parseOne(
				"Content-Disposition: form-data; name=\"file\"; filename=\"data.csv\"\r\nContent-Type: text/csv", utf16);
		File target = temp.resolve("data.csv").toFile();

		uploader.writeFile(item, target);

		assertEquals("\uFEFFid,name\n1,caf\u00e9\n", Files.readString(target.toPath(), StandardCharsets.UTF_8));
	}

	@Test
	void writeFileCopiesBinaryContentByteForByte() throws Exception {
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", png);
		DiskFileItem item = parseOne(
				"Content-Disposition: form-data; name=\"file\"; filename=\"pic.png\"\r\nContent-Type: image/png",
				png.toByteArray());
		File target = temp.resolve("pic.png").toFile();

		uploader.writeFile(item, target);

		assertArrayEquals(png.toByteArray(), Files.readAllBytes(target.toPath()));
	}

	@Test
	void deleteFilesFromServerRemovesEachListedFile() throws Exception {
		Path first = Files.writeString(temp.resolve("first.txt"), "1");
		Path second = Files.writeString(temp.resolve("second.txt"), "2");

		uploader.deleteFilesFromServer(
				new String[] { first.toString(), second.toString(), temp.resolve("missing.txt").toString() });

		assertFalse(Files.exists(first));
		assertFalse(Files.exists(second));
	}

	@Test
	void createUploadHandlerSpoolsLargePartsIntoTheConfiguredTempFolder() throws Exception {
		Path spool = temp.resolve("spool").resolve("nested");
		JakartaServletDiskFileUpload upload = AbstractUploader.createUploadHandler(context(spool));
		String large = "y".repeat(AbstractUploader.maxMemSize + 1);

		List<DiskFileItem> items = upload.parseRequest(request(multipart(part(
				"Content-Disposition: form-data; name=\"file\"; filename=\"big.txt\"\r\nContent-Type: text/plain",
				large))));

		assertEquals(StandardCharsets.UTF_8, upload.getHeaderCharset());
		assertFalse(items.get(0).isInMemory());
		assertTrue(items.get(0).getPath().toAbsolutePath().startsWith(spool.toAbsolutePath()));
		items.get(0).delete();
	}

	@Test
	void createUploadHandlerFallsBackToTheJvmTempFolder() throws Exception {
		JakartaServletDiskFileUpload upload = AbstractUploader.createUploadHandler(context(null));

		assertEquals(StandardCharsets.UTF_8, upload.getHeaderCharset());
	}

	@Test
	void processRequestReturnsEveryPartInOrder() throws Exception {
		byte[] body = multipart(part("Content-Disposition: form-data; name=\"engineId\"", "abc"),
				part("Content-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\nContent-Type: text/plain",
						"hello"));

		List<DiskFileItem> items = uploader.processRequest(context(temp.resolve("tmp")), request(body),
				"insight-that-is-not-open");

		assertEquals(2, items.size());
		assertTrue(items.get(0).isFormField());
		assertEquals("engineId", items.get(0).getFieldName());
		assertEquals("abc", items.get(0).getString());
		assertFalse(items.get(1).isFormField());
		assertEquals("a.txt", items.get(1).getName());
		assertEquals("hello", items.get(1).getString());
	}

	@Test
	void processRequestRejectsABodyOverTheRequestLimit() throws Exception {
		HttpServletRequest request = request(multipart(part(
				"Content-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\nContent-Type: text/plain",
				"z".repeat(500))));
		int limit = AbstractUploader.maxFileSize;
		AbstractUploader.maxFileSize = 200;
		try {
			assertThrows(FileUploadSizeException.class,
					() -> uploader.processRequest(context(temp.resolve("tmp")), request, null));
		} finally {
			AbstractUploader.maxFileSize = limit;
		}
	}
}
