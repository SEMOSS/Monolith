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

import prerna.cache.ICache;
import prerna.om.InsightStore;
import prerna.util.Constants;
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
	
	public static String normalizeAndCreatePath(String filePath) {
		// first, normalize path
		String normalizedfilePath = WebUtility.normalizePath(filePath);

		// then set path
		if(!normalizedfilePath.endsWith(DIR_SEPARATOR)) {
			normalizedfilePath = normalizedfilePath + DIR_SEPARATOR;
		}
		File f = new File(normalizedfilePath);
		if(!f.exists() && !f.isDirectory()) {
			Boolean success = f.mkdirs();
			if(!success) {
				logger.info("Unable to create file at: " + Utility.cleanLogString(f.getAbsolutePath()));
			}
		}
		
		return normalizedfilePath;
	}

	public void writeFile(FileItem fi, File file){
		try {
			fi.write(new File(WebUtility.normalizePath(file.getAbsolutePath())));
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
		}
	}

	protected void deleteFilesFromServer(String[] files) {
		for(String file : files) {
			// first, normalize path
			String normalizedFile = WebUtility.normalizePath(file);

			// then delete
			File f = new File(normalizedFile);
			ICache.deleteFile(f);
		}
	}

	protected List<FileItem> processRequest(@Context ServletContext context, @Context HttpServletRequest request, String insightId) throws FileUploadException {
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
		if(insightId != null) {
			if(InsightStore.getInstance().get(insightId) == null) {
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
