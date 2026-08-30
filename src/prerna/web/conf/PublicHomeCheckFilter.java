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

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tika.Tika;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import prerna.auth.User;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.project.api.IProject;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

public class PublicHomeCheckFilter implements Filter {

	private static final Logger classLogger = LogManager.getLogger(PublicHomeCheckFilter.class);

	// larger than the 8KB this used to read in, which measured faster for the file
	// sizes a portal serves
	private static final int COPY_BUFFER_SIZE = 64 * 1024;

	// Connector attributes for handing a file off to be streamed by the kernel.
	// Named here rather than imported so this does not compile against Tomcat
	// internals, and so it simply degrades on a container that does not set them.
	private static final String SENDFILE_SUPPORTED_ATTR = "org.apache.tomcat.sendfile.support";
	private static final String SENDFILE_FILENAME_ATTR = "org.apache.tomcat.sendfile.filename";
	private static final String SENDFILE_FILE_START_ATTR = "org.apache.tomcat.sendfile.start";
	private static final String SENDFILE_FILE_END_ATTR = "org.apache.tomcat.sendfile.end";

	// below this the syscall costs more than it saves, matching the threshold
	// Tomcat's DefaultServlet uses
	private static final long SENDFILE_MIN_SIZE = 48 * 1024;

	// building a Tika loads its mime registry, so it is built once rather than per
	// request. Tika is documented as thread safe for detection
	private static final Tika TIKA = new Tika();

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
			throws IOException, ServletException {

		HttpServletRequest request = (HttpServletRequest) arg0;
		HttpServletResponse response = (HttpServletResponse) arg1;

		// Set cache control headers
		response.setHeader("Cache-Control", "private, no-store, no-cache, must-revalidate");

		ServletContext context = arg0.getServletContext();
		HttpSession session = request.getSession(false);
		String fullUrl = WebUtility.cleanHttpResponse(request.getRequestURL().toString());

		String publicHomeFolder = Utility.getPublicHomeFolder();
		String contextPath = context.getContextPath();
		String contextPathPublicHome = contextPath + "/" + publicHomeFolder;
		String realPath = context.getRealPath(File.separator);

		// Extract project ID
		String projectId = extractProjectId(fullUrl, contextPathPublicHome, response);
		if (projectId == null) {
			return; // Error already written to response
		}

		// Security checks
		if (!performSecurityChecks(projectId, session, request, response)) {
			return; // Access denied
		}

		// Load project
		IProject project = Utility.getProject(projectId);
		if (project == null) {
			response.setContentType("text/plain; charset=UTF-8");
			response.getWriter().write("Unable to load project with id='" + projectId + "'");
			return;
		}

		// Ensure public home directory exists
		File publicHomeDir = new File(realPath + "/" + publicHomeFolder);
		if (!publicHomeDir.exists()) {
			publicHomeDir.mkdir();
		}

		// Check if publish is required
		if (!project.requirePublish(false)) {
			handleExistingPublish(project, fullUrl, publicHomeFolder, request, response, arg2, realPath, context);
			return;
		}

		// Publish and serve
		boolean successfulPublish = project.publish(realPath + "/" + publicHomeFolder, true);
		if (successfulPublish) {
			servePublishedFile(project, fullUrl, realPath, publicHomeFolder, context, request, response);
			return;
		}

		handleExistingPublish(project, fullUrl, publicHomeFolder, request, response, arg2, realPath, context);
	}

	/**
	 * Extract and validate project ID from URL
	 * 
	 * @param fullUrl
	 * @param contextPathPublicHome
	 * @param response
	 * @return
	 * @throws IOException
	 */
	private String extractProjectId(String fullUrl, String contextPathPublicHome, HttpServletResponse response)
			throws IOException {
		int locForPath = fullUrl.indexOf(contextPathPublicHome);
		int locLength = contextPathPublicHome.length();
		int subStringIndex = locForPath + locLength + 1;

		if (subStringIndex > fullUrl.length()) {
			response.setContentType("text/plain; charset=UTF-8");
			response.getWriter().write("Improper portal URL - unable to find project ID for the portal");
			return null;
		}

		String projectId = fullUrl.substring(locForPath + locLength + 1);
		if (projectId.contains("/")) {
			projectId = projectId.substring(0, projectId.indexOf("/"));
		}

		if (projectId == null || projectId.isEmpty()) {
			response.setContentType("text/plain; charset=UTF-8");
			response.getWriter().write("Improper portal URL - unable to find project ID for the portal");
			return null;
		}

		return WebUtility.inputSanitizer(projectId);
	}

