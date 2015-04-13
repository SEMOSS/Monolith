/*******************************************************************************
 * Copyright 2015 SEMOSS.ORG
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
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.FileUtils;

import prerna.error.EngineException;
import prerna.error.FileReaderException;
import prerna.error.FileWriterException;
import prerna.error.HeaderClassException;
import prerna.error.NLPException;
import prerna.rdf.engine.api.IEngine;
import prerna.ui.components.CSVPropFileBuilder;
import prerna.ui.components.ImportDataProcessor;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;

import com.google.gson.Gson;
import com.ibm.icu.util.StringTokenizer;

/**
 * Servlet implementation class Uploader
 */
@SuppressWarnings("serial")
public class Uploader extends HttpServlet {

	int maxFileSize = 1000000 * 1024;
	int maxMemSize = 4 * 1024;
	String output = "";

	String filePath;
	String tempFilePath = "";

	public void setFilePath(String filePath){
		this.filePath = filePath;
	}

	public void setTempFilePath(String tempFilePath){
		this.tempFilePath = tempFilePath;
	}

	public void writeFile(FileItem fi, File file){
		try {
			fi.write(file);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public List<FileItem> processRequest(@Context HttpServletRequest request)
	{
		List<FileItem> fileItems = null;
		try {
			DiskFileItemFactory factory = new DiskFileItemFactory();
			// maximum size that will be stored in memory
			factory.setSizeThreshold(maxMemSize);
			// Location to save data that is larger than maxMemSize.
			factory.setRepository(new File(tempFilePath)); //removing hard code to C:\\temp, get from web.xml tag temp-file-upload
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
	
	public void loadEngineIntoSession(HttpServletRequest request, String engineName) {
		Properties prop = new Properties();
		String baseFolder = DIHelper.getInstance().getProperty("BaseFolder");
		String fileName = baseFolder + "/db/"  +  engineName + ".smss";
		FileInputStream fileIn = null;
		try {
			fileIn = new FileInputStream(fileName);
			prop.load(fileIn);			
			Utility.loadEngine(fileName, prop);
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				if(fileIn != null) {
					fileIn.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		HttpSession session = request.getSession();
		String engineNames = (String)DIHelper.getInstance().getLocalProp(Constants.ENGINES);
		StringTokenizer tokens = new StringTokenizer(engineNames, ";");
		ArrayList<Hashtable<String, String>> engines = new ArrayList<Hashtable<String, String>>();
		while(tokens.hasMoreTokens())
		{
			// this would do some check to see
			String nextEngine = tokens.nextToken();
			System.out.println(" >>> " + nextEngine);
			IEngine engine = (IEngine) DIHelper.getInstance().getLocalProp(nextEngine);
			boolean hidden = (engine.getProperty(Constants.HIDDEN_DATABASE) != null && Boolean.parseBoolean(engine.getProperty(Constants.HIDDEN_DATABASE)));
			if(!hidden) {
				Hashtable<String, String> engineHash = new Hashtable<String, String> ();
				engineHash.put("name", nextEngine);
				engineHash.put("type", engine.getEngineType() + "");
				engines.add(engineHash);
			}
			// set this guy into the session of our user
			session.setAttribute(nextEngine, engine);
			// and over
		}			
		session.setAttribute(Constants.ENGINES, engines);
	}

	public Hashtable<String, String> getInputData(List<FileItem> fileItems) 
	{
		// Process the uploaded file items
		Iterator<FileItem> iteratorFileItems = fileItems.iterator();

		// collect all of the data input on the form
		Hashtable<String, String> inputData = new Hashtable<String, String>();
		ArrayList<File> allLoadingFiles = new ArrayList<File>();
		File file;

		while(iteratorFileItems.hasNext()) 
		{
			FileItem fi = (FileItem) iteratorFileItems.next();
			// Get the uploaded file parameters
			String fieldName = fi.getFieldName();
			String fileName = fi.getName();
			String value = fi.getString();
			if (!fi.isFormField()) 
			{
				if(fileName.equals("")) {
					continue;
				}
				else {
					if(fieldName.equals("file"))
					{
						value = filePath + fileName.substring(fileName.lastIndexOf("\\") + 1);
						file = new File(value);
						writeFile(fi, file);
						allLoadingFiles.add(file);
						System.out.println( "Saved Filename: " + fileName + "  to "+ file);
					} 
				}
			}
			else 
			{
				System.err.println("Type is " + fi.getFieldName() + fi.getString());
			}
			//need to handle multiple files getting selected for upload
			if(inputData.get(fieldName) != null)
			{
				value = inputData.get(fieldName) + ";" + value;
			}
			inputData.put(fieldName, value);
		}

		return inputData;
	}

	@SuppressWarnings("unchecked")
	@POST
	@Path("/csv/upload")
	@Produces("text/html")
	public Response uploadCSVFile(@Context HttpServletRequest request)
	{
		List<FileItem> fileItems = processRequest(request);
		Hashtable<String, String> inputData = getInputData(fileItems);
		Gson gson = new Gson();
		List<String> allFileData = gson.fromJson(inputData.get("fileInfoArray"), List.class);
		int size = allFileData.size();
		
		Hashtable<String, String>[] propHashArr = new Hashtable[size];
		String[] propFileArr = new String[size];
		String[] fileNames = inputData.get("filename").split(";");
		int i = 0;
		for(i = 0; i < size; i++) {
			Hashtable<String, String> itemForFile = gson.fromJson(allFileData.get(i), Hashtable.class);
			
			CSVPropFileBuilder propWriter = new CSVPropFileBuilder();

			List<String> rel = gson.fromJson(itemForFile.get("rowsRelationship"), List.class);
			if(rel != null) {
				for(String str : rel) {
					// subject and object keys link to array list for concatenations, while the predicate is always a string
					Hashtable<String, Object> mRow = gson.fromJson(str, Hashtable.class);
					if(!((String) mRow.get("selectedRelSubject").toString()).isEmpty() && !((String) mRow.get("relPredicate").toString()).isEmpty() && !((String) mRow.get("selectedRelObject").toString()).isEmpty())
					{
						propWriter.addRelationship((ArrayList<String>) mRow.get("selectedRelSubject"), mRow.get("relPredicate").toString(), (ArrayList<String>) mRow.get("selectedRelObject"));
					}
				}
			}

			List<String> prop = gson.fromJson(itemForFile.get("rowsProperty"), List.class);
			if(prop != null) {
				for(String str : prop) {
					Hashtable<String, Object> mRow = gson.fromJson(str, Hashtable.class);
					if(!((String) mRow.get("selectedPropSubject").toString()).isEmpty() && !((String) mRow.get("selectedPropObject").toString()).isEmpty() && !((String) mRow.get("selectedPropDataType").toString()).isEmpty())
					{
						propWriter.addProperty((ArrayList<String>) mRow.get("selectedPropSubject"), (ArrayList<String>) mRow.get("selectedPropObject"), (String) mRow.get("selectedPropDataType").toString());
					}
				}
			}

			String headersList = itemForFile.get("allHeaders"); 
			Hashtable<String, Object> headerHash = gson.fromJson(headersList, Hashtable.class);
			ArrayList<String> headers = (ArrayList<String>) headerHash.get("AllHeaders");

			propWriter.columnTypes(headers);
			propHashArr[i] = propWriter.getPropHash(itemForFile.get("csvStartLineCount"), itemForFile.get("csvEndLineCount")); 
			propFileArr[i] = propWriter.getPropFile();
		}
						
		ImportDataProcessor importer = new ImportDataProcessor();
		importer.setPropHashArr(propHashArr);
		importer.setBaseDirectory(DIHelper.getInstance().getProperty("BaseFolder"));

		// figure out what type of import we need to do based on parameters
		String methodString = inputData.get("dbImportOption");
		ImportDataProcessor.IMPORT_METHOD importMethod = 
				methodString.equals("Create new database engine") ? ImportDataProcessor.IMPORT_METHOD.CREATE_NEW
						: methodString.equals("addEngine") ? ImportDataProcessor.IMPORT_METHOD.ADD_TO_EXISTING
								: methodString.equals("modifyEngine") ? ImportDataProcessor.IMPORT_METHOD.OVERRIDE
										: null;
		if(importMethod == null) {
			String errorMessage = "Import method \'" + methodString + "\' is not supported";
			return Response.status(400).entity(errorMessage).build();
		}
		//call the right process method with correct parameters
		String dbName = inputData.get("dbName");
		
		String dataOutputType = inputData.get("dataOutputType");
		ImportDataProcessor.DB_TYPE storeType = ImportDataProcessor.DB_TYPE.RDF;
		if(dataOutputType.equalsIgnoreCase("RDBMS"))
			storeType = ImportDataProcessor.DB_TYPE.RDBMS;
		
		try {
			if(methodString.equals("Create new database engine")) {
				// force fitting the RDBMS here
				importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.CSV, inputData.get("file")+"", 
						inputData.get("designateBaseUri"), dbName,"","","","", storeType);
				loadEngineIntoSession(request, dbName);
			} else {
				importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.CSV, inputData.get("file")+"", 
						inputData.get("designateBaseUri"), "","","","", dbName, storeType);
			}
		} catch (EngineException e) {
			e.printStackTrace();
			return Response.status(400).entity(e.getMessage()).build();
		} catch (FileReaderException e) {
			e.printStackTrace();
			return Response.status(400).entity(e.getMessage()).build();
		} catch (HeaderClassException e) {
			e.printStackTrace();
			return Response.status(400).entity(e.getMessage()).build();
		} catch (FileWriterException e) {
			e.printStackTrace();
			return Response.status(400).entity(e.getMessage()).build();
		} catch (NLPException e) {
			e.printStackTrace();
			return Response.status(400).entity(e.getMessage()).build();
		}

		try {
			for(i = 0; i < size; i++) {
				FileUtils.writeStringToFile(new File(DIHelper.getInstance().getProperty("BaseFolder").concat(File.separator).concat("db").concat(File.separator).concat(Utility.cleanString(dbName, true).toString()).concat(File.separator).concat(dbName.toString()).concat("_").concat(fileNames[i].replace(".csv", "")).concat("_PROP.prop")), propFileArr[i]);
			}
		} catch (IOException e) {
			e.printStackTrace();
			String outputText = "Failure to write CSV Prop File based on user-defined metamodel.";
			return Response.status(400).entity(outputText).build();
		}

		String outputText = "CSV Loading was a success.";
		return Response.status(200).entity(outputText).build();
	}


	@POST
	@Path("/excel/upload")
	@Produces("text/html")
	public Response uploadExcelFile(@Context HttpServletRequest request) 
	{
		List<FileItem> fileItems = processRequest(request);
		// collect all of the data input on the form
		Hashtable<String, String> inputData = getInputData(fileItems);

		System.out.println(inputData);
		// time to run the import
		ImportDataProcessor importer = new ImportDataProcessor();
		importer.setBaseDirectory(DIHelper.getInstance().getProperty("BaseFolder"));

		// figure out what type of import we need to do based on parameters
		// selected
		String methodString = inputData.get("importMethod") + "";
		ImportDataProcessor.IMPORT_METHOD importMethod = 
				methodString.equals("Create new database engine") ? ImportDataProcessor.IMPORT_METHOD.CREATE_NEW
						: methodString.equals("addEngine") ? ImportDataProcessor.IMPORT_METHOD.ADD_TO_EXISTING
								: methodString.equals("modifyEngine") ? ImportDataProcessor.IMPORT_METHOD.OVERRIDE
										: null;
		if(importMethod == null) {
			String errorMessage = "Import method \'" + methodString + "\' is not supported";
			return Response.status(400).entity(errorMessage).build();
		}
		
		String dbName = "";
		
		String dataOutputType = inputData.get("dataOutputType");
		ImportDataProcessor.DB_TYPE storeType = ImportDataProcessor.DB_TYPE.RDF; // needs to be set later
		if(dataOutputType.equalsIgnoreCase("RDBMS"))
			storeType = ImportDataProcessor.DB_TYPE.RDBMS; // needs to be set later
		
		try {
			if(methodString.equals("Create new database engine")) {
				dbName = inputData.get("newDBname");
				importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.EXCEL, inputData.get("file")+"", 
						inputData.get("customBaseURI"), dbName,"","","","", storeType);
				loadEngineIntoSession(request, dbName);
			} else {
				dbName = inputData.get("addDBname");
				importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.EXCEL, inputData.get("file")+"", 
						inputData.get("customBaseURI"), "","","","", dbName, storeType);
			}
		} catch (EngineException e) {
			e.printStackTrace();
			return Response.status(400).entity(e.getMessage()).build();
		} catch (FileReaderException e) {
			e.printStackTrace();
			return Response.status(400).entity(e.getMessage()).build();
		} catch (HeaderClassException e) {
			e.printStackTrace();
			return Response.status(400).entity(e.getMessage()).build();
		} catch (FileWriterException e) {
			e.printStackTrace();
			return Response.status(400).entity(e.getMessage()).build();
		} catch (NLPException e) {
			e.printStackTrace();
			return Response.status(400).entity(e.getMessage()).build();
		}

		String outputText = "Excel Loading was a success.";
		return Response.status(200).entity(outputText).build();
	}

	@POST
	@Path("/nlp/upload")
	@Produces("text/html")
	public Response uploadNLPFile(@Context HttpServletRequest request) {
		List<FileItem> fileItems = processRequest(request);
		// collect all of the data input on the form
		Hashtable<String, String> inputData = getInputData(fileItems);

		System.out.println(inputData);
		// time to run the import
		ImportDataProcessor importer = new ImportDataProcessor();
		importer.setBaseDirectory(DIHelper.getInstance().getProperty("BaseFolder"));

		// figure out what type of import we need to do based on parameters
		// selected
		String methodString = inputData.get("importMethod") + "";
		ImportDataProcessor.IMPORT_METHOD importMethod = 
				methodString.equals("Create new database engine") ? ImportDataProcessor.IMPORT_METHOD.CREATE_NEW
						: methodString.equals("addEngine") ? ImportDataProcessor.IMPORT_METHOD.ADD_TO_EXISTING
								: methodString.equals("modifyEngine") ? ImportDataProcessor.IMPORT_METHOD.OVERRIDE
										: null;

		//call the right process method with correct parameters
		String dbName = "";
		
		String dataOutputType = inputData.get("dataOutputType");
		ImportDataProcessor.DB_TYPE storeType = ImportDataProcessor.DB_TYPE.RDF; // needs to be set later
		if(dataOutputType.equalsIgnoreCase("RDBMS"))
			storeType = ImportDataProcessor.DB_TYPE.RDBMS; // needs to be set later
		
		try {
			if(methodString.equals("Create new database engine")) {
				dbName = inputData.get("newDBname");
				importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.NLP, inputData.get("file")+"", 
						inputData.get("customBaseURI")+"", dbName,"","","","", storeType);
				loadEngineIntoSession(request, dbName);
			} else {
				dbName = inputData.get("addDBname");
				importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.NLP, inputData.get("file")+"", 
						inputData.get("customBaseURI")+"", "","","","", dbName, storeType);
			}
		} catch (EngineException e) {
			e.printStackTrace();
			return Response.status(400).entity(e.getMessage()).build();
		} catch (FileReaderException e) {
			e.printStackTrace();
			return Response.status(400).entity(e.getMessage()).build();
		} catch (HeaderClassException e) {
			e.printStackTrace();
			return Response.status(400).entity(e.getMessage()).build();
		} catch (FileWriterException e) {
			e.printStackTrace();
			return Response.status(400).entity(e.getMessage()).build();
		} catch (NLPException e) {
			e.printStackTrace();
			return Response.status(400).entity(e.getMessage()).build();
		}

		String outputText = "NLP Loading was a success.";
		return Response.status(200).entity(outputText).build();
	}

