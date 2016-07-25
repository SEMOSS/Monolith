package prerna.upload;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.io.FileUtils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import prerna.auth.User;
import prerna.auth.UserPermissionsMasterDB;
import prerna.poi.main.CSVPropFileBuilder;
import prerna.poi.main.ExcelPropFileBuilder;
import prerna.poi.main.HeadersException;
import prerna.poi.main.MetaModelCreator;
import prerna.poi.main.helper.CSVFileHelper;
import prerna.poi.main.helper.ImportOptions;
import prerna.poi.main.helper.XLFileHelper;
import prerna.rdf.main.ImportRDBMSProcessor;
import prerna.ui.components.ImportDataProcessor;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;
import prerna.util.sql.SQLQueryUtil;
import prerna.web.services.util.WebUtility;

public class DatabaseUploader extends Uploader {

	// we will control the adding of the engine into local master and solr
	// such that we dont send a success before those processes are complete
	boolean autoLoad = false;

	///////////////////////////////////////////// SET DEFAULT OPTIONS ///////////////////////////////////////////////

	/*
	 * We currently have two options for this... which are exactly the same
	 * but one takes in a hashtable and the other a multivalued map
	 * 
	 * -> hashtable is used when the file is passed in the existing call
	 * -> multivalued map is used when the file already exists on the server
	 * 		and we need to perform the data load
	 * 
	 * Sets basically all the default options except the files to upload!!!
	 * -> this is because NLP can load in files, or websites, etc. so that messes
	 * 		up the generic flow :/
	 * 
	 */

	/**
	 * Does the following for the passed in ImportOptions object
	 * 1) Sets the Import Method
	 * 2) Sets the Database Name
	 * 3) Sets the Database Type
	 * 		If RDBMS Type
	 * 		3a) Sets the RDBMS driver type
	 * 		3b) Sets the boolean to allow duplicates or not
	 * 4) Sets the default question file
	 * @param options					ImportOptions object used to load files
	 * @param inputData					The input data map containing the user values
	 * @throws IOException
	 */
	private void setDefaultOptions(ImportOptions options, Map<String, String> inputData) throws IOException{
		options.setBaseFolder(DIHelper.getInstance().getProperty("BaseFolder"));
		options.setAutoLoad(autoLoad);

		// figure out what type of import we need to do based on parameters selected
		String methodString = inputData.get("importMethod");
		ImportOptions.IMPORT_METHOD importMethod = 
				methodString.equals("Create new database engine") ? ImportOptions.IMPORT_METHOD.CREATE_NEW
						: methodString.equals("addEngine") ? ImportOptions.IMPORT_METHOD.ADD_TO_EXISTING
								: methodString.equals("modifyEngine") ? ImportOptions.IMPORT_METHOD.OVERRIDE
										: null;
		if(importMethod == null) {
			String errorMessage = "Import method \'" + methodString + "\' is not supported";
			throw new IOException(errorMessage);
		}
		options.setImportMethod(importMethod);

		// get the db name
		String dbName = inputData.get("dbName");
		if(dbName == null || dbName.trim().isEmpty()) {
			String errorMessage = "Database name \'" + dbName + "\' is invalid";
			throw new IOException(errorMessage);
		}
		options.setDbName(makeAlphaNumeric(dbName));

		// determine the db type
		String dbType = inputData.get("dataOutputType");
		if(dbType.equalsIgnoreCase("RDBMS")) {
			options.setDbType(ImportOptions.DB_TYPE.RDBMS);

			// if rdbms, also get the rdbms driver type
			String rdbmsDriverType = inputData.get("rdbmsOutputType");
			if(rdbmsDriverType == null || rdbmsDriverType.isEmpty()) {
				// default to h2
				options.setRDBMSDriverType(SQLQueryUtil.DB_TYPE.H2_DB);
			} else {
				options.setRDBMSDriverType(SQLQueryUtil.DB_TYPE.valueOf(rdbmsDriverType.toUpperCase()));
			}

			// if rdbms, also need to know if user wants duplicates or not in table
			// TODO: need to expose this to the user on the UI
			boolean allowDuplicates = false;
			options.setAllowDuplicates(allowDuplicates);

		} else {
			// default to RDF db type
			options.setDbType(ImportOptions.DB_TYPE.RDF);
		}

		// get the question file is present
		String questionFile = inputData.get("questionFile");
		if(questionFile != null && !questionFile.trim().isEmpty()) {
			options.setQuestionFile(questionFile);
		}
	}

