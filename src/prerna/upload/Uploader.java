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

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Context;

import org.apache.commons.fileupload.DiskFileUpload;
import org.apache.commons.fileupload.FileUploadBase;
//import org.apache.commons.fileupload.FileItem;
//import org.apache.commons.fileupload.FileUploadException;
//import org.apache.commons.fileupload.ProgressListener;
//import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload2.core.FileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

import org.apache.commons.fileupload2.core.DiskFileItemFactory;
import org.apache.commons.fileupload2.core.FileItem;
import org.apache.commons.fileupload2.core.FileUploadException;
import org.apache.commons.fileupload2.core.ProgressListener;
import org.apache.commons.fileupload2.jakarta.servlet6.JakartaServletFileUpload;


import prerna.om.InsightStore;
import prerna.util.FileEncoderDetector;
import prerna.util.FileSystemUtil;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

/**
 * Servlet implementation class Uploader
 */
@SuppressWarnings("serial")
public abstract class Uploader extends HttpServlet {

	private static final Logger classLogger = LogManager.getLogger(Uploader.class);

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
				classLogger.info("Unable to create file at: {}", Utility.cleanLogString(f.getAbsolutePath()));
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
			FileEncoderDetector analyzer = new FileEncoderDetector((org.apache.commons.fileupload.FileItem) fi);
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
					fi.write(new File(WebUtility.normalizePath(file.getAbsolutePath())).toPath());
				} catch (Exception e) {
					classLogger.error("Failed to write the uploaded binary file item to disk", e);
				}
			}
		} catch (Exception e) {
			classLogger.error("Failed to write the uploaded file to disk", e);
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
	/*
	 * protected List<FileItem> processRequest(@Context ServletContext
	 * context, @Context HttpServletRequest request, String insightId) throws
	 * FileUploadException { String tempFilePath =
	 * context.getInitParameter(TEMP_FILE_UPLOAD_KEY); tempFilePath =
	 * normalizeAndCreatePath(tempFilePath);
	 * 
	 * List<FileItem> fileItems = null; DiskFileItemFactory factory = new
	 * DiskFileItemFactory(); // maximum size that will be stored in memory
	 * factory.setSizeThreshold(maxMemSize); // Location to save data that is larger
	 * than maxMemSize. factory.setRepository(new File(tempFilePath)); // Create a
	 * new file upload handler
	 * 
	 * DiskFileUpload upload = new DiskFileUpload(); //JakartaServletDiskFileUpload
	 * upload = new JakartaServletDiskFileUpload(); // maximum file size to be
	 * uploaded. upload.setSizeMax(maxFileSize); // set encoding as well for the
	 * request upload.setHeaderEncoding("UTF-8"); // make sure the insight id is
	 * valid if present if (insightId != null) { if
	 * (InsightStore.getInstance().get(insightId) == null) { // this is an invalid
	 * insight id // null it out // no logging for you insightId = null; } }
	 * ProgressListener progressListener = new
	 * FileUploadProgressListener(insightId);
	 * upload.setProgressListener(progressListener);
	 * 
	 * // Parse the request to get file items fileItems =
	 * upload.parseRequest(request); return fileItems; }
	 */
	
	
	protected List<FileItem> processRequest(@Context ServletContext context, @Context HttpServletRequest request,
			String insightId) throws FileUploadException {
		String tempFilePath = context.getInitParameter(TEMP_FILE_UPLOAD_KEY);
		tempFilePath = normalizeAndCreatePath(tempFilePath);

		List<FileItem> fileItems = null;
		DiskFileItemFactory factory = DiskFileItemFactory.builder()
				.setPath(new File(tempFilePath).toPath()).setThreshold(maxMemSize)
				.get();
		// maximum size that will be stored in memory
		
		// Location to save data that is larger than maxMemSize.
		//factory.setRepository(new File(tempFilePath));
		// Create a new file upload handler
		
		JakartaServletFileUpload upload = new JakartaServletFileUpload(factory);
		upload.setMaxFileSize(maxFileSize);
		upload.setHeaderCharset(StandardCharsets.UTF_8);

		// make sure the insight id is valid if present
		if (insightId != null) {
			if (InsightStore.getInstance().get(insightId) == null) {
				// this is an invalid insight id
				// null it out
				// no logging for you
				insightId = null;
			}
		}
		FileUploadProgressListener progressListener = new FileUploadProgressListener(insightId);
		upload.setProgressListener((ProgressListener) progressListener);

		// Parse the request to get file items
		fileItems = upload.parseRequest(request);
		return fileItems;
	}

}
