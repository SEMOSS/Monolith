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
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.ProgressListener;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import prerna.cache.ICache;
import prerna.om.InsightStore;
import prerna.poi.main.HeadersException;
import prerna.poi.main.helper.CSVFileHelper;
import prerna.util.Utility;

/**
 * Servlet implementation class Uploader
 */
@SuppressWarnings("serial")
public abstract class Uploader extends HttpServlet {
	private static final Logger logger = LogManager.getLogger(Uploader.class);

	// get the directory separator
	protected static final String DIR_SEPARATOR = java.nio.file.FileSystems.getDefault().getSeparator();

	public static final String CSV_FILE_KEY = "CSV";
	public static final String CSV_HELPER_MESSAGE = "HTML_RESPONSE";
	private static final String STACKTRACE = "StackTrace: ";

	protected int maxFileSize = 10_000_000 * 1024;
	protected int maxMemSize = 8 * 1024;
	protected String filePath;
	protected String tempFilePath = "";
	protected boolean securityEnabled;
	
	// we will control the adding of the engine into local master and solr
	// such that we dont send a success before those processes are complete
	boolean autoLoad = false;

	public void setFilePath(String filePath) {
		// first, normalize path
		String normalizedfilePath = Utility.normalizePath(filePath);

		// then set path
		if(normalizedfilePath.endsWith(DIR_SEPARATOR)) {
			this.filePath = normalizedfilePath;
		} else {
			this.filePath = normalizedfilePath + DIR_SEPARATOR;
		}
		File f = new File(this.filePath);
		if(!f.exists() && !f.isDirectory()) {
			f.mkdirs();
		}
	}

	public void setTempFilePath(String tempFilePath){
		// first, normalize path
		String normalizedTempFilePath = Utility.normalizePath(tempFilePath);

		// then set path
		this.tempFilePath = normalizedTempFilePath;
		File tFile = new File(normalizedTempFilePath);
		if(!tFile.exists() && !tFile.isDirectory()) {
			tFile.mkdirs();
		}
	}

	public void setSecurityEnabled(boolean securityEnabled) {
		this.securityEnabled = securityEnabled;
	}

	public void writeFile(FileItem fi, File file){
		try {
			fi.write(file);
		} catch (Exception e) {
			logger.error(STACKTRACE, e);
		}
	}

	protected void deleteFilesFromServer(String[] files) {
		for(String file : files) {
			// first, normalize path
			String normalizedFile = Utility.normalizePath(file);

			// then delete
			File f = new File(normalizedFile);
			ICache.deleteFile(f);
		}
	}

	protected List<FileItem> processRequest(@Context HttpServletRequest request, String insightId) {
		List<FileItem> fileItems = null;
		try {
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
		} catch (FileUploadException fue) {
			logger.error(STACKTRACE, fue);
		} catch (Exception e) {
			logger.error(STACKTRACE, e);
		}
		return fileItems;
	}

	protected Hashtable<String, String> getInputData(List<FileItem> fileItems) 
	{
		// Process the uploaded file items
		Iterator<FileItem> iteratorFileItems = fileItems.iterator();

		// collect all of the data input on the form
		Hashtable<String, String> inputData = new Hashtable<>();
		File file;

		while(iteratorFileItems.hasNext()) 
		{
			FileItem fi = iteratorFileItems.next();
			// Get the uploaded file parameters
			String fieldName = fi.getFieldName();
			String fileName = fi.getName();
			// keep this null
			// dont want to grab the contents of the file 
			// and push to a string
			String value = null;
			if (!fi.isFormField()) {
				if(fileName.equals("")) {
					continue;
				}
				else {
					if(fieldName.equals("file") || fieldName.equals("propFile")) {
						// need to clean the fileName to not have ";" since we split on that in upload data
						fileName = fileName.replace(";", "");
						Date date = new Date();
						String modifiedDate = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSSS").format(date);
						value = this.filePath + fileName.substring(fileName.lastIndexOf(DIR_SEPARATOR) + 1, fileName.lastIndexOf(".")).trim().replace(" ", "_") + "_____UNIQUE" + modifiedDate + fileName.substring(fileName.lastIndexOf("."));
						file = new File(value);

						writeFile(fi, file);
						logger.info("File item is the actual data. Saved Filename: " + fileName + "  to "+ file);
					}
				}
			} else {
				logger.info("File item type is " + fi.getFieldName() + fi.getString());
			}
			//need to handle multiple files getting selected for upload
			if(value == null) {
				value = fi.getString();
			}
			if(inputData.get(fieldName) != null) {
				value = inputData.get(fieldName) + ";" + value;
			}
			inputData.put(fieldName, value);
			fi.delete();
		}

		return inputData;
	}
	
	/**
	 * Method used to determine if there are errors in a csv file headers
	 * @param files				The list of files in the headers
	 * @return					boolean true if no issues
	 * @throws IOException 
	 * 							
	 */
	public boolean checkHeaders(String[] files) throws IOException {
		// iterate through the files
		for(String file : files) {
			CSVFileHelper helper = new CSVFileHelper();
			helper.parse(file);
			String[] headers = helper.getHeaders();
			
			// if there is an issue, it will throw the IOException
			HeadersException.getInstance().compareHeaders(new File(file).getName(), headers);
		}
		
		return true;
	}
}