	/**
	 * Does the following for the passed in ImportOptions object
	 * 1) Sets the Import Method
	 * 2) Sets the Database Name
	 * 3) Sets the Database Type
	 * 		If RDBMS Type
	 * 		3a) Sets the RDBMS driver type
	 * 		3b) Sets the boolean to allow duplicates or not
	 * 4) Sets the default question file
	 * @param options					ImportOptions object used to load files
	 * @param inputData					The input data map containing the user values
	 * @throws IOException
	 */
	private void setDefaultOptions(ImportOptions options, MultivaluedMap<String, String> form) throws IOException{
		options.setBaseFolder(DIHelper.getInstance().getProperty("BaseFolder"));
		options.setAutoLoad(autoLoad);

		// figure out what type of import we need to do based on parameters selected
		String methodString = form.getFirst("importMethod");
		ImportOptions.IMPORT_METHOD importMethod = 
				methodString.equals("Create new database engine") ? ImportOptions.IMPORT_METHOD.CREATE_NEW
						: methodString.equals("addEngine") ? ImportOptions.IMPORT_METHOD.ADD_TO_EXISTING
								: methodString.equals("modifyEngine") ? ImportOptions.IMPORT_METHOD.OVERRIDE
										: null;
		if(importMethod == null) {
			String errorMessage = "Import method \'" + methodString + "\' is not supported";
			throw new IOException(errorMessage);
		}
		options.setImportMethod(importMethod);

		// get the db name
		String dbName = form.getFirst("dbName");
		if(dbName == null || dbName.trim().isEmpty()) {
			String errorMessage = "Database name \'" + dbName + "\' is invalid";
			throw new IOException(errorMessage);
		}
		options.setDbName(makeAlphaNumeric(dbName));

		// determine the db type
		// do not need this when we do add to existing
		String dbType = form.getFirst("dataOutputType");
		if(dbType != null) {
			if(dbType.equalsIgnoreCase("RDBMS")) {
				options.setDbType(ImportOptions.DB_TYPE.RDBMS);

				// if rdbms, also get the rdbms driver type
				String rdbmsDriverType = form.getFirst("rdbmsOutputType");
				if(rdbmsDriverType == null || rdbmsDriverType.isEmpty()) {
					// default to h2
					options.setRDBMSDriverType(SQLQueryUtil.DB_TYPE.H2_DB);
				} else {
					options.setRDBMSDriverType(SQLQueryUtil.DB_TYPE.valueOf(rdbmsDriverType.toUpperCase()));
				}

				// if rdbms, also need to know if user wants duplicates or not in table
				// TODO: need to expose this to the user on the UI
				boolean allowDuplicates = false;
				options.setAllowDuplicates(allowDuplicates);

			} else {
				// default to RDF db type
				options.setDbType(ImportOptions.DB_TYPE.RDF);
			}
		}

		// get the question file is present
		String questionFile = form.getFirst("questionFile");
		if(questionFile != null && !questionFile.trim().isEmpty()) {
			options.setQuestionFile(questionFile);
		}
	}

	///////////////////////////////////////////// END SET DEFAULT OPTIONS ///////////////////////////////////////////////

	///////////////////////////////////////////// CHECK HEADERS ////////////////////////////////////////////////////////
	
	@POST
	@Path("/headerCheck")
	@Produces("application/json")
	public Response checkUserDefinedHeaders(MultivaluedMap<String, String> form) {
		Gson gson = new Gson();
		
		// this will let me know if I should expect the csv data type map or the excel
		// data type map
		// this is really annoying... 
		// TODO: really should consider consolidating the formats it will make csv format look dumb 
		// since its a useless additional key but then I wouldn't need to have the bifurcation in 
		// formats here and bifurcation in formats in the ImportOptions object as well
		String type = form.getFirst("uploadType").toUpperCase();
		
		// grab the checker
		HeadersException headerChecker = HeadersException.getInstance();

		if(type.equals("CSV")) {
			Map<String, Map<String, String>> invalidHeadersMap = new Hashtable<String, Map<String, String>>();
			
			// the key is for each file name
			// the list are the headers inside that file
			Map<String, String[]> userDefinedHeadersMap = gson.fromJson(form.getFirst("userHeaders"), new TypeToken<Map<String, String[]>>() {}.getType());
			
			for(String fileName : userDefinedHeadersMap.keySet()) {
				String[] userHeaders = userDefinedHeadersMap.get(fileName);
				
				// now we need to check all of these headers
				// now we need to check all of these headers
				for(int colIdx = 0; colIdx < userHeaders.length; colIdx++) {
					String userHeader = userHeaders[colIdx];
					Map<String, String> badHeaderMap = new Hashtable<String, String>();
					if(headerChecker.isIllegalHeader(userHeader)) {
						badHeaderMap.put(userHeader, "This header name is a reserved word");
					} else if(headerChecker.containsIllegalCharacter(userHeader)) {
						badHeaderMap.put(userHeader, "Header names cannot contain +%@;");
					} else if(headerChecker.isDuplicated(userHeader, userHeaders, colIdx)) {
						badHeaderMap.put(userHeader, "Cannot have duplicate header names");
					}
					
					// map is filled in only if the header is bad
					if(!badHeaderMap.isEmpty()) {
						// need to make sure we do not override existing bad headers stored
						// within the map
						Map<String, String> invalidHeadersForFile = null;
						if(invalidHeadersMap.containsKey(fileName)) {
							invalidHeadersForFile = invalidHeadersMap.get(fileName);
						} else {
							invalidHeadersForFile = new Hashtable<String, String>();
						}
						
						// now add in the bad header for the file map
						invalidHeadersForFile.putAll(badHeaderMap);
						// now store it in the overall object
						invalidHeadersMap.put(fileName, invalidHeadersForFile);
					}
				}
			}
			return Response.status(200).entity(WebUtility.getSO(invalidHeadersMap)).build();

		} else if(type.equals("EXCEL")) {
			List<Map<String, Map<String, String>>> invalidHeadersList = new Vector<Map<String, Map<String, String>>>();
			
			// each entry (outer map object) in the list if a workbook
			// each key in that map object is the sheetName for that given workbook
			// the list are the headers inside that sheet
			List<Map<String, String[]>> userDefinedHeadersMap = gson.fromJson(form.getFirst("userHeaders"), new TypeToken<List<Map<String, Map<String, String[]>>>>() {}.getType());
			
			// iterate through each workbook
			for(Map<String, String[]> excelWorkbook : userDefinedHeadersMap) {
				Map<String, Map<String, String>> invalidHeadersMap = new Hashtable<String, Map<String, String>>();
				
				for(String sheetName : excelWorkbook.keySet()) {
					// grab all the headers for the given sheet
					String[] userHeaders = excelWorkbook.get(sheetName);

					// now we need to check all of these headers
					for(int colIdx = 0; colIdx < userHeaders.length; colIdx++) {
						String userHeader = userHeaders[colIdx];
						Map<String, String> badHeaderMap = new Hashtable<String, String>();
						if(headerChecker.isIllegalHeader(userHeader)) {
							badHeaderMap.put(userHeader, "This header name is a reserved word");
						} else if(headerChecker.containsIllegalCharacter(userHeader)) {
							badHeaderMap.put(userHeader, "Header names cannot contain +%@;");
						} else if(headerChecker.isDuplicated(userHeader, userHeaders, colIdx)) {
							badHeaderMap.put(userHeader, "Cannot have duplicate header names");
						}
						
						// map is filled in only if the header is bad
						if(!badHeaderMap.isEmpty()) {
							// need to make sure we do not override existing bad headers stored
							// within the map
							Map<String, String> invalidHeadersForSheet = null;
							if(invalidHeadersMap.containsKey(sheetName)) {
								invalidHeadersForSheet = invalidHeadersMap.get(sheetName);
							} else {
								invalidHeadersForSheet = new Hashtable<String, String>();
							}
							
							// now add in the bad header for the file map
							invalidHeadersForSheet.putAll(badHeaderMap);
							// now store it in the overall object
							invalidHeadersMap.put(sheetName, invalidHeadersForSheet);
						}
					}
				}
				
				// now store the invalid headers map inside the list
				// even if it is empty, we need to store it since the FE does this based on indices
				invalidHeadersList.add(invalidHeadersMap);
			}
			return Response.status(200).entity(WebUtility.getSO(invalidHeadersList)).build();
		} else {
			return Response.status(400).entity("Format does not conform to checking headers").build();
		}
	}
	
