package prerna.upload;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.io.FileUtils;
import org.openrdf.repository.RepositoryException;
import org.openrdf.sail.SailException;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import prerna.algorithm.learning.unsupervised.recommender.DataStructureFromCSV;
import prerna.auth.User;
import prerna.auth.UserPermissionsMasterDB;
import prerna.engine.api.IEngine;
import prerna.engine.impl.rdbms.RDBMSNativeEngine;
import prerna.poi.main.CSVPropFileBuilder;
import prerna.poi.main.ExcelPropFileBuilder;
import prerna.rdf.main.ImportRDBMSProcessor;
import prerna.ui.components.ImportDataProcessor;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;
import prerna.util.sql.SQLQueryUtil;

public class DatabaseUploader extends Uploader {
	
	// we will control the adding of the engine into local master and solr
	// such that we dont send a success before those processes are complete
	boolean autoLoad = false;
	
	private void loadEngineIntoSession(HttpServletRequest request, String engineName) throws KeyManagementException, NoSuchAlgorithmException, KeyStoreException {
		Properties prop = new Properties();
		String baseFolder = DIHelper.getInstance().getProperty("BaseFolder");
		String fileName = baseFolder + "/db/"  +  engineName + ".smss";
		FileInputStream fileIn = null;
		try {
			fileIn = new FileInputStream(fileName);
			prop.load(fileIn);			
			Utility.loadWebEngine(fileName, prop);
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

	
	@POST
	@Path("/csv/autoGenMetamodel")
	@Produces("text/html")
	public Response createCSVMetamodel(@Context HttpServletRequest request) 
	{
		Gson gson = new Gson();
		
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
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "Could not write the cleaned data to file. Please check application file-upload path.");
				return Response.status(400).entity(gson.toJson(errorHash)).build();
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
	
	@POST
	@Path("/csv/upload")
	@Produces("application/json")
	public Response uploadCSVFile(@Context HttpServletRequest request)
	{
		List<FileItem> fileItems = processRequest(request);
		Hashtable<String, String> inputData = getInputData(fileItems);
		
		Gson gson = new Gson();

		//cleanedFiles - stringified CSV file returned from OpenRefine
		//If OpenRefine-returned CSV string exists, user went through OpenRefine - write returned data to file first
		Object obj = inputData.get("cleanedFiles");

		String dbName = "";
		if(inputData.get("dbName") != null && !inputData.get("dbName").isEmpty()) {
			dbName = inputData.get("dbName");
		} else if(inputData.get("addDBname") != null && !inputData.get("addDBname").isEmpty()) {
			dbName = inputData.get("addDBname");
		} else {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "No database name was entered");
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		}
		dbName = cleanSpaces(dbName);
		
		if(obj != null) {
			String cleanedFileName = processOpenRefine(dbName, (String) obj);
			if(cleanedFileName.startsWith("Error")) {
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "Could not write the cleaned data to file. Please check application file-upload path.");
				return Response.status(400).entity(gson.toJson(errorHash)).build();
			}
			inputData.put("file", cleanedFileName);
		}
		
		List<String> allFileData = gson.fromJson(inputData.get("fileInfoArray"), List.class);
		int size = allFileData.size();
		
		Hashtable<String, String>[] propHashArr = new Hashtable[size];
		String[] propFileArr = new String[size];
		String[] fileNames = inputData.get("filename").split(";");
		boolean allEmpty = true;
		for(int i = 0; i < size; i++) {
			Hashtable<String, String> itemForFile = gson.fromJson(allFileData.get(i), Hashtable.class);
			
			CSVPropFileBuilder propWriter = new CSVPropFileBuilder();

			List<String> rel = gson.fromJson(itemForFile.get("rowsRelationship"), List.class);
			List<String> prop = gson.fromJson(itemForFile.get("rowsProperty"), List.class);
			List<String> displayNames = gson.fromJson(itemForFile.get("itemDisplayName"), List.class);

			if((rel != null &&!rel.isEmpty()) || (prop != null && !prop.isEmpty()) ) {
				allEmpty = false;
			}
			
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

			if(prop != null) {
				for(String str : prop) {
					Hashtable<String, Object> mRow = gson.fromJson(str, Hashtable.class);
					if(!((String) mRow.get("selectedPropSubject").toString()).isEmpty() && !((String) mRow.get("selectedPropObject").toString()).isEmpty() && !((String) mRow.get("selectedPropDataType").toString()).isEmpty())
					{
						propWriter.addProperty((ArrayList<String>) mRow.get("selectedPropSubject"), (ArrayList<String>) mRow.get("selectedPropObject"), (String) mRow.get("selectedPropDataType").toString());
					}
				}
			}
			
			if(displayNames != null) {
				for(String str : displayNames) {
					Hashtable<String, Object> mRow = gson.fromJson(str, Hashtable.class);
					if(!((String) mRow.get("selectedNode").toString()).isEmpty() && !((String) mRow.get("selectedProperty").toString()).isEmpty() && !((String) mRow.get("selectedDisplayName").toString()).isEmpty())
					{
						propWriter.addDisplayName((ArrayList<String>) mRow.get("selectedNode"), (ArrayList<String>) mRow.get("selectedProperty"), (ArrayList<String>) mRow.get("selectedDisplayName"));
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
		if(allEmpty) {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "No metamodel has been specified.\n Please specify a metamodel in order to determine how to load this data.");
			return Response.status(400).entity(gson.toJson(errorHash)).build();
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
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Import method \'" + methodString + "\' is not supported");
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		}
		
		//Add engine owner for permissions
		if(this.securityEnabled) {
			Object user = request.getSession().getAttribute(Constants.SESSION_USER);
			if(user != null && !((User) user).getId().equals(Constants.ANONYMOUS_USER_ID)) {
				addEngineOwner(dbName, ((User) user).getId());
			} else {
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "Please log in to upload data.");
				return Response.status(400).entity(gson.toJson(errorHash)).build();
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
		
		String mapFile = "";
		if(inputData.get("mapFile") != null) {
			mapFile = inputData.get("mapFile");
		}
		String questionFile = "";
		if(inputData.get("questionFile") != null) {
			questionFile = inputData.get("questionFile");
		}
		try {
			if(importMethod == ImportDataProcessor.IMPORT_METHOD.CREATE_NEW) {
				// force fitting the RDBMS here
				importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.CSV, inputData.get("file")+"", 
						inputData.get("designateBaseUri"), dbName, mapFile,"", questionFile,"", storeType, rdbmsType, allowDuplicates, autoLoad);
				loadEngineIntoSession(request, dbName);
			} else { // add to existing or modify
				IEngine dbEngine = (IEngine) DIHelper.getInstance().getLocalProp(dbName);
				if (dbEngine.getEngineType() == IEngine.ENGINE_TYPE.RDBMS) {
					RDBMSNativeEngine rdbmsEngine = (RDBMSNativeEngine) dbEngine;
					storeType = ImportDataProcessor.DB_TYPE.RDBMS;
					rdbmsType = rdbmsEngine.getDbType();
				}
				importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.CSV, inputData.get("file")+"", 
						inputData.get("designateBaseUri"), "","","","", dbName, storeType, rdbmsType, allowDuplicates, autoLoad);
			}
		} catch (IOException e) {
			e.printStackTrace();
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", e.getMessage());
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		} catch (RepositoryException e) {
			e.printStackTrace();
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", e.getMessage());
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		} catch (SailException e) {
			e.printStackTrace();
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", e.getMessage());
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		} catch(Exception e) {
			e.printStackTrace();
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", e.getMessage());
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		} finally {
			deleteFilesFromServer(inputData.get("file").toString().split(";"));
			if(!mapFile.isEmpty()) {
				deleteFilesFromServer(new String[]{mapFile});
			}
			if(!questionFile.isEmpty()) {
				deleteFilesFromServer(new String[]{questionFile});
			}
		}

		try {
			Date currDate = Calendar.getInstance().getTime();
			SimpleDateFormat sdf = new SimpleDateFormat("yyMMddHHmmssZ");
			String dateName = sdf.format(currDate);
			for(int i = 0; i < size; i++) {
				FileUtils.writeStringToFile(new File(
						DIHelper.getInstance().getProperty("BaseFolder")
						.concat(File.separator).concat("db").concat(File.separator)
						.concat(Utility.cleanString(dbName, true).toString()).concat(File.separator)
						.concat(dbName.toString()).concat("_").concat(fileNames[i].replace(".csv", ""))
						.concat("_").concat(dateName).concat("_PROP.prop")), propFileArr[i]);
			}
		} catch (IOException e) {
			e.printStackTrace();
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Failure to write CSV Prop File based on user-defined metamodel.");
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		} 

		String outputText = "CSV Loading was a success.";
		return Response.status(200).entity(gson.toJson(outputText)).build();
	}
	
	@POST
	@Path("/excelTable/upload")
	@Produces("application/json")
	public Response uploadExcelReaderFile(@Context HttpServletRequest request)
	{
		//TODO: need to add rdbms version of this code
		List<FileItem> fileItems = processRequest(request);
		Hashtable<String, String> inputData = getInputData(fileItems);
		
		Gson gson = new Gson();

		//cleanedFiles - stringfield ExcelReader file returned from OpenRefine
		//If OpenRefine-returned ExcelReader string exists, user went through OpenRefine - write returned data to file first
		Object obj = inputData.get("cleanedFiles");

		String dbName = "";
		if(inputData.get("dbName") != null && !inputData.get("dbName").isEmpty()) {
			dbName = inputData.get("dbName");
		} else if(inputData.get("addDBname") != null && !inputData.get("addDBname").isEmpty()) {
			dbName = inputData.get("addDBname");
		} else {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "No database name was entered");
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		}
		dbName = cleanSpaces(dbName);

		if(obj != null) {
			String cleanedFileName = processOpenRefine(dbName, (String) obj);
			if(cleanedFileName.startsWith("Error")) {
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "Could not write the cleaned data to file. Please check application file-upload path.");
				return Response.status(400).entity(gson.toJson(errorHash)).build();
			}
			inputData.put("file", cleanedFileName);
		}
		
		List<String> allFileData = gson.fromJson(inputData.get("fileInfoArray"), List.class);
		int size = allFileData.size();
		
		Hashtable<String, String>[] propHashArr = new Hashtable[size];
		String[] propFileArr = new String[size];
		String[] fileNames = inputData.get("filename").split(";");
		boolean allEmpty = true;
		for(int i = 0; i < size; i++) {
			Hashtable<String, String> itemForFile = gson.fromJson(allFileData.get(i), Hashtable.class);
			ExcelPropFileBuilder propWriter = new ExcelPropFileBuilder();

			Hashtable<String, String> allSheetInfo = gson.fromJson(itemForFile.get("sheetInfo"), Hashtable.class);
			for(String sheet : allSheetInfo.keySet()) {
				Hashtable<String, String> sheetInfo = gson.fromJson(allSheetInfo.get(sheet), Hashtable.class);
				
				List<String> rel = gson.fromJson(sheetInfo.get("rowsRelationship"), List.class);
				List<String> prop = gson.fromJson(sheetInfo.get("rowsProperty"), List.class);
				if((rel != null &&!rel.isEmpty()) || (prop != null && !prop.isEmpty()) ) {
					allEmpty = false;
				}
				
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
		if(allEmpty) {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "No metamodel has been specified. \nPlease specify a metamodel in order to determine how to load this data.");
			return Response.status(400).entity(gson.toJson(errorHash)).build();
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
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Import method \'" + methodString + "\' is not supported");
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		}
		
		//Add engine owner for permissions
		if(this.securityEnabled) {
			Object user = request.getSession().getAttribute(Constants.SESSION_USER);
			if(user != null && !((User) user).getId().equals(Constants.ANONYMOUS_USER_ID)) {
				addEngineOwner(dbName, ((User) user).getId());
			} else {
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "Please log in to upload data.");
				return Response.status(400).entity(gson.toJson(errorHash)).build();
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
		
		String mapFile = "";
		if(inputData.get("mapFile") != null) {
			mapFile = inputData.get("mapFile");
		}
		String questionFile = "";
		if(inputData.get("questionFile") != null) {
			questionFile = inputData.get("questionFile");
		}
		try {
			if(methodString.equals("Create new database engine")) {
				// force fitting the RDBMS here
				importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.EXCEL, inputData.get("file")+"", 
						inputData.get("designateBaseUri"), dbName, mapFile,"", questionFile,"", storeType, rdbmsType, allowDuplicates, autoLoad);
				loadEngineIntoSession(request, dbName);
			} else { // add to existing or modify
				IEngine dbEngine = (IEngine) DIHelper.getInstance().getLocalProp(dbName);
				if (dbEngine.getEngineType() == IEngine.ENGINE_TYPE.RDBMS) {
					RDBMSNativeEngine rdbmsEngine = (RDBMSNativeEngine) dbEngine;
					storeType = ImportDataProcessor.DB_TYPE.RDBMS;
					rdbmsType = rdbmsEngine.getDbType();
				}
				importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.EXCEL, inputData.get("file")+"", 
						inputData.get("designateBaseUri"), mapFile,"", questionFile,"", dbName, storeType, rdbmsType, allowDuplicates, autoLoad);
			}
		} catch (IOException e) {
			e.printStackTrace();
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", e.getMessage());
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		} catch (RepositoryException e) {
			e.printStackTrace();
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", e.getMessage());
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		} catch (SailException e) {
			e.printStackTrace();
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", e.getMessage());
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		} catch(Exception e) {
			e.printStackTrace();
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", e.getMessage());
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		} finally {
			deleteFilesFromServer(inputData.get("file").toString().split(";"));
			if(!mapFile.isEmpty()) {
				deleteFilesFromServer(new String[]{mapFile});
			}
			if(!questionFile.isEmpty()) {
				deleteFilesFromServer(new String[]{questionFile});
			}
		}

		try {
			Date currDate = Calendar.getInstance().getTime();
			SimpleDateFormat sdf = new SimpleDateFormat("yyMMddHHmmssZ");
			String dateName = sdf.format(currDate);
			for(int i = 0; i < size; i++) {
				FileUtils.writeStringToFile(new File(
						DIHelper.getInstance().getProperty("BaseFolder")
						.concat(File.separator).concat("db").concat(File.separator)
						.concat(Utility.cleanString(dbName, true).toString()).concat(File.separator)
						.concat(dbName.toString()).concat("_").concat(fileNames[i].replace(".xls*", ""))
						.concat("_").concat(dateName).concat("_PROP.prop")), propFileArr[i]);
			}
		} catch (IOException e) {
			e.printStackTrace();
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Failure to write Excel Prop File based on user-defined metamodel.");
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		}
		
		String outputText = "CSV Loading was a success.";
		return Response.status(200).entity(gson.toJson(outputText)).build();
	}
	
