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
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tika.Tika;

import com.google.common.base.Strings;

import prerna.auth.User;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.project.api.IProject;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

public class PublicHomeCheckFilter implements Filter {

	private static final Logger classLogger = LogManager.getLogger(PublicHomeCheckFilter.class);

	private static final int BUFFER_SIZE = 8192;

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
			response.getWriter().write("Improper portal URL - unable to find project ID for the portal");
			return null;
		}

		String projectId = fullUrl.substring(locForPath + locLength + 1);
		if (projectId.contains("/")) {
			projectId = projectId.substring(0, projectId.indexOf("/"));
		}

		if (Strings.isNullOrEmpty(projectId)) {
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
			if (session != null) {
				User user = (User) session.getAttribute(Constants.SESSION_USER);
				if (!SecurityProjectUtils.userCanViewProject(user, projectId)) {
					sendError(request, response, HttpServletResponse.SC_FORBIDDEN,
							"You do not have access to this project.");
					return false;
				}
			} else {
				sendError(request, response, HttpServletResponse.SC_FORBIDDEN,
						"You must be logged in to access this project.");
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

		long lastModified = file.lastModified();
		long fileSize = file.length();

		// Check If-Modified-Since for caching
		long ifModifiedSince = request.getDateHeader("If-Modified-Since");
		if (ifModifiedSince != -1 && lastModified <= ifModifiedSince) {
			response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
			return;
		}

		// Determine content type using multiple methods (cascading fallback)
		String contentType = determineContentType(file, context);
		response.setContentType(contentType);

		// Set standard headers
		response.setDateHeader("Last-Modified", lastModified);
		response.setHeader("Accept-Ranges", "bytes");

		// Handle Range requests
		String rangeHeader = request.getHeader("Range");
		if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
			servePartialContent(file, fileSize, rangeHeader, response);
		} else {
			// Serve full content
			response.setContentLengthLong(fileSize);
			response.setStatus(HttpServletResponse.SC_OK);

			try (FileInputStream fis = new FileInputStream(file);
					ServletOutputStream out = response.getOutputStream()) {
				copyStream(fis, out);
			}
		}
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
			Tika tika = new Tika();
			contentType = tika.detect(file);
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
	 * Serve partial content for HTTP Range requests (supports resumable downloads
	 * and streaming)
	 * 
	 * @param file
	 * @param fileSize
	 * @param rangeHeader
	 * @param response
	 * @throws IOException
	 */
	private void servePartialContent(File file, long fileSize, String rangeHeader, HttpServletResponse response)
			throws IOException {

		// Parse range header (supports single range only)
		String range = rangeHeader.substring(6).trim(); // Remove "bytes="
		String[] parts = range.split("-");

		long start = 0;
		long end = fileSize - 1;

		try {
			if (!parts[0].isEmpty()) {
				start = Long.parseLong(parts[0]);
			}
			if (parts.length > 1 && !parts[1].isEmpty()) {
				end = Long.parseLong(parts[1]);
			}
		} catch (NumberFormatException e) {
			response.setHeader("Content-Range", "bytes */" + fileSize);
			response.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
			return;
		}

		// Validate range
		if (start > end || start < 0 || end >= fileSize) {
			response.setHeader("Content-Range", "bytes */" + fileSize);
			response.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
			return;
		}

		long contentLength = end - start + 1;

		// Set headers for partial content
		response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
		response.setHeader("Content-Range", String.format("bytes %d-%d/%d", start, end, fileSize));
		response.setContentLengthLong(contentLength);

		// Stream the requested range
		try (FileInputStream fis = new FileInputStream(file); ServletOutputStream out = response.getOutputStream()) {

			fis.skip(start);
			copyStreamWithLimit(fis, out, contentLength);
		}
	}

	/**
	 * Copy stream with larger buffer for better performance
	 * 
	 * @param in
	 * @param out
	 * @throws IOException
	 */
	private void copyStream(FileInputStream in, ServletOutputStream out) throws IOException {
		byte[] buffer = new byte[BUFFER_SIZE];
		int bytesRead;
		while ((bytesRead = in.read(buffer)) != -1) {
			out.write(buffer, 0, bytesRead);
		}
	}

	/**
	 * Copy stream with byte limit (for range requests)
	 */
	private void copyStreamWithLimit(FileInputStream in, ServletOutputStream out, long limit) throws IOException {
		byte[] buffer = new byte[BUFFER_SIZE];
		long remaining = limit;

		while (remaining > 0) {
			int toRead = (int) Math.min(buffer.length, remaining);
			int bytesRead = in.read(buffer, 0, toRead);
			if (bytesRead == -1) {
				break;
			}

			out.write(buffer, 0, bytesRead);
			remaining -= bytesRead;
		}
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