	///////////////////////////////////////////// END CHECK HEADERS ////////////////////////////////////////////////////////

	
	////////////////////////////////////////////// START CSV UPLOADING //////////////////////////////////////////////////////////

	@POST
	@Path("/csv/uploadFile")
	@Produces("application/json")
	public Response uploadCsvFileAndPredictMetamodel(@Context HttpServletRequest request) {
		Gson gson = new Gson();
		
		//process request
		List<FileItem> fileItems = processRequest(request);
		// collect all of the data input on the form
		Hashtable<String, String> inputData = getInputData(fileItems);
		
		// objects to store data
		// master object to send to FE
		Map<String, Object> returnObj = new HashMap<>(2);
		// this will store the MM info for all the files
		List<Map<String, Object>> metaModelData = new Vector<>(2);
		
		try {

			String[] files = inputData.get("file").split(";");
			String props = inputData.get("propFile");
			String[] propFiles = null;
			if(props != null && !props.isEmpty()) {
				propFiles = props.split(";");
			}
			for(int i = 0; i < files.length; i++) {
				// this is the MM info for one of the files within the metaModelData list
				Map<String, Object> fileMetaModelData = new HashMap<String, Object>();
				
				// store the file location on server so FE can send that back into actual upload routine
				fileMetaModelData.put("fileLocation", files[i]);
				String file = files[i].substring(files[i].lastIndexOf("\\") + 1, files[i].lastIndexOf("."));
				try {
					file = file.substring(0, file.length() - 24); //taking out the date added onto the original file name
				} catch(Exception e) {
					file = files[i].substring(files[i].lastIndexOf("\\") + 1, files[i].lastIndexOf(".")); //just in case that fails, this shouldnt because if its a filename it should have a "."
				}
				fileMetaModelData.put("fileName", file);
				
				CSVFileHelper helper = new CSVFileHelper();
				// TODO: should enable any kind of single char delimited file
				// have FE pass this info
				helper.setDelimiter(',');
				helper.parse(files[i]);
				
				// store messages when the csv file helper automatically modifies the column headers
				Map<String, String> fileHeaderMods = helper.getChangedHeaders();
				fileMetaModelData.put("headerModifications", fileHeaderMods);

				//if we get a flag from the front end to create meta model then create it
				String generateMetaModel = inputData.get("generateMetaModel").toString();
				MetaModelCreator predictor;
				if(generateMetaModel.equals("auto")) {
					predictor = new MetaModelCreator(helper, MetaModelCreator.CreatorMode.AUTO);
					predictor.constructMetaModel();
					Map<String, List<Map<String, Object>>> metaModel = predictor.getMetaModelData();
					fileMetaModelData.putAll(metaModel);
				} else if(generateMetaModel.equals("prop")) {
					//turn prop file into meta data
					predictor = new MetaModelCreator(helper, MetaModelCreator.CreatorMode.PROP);
					predictor.addPropFile(propFiles[i]);
					predictor.constructMetaModel();
					Map<String, List<Map<String, Object>>> metaModel = predictor.getMetaModelData();
					fileMetaModelData.putAll(metaModel);

				} 
				// flat table now doesn't generate a metamodel
				// just put all the data types
//				else if(generateMetaModel.equals("table")) {
//					//return metamodel with one column as main column/primary key
//					predictor = new MetaModelCreator(helper, MetaModelCreator.CreatorMode.TABLE);
//					predictor.constructMetaModel();
//					Map<String, List<Map<String, Object>>> metaModel = predictor.getMetaModelData();
//					fileMetaModelData.putAll(metaModel);
//				} 
				else {
					predictor = new MetaModelCreator(helper, null);
					fileMetaModelData.put("dataTypes", predictor.getDataTypeMap());
				}
				
				//TODO: NEED TO BETTER CONSOLIDATE ALL THIS STUFF
				if(generateMetaModel.equals("auto") || generateMetaModel.equals("prop")) {
					// if we have a metamodel, do the following options
					int start = predictor.getStartRow();
					int end = predictor.getEndRow();
					fileMetaModelData.put("startCount", start);
					fileMetaModelData.put("endCount", end);
					
					// determine the allowableDataTypes
					Map<String, String> dataTypeMap = predictor.getDataTypeMap();
					Map<String, List<String>> allowableDataTypes = new LinkedHashMap<>();
					for(String header : dataTypeMap.keySet()) {
						List<String> dataTypeList = new ArrayList<>(2);
						dataTypeList.add("STRING");
	
						String type = dataTypeMap.get(header);
						if(!type.equals("STRING")) {
							if(type.equals("DOUBLE")) {
								dataTypeList.add("NUMBER");
							}
							else {
								dataTypeList.add(type);
							}
						}
						allowableDataTypes.put(header, dataTypeList);
					}
					fileMetaModelData.put("allowable", allowableDataTypes);
				}
				
				// add the info to the metamodel data to send
				metaModelData.add(fileMetaModelData);
			}
		} catch(Exception e) { 
			e.printStackTrace();
			
			// grab the error thrown and send it to the FE
			HashMap<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("errorMessage", e.getMessage());
			return Response.status(400).entity(WebUtility.getSO(errorMap)).build();
		}
		
		// store the info
		returnObj.put("metaModelData", metaModelData);
		
		// add question file location on server is present
		if(inputData.containsKey("questionFile")){
			returnObj.put("questionFile", returnObj.get("questionFile"));
		}
		
		return Response.status(200).entity(gson.toJson(returnObj)).build();
	}