	/**
	 * Perform security checks for project access
	 * 
	 * @param projectId
	 * @param session
	 * @param response
	 * @return
	 * @throws IOException
	 */
	private boolean performSecurityChecks(String projectId, HttpSession session, HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		if (!SecurityProjectUtils.projectIsGlobal(projectId)) {
			User user = session == null ? null : (User) session.getAttribute(Constants.SESSION_USER);
			if (user == null) {
				sendError(request, response, HttpServletResponse.SC_FORBIDDEN,
						"You must be logged in to access this project.");
				return false;
			}
			if (!SecurityProjectUtils.userCanViewProject(user, projectId)) {
				sendError(request, response, HttpServletResponse.SC_FORBIDDEN,
						"You do not have access to this project.");
				return false;
			}
		}
		return true;
	}

	/**
	 * Handle requests for already published content
	 * 
	 * @param project
	 * @param fullUrl
	 * @param publicHomeFolder
	 * @param request
	 * @param response
	 * @param chain
	 * @throws IOException
	 * @throws ServletException
	 */
	private void handleExistingPublish(IProject project, String fullUrl, String publicHomeFolder,
			HttpServletRequest request, HttpServletResponse response, FilterChain chain, String realPath,
			ServletContext context) throws IOException, ServletException {

		// Directly serve the file instead of passing to chain
		servePublishedFile(project, fullUrl, realPath, publicHomeFolder, context, request, response);
	}

	/**
	 * Serve a published file with proper HTTP semantics
	 * 
	 * @param project
	 * @param fullUrl
	 * @param realPath
	 * @param publicHomeFolder
	 * @param context
	 * @param request
	 * @param response
	 * @throws IOException
	 */
	private void servePublishedFile(IProject project, String fullUrl, String realPath, String publicHomeFolder,
			ServletContext context, HttpServletRequest request, HttpServletResponse response) throws IOException {

		String projectId = project.getProjectId();
		String thisPortalsPath = "/" + publicHomeFolder + "/" + projectId + "/" + Constants.PORTALS_FOLDER + "/";
		String fileToPull = realPath + thisPortalsPath;

		if (!fileToPull.endsWith("/")) {
			fileToPull += "/";
		}

		// Determine which file to serve
		int index = fullUrl.indexOf(thisPortalsPath) + thisPortalsPath.length();
		String specificFile = null;

		if (index < fullUrl.length()) {
			specificFile = fullUrl.substring(fullUrl.indexOf(thisPortalsPath) + thisPortalsPath.length());
			fileToPull += specificFile;
		} else {
			// Default file based on project type
			if (project.getProjectType() == IProject.PROJECT_TYPE.BLOCKS) {
				fileToPull += IProject.BLOCK_FILE_NAME;
			} else {
				fileToPull += "index.html";
			}
		}

		File file = new File(fileToPull);

		// Validate file exists
		if (!file.exists()) {
			sendError(request, response, 404,
					"Could not find file at path " + thisPortalsPath + (specificFile != null ? specificFile : ""));
			return;
		}

		// Handle directory requests
		if (file.isDirectory()) {
			file = new File(file.getAbsolutePath() + "/index.html");
			if (!file.exists()) {
				sendError(request, response, 404, "Could not find index.html in directory " + thisPortalsPath
						+ (specificFile != null ? specificFile : ""));
				return;
			}
		}

		// Security: Prevent directory traversal
		Path filePath = file.toPath().toRealPath();
		Path basePath = new File(realPath + thisPortalsPath).toPath().toRealPath();
		if (!filePath.startsWith(basePath)) {
			sendError(request, response, 403, "Access denied");
			return;
		}

		// Serve the file with proper headers
		serveFileWithHeaders(file, context, request, response);
	}