	@POST
	@Path("/excel/upload")
	@Produces("application/json")
	public Response uploadExcelFile(@Context HttpServletRequest request) 
	{
		List<FileItem> fileItems = processRequest(request);
		// collect all of the data input on the form
		Hashtable<String, String> inputData = getInputData(fileItems);

		Gson gson = new Gson();

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
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Import method \'" + methodString + "\' is not supported");
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		}
		
		String dbName = "";
		if(inputData.get("dbName") != null && !inputData.get("dbName").isEmpty()) {
			dbName = inputData.get("dbName");
		} else if(inputData.get("addDBname") != null && !inputData.get("addDBname").isEmpty()) {
			dbName = inputData.get("addDBname");
		} else {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "No database name was entered");
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		}
		dbName = cleanSpaces(dbName);

		//Add engine owner for permissions
		if(this.securityEnabled) {
			Object user = request.getSession().getAttribute(Constants.SESSION_USER);
			if(user != null && !((User) user).getId().equals(Constants.ANONYMOUS_USER_ID)) {
				addEngineOwner(dbName, ((User) user).getId());
			} else {
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "Please log in to upload data.");
				return Response.status(400).entity(gson.toJson(errorHash)).build();
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
		
		String mapFile = "";
		if(inputData.get("mapFile") != null) {
			mapFile = inputData.get("mapFile");
		}
		String questionFile = "";
		if(inputData.get("questionFile") != null) {
			questionFile = inputData.get("questionFile");
		}
		
		try {
			if(methodString.equals("Create new database engine")) {
				importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.EXCEL_POI, inputData.get("file")+"", 
						inputData.get("customBaseURI"), dbName, mapFile,"", questionFile,"", storeType, rdbmsType, allowDuplicates, autoLoad);
				loadEngineIntoSession(request, dbName);
			} else {
				importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.EXCEL_POI, inputData.get("file")+"", 
						inputData.get("customBaseURI"), "", mapFile,"", questionFile, dbName, storeType, rdbmsType, allowDuplicates, autoLoad);
			}
		} catch (IOException e) {
			e.printStackTrace();
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", e.getMessage());
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		} catch (RepositoryException e) {
			e.printStackTrace();
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", e.getMessage());
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		} catch (SailException e) {
			e.printStackTrace();
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", e.getMessage());
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		} catch(Exception e) {
			e.printStackTrace();
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", e.getMessage());
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		} finally {
			deleteFilesFromServer(inputData.get("file").toString().split(";"));
			if(!mapFile.isEmpty()) {
				deleteFilesFromServer(new String[]{mapFile});
			}
			if(!questionFile.isEmpty()) {
				deleteFilesFromServer(new String[]{questionFile});
			}
		}

		String outputText = "Excel Loading was a success.";
		return Response.status(200).entity(gson.toJson(outputText)).build();
	}

	@POST
	@Path("/nlp/upload")
	@Produces("application/json")
	public Response uploadNLPFile(@Context HttpServletRequest request) {
		List<FileItem> fileItems = processRequest(request);
		// collect all of the data input on the form
		Hashtable<String, String> inputData = getInputData(fileItems);

		Gson gson = new Gson();
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
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Import method \'" + methodString + "\' is not supported");
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		}

		//call the right process method with correct parameters
		String dbName = "";
		if(inputData.get("dbName") != null && !inputData.get("dbName").isEmpty()) {
			dbName = inputData.get("dbName");
		} else if(inputData.get("addDBname") != null && !inputData.get("addDBname").isEmpty()) {
			dbName = inputData.get("addDBname");
		} else {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "No database name was entered");
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		}
		dbName = cleanSpaces(dbName);

		//Add engine owner for permissions
		if(this.securityEnabled) {
			Object user = request.getSession().getAttribute(Constants.SESSION_USER);
			if(user != null && !((User) user).getId().equals(Constants.ANONYMOUS_USER_ID)) {
				addEngineOwner(dbName, ((User) user).getId());
			} else {
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "Please log in to upload data.");
				return Response.status(400).entity(gson.toJson(errorHash)).build();
			}
		}
		
		// can only store as RDF
		ImportDataProcessor.DB_TYPE storeType = ImportDataProcessor.DB_TYPE.RDF;

		
		String uploadFiles = "";
		String file = "";
		if(inputData.get("file") != null && !inputData.get("file").toString().isEmpty()) {
			file = inputData.get("file").toString();
			uploadFiles = uploadFiles.concat(file);
		}
		if(inputData.get("nlptext") != null && !inputData.get("nlptext").toString().isEmpty()) {
			String inputText = filePath + System.getProperty("file.separator") + "Text_Input.txt";
			PrintWriter writer = null;
			try {
				writer = new PrintWriter(inputText);
				writer.write(inputData.get("nlptext").toString());
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} finally {
				if(writer != null) {
					writer.close();
				}
			}
			if(uploadFiles.isEmpty()) {
				uploadFiles = uploadFiles.concat(inputText);
				file = file.concat(inputText);
			} else {
				uploadFiles = uploadFiles.concat(";").concat(inputText);
				file = file.concat(":").concat(inputText);
			}
		}
		if(inputData.get("nlphttpurl") != null && !inputData.get("nlphttpurl").toString().isEmpty()) {
			if(uploadFiles.isEmpty()) {
				uploadFiles = uploadFiles.concat(inputData.get("nlphttpurl").toString());
			} else {
				uploadFiles = uploadFiles.concat(";").concat(inputData.get("nlphttpurl").toString());
			}
		}
		
		String questionFile = "";
		if(inputData.get("questionFile") != null) {
			questionFile = inputData.get("questionFile");
		}
		try {
			if(methodString.equals("Create new database engine")) {
				importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.NLP, uploadFiles, 
						inputData.get("customBaseURI")+"", dbName,"","", questionFile,"", storeType, null, false, autoLoad);
				loadEngineIntoSession(request, dbName);
			} else {
				importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.NLP, uploadFiles, 
						inputData.get("customBaseURI")+"", "","", questionFile,"", dbName, storeType, null, false, autoLoad);
			}
		} catch (IOException e) {
			e.printStackTrace();
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", e.getMessage());
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		} catch (RepositoryException e) {
			e.printStackTrace();
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", e.getMessage());
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		} catch (SailException e) {
			e.printStackTrace();
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", e.getMessage());
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		} catch(Exception e) {
			e.printStackTrace();
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", e.getMessage());
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		} finally {
			if(!file.isEmpty()) {
				deleteFilesFromServer(file.split(";"));
			}
			if(!questionFile.isEmpty()) {
				deleteFilesFromServer(new String[]{questionFile});
			}
		}

		String outputText = "NLP Loading was a success.";
		return Response.status(200).entity(gson.toJson(outputText)).build();
	}
	
	@POST
	@Path("/rdbms/getMetadata")
	@Produces("application/json")
	public Response getExistingRDBMSMetadata(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		
		Gson gson = new Gson();
		HashMap<String, ArrayList<String>> ret = new HashMap<String, ArrayList<String>>();
		ImportRDBMSProcessor importer = new ImportRDBMSProcessor();
		
		String driver = gson.fromJson(form.getFirst("driver"), String.class);
		String hostname = gson.fromJson(form.getFirst("hostname"), String.class);
		String port = gson.fromJson(form.getFirst("port"), String.class);
		String username = gson.fromJson(form.getFirst("username"), String.class);
		String password = gson.fromJson(form.getFirst("password"), String.class);
		String schema = gson.fromJson(form.getFirst("schema"), String.class);
		String connectionURL = gson.fromJson(form.getFirst("connectionURL"), String.class);
		
//		if(connectionURL != null && !connectionURL.isEmpty()) {
//			importer.setConnectionURL(connectionURL);
//		}
		ret = importer.getAllFields(driver, hostname, port, username, password, schema);
		
		return Response.status(200).entity(gson.toJson(ret)).build();
	}
	
	@POST
	@Path("/rdbms/connect")
	@Produces("application/json")
	public Response connectExistingRDBMS(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		
		Gson gson = new Gson();
		HashMap<String, Object> ret = new HashMap<String, Object>();
		ImportRDBMSProcessor importer = new ImportRDBMSProcessor();
		HashMap<String, Object> metamodel = new HashMap<String, Object>();
		HashMap<String, ArrayList<String>> nodesAndProps = new HashMap<String, ArrayList<String>>();
		ArrayList<String[]> nodeRelationships = new ArrayList<String[]>();
		
		HashMap<String, Object> details = gson.fromJson(form.getFirst("details"), new TypeToken<HashMap<String, Object>>() {}.getType());		
		HashMap<String, String> options = gson.fromJson(gson.toJson(details.get("options")), new TypeToken<HashMap<String, Object>>() {}.getType());
		HashMap<String, String> metamodelData = gson.fromJson(gson.toJson(details.get("metamodelData")), new TypeToken<HashMap<String, Object>>() {}.getType());
		HashMap<String, String> databaseOptions = gson.fromJson(gson.toJson(details.get("databaseOptions")), new TypeToken<HashMap<String, String>>() {}.getType());
		
		ArrayList<Object> nodes = gson.fromJson(gson.toJson(metamodelData.get("nodes")), new TypeToken<ArrayList<Object>>() {}.getType());
		for(Object o: nodes) {
			ArrayList<String> props = new ArrayList<String>();
			HashMap<String, Object> nodeHash = gson.fromJson(gson.toJson(o), new TypeToken<HashMap<String, Object>>() {}.getType());
			ArrayList<String> properties = gson.fromJson(gson.toJson(nodeHash.get("prop")), new TypeToken<ArrayList<String>>() {}.getType());
			for(String prop : properties) {
				props.add(prop); 
			}
			nodesAndProps.put(nodeHash.get("node") + "." + nodeHash.get("primaryKey"), props); // Table1.idTable1 -> [prop1, prop2, ...]
		}
		metamodel.put("nodes", nodesAndProps);
		
		ArrayList<Object> relationships = gson.fromJson(gson.toJson(metamodelData.get("relationships")), new TypeToken<ArrayList<Object>>() {}.getType());
		for(Object o: relationships) {
			HashMap<String, String> relationship = gson.fromJson(gson.toJson(o), new TypeToken<HashMap<String, String>>() {}.getType());
			nodeRelationships.add(new String[] { relationship.get("sub"), relationship.get("pred"), relationship.get("obj") });
		}
		metamodel.put("relationships", nodeRelationships);
		
		boolean success = importer.addNewRDBMS(options.get("driver"), options.get("hostname"), options.get("port"), options.get("username"), options.get("password"), options.get("schema"), cleanSpaces(databaseOptions.get("databaseName")), metamodel);
		
		ret.put("success", success);
		if(success) {
			return Response.status(200).entity(gson.toJson(ret)).build();
		} else {
			return Response.status(400).entity(gson.toJson(ret)).build();
		}
	}
	
	@POST
	@Path("/rdbms/test")
	@Produces("application/json")
	public Response testExistingRDBMSConnection(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		
		Gson gson = new Gson();
		String driver = gson.fromJson(form.getFirst("driver"), String.class);
		String hostname = gson.fromJson(form.getFirst("hostname"), String.class);
		String port = gson.fromJson(form.getFirst("port"), String.class);
		String username = gson.fromJson(form.getFirst("username"), String.class);
		String password = gson.fromJson(form.getFirst("password"), String.class);
		String schema = gson.fromJson(form.getFirst("schema"), String.class);
		String connectionURL = gson.fromJson(form.getFirst("connectionURL"), String.class);
		
		HashMap<String, Object> ret = new HashMap<String, Object>();
		ImportRDBMSProcessor importer = new ImportRDBMSProcessor();
		
//		if(connectionURL != null && !connectionURL.isEmpty()) {
//			importer.setConnectionURL(connectionURL);
//		}
		
		String test = importer.checkConnectionParams(driver, hostname, port, username, password, schema);
		if(Boolean.parseBoolean(test)) {
			ret.put("success", Boolean.parseBoolean(test));
			return Response.status(200).entity(gson.toJson(ret)).build();
		} else {
			ret.put("error", test);
			return Response.status(400).entity(gson.toJson(ret)).build();
		}
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
		masterDB.addEngineAndOwner(engine, userId);
	}
	
	//TODO: this cleaning will not be necessary once insights are shifted to RDBMS
	private String cleanSpaces(String s) {
		while (s.contains("  ")){
			s = s.replace("  ", " ");
		}
		return s.replaceAll(" ", "_");
	}
	
}