	@POST
	@Path("/csv/processUpload")
	@Produces("application/json")
	public Response uploadCSVFile(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		Gson gson = new Gson();
		System.out.println(form);

		ImportOptions options = null;
		String[] propFileArr = null;
		try {
			options = new ImportOptions();
			// set the default options passed from user
			setDefaultOptions(options, form);
			// can only load flat files as RDBMS
			options.setImportType(ImportOptions.IMPORT_TYPE.CSV);

			// open refine logic
			Object obj = form.get("cleanedFiles");
			if(obj != null) {
				String cleanedFileName = processOpenRefine(options.getDbName(), (String) obj);
				if(cleanedFileName.startsWith("Error")) {
					Map<String, String> errorHash = new HashMap<String, String>();
					errorHash.put("errorMessage", "Could not write the cleaned data to file. Please check application file-upload path.");
					return Response.status(400).entity(gson.toJson(errorHash)).build();
				}
				form.putSingle("file", cleanedFileName);
			}

			// set the files
			String files = form.getFirst("file");
			if(files == null || files.trim().isEmpty()) {
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "No files have been identified to upload");
				return Response.status(400).entity(gson.toJson(errorHash)).build();
			}
			options.setFileLocation(files);

			////////////////////// begin logic to process metamodel for csv flat table //////////////////////
			List<String> allFileData = gson.fromJson(form.getFirst("fileInfoArray"), List.class);
			int size = allFileData.size();

			Hashtable<String, String>[] propHashArr = new Hashtable[size];
			propFileArr = new String[size];

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
			// set it in the options
			options.setMetamodelArray(propHashArr);
			////////////////////// end logic to process metamodel for csv flat table //////////////////////

			// add engine owner for permissions
			if(this.securityEnabled) {
				Object user = request.getSession().getAttribute(Constants.SESSION_USER);
				if(user != null && !((User) user).getId().equals(Constants.ANONYMOUS_USER_ID)) {
					addEngineOwner(options.getDbName(), ((User) user).getId());
				} else {
					Map<String, String> errorHash = new HashMap<String, String>();
					errorHash.put("errorMessage", "Please log in to upload data.");
					return Response.status(400).entity(gson.toJson(errorHash)).build();
				}
			}

			// run the import
			ImportDataProcessor importer = new ImportDataProcessor();
			importer.runProcessor(options);
		} catch (IOException e) {
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
			// we can't really use the options object for this :/
			String files = form.getFirst("file");
			if(files != null && !files.isEmpty()) {
				deleteFilesFromServer(files.split(";"));
			}
			String questionFile = form.getFirst("questionFile");
			if(questionFile != null && !questionFile.isEmpty()) {
				deleteFilesFromServer(new String[]{questionFile});
			}
		}

