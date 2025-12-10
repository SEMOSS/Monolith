package prerna.web.conf;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

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
		if (!performSecurityChecks(projectId, session, response)) {
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
	private boolean performSecurityChecks(String projectId, HttpSession session, HttpServletResponse response)
			throws IOException {
		if (!SecurityProjectUtils.projectIsGlobal(projectId)) {
			if (session != null) {
				User user = (User) session.getAttribute(Constants.SESSION_USER);
				if (!SecurityProjectUtils.userCanViewProject(user, projectId)) {
					response.getWriter().write("User does not have access to this project");
					return false;
				}
			} else {
				response.getWriter().write("User must be logged in to access this project");
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
			sendError(response, 404,
					"Could not find file at path " + thisPortalsPath + (specificFile != null ? specificFile : ""));
			return;
		}

		// Handle directory requests
		if (file.isDirectory()) {
			file = new File(file.getAbsolutePath() + "/index.html");
			if (!file.exists()) {
				sendError(response, 404, "Could not find index.html in directory " + thisPortalsPath
						+ (specificFile != null ? specificFile : ""));
				return;
			}
		}

		// Security: Prevent directory traversal
		Path filePath = file.toPath().toRealPath();
		Path basePath = new File(realPath + thisPortalsPath).toPath().toRealPath();
		if (!filePath.startsWith(basePath)) {
			sendError(response, 403, "Access denied");
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
			classLogger.debug("Files.probeContentType failed for " + file.getName(), e);
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
	private void sendError(HttpServletResponse response, int statusCode, String message) throws IOException {
		response.setStatus(statusCode);
		response.setContentType("text/html; charset=UTF-8");
		response.getWriter()
				.write(String.format("<html><body><h1>%d Error</h1><p>%s</p></body></html>", statusCode, message));
		response.flushBuffer();
	}

	@Override
	public void destroy() {

	}

	@Override
	public void init(FilterConfig config) throws ServletException {

	}

}