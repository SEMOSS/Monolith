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
package prerna.web.conf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import prerna.auth.User;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.project.api.IProject;
import prerna.usertracking.UserAuditTrailUtils;
import prerna.util.Constants;
import prerna.util.Utility;

class PublicHomeCheckFilterTest {

	private static final String PORTAL_URL = "http://localhost:8080/Monolith/public_home/p1/portals/";
	private static final String CONTENT = "0123456789";

	@TempDir
	Path root;

	private Path portals;
	private IProject project;
	private ServletContext context;
	private HttpServletResponse response;
	private ByteArrayOutputStream body;
	private StringWriter text;
	private boolean global;
	private boolean canView;

	/**
	 * Collects what the filter streams to the response.
	 */
	private static final class CapturingOutputStream extends ServletOutputStream {
		private final ByteArrayOutputStream target;

		CapturingOutputStream(ByteArrayOutputStream target) {
			this.target = target;
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setWriteListener(WriteListener listener) {
			// writes are synchronous
		}

		@Override
		public void write(int b) {
			target.write(b);
		}
	}

	@BeforeEach
	void setUp() throws Exception {
		portals = Files.createDirectories(root.resolve("public_home/p1/portals"));
		Files.writeString(portals.resolve("index.html"), CONTENT);
		project = mock(IProject.class);
		when(project.getProjectId()).thenReturn("p1");
		when(project.getProjectType()).thenReturn(IProject.PROJECT_TYPE.CODE);
		context = mock(ServletContext.class);
		when(context.getContextPath()).thenReturn("/Monolith");
		when(context.getRealPath(File.separator)).thenReturn(root.toString());
		when(context.getMimeType(anyString())).thenAnswer(
				i -> ((String) i.getArgument(0)).endsWith(".html") ? "text/html" : "application/octet-stream");
		response = mock(HttpServletResponse.class);
		body = new ByteArrayOutputStream();
		text = new StringWriter();
		when(response.getOutputStream()).thenReturn(new CapturingOutputStream(body));
		when(response.getWriter()).thenReturn(new PrintWriter(text));
		global = true;
		canView = false;
	}