		try {
			Date currDate = Calendar.getInstance().getTime();
			SimpleDateFormat sdf = new SimpleDateFormat("yyMMddHHmmssZ");
			String dateName = sdf.format(currDate);

			String dbName = options.getDbName();
			String[] fileNames = options.getFileLocations().split(";");
			for(int i = 0; i < propFileArr.length; i++) {
				String fileName = new File(fileNames[i]).getName().replace(".csv", "");
				FileUtils.writeStringToFile(new File(
						DIHelper.getInstance().getProperty("BaseFolder")
						.concat(File.separator).concat("db").concat(File.separator)
						.concat(Utility.cleanString(dbName, true).toString()).concat(File.separator)
						.concat(dbName.toString()).concat("_").concat(fileName)
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
	@Path("/flat/upload")
	@Produces("application/json")
	/**
	 * The process flow for loading a flat file based on drag/drop
	 * Since the user confirms what type of datatypes they want for each column
	 * The file has already been loaded onto the server
	 * @param form					Form object containing all the information regarding the load
	 * @param request
	 * @return
	 */
	public Response uploadFlatCsvFile(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		Gson gson = new Gson();
		System.out.println(form);

		ImportOptions options = null;
		try {
			options = new ImportOptions();
			setDefaultOptions(options, form);

			// can only load flat files as RDBMS
			options.setDbType(ImportOptions.DB_TYPE.RDBMS);
			options.setImportType(ImportOptions.IMPORT_TYPE.CSV_FLAT_LOAD);

			// set the files
			String files = form.getFirst("file");
			if(files == null || files.trim().isEmpty()) {
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "No files have been identified to upload");
				return Response.status(400).entity(gson.toJson(errorHash)).build();
			}
			options.setFileLocation(files);

			List<Map<String, String[]>> headerData = null;
			String headerDataStr = form.getFirst("headerData");
			if(headerDataStr != null) {
				headerData = gson.fromJson(headerDataStr, new TypeToken<List<Map<String, String[]>>>() {}.getType());
				options.setCsvDataTypeMap(headerData);
			}
			
			// add engine owner for permissions
			if(this.securityEnabled) {
				Object user = request.getSession().getAttribute(Constants.SESSION_USER);
				if(user != null && !((User) user).getId().equals(Constants.ANONYMOUS_USER_ID)) {
					addEngineOwner(options.getDbName(), ((User) user).getId());
				} else {
					Map<String, String> errorHash = new HashMap<String, String>();
					errorHash.put("errorMessage", "Please log in to upload data.");
					return Response.status(400).entity(gson.toJson(errorHash)).build();
				}
			}

			ImportDataProcessor importer = new ImportDataProcessor();
			importer.runProcessor(options);
		} catch (IOException e) {
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
			// we can't really use the options object for this :/
			String files = form.getFirst("file");
			if(files != null && !files.isEmpty()) {
				deleteFilesFromServer(files.split(";"));
			}
			String questionFile = form.getFirst("questionFile");
			if(questionFile != null && !questionFile.isEmpty()) {
				deleteFilesFromServer(new String[]{questionFile});
			}
		}

		String outputText = "Flat database loading was a success.";
		return Response.status(200).entity(gson.toJson(outputText)).build();
	}
	
	////////////////////////////////////////////// END CSV UPLOADING //////////////////////////////////////////////////////////
	
	////////////////////////////////////////////// START EXCEL UPLOADING //////////////////////////////////////////////////////////

	@POST
	@Path("/excel/uploadFile")
	@Produces("application/json")
	public Response uploadExcelFile(@Context HttpServletRequest request) {
		Gson gson = new Gson();
		
		//process request
		List<FileItem> fileItems = processRequest(request);
		// collect all of the data input on the form
		Hashtable<String, String> inputData = getInputData(fileItems);
		
		// objects to store data
		// master object to send to FE
		Map<String, Object> returnObj = new HashMap<>(2);
		// this will store the MM info for all the files
		List<Map<String, Object>> metaModelData = new Vector<>(2);
		
		try {
			String[] files = inputData.get("file").split(";");
			for(int i = 0; i < files.length; i++) {
				// this is the MM info for one of the files within the metaModelData list
				Map<String, Object> fileMetaModelData = new HashMap<String, Object>();
				
				// store the file location on server so FE can send that back into actual upload routine
				fileMetaModelData.put("fileLocation", files[i]);
				String file = files[i].substring(files[i].lastIndexOf("\\") + 1, files[i].lastIndexOf("."));
				try {
					file = file.substring(0, file.length() - 24); //taking out the date added onto the original file name
				} catch(Exception e) {
					file = files[i].substring(files[i].lastIndexOf("\\") + 1, files[i].lastIndexOf(".")); //just in case that fails, this shouldnt because if its a filename it should have a "."
				}
				fileMetaModelData.put("fileName", file);
				
				XLFileHelper helper = new XLFileHelper();
				helper.parse(files[i]);
				
				// store messages when the csv file helper automatically modifies the column headers
				Map<String, Map<String, String>> fileHeaderMods = helper.getChangedHeaders();
				fileMetaModelData.put("headerModifications", fileHeaderMods);

				// add the info to the metamodel data to send
				metaModelData.add(fileMetaModelData);
			}
		} catch(Exception e) { 
			e.printStackTrace();
			
			// grab the error thrown and send it to the FE
			HashMap<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("errorMessage", e.getMessage());
			return Response.status(400).entity(WebUtility.getSO(errorMap)).build();
		}
		
		// store the info
		returnObj.put("metaModelData", metaModelData);
		
		return Response.status(200).entity(gson.toJson(returnObj)).build();
	}
	
	
	@POST
	@Path("/excel/processUpload")
	@Produces("application/json")
	public Response processExcelFile(@Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		List<FileItem> fileItems = processRequest(request);
		// collect all of the data input on the form
		Hashtable<String, String> inputData = getInputData(fileItems);
		System.out.println(inputData);

		ImportOptions options = null;
		String[] propFileArr = null;
		try {
			// load as many default options as possible
			options = new ImportOptions();
			// now just load the rest as usual
			setDefaultOptions(options, inputData);
			// set the import type
			options.setImportType(ImportOptions.IMPORT_TYPE.EXCEL);

			// open refine logic
			Object obj = inputData.get("cleanedFiles");
			if(obj != null) {
				String cleanedFileName = processOpenRefine(options.getDbName(), (String) obj);
				if(cleanedFileName.startsWith("Error")) {
					Map<String, String> errorHash = new HashMap<String, String>();
					errorHash.put("errorMessage", "Could not write the cleaned data to file. Please check application file-upload path.");
					return Response.status(400).entity(gson.toJson(errorHash)).build();
				}
				inputData.put("file", cleanedFileName);
			}

			// only default options not set are the files
			String files = inputData.get("file");
			if(files == null || files.trim().isEmpty()) {
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "No files have been identified to upload");
				return Response.status(400).entity(gson.toJson(errorHash)).build();
			}
			options.setFileLocation(files);

			////////////////////// begin logic to process metamodel for excel flat table rdf //////////////////////
			List<String> allFileData = gson.fromJson(inputData.get("fileInfoArray"), List.class);
			int size = allFileData.size();

			Hashtable<String, String>[] propHashArr = new Hashtable[size];
			propFileArr = new String[size];
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
			// set it in the options
			options.setMetamodelArray(propHashArr);
			////////////////////// end logic to process metamodel for excel flat table rdf //////////////////////

			// add engine owner for permissions
			if(this.securityEnabled) {
				Object user = request.getSession().getAttribute(Constants.SESSION_USER);
				if(user != null && !((User) user).getId().equals(Constants.ANONYMOUS_USER_ID)) {
					addEngineOwner(options.getDbName(), ((User) user).getId());
				} else {
					Map<String, String> errorHash = new HashMap<String, String>();
					errorHash.put("errorMessage", "Please log in to upload data.");
					return Response.status(400).entity(gson.toJson(errorHash)).build();
				}
			}

			// run the processor
			ImportDataProcessor importer = new ImportDataProcessor();
			importer.runProcessor(options);
		} catch (IOException e) {
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
			String questionFile = inputData.get("questionFile");
			if(questionFile != null && !questionFile.isEmpty()) {
				deleteFilesFromServer(new String[]{questionFile});
			}				
		}

		// need to write out the property files
		try {
			Date currDate = Calendar.getInstance().getTime();
			SimpleDateFormat sdf = new SimpleDateFormat("yyMMddHHmmssZ");
			String dateName = sdf.format(currDate);

			String dbName = options.getDbName();
			String[] fileNames = options.getFileLocations().split(";");
			for(int i = 0; i < propFileArr.length; i++) {
				FileUtils.writeStringToFile(new File(
						DIHelper.getInstance().getProperty("BaseFolder")
						.concat(File.separator).concat("db").concat(File.separator)
						.concat(dbName).concat(File.separator).concat(dbName).concat("_")
						.concat(fileNames[i].replace(".xls*", ""))
						.concat("_").concat(dateName).concat("_PROP.prop")), propFileArr[i]);
			}
		} catch (IOException e) {
			e.printStackTrace();
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Failure to write Excel Prop File based on user-defined metamodel.");
			return Response.status(400).entity(gson.toJson(errorHash)).build();
		}

		String outputText = "Excel Loading was a success.";
		return Response.status(200).entity(gson.toJson(outputText)).build();
	}

	@POST
	//TODO: this path should be excel/uploadPOI
	//TODO: cannot make this change without changing the path above
	@Path("/excel/upload")
	@Produces("application/json")
	public Response uploadExcelPOIFormat(@Context HttpServletRequest request) 
	{
		List<FileItem> fileItems = processRequest(request);
		// collect all of the data input on the form
		Hashtable<String, String> inputData = getInputData(fileItems);
		System.out.println(inputData);

		Gson gson = new Gson();
		ImportOptions options = null;
		try {
			// load as many default options as possible
			options = new ImportOptions();
			// now just load the rest as usual
			setDefaultOptions(options, inputData);
			// set the import type
			options.setImportType(ImportOptions.IMPORT_TYPE.EXCEL_POI);

			// only default options not set are the files
			String files = inputData.get("file");
			if(files == null || files.trim().isEmpty()) {
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "No files have been identified to upload");
				return Response.status(400).entity(gson.toJson(errorHash)).build();
			}
			options.setFileLocation(files);

			// add engine owner for permissions
			if(this.securityEnabled) {
				Object user = request.getSession().getAttribute(Constants.SESSION_USER);
				if(user != null && !((User) user).getId().equals(Constants.ANONYMOUS_USER_ID)) {
					addEngineOwner(options.getDbName(), ((User) user).getId());
				} else {
					Map<String, String> errorHash = new HashMap<String, String>();
					errorHash.put("errorMessage", "Please log in to upload data.");
					return Response.status(400).entity(gson.toJson(errorHash)).build();
				}
			}

			// run the processor
			ImportDataProcessor importer = new ImportDataProcessor();
			importer.runProcessor(options);
		} catch (IOException e) {
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
			String questionFile = inputData.get("questionFile");
			if(questionFile != null && !questionFile.isEmpty()) {
				deleteFilesFromServer(new String[]{questionFile});
			}				
		}

		String outputText = "Excel Loading was a success.";
		return Response.status(200).entity(gson.toJson(outputText)).build();
	}
	
	@POST
	@Path("/excel/flat")
	@Produces("application/json")
	/**
	 * The process flow for loading a flat file based on drag/drop
	 * Since the user confirms what type of datatypes they want for each column
	 * The file has already been loaded onto the server
	 * @param form					Form object containing all the information regarding the load
	 * @param request
	 * @return
	 */
	public Response uploadFlatExcelFile(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		Gson gson = new Gson();
		System.out.println(form);

		ImportOptions options = null;
		try {
			options = new ImportOptions();
			setDefaultOptions(options, form);

			// can only load flat files as RDBMS
			options.setDbType(ImportOptions.DB_TYPE.RDBMS);
			options.setImportType(ImportOptions.IMPORT_TYPE.EXCEL_FLAT_UPLOAD);

			// set the files
			String files = form.getFirst("file");
			if(files == null || files.trim().isEmpty()) {
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "No files have been identified to upload");
				return Response.status(400).entity(gson.toJson(errorHash)).build();
			}
			options.setFileLocation(files);

			List<Map<String, Map<String, String[]>>> headerData = null;
			String headerDataStr = form.getFirst("headerData");
			if(headerDataStr != null) {
				headerData = gson.fromJson(headerDataStr, new TypeToken<List<Map<String, Map<String, String[]>>>>() {}.getType());
				options.setExcelDataTypeMap(headerData);
			}
			
			// add engine owner for permissions
			if(this.securityEnabled) {
				Object user = request.getSession().getAttribute(Constants.SESSION_USER);
				if(user != null && !((User) user).getId().equals(Constants.ANONYMOUS_USER_ID)) {
					addEngineOwner(options.getDbName(), ((User) user).getId());
				} else {
					Map<String, String> errorHash = new HashMap<String, String>();
					errorHash.put("errorMessage", "Please log in to upload data.");
					return Response.status(400).entity(gson.toJson(errorHash)).build();
				}
			}

			ImportDataProcessor importer = new ImportDataProcessor();
			importer.runProcessor(options);
		} catch (IOException e) {
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
			// we can't really use the options object for this :/
			String files = form.getFirst("file");
			if(files != null && !files.isEmpty()) {
				deleteFilesFromServer(files.split(";"));
			}
			String questionFile = form.getFirst("questionFile");
			if(questionFile != null && !questionFile.isEmpty()) {
				deleteFilesFromServer(new String[]{questionFile});
			}
		}

		String outputText = "Flat database loading was a success.";
		return Response.status(200).entity(gson.toJson(outputText)).build();
	}
	