	/**
	 * Determine content type using cascading methods: ServletContext,
	 * Files.probeContentType, Tika
	 * 
	 * @param file
	 * @param context
	 * @return
	 */
	private String determineContentType(File file, ServletContext context) {
		String contentType = null;

		// Try ServletContext (uses Tomcat's mime mappings)
		contentType = context.getMimeType(file.getAbsolutePath());
		if (contentType != null && !contentType.isEmpty()) {
			return contentType;
		}

		// Try Java NIO Files.probeContentType
		try {
			contentType = Files.probeContentType(Paths.get(file.getAbsolutePath()));
			if (contentType != null && !contentType.isEmpty()) {
				return contentType;
			}
		} catch (IOException e) {
			classLogger.debug("Files.probeContentType failed for {}", file.getName(), e);
		}

		// Try Apache Tika as last resort
		try {
			contentType = TIKA.detect(file);
			if (contentType != null && !contentType.isEmpty()) {
				return contentType;
			}
		} catch (IOException e) {
			classLogger.error("Tika content type detection failed", e);
		}

		// Default fallback
		return "application/octet-stream";
	}

	/**
	 * Adds an explicit UTF-8 charset to content types that carry text.
	 *
	 * The bytes are written straight through, so the charset is purely what the
	 * browser is told. Without it a text/* or JSON/XML/JavaScript response is
	 * decoded using the browser's own default, which mangles any character outside
	 * ascii. Types that already name a charset are left alone, and binary types
	 * never get one.
	 *
	 * @param contentType the detected type
	 * @return the type to put on the response
	 */
	private String withCharset(String contentType) {
		if (contentType == null || contentType.isEmpty()) {
			return "application/octet-stream";
		}
		String lower = contentType.toLowerCase();
		if (lower.contains("charset=")) {
			return contentType;
		}
		boolean isText = lower.startsWith("text/") || lower.endsWith("+json") || lower.endsWith("+xml")
				|| lower.equals("application/json") || lower.equals("application/xml")
				|| lower.equals("application/javascript") || lower.equals("application/ecmascript")
				|| lower.equals("image/svg+xml");
		return isText ? contentType + "; charset=UTF-8" : contentType;
	}

	/**
	 * Serve file with proper HTTP headers including caching, content type, and
	 * range support
	 * 
	 * @param file
	 * @param context
	 * @param request
	 * @param response
	 * @throws IOException
	 */
	private void serveFileWithHeaders(File file, ServletContext context, HttpServletRequest request,
			HttpServletResponse response) throws IOException {

		if (file == null || !file.exists() || !file.isFile()) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		// HTTP Date headers drop milliseconds; normalize to avoid subtle caching bugs
		long lastModified = (file.lastModified() / 1000) * 1000;
		long fileSize = file.length();

		// Check Caching Headers
		long ifModifiedSince = request.getDateHeader("If-Modified-Since");
		if (ifModifiedSince != -1 && lastModified <= ifModifiedSince) {
			response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
			return;
		}

		// Apply fallback cascading content type
		String contentType = withCharset(determineContentType(file, context));
		response.setContentType(contentType);

		response.setDateHeader("Last-Modified", lastModified);
		response.setHeader("Accept-Ranges", "bytes");

		// Process HTTP Range Header
		String rangeHeader = request.getHeader("Range");
		boolean isRangeRequest = rangeHeader != null && rangeHeader.startsWith("bytes=");

		if (isRangeRequest) {
			servePartialContent(file, fileSize, rangeHeader, request, response);
		} else {
			response.setContentLengthLong(fileSize);
			response.setStatus(HttpServletResponse.SC_OK);

			if (trySendfile(request, file, 0, fileSize)) {
				return;
			}
			try (FileChannel fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
					ServletOutputStream out = response.getOutputStream()) {
				copyRange(fileChannel, out, 0, fileSize);
			}
		}
	}

