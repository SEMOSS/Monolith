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
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import prerna.cache.FileStore;
import prerna.cache.ICache;
import prerna.poi.main.HeadersException;
import prerna.poi.main.helper.AmazonApiHelper;
import prerna.poi.main.helper.CSVFileHelper;
import prerna.poi.main.helper.ImportApiHelper;
import prerna.poi.main.helper.WebAPIHelper;
import prerna.poi.main.helper.XLFileHelper;
import prerna.util.Utility;

/**
 * Servlet implementation class Uploader
 */
@SuppressWarnings("serial")
public abstract class Uploader extends HttpServlet {

	private static final Logger LOGGER = LogManager.getLogger(FileUploader.class);

	// get the directory separator
	protected static final String DIR_SEPARATOR = java.nio.file.FileSystems.getDefault().getSeparator();

	public static final String CSV_FILE_KEY = "CSV";
	public static final String CSV_HELPER_MESSAGE = "HTML_RESPONSE";

	protected int maxFileSize = 10000000 * 1024;
	protected int maxMemSize = 8 * 1024;
	protected String filePath;
	protected String tempFilePath = "";
	protected boolean securityEnabled;
	
	// we will control the adding of the engine into local master and solr
	// such that we dont send a success before those processes are complete
	boolean autoLoad = false;

	public void setFilePath(String filePath) {
		if(filePath.endsWith(DIR_SEPARATOR)) {
			this.filePath = filePath;
		} else {
			this.filePath = filePath + DIR_SEPARATOR;
		}
	}

	public void setTempFilePath(String tempFilePath){
		this.tempFilePath = tempFilePath;
	}

	public void setSecurityEnabled(boolean securityEnabled) {
		this.securityEnabled = securityEnabled;
	}

	public void writeFile(FileItem fi, File file){
		try {
			fi.write(file);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	protected void deleteFilesFromServer(String[] files) {
		for(String file : files) {
			File f = new File(file);
			ICache.deleteFile(f);
		}
	}

	protected List<FileItem> processRequest(@Context HttpServletRequest request)
	{
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

			// Parse the request to get file items
			fileItems = upload.parseRequest(request);
		} catch (FileUploadException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return fileItems;
	}

	protected Hashtable<String, String> getInputData(List<FileItem> fileItems) 
	{
		// Process the uploaded file items
		Iterator<FileItem> iteratorFileItems = fileItems.iterator();

		// collect all of the data input on the form
		Hashtable<String, String> inputData = new Hashtable<String, String>();
		File file;

		while(iteratorFileItems.hasNext()) 
		{
			FileItem fi = (FileItem) iteratorFileItems.next();
			// Get the uploaded file parameters
			String fieldName = fi.getFieldName();
			String fileName = fi.getName();
			String value = fi.getString();
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
						value = this.filePath + fileName.substring(fileName.lastIndexOf(DIR_SEPARATOR) + 1, fileName.lastIndexOf(".")).trim().replace(" ", "_") + modifiedDate + fileName.substring(fileName.lastIndexOf("."));
						file = new File(value);
						writeFile(fi, file);
						LOGGER.info("File item is the actual data. Saved Filename: " + fileName + "  to "+ file);
					}
				}
			} else {
				LOGGER.info("File item type is " + fi.getFieldName() + fi.getString());
			}
			//need to handle multiple files getting selected for upload
			if(inputData.get(fieldName) != null)
			{
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
	
	/**
	 * 
	 * @param inputData
	 * @return
	 * @throws IOException
	 */
	protected static Map<String, Object> generateDataTypes(Map<String, String> inputData) throws IOException {
		Map<String, Object> retObj = new HashMap<String, Object>();

		Map<String, Map<String, String>> headerTypeMap = new Hashtable<String, Map<String, String>>();

		boolean webExtract = (inputData.get("file") == null);
		String keyvalue = "";

		if(webExtract){//Provision for extracted data via import.io
			WebAPIHelper helper = null;
			if(inputData.get("api") != null){
				keyvalue = inputData.get("api");
				helper = new ImportApiHelper();
			}else if(inputData.get("itemSearch") != null){
				keyvalue = inputData.get("itemSearch");
				helper = new AmazonApiHelper();
				((AmazonApiHelper) helper).setOperationType("ItemSearch");
			}else if(inputData.get("itemLookup") != null){
				keyvalue = inputData.get("itemLookup");
				helper = new AmazonApiHelper();
				((AmazonApiHelper) helper).setOperationType("ItemLookup");
			}

			helper.setApiParam(keyvalue);
			helper.parse();	


			String [] headers = helper.getHeaders();
			String [] types = helper.predictTypes();

			Map<String, String> headerTypes = new LinkedHashMap<String, String>();
			for(int j = 0; j < headers.length; j++) {
				headerTypes.put(headers[j], Utility.getCleanDataType(types[j]));
			}
			headerTypeMap.put(CSV_FILE_KEY, headerTypes);

		} else {

			String fileLoc = inputData.get("file");
			String uniqueStorageId = FileStore.getInstance().put(fileLoc);
			retObj.put("uniqueFileKey", uniqueStorageId);
			if(fileLoc.endsWith(".xlsx") || fileLoc.endsWith(".xlsm")) {
				XLFileHelper helper = new XLFileHelper();
				helper.parse(fileLoc);

				String[] sheets = helper.getTables();
				for(int i = 0; i < sheets.length; i++)
				{
					String sheetName = sheets[i];
					String[] types = helper.predictRowTypes(sheetName);
					String[] headers = helper.getHeaders(sheetName);

					String [] cleanHeaders = new String[headers.length];
					for(int x = 0; x < headers.length; x++) {
						cleanHeaders[i] = Utility.cleanVariableString(headers[i]);
					}

					Map<String, String> headerTypes = new LinkedHashMap<String, String>();
					for(int j = 0; j < cleanHeaders.length; j++) {
						headerTypes.put(cleanHeaders[j], Utility.getCleanDataType(types[j]));
					}
					headerTypeMap.put(sheetName, headerTypes);
				}
			} else {
				String delimiter = inputData.get("delimiter");
				// generate tinker frame from 
				if(inputData.get("delimiter") == null || inputData.get("delimiter").isEmpty()) {
					delimiter = "\t";
				}

				CSVFileHelper helper = new CSVFileHelper();
				helper.setDelimiter(delimiter.charAt(0));
				helper.parse(fileLoc);

				String [] headers = helper.getHeaders();
				String [] types = helper.predictTypes();

				Map<String, String> headerTypes = new LinkedHashMap<String, String>();
				for(int j = 0; j < headers.length; j++) {
					headerTypes.put(headers[j], Utility.getCleanDataType(types[j]));
				}
				headerTypeMap.put(CSV_FILE_KEY, headerTypes);
				retObj.put("delimiter", delimiter);
				
//				String htmlMessage = helper.getHTMLBasedHeaderChanges();
//				if(htmlMessage != null) {
//					retObj.put(CSV_HELPER_MESSAGE, htmlMessage);
//				}
				
				helper.clear();
			}
		}

		retObj.put("headerData", headerTypeMap);
		return retObj;
	}
}