	@POST
	@Path("/d2rq/upload")
	@Produces("text/html")
	public Response uploadD2RQFile(@Context HttpServletRequest request) {
		List<FileItem> fileItems = processRequest(request);
		// collect all of the data input on the form
		Hashtable<String, String> inputData = getInputData(fileItems);

		System.out.println(inputData);
		// time to run the import
		ImportDataProcessor importer = new ImportDataProcessor();
		importer.setBaseDirectory(DIHelper.getInstance().getProperty("BaseFolder"));

		// figure out what type of import we need to do based on parameters
		// selected
		//		String methodString = inputData.get("importMethod") + "";
		//		ImportDataProcessor.IMPORT_METHOD importMethod = 
		//				methodString.equals("Create new database engine") ? ImportDataProcessor.IMPORT_METHOD.CREATE_NEW
		//						: methodString.equals("addEngine") ? ImportDataProcessor.IMPORT_METHOD.ADD_TO_EXISTING
		//										: null;

		//call the right process method with correct parameters
		try {
			importer.processNewRDBMS((String) inputData.get("customBaseURI"), (String) inputData.get("file"), 
					(String) inputData.get("newDBname"), (String) inputData.get("dbType"), (String) inputData.get("dbUrl"), 
					(String) inputData.get("accountName"), (char[]) inputData.get("accountPassword").toCharArray());
		} catch (FileReaderException e) {
			e.printStackTrace();
			return Response.status(400).entity(e.getMessage()).build();
		} catch (EngineException e) {
			e.printStackTrace();
			return Response.status(400).entity(e.getMessage()).build();
		}

		String outputText = "R2RQ Loading was a success.";
		return Response.status(200).entity(outputText).build();
	}
}