	/**
	 * Serve partial content for HTTP Range requests (supports resumable downloads
	 * and streaming)
	 * 
	 * @param file
	 * @param fileSize
	 * @param rangeHeader
	 * @param response
	 * @throws IOException
	 */
	private void servePartialContent(File file, long fileSize, String rangeHeader, HttpServletRequest request,
			HttpServletResponse response) throws IOException {

		String range = rangeHeader.substring(6).trim();
		String[] parts = range.split("-", -1);

		long start = 0;
		long end = fileSize - 1;

		try {
			if (parts[0].isEmpty()) {
				// suffix spec, "-500" means the last 500 bytes rather than through
				// byte 500. This has to be checked first or the branch below claims it
				if (parts.length < 2 || parts[1].isEmpty()) {
					sendRangeNotSatisfiable(response, fileSize);
					return;
				}
				long suffixLength = Long.parseLong(parts[1]);
				if (suffixLength <= 0) {
					sendRangeNotSatisfiable(response, fileSize);
					return;
				}
				// asking for more than there is means the whole file
				start = Math.max(0, fileSize - suffixLength);
				end = fileSize - 1;
			} else {
				start = Long.parseLong(parts[0]);
				if (parts.length > 1 && !parts[1].isEmpty()) {
					end = Long.parseLong(parts[1]);
					// an end past the last byte is clamped rather than rejected
					if (end >= fileSize) {
						end = fileSize - 1;
					}
				}
			}
		} catch (NumberFormatException e) {
			sendRangeNotSatisfiable(response, fileSize);
			return;
		}

		if (start > end || start < 0 || end >= fileSize) {
			sendRangeNotSatisfiable(response, fileSize);
			return;
		}

		long contentLength = end - start + 1;

		response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
		response.setHeader("Content-Range", String.format("bytes %d-%d/%d", start, end, fileSize));
		response.setContentLengthLong(contentLength);

		if (trySendfile(request, file, start, contentLength)) {
			return;
		}
		try (FileChannel fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
				ServletOutputStream out = response.getOutputStream()) {
			copyRange(fileChannel, out, start, contentLength);
		}
	}

	/**
	 * Asks the connector to stream the file itself.
	 *
	 * When the connector takes it, the bytes go from the page cache to the socket
	 * inside the kernel and never pass through the jvm at all, which is the only
	 * real zero copy path available here. Reading the file and writing it to the
	 * servlet output stream cannot do that no matter how it is written.
	 *
	 * The connector reports whether it can: it says no over TLS, for instance,
	 * because the bytes have to be encrypted on the way out. Small files are not
	 * worth the syscall, so those are left to the normal path, the same tradeoff
	 * Tomcat's own DefaultServlet makes.
	 *
	 * Nothing may be written to the response afterwards, so callers return as soon
	 * as this reports true. Headers set before this point still apply.
	 *
	 * @param request the request, which carries the connector's answer
	 * @param file    the file to stream
	 * @param start   first byte to send
	 * @param length  how many bytes to send
	 * @return true when the connector accepted it
	 */
	private boolean trySendfile(HttpServletRequest request, File file, long start, long length) {
		if (length < SENDFILE_MIN_SIZE) {
			return false;
		}
		if (!Boolean.TRUE.equals(request.getAttribute(SENDFILE_SUPPORTED_ATTR))) {
			return false;
		}

		String canonicalPath;
		try {
			canonicalPath = file.getCanonicalPath();
		} catch (IOException e) {
			// no canonical path means no handoff, fall back to reading it here
			classLogger.debug("Unable to resolve a canonical path for {}, not using sendfile", file.getName(), e);
			return false;
		}

		request.setAttribute(SENDFILE_FILENAME_ATTR, canonicalPath);
		request.setAttribute(SENDFILE_FILE_START_ATTR, Long.valueOf(start));
		// the connector treats the end as exclusive
		request.setAttribute(SENDFILE_FILE_END_ATTR, Long.valueOf(start + length));
		return true;
	}

	/**
	 * Writes count bytes of the channel, starting at position, to the response.
	 *
	 * Reading through a heap buffer rather than FileChannel.transferTo is
	 * deliberate. transferTo only reaches the kernel's sendfile path when the
	 * destination is a socket or file channel; a ServletOutputStream wrapped by
	 * Channels.newChannel is neither, so it falls back to copying through a
	 * temporary direct buffer, which measures slower than this.
	 *
	 * @param fileChannel the open file
	 * @param out         the response stream
	 * @param position    first byte to send
	 * @param count       how many bytes to send
	 * @throws IOException if the read or write fails
	 */
	private void copyRange(FileChannel fileChannel, ServletOutputStream out, long position, long count)
			throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate(COPY_BUFFER_SIZE);
		long remaining = count;
		long offset = position;

