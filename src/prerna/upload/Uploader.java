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
 *******************************************************************************/
package prerna.upload;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.ProgressListener;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.om.InsightStore;
import prerna.util.Constants;
import prerna.util.FileEncoderDetector;
import prerna.util.FileSystemUtil;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

/**
 * Servlet implementation class Uploader
 */
@SuppressWarnings("serial")
public abstract class Uploader extends HttpServlet {

	private static final Logger logger = LogManager.getLogger(Uploader.class);

	protected static final String DIR_SEPARATOR = java.nio.file.FileSystems.getDefault().getSeparator();

	public static final String CSV_FILE_KEY = "CSV";
	public static final String CSV_HELPER_MESSAGE = "HTML_RESPONSE";

	public static final String FILE_UPLOAD_KEY = "file-upload";
	public static final String TEMP_FILE_UPLOAD_KEY = "temp-file-upload";

	protected static int maxFileSize = 10_000_000 * 1024;
	protected static int maxMemSize = 8 * 1024;

	/**
	 * Normalizes and creates a path.
	 * 
	 * @param filePath The file path to normalize and create.
	 * @return The normalized file path.
	 */
	public static String normalizeAndCreatePath(String filePath) {
		// first, normalize path
		String normalizedfilePath = WebUtility.normalizePath(filePath);

		// then set path
		if (!normalizedfilePath.endsWith(DIR_SEPARATOR)) {
			normalizedfilePath = normalizedfilePath + DIR_SEPARATOR;
		}
		File f = new File(normalizedfilePath);
		if (!f.exists() && !f.isDirectory()) {
			Boolean success = f.mkdirs();
			if (!success) {
				logger.info("Unable to create file at: " + Utility.cleanLogString(f.getAbsolutePath()));
			}
		}

		return normalizedfilePath;
	}

	/**
	 * Writes a file item to a file.
	 * 
	 * @param fi   The file item to write.
	 * @param file The file to write to.
	 */
	public void writeFile(FileItem fi, File file) {
		try {
			FileEncoderDetector analyzer = new FileEncoderDetector(fi);
			if (analyzer.isTextContent()) {
				Charset detectedCharset = analyzer.getCharset();
				try (InputStream is = fi.getInputStream();
						OutputStream os = new FileOutputStream(file);
						Reader reader = new InputStreamReader(is, detectedCharset);
						Writer writer = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
					char[] buffer = new char[1024];
					int bytesRead;
					while ((bytesRead = reader.read(buffer)) != -1) {
						writer.write(buffer, 0, bytesRead);
					}
				}
			} else {
				try {
					fi.write(new File(WebUtility.normalizePath(file.getAbsolutePath())));
				} catch (Exception e) {
					logger.error(Constants.STACKTRACE, e);
				}
			}
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
		}
	}

	/**
	 * Deletes files from the server.
	 * 
	 * @param files The files to delete.
	 */
	protected void deleteFilesFromServer(String[] files) {
		for (String file : files) {
			// first, normalize path
			String normalizedFile = WebUtility.normalizePath(file);
			// then delete
			FileSystemUtil.deleteFileIfExists(normalizedFile);
		}
	}

	/**
	 * Processes a request to upload a file.
	 * 
	 * @param context   The servlet context.
	 * @param request   The HTTP servlet request.
	 * @param insightId The ID of the insight to upload the file to.
	 * @return A list of file items.
	 * @throws FileUploadException if an error occurs while processing the request.
	 */
	protected List<FileItem> processRequest(@Context ServletContext context, @Context HttpServletRequest request,
			String insightId) throws FileUploadException {
		String tempFilePath = context.getInitParameter(TEMP_FILE_UPLOAD_KEY);
		tempFilePath = normalizeAndCreatePath(tempFilePath);

		List<FileItem> fileItems = null;
		DiskFileItemFactory factory = new DiskFileItemFactory();
		// maximum size that will be stored in memory
		factory.setSizeThreshold(maxMemSize);
		// Location to save data that is larger than maxMemSize.
		factory.setRepository(new File(tempFilePath));
		// Create a new file upload handler
		ServletFileUpload upload = new ServletFileUpload(factory);
		// maximum file size to be uploaded.
		upload.setSizeMax(maxFileSize);
		// set encoding as well for the request
		upload.setHeaderEncoding("UTF-8");
		// make sure the insight id is valid if present
		if (insightId != null) {
			if (InsightStore.getInstance().get(insightId) == null) {
				// this is an invalid insight id
				// null it out
				// no logging for you
				insightId = null;
			}
		}
		ProgressListener progressListener = new FileUploadProgressListener(insightId);
		upload.setProgressListener(progressListener);

		// Parse the request to get file items
		fileItems = upload.parseRequest(request);
		return fileItems;
	}

}