	////////////////////////////////////////////// END EXCEL UPLOADING //////////////////////////////////////////////////////////

	
	@POST
	@Path("/nlp/upload")
	@Produces("application/json")
	public Response uploadNLPFile(@Context HttpServletRequest request) {
		List<FileItem> fileItems = processRequest(request);
		// collect all of the data input on the form
		Hashtable<String, String> inputData = getInputData(fileItems);
		System.out.println(inputData);

		Gson gson = new Gson();
		// we need to keep track of which NLP items loaded are files verses websites
		// we need this so we can delete files after upload is done
		String uploadFiles = "";
		String file = "";
		ImportOptions options = null;
		try {
			options = new ImportOptions();
			// set the default options
			setDefaultOptions(options, inputData);
			// can only store as RDF
			options.setDbType(ImportOptions.DB_TYPE.RDF);
			// set the type to be nlp
			options.setImportType(ImportOptions.IMPORT_TYPE.NLP);

			// only default options not set are the files
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
			// set the files
			if(uploadFiles.trim().isEmpty()) {
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "No files have been identified to upload");
				return Response.status(400).entity(gson.toJson(errorHash)).build();
			}
			options.setFileLocation(uploadFiles);

			// add engine owner for permissions
			if(this.securityEnabled) {
				Object user = request.getSession().getAttribute(Constants.SESSION_USER);
				if(user != null && !((User) user).getId().equals(Constants.ANONYMOUS_USER_ID)) {
					addEngineOwner(options.getDbName(), ((User) user).getId());
				} else {
					Map<String, String> errorHash = new HashMap<String, String>();
					errorHash.put("errorMessage", "Please log in to upload data.");
					return Response.status(400).entity(gson.toJson(errorHash)).build();
				}
			}

			// run the importer
			ImportDataProcessor importer = new ImportDataProcessor();
			importer.runProcessor(options);
		} catch (IOException e) {
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
			if(file != null && !file.isEmpty()) {
				deleteFilesFromServer(file.split(";"));
			}
			String questionFile = inputData.get("questionFile");
			if(questionFile != null && !questionFile.isEmpty()) {
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

		boolean success = importer.addNewRDBMS(options.get("driver"), options.get("hostname"), options.get("port"), options.get("username"), options.get("password"), options.get("schema"), makeAlphaNumeric(databaseOptions.get("databaseName")), metamodel);

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

	private String makeAlphaNumeric(String s) {
		s = s.trim();
		s = s.replaceAll(" ", "_");
		s = s.replaceAll("[^a-zA-Z0-9\\_]", "");
		while(s.contains("__")){
			s = s.replace("__", "_");
		}
		return s;
	}

	////////////////////////////////////////////////////////////////////////////////////////////	
	// OLD CSV UPLOAD WHEN THE BE WAS NOT RESPONSIBLE FOR METAMODEL LOGIC
	//	@POST
	//	@Path("/csv/upload")
	//	@Produces("application/json")
	//	public Response uploadCSVFile(@Context HttpServletRequest request)
	//	{
	//		Gson gson = new Gson();
	//		List<FileItem> fileItems = processRequest(request);
	//		// collect all of the data input on the form
	//		Hashtable<String, String> inputData = getInputData(fileItems);
	//		System.out.println(inputData);
	//
	//		ImportOptions options = null;
	//		String[] propFileArr = null;
	//		try {
	//			// load as many default options as possible
	//			options = new ImportOptions();
	//			// now just load the rest as usual
	//			setDefaultOptions(options, inputData);
	//			// set the import type
	//			options.setImportType(ImportOptions.IMPORT_TYPE.CSV);
	//			
	//			// open refine logic
	//			Object obj = inputData.get("cleanedFiles");
	//			if(obj != null) {
	//				String cleanedFileName = processOpenRefine(options.getDbName(), (String) obj);
	//				if(cleanedFileName.startsWith("Error")) {
	//					Map<String, String> errorHash = new HashMap<String, String>();
	//					errorHash.put("errorMessage", "Could not write the cleaned data to file. Please check application file-upload path.");
	//					return Response.status(400).entity(gson.toJson(errorHash)).build();
	//				}
	//				inputData.put("file", cleanedFileName);
	//			}
	//			
	//			// only default options not set are the files
	//			String files = inputData.get("file");
	//			if(files == null || files.trim().isEmpty()) {
	//				Map<String, String> errorHash = new HashMap<String, String>();
	//				errorHash.put("errorMessage", "No files have been identified to upload");
	//				return Response.status(400).entity(gson.toJson(errorHash)).build();
	//			}
	//			options.setFileLocation(files);
	//			
	//			////////////////////// begin logic to process metamodel for excel flat table //////////////////////
	//			List<String> allFileData = gson.fromJson(inputData.get("fileInfoArray"), List.class);
	//			int size = allFileData.size();
	//			
	//			Hashtable<String, String>[] propHashArr = new Hashtable[size];
	//			propFileArr = new String[size];
	//			boolean allEmpty = true;
	//			for(int i = 0; i < size; i++) {
	//				Hashtable<String, String> itemForFile = gson.fromJson(allFileData.get(i), Hashtable.class);
	//				CSVPropFileBuilder propWriter = new CSVPropFileBuilder();
	//				List<String> rel = gson.fromJson(itemForFile.get("rowsRelationship"), List.class);
	//				List<String> prop = gson.fromJson(itemForFile.get("rowsProperty"), List.class);
	//				List<String> displayNames = gson.fromJson(itemForFile.get("itemDisplayName"), List.class);
	//				if((rel != null &&!rel.isEmpty()) || (prop != null && !prop.isEmpty()) ) {
	//					allEmpty = false;
	//				}
	//				if(rel != null) {
	//					for(String str : rel) {
	//						// subject and object keys link to array list for concatenations, while the predicate is always a string
	//						Hashtable<String, Object> mRow = gson.fromJson(str, Hashtable.class);
	//						if(!((String) mRow.get("selectedRelSubject").toString()).isEmpty() && !((String) mRow.get("relPredicate").toString()).isEmpty() && !((String) mRow.get("selectedRelObject").toString()).isEmpty())
	//						{
	//							propWriter.addRelationship((ArrayList<String>) mRow.get("selectedRelSubject"), mRow.get("relPredicate").toString(), (ArrayList<String>) mRow.get("selectedRelObject"));
	//						}
	//					}
	//				}
	//				if(prop != null) {
	//					for(String str : prop) {
	//						Hashtable<String, Object> mRow = gson.fromJson(str, Hashtable.class);
	//						if(!((String) mRow.get("selectedPropSubject").toString()).isEmpty() && !((String) mRow.get("selectedPropObject").toString()).isEmpty() && !((String) mRow.get("selectedPropDataType").toString()).isEmpty())
	//						{
	//							propWriter.addProperty((ArrayList<String>) mRow.get("selectedPropSubject"), (ArrayList<String>) mRow.get("selectedPropObject"), (String) mRow.get("selectedPropDataType").toString());
	//						}
	//					}
	//				}
	//				if(displayNames != null) {
	//					for(String str : displayNames) {
	//						Hashtable<String, Object> mRow = gson.fromJson(str, Hashtable.class);
	//						if(!((String) mRow.get("selectedNode").toString()).isEmpty() && !((String) mRow.get("selectedProperty").toString()).isEmpty() && !((String) mRow.get("selectedDisplayName").toString()).isEmpty())
	//						{
	//							propWriter.addDisplayName((ArrayList<String>) mRow.get("selectedNode"), (ArrayList<String>) mRow.get("selectedProperty"), (ArrayList<String>) mRow.get("selectedDisplayName"));
	//						}
	//					}
	//				}
	//				String headersList = itemForFile.get("allHeaders"); 
	//				Hashtable<String, Object> headerHash = gson.fromJson(headersList, Hashtable.class);
	//				ArrayList<String> headers = (ArrayList<String>) headerHash.get("AllHeaders");
	//				propWriter.columnTypes(headers);
	//				propHashArr[i] = propWriter.getPropHash(itemForFile.get("csvStartLineCount"), itemForFile.get("csvEndLineCount")); 
	//				propFileArr[i] = propWriter.getPropFile();
	//			}
	//			if(allEmpty) {
	//				Map<String, String> errorHash = new HashMap<String, String>();
	//				errorHash.put("errorMessage", "No metamodel has been specified.\n Please specify a metamodel in order to determine how to load this data.");
	//				return Response.status(400).entity(gson.toJson(errorHash)).build();
	//			}
	//			// set it in the options
	//			options.setMetamodelArray(propHashArr);
	//			////////////////////// end logic to process metamodel for excel flat table //////////////////////
	//			
	//			// add engine owner for permissions
	//			if(this.securityEnabled) {
	//				Object user = request.getSession().getAttribute(Constants.SESSION_USER);
	//				if(user != null && !((User) user).getId().equals(Constants.ANONYMOUS_USER_ID)) {
	//					addEngineOwner(options.getDbName(), ((User) user).getId());
	//				} else {
	//					Map<String, String> errorHash = new HashMap<String, String>();
	//					errorHash.put("errorMessage", "Please log in to upload data.");
	//					return Response.status(400).entity(gson.toJson(errorHash)).build();
	//				}
	//			}
	//
	//			// run the processor
	//			ImportDataProcessor importer = new ImportDataProcessor();
	//			importer.runProcessor(options);
	//		} catch (IOException e) {
	//			e.printStackTrace();
	//			Map<String, String> errorHash = new HashMap<String, String>();
	//			errorHash.put("errorMessage", e.getMessage());
	//			return Response.status(400).entity(gson.toJson(errorHash)).build();
	//		} catch(Exception e) {
	//			e.printStackTrace();
	//			Map<String, String> errorHash = new HashMap<String, String>();
	//			errorHash.put("errorMessage", e.getMessage());
	//			return Response.status(400).entity(gson.toJson(errorHash)).build();
	//		} finally {
	//			deleteFilesFromServer(inputData.get("file").toString().split(";"));
	//			String questionFile = inputData.get("questionFile");
	//			if(questionFile != null && !questionFile.isEmpty()) {
	//				deleteFilesFromServer(new String[]{questionFile});
	//			}				
	//		}
	//
	//		// need to write out the property files
	//		try {
	//			Date currDate = Calendar.getInstance().getTime();
	//			SimpleDateFormat sdf = new SimpleDateFormat("yyMMddHHmmssZ");
	//			String dateName = sdf.format(currDate);
	//			
	//			String dbName = options.getDbName();
	//			String[] fileNames = options.getFileLocations().split(";");
	//			for(int i = 0; i < propFileArr.length; i++) {
	//				String fileName = new File(fileNames[i]).getName().replace(".xls*", "");
	//				FileUtils.writeStringToFile(new File(
	//						DIHelper.getInstance().getProperty("BaseFolder")
	//						.concat(File.separator).concat("db").concat(File.separator)
	//						.concat(dbName).concat(File.separator).concat(dbName).concat("_")
	//						.concat(fileName)
	//						.concat("_").concat(dateName).concat("_PROP.prop")), propFileArr[i]);
	//			}
	//		} catch (IOException e) {
	//			e.printStackTrace();
	//			Map<String, String> errorHash = new HashMap<String, String>();
	//			errorHash.put("errorMessage", "Failure to write Excel Prop File based on user-defined metamodel.");
	//			return Response.status(400).entity(gson.toJson(errorHash)).build();
	//		}
	//		
	//		String outputText = "CSV Loading was a success.";
	//		return Response.status(200).entity(gson.toJson(outputText)).build();
	//	}


}