		while (remaining > 0) {
			buffer.clear();
			buffer.limit((int) Math.min(buffer.capacity(), remaining));
			int read = fileChannel.read(buffer, offset);
			if (read <= 0) {
				break;
			}
			out.write(buffer.array(), 0, read);
			offset += read;
			remaining -= read;
		}
	}

	private void sendRangeNotSatisfiable(HttpServletResponse response, long fileSize) throws IOException {
		response.setHeader("Content-Range", "bytes */" + fileSize);
		response.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
	}

	/**
	 * Send an error response with proper formatting
	 * 
	 * @param response
	 * @param statusCode
	 * @param message
	 * @throws IOException
	 */
	private void sendError(HttpServletRequest request, HttpServletResponse response, int statusCode, String message)
			throws IOException {
		String safeMessage = WebUtility.cleanHttpResponse(message);
		String headline = statusCode == HttpServletResponse.SC_FORBIDDEN ? "Access Denied"
				: statusCode == HttpServletResponse.SC_NOT_FOUND ? "Page Not Found" : "Request Failed";
		String description = statusCode == HttpServletResponse.SC_FORBIDDEN
				? "You do not have permission to view this public home resource."
				: statusCode == HttpServletResponse.SC_NOT_FOUND ? "The requested resource could not be located."
						: "The request could not be completed.";

		StringBuilder html = new StringBuilder(2048);
		html.append("<!doctype html>");
		html.append("<html lang=\"en\"><head><meta charset=\"UTF-8\">");
		html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
		html.append("<title>").append(statusCode).append(" ").append(headline).append("</title>");
		html.append("<style>");
		html.append(
				":root{--bg:#f5f7fb;--card:#ffffff;--text:#1b2a41;--muted:#5f6c7b;--line:#d9e0ea;--accent:#14532d;--warn:#7f1d1d;--gap:6px;}");
		html.append("*{box-sizing:border-box;}");
		html.append(
				"body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:radial-gradient(circle at top,#eef4ff 0,#f5f7fb 45%,#eaf0f8 100%);font-family:\"Segoe UI\",Tahoma,Geneva,Verdana,sans-serif;color:var(--text);padding:24px;}");
		html.append(
				".card{width:min(760px,100%);background:var(--card);border:1px solid var(--line);border-radius:16px;box-shadow:0 18px 40px rgba(16,24,40,.08);overflow:hidden;}");
		html.append(
				".header{display:flex;gap:14px;align-items:center;padding:20px 24px;border-bottom:1px solid var(--line);background:linear-gradient(180deg,#ffffff 0,#f8fbff 100%);}");
		html.append(
				".code{min-width:64px;padding:8px 12px;border-radius:10px;font-weight:700;text-align:center;color:#fff;background:")
				.append(statusCode == HttpServletResponse.SC_FORBIDDEN ? "var(--warn)" : "var(--accent)").append(";}");
		html.append(".title{margin:0;font-size:1.4rem;line-height:1.2;}");
		html.append(".desc{margin:var(--gap) 0 0;color:var(--muted);font-size:.98rem;}");
		html.append(".body{padding:var(--gap) 24px 0;}");
		html.append(
				".label{font-size:.78rem;letter-spacing:.06em;text-transform:uppercase;color:var(--muted);margin-bottom:var(--gap);}");
		html.append(
				".detail{background:#f7f9fc;border:1px solid var(--line);border-radius:10px;padding:10px 12px;margin:0 0 var(--gap);word-break:break-word;font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace;font-size:.88rem;}");
		html.append(".footer{padding:0 24px 22px;color:var(--muted);font-size:.9rem;}");
		html.append("</style></head><body>");
		html.append("<main class=\"card\">");
		html.append("<section class=\"header\">");
		html.append("<div class=\"code\">").append(statusCode).append("</div>");
		html.append("<div><h1 class=\"title\">").append(headline).append("</h1>");
		html.append("<p class=\"desc\">").append(description).append("</p></div>");
		html.append("</section>");
		html.append("<section class=\"body\">");
		html.append("<div class=\"label\">Details</div><p class=\"detail\">").append(safeMessage).append("</p>");
		html.append("</section>");
		html.append(
				"<section class=\"footer\">If this seems unexpected, verify the path and access settings or request an administrator to republish the app.</section>");
		html.append("</main></body></html>");

		response.setStatus(statusCode);
		response.setContentType("text/html; charset=UTF-8");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write(html.toString());
		response.flushBuffer();
	}

	@Override
	public void destroy() {

	}

	@Override
	public void init(FilterConfig config) throws ServletException {

	}

}