	private HttpServletRequest request(String url, HttpSession session) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getServletContext()).thenReturn(context);
		when(request.getRequestURL()).thenReturn(new StringBuffer(url));
		when(request.getSession(false)).thenReturn(session);
		when(request.getDateHeader("If-Modified-Since")).thenReturn(-1L);
		return request;
	}

	private MockedStatic<UserAuditTrailUtils> run(HttpServletRequest request) throws Exception {
		MockedStatic<UserAuditTrailUtils> audit = mockStatic(UserAuditTrailUtils.class);
		try (MockedStatic<Utility> utility = mockStatic(Utility.class);
				MockedStatic<SecurityProjectUtils> security = mockStatic(SecurityProjectUtils.class)) {
			utility.when(Utility::getPublicHomeFolder).thenReturn("public_home");
			utility.when(() -> Utility.getProject("p1")).thenReturn(project);
			security.when(() -> SecurityProjectUtils.projectIsGlobal(anyString())).thenReturn(global);
			security.when(() -> SecurityProjectUtils.userCanViewProject(any(), eq("p1")))
					.thenReturn(canView);
			new PublicHomeCheckFilter().doFilter(request, response, mock(FilterChain.class));
		} catch (Exception | Error e) {
			audit.close();
			throw e;
		}
		return audit;
	}

	private void serve(HttpServletRequest request) throws Exception {
		run(request).close();
	}

	private String bodyText() {
		return body.toString(StandardCharsets.UTF_8);
	}

	@Test
	void servesTheRequestedPortalFile() throws Exception {
		serve(request(PORTAL_URL + "index.html", null));

		verify(response).setHeader("Cache-Control", "private, no-store, no-cache, must-revalidate");
		verify(response).setContentType("text/html; charset=UTF-8");
		verify(response).setHeader("Accept-Ranges", "bytes");
		verify(response).setContentLengthLong(10);
		verify(response).setStatus(200);
		assertEquals(CONTENT, bodyText());
	}

	@Test
	void servesIndexHtmlForTheBarePortalPath() throws Exception {
		serve(request(PORTAL_URL, null));

		assertEquals(CONTENT, bodyText());
	}

	@Test
	void servesTheBlockFileForABlocksProject() throws Exception {
		when(project.getProjectType()).thenReturn(IProject.PROJECT_TYPE.BLOCKS);
		Files.writeString(portals.resolve(IProject.BLOCK_FILE_NAME), "{\"blocks\":[]}");

		serve(request(PORTAL_URL, null));

		assertEquals("{\"blocks\":[]}", bodyText());
	}

	@Test
	void servesTheIndexOfAFolder() throws Exception {
		Files.createDirectories(portals.resolve("docs"));
		Files.writeString(portals.resolve("docs/index.html"), "docs");

		serve(request(PORTAL_URL + "docs", null));

		assertEquals("docs", bodyText());
	}

	@Test
	void leavesBinaryTypesWithoutACharset() throws Exception {
		Files.write(portals.resolve("logo.bin"), new byte[] { 1, 2, 3 });

		serve(request(PORTAL_URL + "logo.bin", null));

		verify(response).setContentType("application/octet-stream");
	}

	@Test
	void publishesFirstWhenTheProjectNeedsIt() throws Exception {
		when(project.requirePublish(false)).thenReturn(true);
		when(project.publish(root.toString() + "/public_home", true)).thenReturn(true);

		serve(request(PORTAL_URL + "index.html", null));

		verify(project).publish(root.toString() + "/public_home", true);
		assertEquals(CONTENT, bodyText());
	}

	@Test
	void servesTheLastPublishWhenARepublishFails() throws Exception {
		when(project.requirePublish(false)).thenReturn(true);

		serve(request(PORTAL_URL + "index.html", null));

		assertEquals(CONTENT, bodyText());
	}

	@Test
	void answersNotModifiedForAnUnchangedFile() throws Exception {
		HttpServletRequest request = request(PORTAL_URL + "index.html", null);
		long modified = portals.resolve("index.html").toFile().lastModified();
		when(request.getDateHeader("If-Modified-Since")).thenReturn(modified + 1000);

		serve(request);

		verify(response).setStatus(304);
		assertEquals("", bodyText());
	}

	@Test
	void servesASuffixRange() throws Exception {
		HttpServletRequest request = request(PORTAL_URL + "index.html", null);
		when(request.getHeader("Range")).thenReturn("bytes=-3");

		serve(request);

		verify(response).setStatus(206);
		verify(response).setHeader("Content-Range", "bytes 7-9/10");
		verify(response).setContentLengthLong(3);
		assertEquals("789", bodyText());
	}

	@Test
	void servesAnOpenEndedRangeAndClampsAnEndPastTheFile() throws Exception {
		HttpServletRequest openEnded = request(PORTAL_URL + "index.html", null);
		when(openEnded.getHeader("Range")).thenReturn("bytes=2-");
		serve(openEnded);
		assertEquals("23456789", bodyText());

		body.reset();
		HttpServletRequest pastEnd = request(PORTAL_URL + "index.html", null);
		when(pastEnd.getHeader("Range")).thenReturn("bytes=3-100");
		serve(pastEnd);
		assertEquals("3456789", bodyText());
	}

	@Test
	void rejectsARangeItCannotSatisfy() throws Exception {
		for (String range : new String[] { "bytes=20-30", "bytes=-0", "bytes=5-2", "bytes=0-1,4-5", "bytes=x-",
				"bytes=-" }) {
			setUp();
			HttpServletRequest request = request(PORTAL_URL + "index.html", null);
			when(request.getHeader("Range")).thenReturn(range);

			serve(request);

			verify(response).setHeader("Content-Range", "bytes */10");
			verify(response).sendError(416);
			assertEquals("", bodyText(), range);
		}
	}

	@Test
	void handsALargeFileToTheConnector() throws Exception {
		byte[] large = new byte[64 * 1024];
		Files.write(portals.resolve("big.bin"), large);
		HttpServletRequest request = request(PORTAL_URL + "big.bin", null);
		when(request.getAttribute("org.apache.tomcat.sendfile.support")).thenReturn(Boolean.TRUE);

		serve(request);

		verify(request).setAttribute("org.apache.tomcat.sendfile.filename",
				portals.resolve("big.bin").toFile().getCanonicalPath());
		verify(request).setAttribute("org.apache.tomcat.sendfile.start", 0L);
		verify(request).setAttribute("org.apache.tomcat.sendfile.end", (long) large.length);
		verify(response, never()).getOutputStream();
	}

	@Test
	void streamsASmallFileItselfEvenWhenTheConnectorSupportsSendfile() throws Exception {
		HttpServletRequest request = request(PORTAL_URL + "index.html", null);
		when(request.getAttribute("org.apache.tomcat.sendfile.support")).thenReturn(Boolean.TRUE);

		serve(request);

		verify(request, never()).setAttribute(eq("org.apache.tomcat.sendfile.filename"), anyString());
		assertEquals(CONTENT, bodyText());
	}

	@Test
	void refusesAPathThatClimbsOutOfThePortal() throws Exception {
		Files.writeString(root.resolve("public_home/p1/secret.txt"), "secret");

		serve(request(PORTAL_URL + "../secret.txt", null));

		verify(response).setStatus(404);
		assertTrue(text.toString().contains("Could not find the requested resource"));
		assertEquals("", bodyText());
	}

	@Test
	void answersNotFoundForAMissingFile() throws Exception {
		serve(request(PORTAL_URL + "missing.html", null));

		verify(response).setStatus(404);
		assertTrue(text.toString().contains("Could not find the requested resource"));
	}

	@Test
	void forbidsAPrivateProjectWithoutASession() throws Exception {
		global = false;

		try (MockedStatic<UserAuditTrailUtils> audit = run(request(PORTAL_URL + "index.html", null))) {
			audit.verify(() -> UserAuditTrailUtils.recordFailure(isNull(),
					any(), any(), eq("p1"),
					any(Throwable.class), eq(403)));
		}

		verify(response).setStatus(403);
		assertTrue(text.toString().contains("You must be logged in to access this project."));
		assertEquals("", bodyText());
	}

	@Test
	void forbidsAPrivateProjectToAUserWithoutAccess() throws Exception {
		global = false;
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(mock(User.class));

		serve(request(PORTAL_URL + "index.html", session));

		verify(response).setStatus(403);
		assertTrue(text.toString().contains("You do not have access to this project."));
		assertEquals("", bodyText());
	}

	@Test
	void servesAPrivateProjectToAUserWithAccess() throws Exception {
		global = false;
		canView = true;
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(mock(User.class));

		serve(request(PORTAL_URL + "index.html", session));

		assertEquals(CONTENT, bodyText());
	}

	@Test
	void reportsAProjectThatCannotBeLoaded() throws Exception {
		HttpServletRequest request = request("http://localhost:8080/Monolith/public_home/p2/portals/", null);

		serve(request);

		verify(response).setContentType("text/plain; charset=UTF-8");
		assertEquals("Unable to load the requested project", text.toString());
	}

	@Test
	void reportsAPortalUrlWithoutAProjectId() throws Exception {
		for (String url : new String[] { "http://localhost:8080/Monolith/public_home",
				"http://localhost:8080/Monolith/public_home/" }) {
			text.getBuffer().setLength(0);

			serve(request(url, null));

			assertEquals("Improper portal URL - unable to find project ID for the portal", text.toString());
		}
		verify(response, never()).setContentLengthLong(anyLong());
	}
}
