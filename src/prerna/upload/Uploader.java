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
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import javax.servlet.ServletContext;
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

import prerna.algorithm.learning.unsupervised.recommender.DataStructureFromCSV;
import prerna.auth.User;
import prerna.auth.UserPermissionsMasterDB;
import prerna.engine.api.IEngine;
import prerna.engine.impl.rdbms.RDBMSNativeEngine;
import prerna.error.EngineException;
import prerna.error.FileReaderException;
import prerna.error.FileWriterException;
import prerna.error.HeaderClassException;
import prerna.error.NLPException;
import prerna.nameserver.AddToMasterDB;
import prerna.poi.main.CSVPropFileBuilder;
import prerna.poi.main.ExcelPropFileBuilder;
import prerna.ui.components.ImportDataProcessor;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;
import prerna.util.sql.SQLQueryUtil;

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
	
	boolean securityEnabled;
	
	public void setFilePath(String filePath){
		this.filePath = filePath;
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

	public List<FileItem> processRequest(@Context HttpServletRequest request)
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

	public void loadEngineIntoLocalMasterDB(HttpServletRequest request, String engineName, String baseURL) {
		String localMasterDbName = Constants.LOCAL_MASTER_DB_NAME;

		ServletContext servletContext = request.getServletContext();
		String contextPath = servletContext.getRealPath(System.getProperty("file.separator"));
		String wordNet = "WEB-INF" + System.getProperty("file.separator") + "lib" + System.getProperty("file.separator") + "WordNet-3.1";
		String wordNetDir  = contextPath + wordNet;

		AddToMasterDB creater = new AddToMasterDB(localMasterDbName);
		creater.setWordnetPath(wordNetDir);
		creater.registerEngineLocal(engineName);
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
						value = filePath + System.getProperty("file.separator") + fileName.substring(fileName.lastIndexOf("\\") + 1);
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

	@POST
	@Path("/csv/autoGenMetamodel")
	@Produces("text/html")
	public Response createCSVMetamodel(@Context HttpServletRequest request) 
	{
		
		List<FileItem> fileItems = processRequest(request);
		// collect all of the data input on the form
		Hashtable<String, String> inputData = getInputData(fileItems);

		//cleanedFiles - stringified CSV file returned from OpenRefine
		//If OpenRefine-returned CSV string exists, user went through OpenRefine - write returned data to file first
		Object obj = inputData.get("cleanedFiles");
		String dbName = inputData.get("dbName");
		if(obj != null) {
			String cleanedFileName = processOpenRefine(dbName, (String) obj);
			if(cleanedFileName.startsWith("Error")) {
				String errorMessage = "Could not write the cleaned data to file. Please check application file-upload path.";
				return Response.status(400).entity(errorMessage).build();
			}
			inputData.put("file", cleanedFileName);
		}

		String localMasterDbName = inputData.get("localMasterDbName");
		HashMap<String, Object> results = new HashMap<String,Object>();
		// regardless of input master/local databases, uses the same method since it only queries the master db and not the databases used to create it
		if(localMasterDbName == null) {
			// this call is not local, need to get the API to run queries
			DataStructureFromCSV alg = new DataStructureFromCSV();
			ArrayList<HashMap<String,Object>> relList = alg.createMetamodel(inputData.get("file"));
			results.put("relations",relList);
			results.put("headers", alg.getHeaders());
			results.put("colTypes", alg.getAllPossibleTypesHash());
		} else {
			// this call is local, grab the engine from DIHelper
			DataStructureFromCSV alg = new DataStructureFromCSV(localMasterDbName);
			ArrayList<HashMap<String,Object>> relList = alg.createMetamodel(inputData.get("file"));
			results.put("relations",relList);
			results.put("headers", alg.getHeaders());
			results.put("colTypes", alg.getAllPossibleTypesHash());
		}	
			
		String outputText = "Auto Metamodel Generation was a success.";
		return Response.status(200).entity(outputText).build();
	}
	
	@SuppressWarnings("unchecked")
	@POST
	@Path("/csv/upload")
	@Produces("text/html")
	public Response uploadCSVFile(@Context HttpServletRequest request)
	{
		List<FileItem> fileItems = processRequest(request);
		Hashtable<String, String> inputData = getInputData(fileItems);
		
		//cleanedFiles - stringified CSV file returned from OpenRefine
		//If OpenRefine-returned CSV string exists, user went through OpenRefine - write returned data to file first
		Object obj = inputData.get("cleanedFiles");
		String dbName = inputData.get("dbName");
		if(obj != null) {
			String cleanedFileName = processOpenRefine(dbName, (String) obj);
			if(cleanedFileName.startsWith("Error")) {
				String errorMessage = "Could not write the cleaned data to file. Please check application file-upload path.";
				return Response.status(400).entity(errorMessage).build();
			}
			inputData.put("file", cleanedFileName);
		}
		
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
		
		//Add engine owner for permissions
		if(this.securityEnabled) {
			Object user = request.getSession().getAttribute(Constants.SESSION_USER);
			if(user != null && !((User) user).getId().equals(Constants.ANONYMOUS_USER_ID)) {
				addEngineOwner(dbName, ((User) user).getId());
			} else {
				return Response.status(400).entity("Please log in to upload data.").build();
			}
		}
		
		SQLQueryUtil.DB_TYPE rdbmsType = SQLQueryUtil.DB_TYPE.H2_DB;
		boolean allowDuplicates = false;
		String dataOutputType = inputData.get("dataOutputType");
		ImportDataProcessor.DB_TYPE storeType = ImportDataProcessor.DB_TYPE.RDF;
		if(dataOutputType.equalsIgnoreCase("RDBMS")){
			storeType = ImportDataProcessor.DB_TYPE.RDBMS;
			String rdbmsDataOutputType = inputData.get("rdbmsOutputType");
			if(rdbmsDataOutputType!=null && rdbmsDataOutputType.length()>0){//If RDBMS it really shouldnt be anyway...
				rdbmsType = SQLQueryUtil.DB_TYPE.valueOf(rdbmsDataOutputType.toUpperCase());
			}
			allowDuplicates = false;//ToDo: need UI portion of this
		}
		
		try {
			if(methodString.equals("Create new database engine")) {
				// force fitting the RDBMS here
				importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.CSV, inputData.get("file")+"", 
						inputData.get("designateBaseUri"), dbName,"","","","", storeType, rdbmsType, allowDuplicates);
				loadEngineIntoSession(request, dbName);
				loadEngineIntoLocalMasterDB(request, dbName, inputData.get("designateBaseUri"));
			} else { // add to existing or modify
				IEngine dbEngine = (IEngine) DIHelper.getInstance().getLocalProp(dbName);
				if (dbEngine.getEngineType() == IEngine.ENGINE_TYPE.RDBMS) {
					RDBMSNativeEngine rdbmsEngine = (RDBMSNativeEngine) dbEngine;
					storeType = ImportDataProcessor.DB_TYPE.RDBMS;
					rdbmsType = rdbmsEngine.getDbType();
				}
				importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.CSV, inputData.get("file")+"", 
						inputData.get("designateBaseUri"), "","","","", dbName, storeType, rdbmsType, allowDuplicates);
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
	
	@SuppressWarnings("unchecked")
	@POST
	@Path("/excelTable/upload")
	@Produces("text/html")
	public Response uploadExcelReaederFile(@Context HttpServletRequest request)
	{
		List<FileItem> fileItems = processRequest(request);
		Hashtable<String, String> inputData = getInputData(fileItems);
		
		//cleanedFiles - stringfield ExcelReader file returned from OpenRefine
		//If OpenRefine-returned ExcelReader string exists, user went through OpenRefine - write returned data to file first
		Object obj = inputData.get("cleanedFiles");
		String dbName = inputData.get("dbName");
		if(obj != null) {
			String cleanedFileName = processOpenRefine(dbName, (String) obj);
			if(cleanedFileName.startsWith("Error")) {
				String errorMessage = "Could not write the cleaned data to file. Please check application file-upload path.";
				return Response.status(400).entity(errorMessage).build();
			}
			inputData.put("file", cleanedFileName);
		}
		
		Gson gson = new Gson();
		List<String> allFileData = gson.fromJson(inputData.get("fileInfoArray"), List.class);
		int size = allFileData.size();
		
		Hashtable<String, String>[] propHashArr = new Hashtable[size];
		String[] propFileArr = new String[size];
		String[] fileNames = inputData.get("filename").split(";");
		int i = 0;
		for(i = 0; i < size; i++) {
			Hashtable<String, String> itemForFile = gson.fromJson(allFileData.get(i), Hashtable.class);
			
			ExcelPropFileBuilder propWriter = new ExcelPropFileBuilder();

			Hashtable<String, String> allSheetInfo = gson.fromJson(itemForFile.get("sheetInfo"), Hashtable.class);
			for(String sheet : allSheetInfo.keySet()) {
				Hashtable<String, String> sheetInfo = gson.fromJson(allSheetInfo.get(sheet), Hashtable.class);
				List<String> rel = gson.fromJson(sheetInfo.get("rowsRelationship"), List.class);
				if(rel != null) {
					for(String str : rel) {
						// subject and object keys link to array list for concatenations, while the predicate is always a string
						Hashtable<String, Object> mRow = gson.fromJson(str, Hashtable.class);
						if(!((String) mRow.get("selectedRelSubject").toString()).isEmpty() && !((String) mRow.get("relPredicate").toString()).isEmpty() && !((String) mRow.get("selectedRelObject").toString()).isEmpty())
						{
							propWriter.addRelationship(sheet, (ArrayList<String>) mRow.get("selectedRelSubject"), mRow.get("relPredicate").toString(), (ArrayList<String>) mRow.get("selectedRelObject"));
						}
					}
				}
	
				List<String> prop = gson.fromJson(sheetInfo.get("rowsProperty"), List.class);
				if(prop != null) {
					for(String str : prop) {
						Hashtable<String, Object> mRow = gson.fromJson(str, Hashtable.class);
						if(!((String) mRow.get("selectedPropSubject").toString()).isEmpty() && !((String) mRow.get("selectedPropObject").toString()).isEmpty() && !((String) mRow.get("selectedPropDataType").toString()).isEmpty())
						{
							propWriter.addProperty(sheet, (ArrayList<String>) mRow.get("selectedPropSubject"), (ArrayList<String>) mRow.get("selectedPropObject"), (String) mRow.get("selectedPropDataType").toString());
						}
					}
				}
				
				propWriter.addStartRow(sheet, sheetInfo.get("startLine"));
				propWriter.addStartRow(sheet, sheetInfo.get("endLine"));
			}
			propHashArr[i] = propWriter.getPropHash(); 
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
		
		//Add engine owner for permissions
		if(this.securityEnabled) {
			Object user = request.getSession().getAttribute(Constants.SESSION_USER);
			if(user != null && !((User) user).getId().equals(Constants.ANONYMOUS_USER_ID)) {
				addEngineOwner(dbName, ((User) user).getId());
			} else {
				return Response.status(400).entity("Please log in to upload data.").build();
			}
		}
		
		SQLQueryUtil.DB_TYPE rdbmsType = SQLQueryUtil.DB_TYPE.H2_DB;
		boolean allowDuplicates = false;
		String dataOutputType = inputData.get("dataOutputType");
		ImportDataProcessor.DB_TYPE storeType = ImportDataProcessor.DB_TYPE.RDF;
		if(dataOutputType.equalsIgnoreCase("RDBMS")){
			storeType = ImportDataProcessor.DB_TYPE.RDBMS;
			String rdbmsDataOutputType = inputData.get("rdbmsOutputType");
			if(rdbmsDataOutputType!=null && rdbmsDataOutputType.length()>0){//If RDBMS it really shouldnt be anyway...
				rdbmsType = SQLQueryUtil.DB_TYPE.valueOf(rdbmsDataOutputType.toUpperCase());
			}
			allowDuplicates = false;//ToDo: need UI portion of this
		}
		
		try {
			if(methodString.equals("Create new database engine")) {
				// force fitting the RDBMS here
				importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.EXCEL, inputData.get("file")+"", 
						inputData.get("designateBaseUri"), dbName,"","","","", storeType, rdbmsType, allowDuplicates);
				loadEngineIntoSession(request, dbName);
				loadEngineIntoLocalMasterDB(request, dbName, inputData.get("designateBaseUri"));
			} else { // add to existing or modify
				IEngine dbEngine = (IEngine) DIHelper.getInstance().getLocalProp(dbName);
				if (dbEngine.getEngineType() == IEngine.ENGINE_TYPE.RDBMS) {
					RDBMSNativeEngine rdbmsEngine = (RDBMSNativeEngine) dbEngine;
					storeType = ImportDataProcessor.DB_TYPE.RDBMS;
					rdbmsType = rdbmsEngine.getDbType();
				}
				importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.EXCEL, inputData.get("file")+"", 
						inputData.get("designateBaseUri"), "","","","", dbName, storeType, rdbmsType, allowDuplicates);
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
				FileUtils.writeStringToFile(new File(DIHelper.getInstance().getProperty("BaseFolder").concat(File.separator).concat("db").concat(File.separator).concat(Utility.cleanString(dbName, true).toString()).concat(File.separator).concat(dbName.toString()).concat("_").concat(fileNames[i].replace(".excel", "")).concat("_PROP.prop")), propFileArr[i]);
			}
		} catch (IOException e) {
			e.printStackTrace();
			String outputText = "Failure to write Excel Prop File based on user-defined metamodel.";
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
		SQLQueryUtil.DB_TYPE rdbmsType = SQLQueryUtil.DB_TYPE.H2_DB;
		String dataOutputType = inputData.get("dataOutputType");
		ImportDataProcessor.DB_TYPE storeType = ImportDataProcessor.DB_TYPE.RDF; // needs to be set later
		boolean allowDuplicates = false;
		if(dataOutputType.equalsIgnoreCase("RDBMS")){
			storeType = ImportDataProcessor.DB_TYPE.RDBMS; // needs to be set later
			String rdbmsDataOutputType = inputData.get("rdbmsOutputType");
			if(rdbmsDataOutputType!=null && rdbmsDataOutputType.length()>0){//If RDBMS it really shouldnt be anyway...
				rdbmsType = SQLQueryUtil.DB_TYPE.valueOf(rdbmsDataOutputType.toUpperCase());
			}
			allowDuplicates = false;//todo set from UI
		}
		
		try {
			if(methodString.equals("Create new database engine")) {
				dbName = inputData.get("newDBname");
				
				//Add engine owner for permissions
				if(this.securityEnabled) {
					Object user = request.getSession().getAttribute(Constants.SESSION_USER);
					if(user != null && !((User) user).getId().equals(Constants.ANONYMOUS_USER_ID)) {
						addEngineOwner(dbName, ((User) user).getId());
					} else {
						return Response.status(400).entity("Please log in to upload data.").build();
					}
				}
				
				importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.EXCEL_POI, inputData.get("file")+"", 
						inputData.get("customBaseURI"), dbName,"","","","", storeType, rdbmsType, allowDuplicates);
				loadEngineIntoSession(request, dbName);
				loadEngineIntoLocalMasterDB(request, dbName, inputData.get("customBaseURI"));
			} else {
				dbName = inputData.get("addDBname");
				
				//Add engine owner for permissions
				if(this.securityEnabled) {
					Object user = request.getSession().getAttribute(Constants.SESSION_USER);
					if(user != null && !((User) user).getId().equals(Constants.ANONYMOUS_USER_ID)) {
						addEngineOwner(dbName, ((User) user).getId());
					} else {
						return Response.status(400).entity("Please log in to upload data.").build();
					}
				}
				
				importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.EXCEL_POI, inputData.get("file")+"", 
						inputData.get("customBaseURI"), "","","","", dbName, storeType, rdbmsType, allowDuplicates);
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
		SQLQueryUtil.DB_TYPE rdbmsType = SQLQueryUtil.DB_TYPE.H2_DB;
		String dataOutputType = inputData.get("dataOutputType");
		ImportDataProcessor.DB_TYPE storeType = ImportDataProcessor.DB_TYPE.RDF; // needs to be set later
		boolean allowDuplicates = false;
		if(dataOutputType.equalsIgnoreCase("RDBMS")){
			storeType = ImportDataProcessor.DB_TYPE.RDBMS; // needs to be set later
			String rdbmsDataOutputType = inputData.get("rdbmsOutputType");
			if(rdbmsDataOutputType!=null && rdbmsDataOutputType.length()>0){//If RDBMS it really shouldnt be anyway...
				rdbmsType = SQLQueryUtil.DB_TYPE.valueOf(rdbmsDataOutputType.toUpperCase());
			}
			allowDuplicates = false;//todo need to set from the UI
		}
		
		try {
			if(methodString.equals("Create new database engine")) {
				dbName = inputData.get("newDBname");
				//Add engine owner for permissions
				if(this.securityEnabled) {
					Object user = request.getSession().getAttribute(Constants.SESSION_USER);
					if(user != null && !((User) user).getId().equals(Constants.ANONYMOUS_USER_ID)) {
						addEngineOwner(dbName, ((User) user).getId());
					} else {
						return Response.status(400).entity("Please log in to upload data.").build();
					}
				}
				
				importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.NLP, inputData.get("file")+"", 
						inputData.get("customBaseURI")+"", dbName,"","","","", storeType, rdbmsType, allowDuplicates);
				loadEngineIntoSession(request, dbName);
				loadEngineIntoLocalMasterDB(request, dbName, inputData.get("customBaseURI"));
			} else {
				dbName = inputData.get("addDBname");
				//Add engine owner for permissions
				if(this.securityEnabled) {
					Object user = request.getSession().getAttribute(Constants.SESSION_USER);
					if(user != null && !((User) user).getId().equals(Constants.ANONYMOUS_USER_ID)) {
						addEngineOwner(dbName, ((User) user).getId());
					} else {
						return Response.status(400).entity("Please log in to upload data.").build();
					}
				}
				
				importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.NLP, inputData.get("file")+"", 
						inputData.get("customBaseURI")+"", "","","","", dbName, storeType, rdbmsType, allowDuplicates);
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
	
	/**
	 * Writes OpenRefine-cleaned values to CSV file to be processed by ImportDataProcessor.
	 * 
	 * @param cleanedValues		Cleaned data returned from OpenRefine
	 * @return					Name of file on file system the cleaned data was written to
	 */
	private String processOpenRefine(String dbName, String cleanedValues) {
		FileWriter fw = null;
		String filename = this.filePath + System.getProperty("file.separator") + dbName + "_Cleaned.csv";
		try {
			fw = new FileWriter(filename);
			fw.append(cleanedValues);
		} catch (IOException e) {
			return "Error: Could not write cleaned values.";
		} finally {
			try {
				fw.flush();
				fw.close();
			} catch (IOException e) {
				return "Error: Could not flush/close FileWriter";
			}
		}
		
		return filename;
	}
	
	public void addEngineOwner(String engine, String userId) {
		UserPermissionsMasterDB masterDB = new UserPermissionsMasterDB();
		masterDB.addEngineOwner(engine, userId);
	}
}
