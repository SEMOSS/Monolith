package prerna.upload;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

import prerna.algorithm.api.SemossDataType;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.engine.impl.SmssUtilities;
import prerna.nameserver.AddToMasterDB;
import prerna.nameserver.utility.MasterDatabaseUtility;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.poi.main.CSVPropFileBuilder;
import prerna.poi.main.ExcelPropFileBuilder;
import prerna.poi.main.HeadersException;
import prerna.poi.main.MetaModelCreator;
import prerna.poi.main.helper.CSVFileHelper;
import prerna.poi.main.helper.FileHelperUtil;
import prerna.poi.main.helper.ImportOptions;
import prerna.poi.main.helper.ImportOptions.DB_TYPE;
import prerna.poi.main.helper.ImportOptions.IMPORT_METHOD;
import prerna.poi.main.helper.excel.ExcelBlock;
import prerna.poi.main.helper.excel.ExcelRange;
import prerna.poi.main.helper.excel.ExcelSheetPreProcessor;
import prerna.poi.main.helper.excel.ExcelWorkbookFilePreProcessor;
import prerna.rdf.main.ImportRDBMSProcessor;
import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.sablecc2.reactor.IReactor;
import prerna.sablecc2.reactor.PixelPlanner;
import prerna.sablecc2.reactor.app.upload.UploadInputUtility;
import prerna.sablecc2.reactor.app.upload.UploadUtilities;
import prerna.sablecc2.reactor.app.upload.gremlin.file.TinkerCsvUploadReactor;
import prerna.sablecc2.reactor.app.upload.rdbms.csv.RdbmsCsvUploadReactor;
import prerna.sablecc2.reactor.app.upload.rdbms.external.ExternalJdbcSchemaReactor;
import prerna.sablecc2.reactor.app.upload.rdbms.external.RdbmsExternalUploadReactor;
import prerna.sablecc2.reactor.app.upload.rdf.RdfCsvUploadReactor;
import prerna.ui.components.ImportDataProcessor;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;
import prerna.util.sql.RdbmsTypeEnum;
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
										: null;
		if(importMethod == null) {
			String errorMessage = "Import method \'" + methodString + "\' is not supported";
			throw new IOException(errorMessage);
		}
		options.setImportMethod(importMethod);

		if(inputData.containsKey("customBaseURI")) {
			String baseUri = inputData.get("customBaseURI").trim();
			if(!baseUri.isEmpty()) {
				options.setBaseUrl(baseUri);
			}
		}
		
		// get the db name
		String dbName = inputData.get("dbName");
		if(dbName == null || dbName.trim().isEmpty()) {
			String errorMessage = "Database name \'" + dbName + "\' is invalid";
			throw new IOException(errorMessage);
		}
		if(importMethod == ImportOptions.IMPORT_METHOD.ADD_TO_EXISTING) {
			options.setEngineID(dbName);
			options.setDbName(MasterDatabaseUtility.getEngineAliasForId(dbName));
		} else {
			// make a new id
			options.setEngineID(UUID.randomUUID().toString());
			options.setDbName(Utility.makeAlphaNumeric(dbName));
		}

		// determine the db type
		String dbType = inputData.get("dataOutputType");
		
		if(dbType.equalsIgnoreCase("RDBMS")) {
			options.setDbType(ImportOptions.DB_TYPE.RDBMS);

			// if rdbms, also get the rdbms driver type
			String rdbmsDriverType = inputData.get("rdbmsOutputType");
			if(rdbmsDriverType == null || rdbmsDriverType.isEmpty()) {
				// default to h2
				options.setRDBMSDriverType(RdbmsTypeEnum.H2_DB);
			} else {
				options.setRDBMSDriverType(RdbmsTypeEnum.getEnumFromString(rdbmsDriverType));
			}

			// if rdbms, also need to know if user wants duplicates or not in table
			// TODO: need to expose this to the user on the UI
			boolean allowDuplicates = false;
			options.setAllowDuplicates(allowDuplicates);

		} else {
			// default to RDF db type
			options.setDbType(ImportOptions.DB_TYPE.RDF);
		}
		
		// set clean string by default
		options.setCleanString(true);
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
										: null;
		if(importMethod == null) {
			String errorMessage = "Import method \'" + methodString + "\' is not supported";
			throw new IOException(errorMessage);
		}
		options.setImportMethod(importMethod);

		String baseUri = form.getFirst("customBaseURI");
		if(baseUri != null) {
			baseUri = baseUri.trim();
			if(!baseUri.isEmpty()) {
				options.setBaseUrl(baseUri);
			}
		}
		
		// get the db name
		String dbName = form.getFirst("dbName");
		if(dbName == null || dbName.trim().isEmpty()) {
			String errorMessage = "Database name \'" + dbName + "\' is invalid";
			throw new IOException(errorMessage);
		}
		
		if(importMethod == ImportOptions.IMPORT_METHOD.ADD_TO_EXISTING) {
			options.setEngineID(dbName);
			options.setDbName(MasterDatabaseUtility.getEngineAliasForId(dbName));
		} else {
			// make a new id
			options.setEngineID(UUID.randomUUID().toString());
			options.setDbName(Utility.makeAlphaNumeric(dbName));
		}

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
					options.setRDBMSDriverType(RdbmsTypeEnum.H2_DB);
				} else {
					options.setRDBMSDriverType(RdbmsTypeEnum.getEnumFromString(rdbmsDriverType));
				}

				// if rdbms, also need to know if user wants duplicates or not in table
				// TODO: need to expose this to the user on the UI
				boolean allowDuplicates = false;
				options.setAllowDuplicates(allowDuplicates);

			} else if(dbType.equalsIgnoreCase("Tinker")) {
				// load as a tinker engine
				String tinkerDriver = form.getFirst("tinkerOutputType");
				options.setDbType(ImportOptions.DB_TYPE.TINKER);
				//set driver type for smss file 
				if(tinkerDriver.equalsIgnoreCase("TG")) {
					options.setTinkerDriverType(ImportOptions.TINKER_DRIVER.TG);
				} else if (tinkerDriver.equalsIgnoreCase("NEO4J")) {
					options.setTinkerDriverType(ImportOptions.TINKER_DRIVER.NEO4J);
				} else if (tinkerDriver.equalsIgnoreCase("XML")) {
					options.setTinkerDriverType(ImportOptions.TINKER_DRIVER.XML);
				} else if (tinkerDriver.equalsIgnoreCase("JSON")) {
					options.setTinkerDriverType(ImportOptions.TINKER_DRIVER.JSON);
				}
				
			} else {
				// default to RDF db type
				options.setDbType(ImportOptions.DB_TYPE.RDF);
			}
		}
		
		// set clean string by default
		options.setCleanString(true);
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
			return WebUtility.getResponse(invalidHeadersMap, 200);

		} else if(type.equals("EXCEL")) {
			List<Map<String, Map<String, String>>> invalidHeadersList = new Vector<Map<String, Map<String, String>>>();
			
			// each entry (outer map object) in the list if a workbook
			// each key in that map object is the sheetName for that given workbook
			// the list are the headers inside that sheet
			List<Map<String, String[]>> userDefinedHeadersMap = gson.fromJson(form.getFirst("userHeaders"), new TypeToken<List<Map<String, String[]>>>() {}.getType());
			
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
			return WebUtility.getResponse(invalidHeadersList, 200);
		} else {
			return WebUtility.getResponse("Format does not conform to checking headers", 400);
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
			//if we get a flag from the front end to create meta model then create it
			String generateMetaModel = inputData.get("generateMetaModel").toString();
			
			// get the files
			String[] files = inputData.get("file").split(";");
			String[] delimiters = inputData.get("delimiter").split(";");
			// get the property files if necessary
			String props = inputData.get("propFile");
			String[] propFiles = null;
			if(props != null && !props.isEmpty()) {
				propFiles = props.split(";");
			}
			
			// do validation on the property files
			if("prop".equals(generateMetaModel)) {
				if(props == null) {
					throw new IOException("No prop file has been selected. Please select a prop file for the file");
				}
				if(propFiles.length != files.length) {
					throw new IOException("No prop file has been selected. Please select a prop file for the file");
				}
			}
			
			for(int i = 0; i < files.length; i++) {
				// this is the MM info for one of the files within the metaModelData list
				Map<String, Object> fileMetaModelData = new HashMap<String, Object>();
				
				// store the file location on server so FE can send that back into actual upload routine
				String filePath = files[i];
				char delimiter = delimiters[i].charAt(0);
				String file = filePath.substring(filePath.lastIndexOf(DIR_SEPARATOR) + DIR_SEPARATOR.length(), filePath.lastIndexOf("."));
				try {
					file = file.substring(0, file.indexOf("_____UNIQUE")); //taking out the date added onto the original file name
				} catch(Exception e) {
					//just in case that fails, this shouldnt because if its a filename it should have a "."
					file = filePath.substring(filePath.lastIndexOf(DIR_SEPARATOR) + DIR_SEPARATOR.length(), filePath.lastIndexOf(".")); 
				}
				
				// store file path and file name to send to FE
				fileMetaModelData.put("fileLocation", filePath);
				fileMetaModelData.put("fileName", file);
				
				CSVFileHelper helper = new CSVFileHelper();
				helper.setDelimiter(delimiter);
				helper.parse(filePath);
				
				MetaModelCreator predictor;
				if(generateMetaModel.equals("auto")) {
					predictor = new MetaModelCreator(helper, MetaModelCreator.CreatorMode.AUTO);
					predictor.constructMetaModel();
					
					Map<String, List<Map<String, Object>>> metaModel = predictor.getMetaModelData();
					fileMetaModelData.putAll(metaModel);
				
				} else if(generateMetaModel.equals("prop")) {
					if(propFiles[i] == null) {
						throw new IOException("No prop file has been selected for file " + file);
					}
					//turn prop file into meta data
					predictor = new MetaModelCreator(helper, MetaModelCreator.CreatorMode.PROP, propFiles[i]);
					predictor.constructMetaModel();
					
					Map<String, List<Map<String, Object>>> metaModel = predictor.getMetaModelData();
					fileMetaModelData.putAll(metaModel);
				}
				else {
					// user is creating their own
					predictor = new MetaModelCreator(helper, null);
				}
				
				int start = predictor.getStartRow();
				int end = predictor.getEndRow();
				Map<String, String> additionalInfo = predictor.getAdditionalInfo();

				// add in other information relevant to FE
				fileMetaModelData.put("startCount", start);
				fileMetaModelData.put("endCount", end);
				fileMetaModelData.put("dataTypes", predictor.getDataTypeMap());
				fileMetaModelData.put("additionalDataTypes", predictor.getAdditionalDataTypeMap());
				fileMetaModelData.put("additionalInfo", additionalInfo);
				// store auto modified header names
				fileMetaModelData.put("headerModifications", helper.getChangedHeaders());
				
				// store this in a list
				metaModelData.add(fileMetaModelData);
				
				// need to close the helper
				helper.clear();
			}
		} catch(Exception e) { 
			e.printStackTrace();
			
			// grab the error thrown and send it to the FE
			HashMap<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("errorMessage", e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// store the info
		returnObj.put("metaModelData", metaModelData);
		
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
			Map[] dataTypes = new Map[size];

			boolean allEmpty = true;
			for(int i = 0; i < size; i++) {
				Hashtable<String, String> itemForFile = gson.fromJson(allFileData.get(i), Hashtable.class);

				CSVPropFileBuilder propWriter = new CSVPropFileBuilder();

				List<String> rel = gson.fromJson(itemForFile.get("rowsRelationship"), List.class);
				List<String> prop = gson.fromJson(itemForFile.get("rowsProperty"), List.class);
				
				// add a check that something is coming back from the FE
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
				
				// add column data types
				Hashtable<String, Object> headerHash = gson.fromJson(itemForFile.get("allHeaders"), Hashtable.class);
				ArrayList<String> headers = (ArrayList<String>) headerHash.get("AllHeaders");
				propWriter.columnTypes(headers);
				
				// add additional info and start/end rows
				Map<String, String> additionalMods = gson.fromJson(itemForFile.get("additionalInfo"), Map.class);
				propHashArr[i] = propWriter.getPropHash(itemForFile.get("csvStartLineCount"), itemForFile.get("csvEndLineCount"), additionalMods); 
				propFileArr[i] = propWriter.getPropFile();
				dataTypes[i] = propWriter.getDataTypeHash();
			}
			
			// if no meta data specified, send an error
			if(allEmpty) {
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "No metamodel has been specified.\n Please specify a metamodel in order to determine how to load this data.");
				return Response.status(400).entity(gson.toJson(errorHash)).build();
			}
			// set it in the options
			options.setMetamodelArray(propHashArr);
			options.setDataTypes(dataTypes);
			
			////////////////////// end logic to process metamodel for csv flat table //////////////////////

			// run the import
			ImportDataProcessor importer = new ImportDataProcessor();
			// the reactors handle all the security! :)
			String appId = reactorImport(options, request);
			if (appId != null) {
				options.setEngineID(appId);
			} else {
				// old way... i believe this shouldn't be invoked anymore...
				importer.runProcessor(options);

				// add engine owner for permissions
				// the 
				if(this.securityEnabled) {
					User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
					if(user == null) {
						Map<String, String> errorHash = new HashMap<String, String>();
						errorHash.put("errorMessage", "User must be signed into an account in order to create a database");
						return Response.status(400).entity(gson.toJson(errorHash)).build();
					}
					
					DB_TYPE dbType = options.getDbType();
					if(dbType == DB_TYPE.RDBMS) {
						addEngineOwner(options.getEngineID(), options.getDbName(), "H2_DB", "", user);
					} else if(dbType == DB_TYPE.TINKER) {
						addEngineOwner(options.getEngineID(), options.getDbName(), "TINKER", "", user);
					} else {
						addEngineOwner(options.getEngineID(), options.getDbName(), "RDF", "", user);
					}
				}
			}
			
			// reactors output a json
			// this is the old prop file format
			try {
				Date currDate = Calendar.getInstance().getTime();
				SimpleDateFormat sdf = new SimpleDateFormat("yyMMddHHmmssZ");
				String dateName = sdf.format(currDate);

				String dbName = options.getDbName();
				String[] fileNames = options.getFileLocations().split(";");
				String dbLocation = SmssUtilities.getUniqueName(Utility.cleanString(dbName, true).toString(), options.getEngineID());
				for(int i = 0; i < propFileArr.length; i++) {
					String fileName = new File(fileNames[i]).getName().replace(".csv", "");
					FileUtils.writeStringToFile(new File(
							DIHelper.getInstance().getProperty("BaseFolder")
							.concat(File.separator).concat("db").concat(File.separator)
							.concat(dbLocation).concat(File.separator)
							.concat(dbName.toString()).concat("_").concat(fileName)
							.concat("_").concat(dateName).concat("_PROP.prop")), propFileArr[i]);
				}
			} catch (IOException e) {
				e.printStackTrace();
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "Failure to write CSV Prop File based on user-defined metamodel.");
				return Response.status(400).entity(gson.toJson(errorHash)).build();
			} 
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
		}

		String appId = options.getEngineID();
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		Map<String, Object> retMap = UploadUtilities.getAppReturnData(user, appId);
		return Response.status(200).entity(gson.toJson(retMap)).build();
	}
	
	////////////////////////////////////////////// END CSV UPLOADING //////////////////////////////////////////////////////////
	
	////////////////////////////////////////////// START EXCEL UPLOADING //////////////////////////////////////////////////////////

	private String reactorImport(ImportOptions options, HttpServletRequest request) {
		// get upload inputs
		IMPORT_METHOD importType = options.getImportMethod();
		String fileLocations = options.getFileLocations();
		String dbName = options.getDbName();
		String appId = options.getEngineID();
		// create each specific reactor for upload type
		IReactor reactor = getImportReactor(options);
		
		// if the reactor is supported, get the rest of the upload inputs
		if (reactor != null) {
			// create new metamodels for the reactor 
			// index based for each file
			List<Map<String, Object>> newMetamodelList = createNewMetamodel(options);
			// get property data types for each file
			Map[] dataTypes = options.getDataTypes();
			
			// if importing multiple files add to existing
			String[] inputFiles = fileLocations.split(";");
			
			for (int i = 0; i < inputFiles.length; i++) {
				String fileLocation = inputFiles[i];
				fileLocation = fileLocation.replace("\\\\", "/");
				
				Insight dummyIn = new Insight();
				InsightStore.getInstance().put(dummyIn);
				User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
				dummyIn.setUser(user);
					
				reactor.setInsight(dummyIn);
				PixelPlanner planner = new PixelPlanner();
				planner.setVarStore(dummyIn.getVarStore());
				reactor.setPixelPlanner(planner);
				
				// need to determine if we are doing CREATE_NEW or ADD_TO_EXISTING
				GenRowStruct grs = new GenRowStruct();
				if (i == 0 && importType == IMPORT_METHOD.CREATE_NEW) {
					// add app name
					grs.add(new NounMetadata(dbName, PixelDataType.CONST_STRING));
				} else {
					GenRowStruct existingGrs = new GenRowStruct();
					existingGrs.add(new NounMetadata(true, PixelDataType.BOOLEAN));
					reactor.getNounStore().addNoun(ReactorKeysEnum.EXISTING.getKey(), existingGrs);

					// we are adding to an existing app at this point
					// since even if it is a "CREATE_NEW", we run the reactor a different time
					// so the second file is an "ADD_TO_EXISTING"
					grs.add(new NounMetadata(appId, PixelDataType.CONST_STRING));
				}
				reactor.getNounStore().addNoun(ReactorKeysEnum.APP.getKey(), grs);

				// add metamodel
				grs = new GenRowStruct();
				Map<String, Object> metamodel = newMetamodelList.get(i);
				grs.add(new NounMetadata(metamodel, PixelDataType.MAP));
				reactor.getNounStore().addNoun(ReactorKeysEnum.METAMODEL.getKey(), grs);

				// add dataTypes
				grs = new GenRowStruct();
				Map dataTypesMap = dataTypes[i];
				if (dataTypesMap != null) {
					Map<String, Object> nodeMap = (Map<String, Object>) metamodel.get(Constants.NODE_PROP);
					// add each concept as a string
					for (String concept : nodeMap.keySet()) {
						dataTypesMap.put(concept, SemossDataType.STRING.toString());
					}
					// check relationships in case concept does not have properties
					// add missing concept as a string
					if (metamodel.get(Constants.RELATION) != null) {
						List<Map<String, Object>> edgeList = (List<Map<String, Object>>) metamodel.get(Constants.RELATION);
						// process each relationship
						for (Map relMap : edgeList) {
							String fromConcept = (String) relMap.get(Constants.FROM_TABLE);
							dataTypesMap.put(fromConcept, SemossDataType.STRING.toString());
							String toConcept = (String) relMap.get(Constants.TO_TABLE);
							dataTypesMap.put(toConcept, SemossDataType.STRING.toString());
						}
					}
					
				}
				grs.add(new NounMetadata(dataTypesMap, PixelDataType.MAP));
				reactor.getNounStore().addNoun(ReactorKeysEnum.DATA_TYPE_MAP.getKey(), grs);

				// add file path
				grs = new GenRowStruct();
				grs.add(new NounMetadata(fileLocation, PixelDataType.CONST_STRING));
				reactor.getNounStore().addNoun(ReactorKeysEnum.FILE_PATH.getKey(), grs);

				// execute!
				NounMetadata response = reactor.execute();
				// get the app id
				// if we CREATE_NEW -> this is a new id
				// if ADD_TO_EXISTING -> it is the same value
				Map<String, String> retMap = (Map) response.getValue();
				appId = retMap.get("app_id");
				
				// get a base new reactor if we have more files to process
				if( (i+1) < inputFiles.length) {
					reactor = getImportReactor(options);
				}
			}
		}
		
		// return the app id to send to FE
		return appId;
	}

	/**
	 * Get the correct type of reactor based on the import type
	 * @param options
	 * @return
	 */
	private IReactor getImportReactor(ImportOptions options) {
		DB_TYPE importDBType = options.getDbType();
		IMPORT_METHOD importType = options.getImportMethod();
		IReactor reactor = null;
		// either RDBMS, RDF, or Tinker
		if (importDBType == DB_TYPE.RDBMS && (importType == IMPORT_METHOD.CREATE_NEW || importType == IMPORT_METHOD.ADD_TO_EXISTING)) {
			reactor = new RdbmsCsvUploadReactor();
			
		} else if (importDBType == DB_TYPE.RDF && (importType == IMPORT_METHOD.CREATE_NEW || importType == IMPORT_METHOD.ADD_TO_EXISTING)) {
			reactor = new RdfCsvUploadReactor();
			String baseURI = options.getBaseUrl();
			GenRowStruct grs = new GenRowStruct();
			grs = new GenRowStruct();
			grs.add(new NounMetadata(baseURI, PixelDataType.CONST_STRING));
			reactor.getNounStore().addNoun(UploadInputUtility.CUSTOM_BASE_URI, grs);

		} else if (importDBType == DB_TYPE.TINKER && (importType == IMPORT_METHOD.CREATE_NEW || importType == IMPORT_METHOD.ADD_TO_EXISTING)) {
			reactor = new TinkerCsvUploadReactor();
			GenRowStruct grs = new GenRowStruct();
			grs = new GenRowStruct();
			grs.add(new NounMetadata(options.getTinkerDriverType().toString(), PixelDataType.CONST_STRING));
			reactor.getNounStore().addNoun("tinkerDriver", grs);
		}
		
		if(reactor != null) {
			reactor.In();
		}
		
		return reactor;
	}

	private List<Map<String, Object>> createNewMetamodel(ImportOptions options) {
		List<Map<String, Object>> newMetamodelList = new ArrayList<>();
		Hashtable<String, String>[] metamodel = options.getMetamodelArray();
		for (Hashtable<String, String> mm : metamodel) {
			HashMap<String, Object> newMetamodel = new HashMap<>();
			String relationStr = mm.get("RELATION");
			String[] relations = relationStr.split(";");
			ArrayList<Map<String, String>> relationships = new ArrayList<>();
			for (String relStr : relations) {
				if (relStr.contains("@")) {
					String[] rel = relStr.split("@");
					HashMap<String, String> relMap = new HashMap<>();
					relMap.put(Constants.FROM_TABLE, rel[0]);
					relMap.put(Constants.REL_NAME, rel[1]);
					relMap.put(Constants.TO_TABLE, rel[2]);
					relationships.add(relMap);
				}
			}
			newMetamodel.put(Constants.RELATION, relationships);
			String nodePropStr = mm.get("NODE_PROP");
			HashMap<String, List<String>> nodePropMap = new HashMap<>();

			if (nodePropStr.contains(";")) {
				String[] nodeProps = nodePropStr.split(";");
				for (String nodeStr : nodeProps) {
					String[] propSplit = nodeStr.split("%");
					String node = propSplit[0];
					String prop = propSplit[1];
					List<String> properties = new ArrayList<>();
					if (nodePropMap.containsKey(node)) {
						properties = nodePropMap.get(node);
					}
					properties.add(prop);
					nodePropMap.put(node, properties);
				}
			}
			newMetamodel.put(Constants.NODE_PROP, nodePropMap);
			newMetamodelList.add(newMetamodel);
		}
		return newMetamodelList;
	}

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
					file = file.substring(0, file.indexOf("_____UNIQUE")); //taking out the date added onto the original file name
				} catch(Exception e) {
					file = files[i].substring(files[i].lastIndexOf("\\") + 1, files[i].lastIndexOf(".")); //just in case that fails, this shouldnt because if its a filename it should have a "."
				}
				fileMetaModelData.put("fileName", file);
				
				// processing excel file data
				// trying to determine data blocks within a sheet
				
				ExcelWorkbookFilePreProcessor preProcessor = new ExcelWorkbookFilePreProcessor();
				preProcessor.parse(files[i]);
				preProcessor.determineTableRanges();
				Map<String, ExcelSheetPreProcessor> sProcessors = preProcessor.getSheetProcessors();
				for(String sheet : sProcessors.keySet()) {
					ExcelSheetPreProcessor processor = sProcessors.get(sheet);
					List<ExcelBlock> blocks = processor.getAllBlocks();
					
					Map<String, Object> rangeInfo = new HashMap<String, Object>();
					
					for(ExcelBlock block : blocks) {
						List<ExcelRange> ranges = block.getRanges();
						for(ExcelRange r : ranges) {
							String rSyntax = r.getRangeSyntax();
							String[] origHeaders = processor.getRangeHeaders(r);
							String[] cleanedHeaders = processor.getCleanedRangeHeaders(r);
							
							Object[][] rangeTypes = block.getRangeTypes(r);
							Map[] retMaps = FileHelperUtil.generateDataTypeMapsFromPrediction(cleanedHeaders, rangeTypes);
														
							Map<String, Object> rangeMap = new HashMap<String, Object>();
							rangeMap.put("headers", origHeaders);
							rangeMap.put("cleanHeaders", cleanedHeaders);
							rangeMap.put("dataTypes", retMaps[0]);
							rangeMap.put("additionalDataTypes", retMaps[1]);

							rangeInfo.put(rSyntax, rangeMap);
						}
					}
					
					// add all ranges in the sheet
					fileMetaModelData.put(sheet, rangeInfo);
				}
				preProcessor.clear();
				
				// add the info to the metamodel data to send
				metaModelData.add(fileMetaModelData);
			}
		} catch(Exception e) { 
			e.printStackTrace();
			
			// grab the error thrown and send it to the FE
			HashMap<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("errorMessage", e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// store the info
		returnObj.put("metaModelData", metaModelData);
		return Response.status(200).entity(gson.toJson(returnObj)).build();
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
				User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
				if(user == null) {
					Map<String, String> errorHash = new HashMap<String, String>();
					errorHash.put("errorMessage", "User must be signed into an account in order to create a database");
					return Response.status(400).entity(gson.toJson(errorHash)).build();
				}
				addEngineOwner(options.getEngineID(), options.getDbName(), "RDF", "", user);
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

		String appId = options.getEngineID();
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		Map<String, Object> retMap = UploadUtilities.getAppReturnData(user, appId);
		return Response.status(200).entity(gson.toJson(retMap)).build();
	}
	
	////////////////////////////////////////////// END EXCEL UPLOADING //////////////////////////////////////////////////////////

	
	@POST
	@Path("/rdbms/getMetadata2")
	@Produces("application/json")
	public Response getExistingRDBMSMetadata(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Gson gson = new Gson();
		ImportRDBMSProcessor importer = new ImportRDBMSProcessor();

		String driver = form.getFirst("driver");
		String hostname = form.getFirst("hostname");
		String port = form.getFirst("port");
		String username = form.getFirst("username");
		String password = form.getFirst("password");
		String schema = form.getFirst("schema");
		String additionalProperties = form.getFirst("additionalParams");

		Map<String, Object>	ret = new HashMap<String, Object>();
		try {
			ret = importer.getSchemaDetails(driver, hostname, port, username, password, schema, additionalProperties);
		} catch(Exception e) {
			e.printStackTrace();
			if(e.getMessage() != null) {
				ret.put("errorMessage", e.getMessage());
			} else {
				ret.put("errorMessage", "Unexpected error determining metadata");
			}
			return Response.status(400).entity(gson.toJson(ret)).build();
		}

		return Response.status(200).entity(gson.toJson(ret)).build();
	}

	@POST
	@Path("/rdbms/connect")
	@Produces("application/json")
	public Response connectExistingRDBMS(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Gson gson = new Gson();
		HashMap<String, Object> ret = new HashMap<String, Object>(1);
		Map<String, Object> metamodel = new HashMap<String, Object>();
		HashMap<String, Object> details = gson.fromJson(form.getFirst("details"), new TypeToken<HashMap<String, Object>>() {}.getType());		
		HashMap<String, String> metamodelData = gson.fromJson(gson.toJson(details.get("metamodelData")), new TypeToken<HashMap<String, Object>>() {}.getType());
		ArrayList<Object> nodes = gson.fromJson(gson.toJson(metamodelData.get("nodes")), new TypeToken<ArrayList<Object>>() {}.getType());
		ArrayList<Object> relationships = gson.fromJson(gson.toJson(metamodelData.get("relationships")), new TypeToken<ArrayList<Object>>() {}.getType());
		// reformat metamodel for new reactor
		HashMap<String, ArrayList<String>> nodesAndProps = new HashMap<String, ArrayList<String>>(nodes.size());
		ArrayList<Map<String, Object>> nodeRelationships = new ArrayList<>(relationships.size());
		for(Object o: nodes) {
			ArrayList<String> props = new ArrayList<String>();
			HashMap<String, Object> nodeHash = gson.fromJson(gson.toJson(o), new TypeToken<HashMap<String, Object>>() {}.getType());
			ArrayList<String> properties = gson.fromJson(gson.toJson(nodeHash.get("prop")), new TypeToken<ArrayList<String>>() {}.getType());
			for(String prop : properties) {
				props.add(prop); 
			}
			nodesAndProps.put(nodeHash.get("node") + "." + nodeHash.get("primaryKey"), props); // Table1.idTable1 -> [prop1, prop2, ...]
		}
		metamodel.put(ExternalJdbcSchemaReactor.TABLES_KEY, nodesAndProps);
		for(Object o: relationships) {
			HashMap<String, String> relationship = gson.fromJson(gson.toJson(o), new TypeToken<HashMap<String, String>>() {}.getType());
			String fromTable = relationship.get("sub");
			String relName = relationship.get("pred");
			String toTable = relationship.get("obj");
			Map<String, Object> relMap = new HashMap<>();
			relMap.put(Constants.FROM_TABLE, fromTable);
			relMap.put(Constants.REL_NAME, relName);
			relMap.put(Constants.TO_TABLE, toTable);
			nodeRelationships.add(relMap);
		}
		metamodel.put(ExternalJdbcSchemaReactor.RELATIONS_KEY, nodeRelationships);
		
		HashMap<String, String> databaseOptions = gson.fromJson(gson.toJson(details.get("databaseOptions")), new TypeToken<HashMap<String, String>>() {}.getType());
		HashMap<String, String> options = gson.fromJson(gson.toJson(details.get("options")), new TypeToken<HashMap<String, Object>>() {}.getType());
		options.put("dbName", Utility.makeAlphaNumeric(databaseOptions.get("databaseName")));

		// mocking inputs until FE makes full pixel call
		RdbmsExternalUploadReactor reactor = new RdbmsExternalUploadReactor();
		reactor.In();
		// make an insight to set
		// so we can pass the user
		Insight dummyIn = new Insight();
		InsightStore.getInstance().put(dummyIn);
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		dummyIn.setUser(user);

		reactor.setInsight(dummyIn);
		PixelPlanner planner = new PixelPlanner();
		planner.setVarStore(dummyIn.getVarStore());
		reactor.setPixelPlanner(planner);
		// add app name
		GenRowStruct grs1 = new GenRowStruct();
		grs1.add(new NounMetadata(Utility.makeAlphaNumeric(databaseOptions.get("databaseName")), PixelDataType.CONST_STRING));
		reactor.getNounStore().addNoun(ReactorKeysEnum.APP.getKey(), grs1);
		// add db driver
		GenRowStruct grs2 = new GenRowStruct();
		grs2.add(new NounMetadata(options.get("driver"), PixelDataType.CONST_STRING));
		reactor.getNounStore().addNoun(ReactorKeysEnum.DB_DRIVER_KEY.getKey(), grs2);
		// add host
		GenRowStruct grs3 = new GenRowStruct();
		grs3.add(new NounMetadata(options.get("hostname"), PixelDataType.CONST_STRING));
		reactor.getNounStore().addNoun(ReactorKeysEnum.HOST.getKey(), grs3);
		// add port
		GenRowStruct grs4 = new GenRowStruct();
		grs4.add(new NounMetadata(options.get("port"), PixelDataType.CONST_STRING));
		reactor.getNounStore().addNoun(ReactorKeysEnum.PORT.getKey(), grs4);
		// add schema
		GenRowStruct grs5 = new GenRowStruct();
		grs5.add(new NounMetadata(options.get("schema"), PixelDataType.CONST_STRING));
		reactor.getNounStore().addNoun(ReactorKeysEnum.SCHEMA.getKey(), grs5);
		// add username
		GenRowStruct grs6 = new GenRowStruct();
		grs6.add(new NounMetadata(options.get("username"), PixelDataType.CONST_STRING));
		reactor.getNounStore().addNoun(ReactorKeysEnum.USERNAME.getKey(), grs6);
		// add password
		GenRowStruct grs7 = new GenRowStruct();
		grs7.add(new NounMetadata(options.get("password"), PixelDataType.CONST_STRING));
		reactor.getNounStore().addNoun(ReactorKeysEnum.PASSWORD.getKey(), grs7);
		// add additional paras
		GenRowStruct grs8 = new GenRowStruct();
		grs8.add(new NounMetadata(options.get("additionalParams"), PixelDataType.CONST_STRING));
		reactor.getNounStore().addNoun(ReactorKeysEnum.ADDITIONAL_CONNECTION_PARAMS_KEY.getKey(), grs8);
		// add metamodel
		// additional paras
		GenRowStruct grs9 = new GenRowStruct();
		grs9.add(new NounMetadata(metamodel, PixelDataType.MAP));
		reactor.getNounStore().addNoun(ReactorKeysEnum.METAMODEL.getKey(), grs9);

		// execute!
		try {
			reactor.execute();
			ret.put("success", true);
			return Response.status(200).entity(gson.toJson(ret)).build();
		} catch(Exception e) {
			ret.put("errorMessage", e.getMessage());
			return Response.status(400).entity(gson.toJson(ret)).build();
		}
		
	}
	
	@POST
	@Path("/rdbms/test")
	@Produces("application/json")
	public Response testExistingRDBMSConnection(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Gson gson = new Gson();
		String driver = form.getFirst(ReactorKeysEnum.DB_DRIVER_KEY.getKey());
		String hostname = form.getFirst(ReactorKeysEnum.HOST.getKey());
		String port = form.getFirst(ReactorKeysEnum.PORT.getKey());
		String username = form.getFirst(ReactorKeysEnum.USERNAME.getKey());
		String password = form.getFirst(ReactorKeysEnum.PASSWORD.getKey());
		String schema = form.getFirst(ReactorKeysEnum.SCHEMA.getKey());
		String additionalProperties = form.getFirst(ReactorKeysEnum.ADDITIONAL_CONNECTION_PARAMS_KEY.getKey());
		HashMap<String, Object> ret = new HashMap<String, Object>();

		ImportRDBMSProcessor importer = new ImportRDBMSProcessor();
		String test = importer.checkConnectionParams(driver, hostname, port, username, password, schema, additionalProperties);
		if(Boolean.parseBoolean(test)) {
			ret.put("success", Boolean.parseBoolean(test));
			return Response.status(200).entity(gson.toJson(ret)).build();
		} else {
			ret.put("error", test);
			return Response.status(400).entity(gson.toJson(ret)).build();
		}
	}

	public void addEngineOwner(String engineId, String engineName, String engineType, String engineCost, User user) {
		List<AuthProvider> logins = user.getLogins();
		for(AuthProvider ap : logins) {
			SecurityUpdateUtils.addEngineOwner(engineId, user.getAccessToken(ap).getId());
		}
	}

	@POST
	@Path("/json/uploadXrayConfig")
	@Produces("application/json")
	/*
	 * This method is used for uploading x-ray configuration 
	 * uploads a json file and returns a string of the parsed json 
	 * and deletes the file.
	 */
	public Response uploadXrayConfig(@Context HttpServletRequest request) {
		Gson gson = new Gson();

		//process request
		List<FileItem> fileItems = processRequest(request);
		String cleanName = fileItems.get(0).getName();
		// collect all of the data input on the form
		Hashtable<String, String> inputData = getInputData(fileItems);

		// objects to store data
		// master object to send to FE
		Map<String, Object> returnObj = new HashMap<>(2);
		// this will store the MM info for all the files
		//returnObj.put("cleanName", cleanName);  

		try {
			// get the files
			String[] files = inputData.get("file").split(";");

			for(int i = 0; i < files.length; i++) {

				// store the file location on server so FE can send that back into actual upload routine
				String filePath = files[i];

				BufferedReader br = new BufferedReader(new FileReader(filePath));
				Map xray = gson.fromJson(br, Map.class);

				//.json file
				//add json to local master 
				String jsonStringEscaped = gson.toJson(xray);
				AddToMasterDB lm = new AddToMasterDB();
				lm.addXrayConfig(jsonStringEscaped, cleanName.replace(".xray", ""));
			}
		} catch(Exception e) { 
			e.printStackTrace();
			// grab the error thrown and send it to the FE
			HashMap<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("errorMessage", e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		return Response.status(200).entity(gson.toJson(returnObj)).build();
	}

	
	
	
	
	
	/*
	 * THESE ARE JUST NOT REALLY USED ...
	 */
	
	
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
				User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
				if(user == null) {
					Map<String, String> errorHash = new HashMap<String, String>();
					errorHash.put("errorMessage", "User must be signed into an account in order to create a database");
					return Response.status(400).entity(gson.toJson(errorHash)).build();
				}
				addEngineOwner(options.getEngineID(), options.getDbName(), "H2_DB", "", user);
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

		String appId = options.getEngineID();
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		Map<String, Object> retMap = UploadUtilities.getAppReturnData(user, appId);
		return Response.status(200).entity(gson.toJson(retMap)).build();
	}
	
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
				User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
				if(user == null) {
					Map<String, String> errorHash = new HashMap<String, String>();
					errorHash.put("errorMessage", "User must be signed into an account in order to create a database");
					return Response.status(400).entity(gson.toJson(errorHash)).build();
				}
				addEngineOwner(options.getEngineID(), options.getDbName(), "RDF", "", user);
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
	
	
	/////////////////////////////////////////////////////////////////////////////
	/////////////////////////////////////////////////////////////////////////////
	/////////////////////////////////////////////////////////////////////////////
	/////////////////////////////////////////////////////////////////////////////

	/*
	 * METHODS PREVIOUSLY USED BUT REPLACED BY PIXEL REACTORS
	 */
	
	
//	@POST
//	@Path("/csv/flat")
//	@Produces("application/json")
//	/**
//	 * The process flow for loading a flat file based on drag/drop
//	 * Since the user confirms what type of datatypes they want for each column
//	 * The file has already been loaded onto the server
//	 * @param form					Form object containing all the information regarding the load
//	 * @param request
//	 * @return
//	 */
//	public Response uploadFlatCsvFile(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
//		Gson gson = new Gson();
//		System.out.println(form);
//
//		ImportOptions options = null;
//		try {
//			options = new ImportOptions();
//			setDefaultOptions(options, form);
//			// can only load flat files as RDBMS
//			options.setDbType(ImportOptions.DB_TYPE.RDBMS);
//			options.setRDBMSDriverType(SQLQueryUtil.DB_TYPE.H2_DB);
//			options.setImportType(ImportOptions.IMPORT_TYPE.CSV_FLAT_LOAD);
//
//			// set the files
//			String files = form.getFirst("file");
//			if(files == null || files.trim().isEmpty()) {
//				Map<String, String> errorHash = new HashMap<String, String>();
//				errorHash.put("errorMessage", "No files have been identified to upload");
//				return Response.status(400).entity(gson.toJson(errorHash)).build();
//			}
//			options.setFileLocation(files);
//
//			// see if user also manually changed some the headers
//			String newHeadersStr = form.getFirst("newHeaders");
//			System.out.println("new headers is " + newHeadersStr);
//			if(newHeadersStr != null && newHeadersStr.equalsIgnoreCase("undefined"))
//				newHeadersStr = null;
//			if(newHeadersStr != null) {
//				Map<String, Map<String, String>> newHeaders = 
//						gson.fromJson(newHeadersStr, new TypeToken<Map<String, Map<String, String>>>() {}.getType());
//				options.setCsvNewHeaders(newHeaders);
//			}
//			
//			// this should always be present now since we dont want to predict types twice on the BE
//			// get the data types and set them in the options
//			String headerDataTypesStr = form.getFirst("headerData");
//			if(headerDataTypesStr != null) {
//				List<Map<String, String[]>> headerDataTypes = gson.fromJson(headerDataTypesStr, new TypeToken<List<Map<String, String[]>>>() {}.getType());
//				options.setCsvDataTypeMap(headerDataTypes);
//				
//				// track GA data
//				String tableName = form.getFirst("dbName");
//				GATracker.getInstance().trackCsvUpload(files, tableName, headerDataTypes);
//			}
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
//			// we can't really use the options object for this :/
//			String files = form.getFirst("file");
//			if(files != null && !files.isEmpty()) {
//				deleteFilesFromServer(files.split(";"));
//			}
//			String questionFile = form.getFirst("questionFile");
//			if(questionFile != null && !questionFile.isEmpty()) {
//				deleteFilesFromServer(new String[]{questionFile});
//			}
//		}
//
//		String outputText = "Flat database loading was a success.";
//		return Response.status(200).entity(gson.toJson(outputText)).build();
//	}
//	
//	@POST
//	@Path("/excel/flat")
//	@Produces("application/json")
//	/**
//	 * The process flow for loading a flat file based on drag/drop
//	 * Since the user confirms what type of datatypes they want for each column
//	 * The file has already been loaded onto the server
//	 * @param form					Form object containing all the information regarding the load
//	 * @param request
//	 * @return
//	 */
//	public Response uploadFlatExcelFile(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
//		Gson gson = new Gson();
//		System.out.println(form);
//
//		ImportOptions options = null;
//		try {
//			options = new ImportOptions();
//			setDefaultOptions(options, form);
//			// can only load flat files as RDBMS
//			options.setDbType(ImportOptions.DB_TYPE.RDBMS);
//			options.setRDBMSDriverType(SQLQueryUtil.DB_TYPE.H2_DB);
//			options.setImportType(ImportOptions.IMPORT_TYPE.EXCEL_FLAT_UPLOAD);
//
//			// set the files
//			String files = form.getFirst("file");
//			if(files == null || files.trim().isEmpty()) {
//				Map<String, String> errorHash = new HashMap<String, String>();
//				errorHash.put("errorMessage", "No files have been identified to upload");
//				return Response.status(400).entity(gson.toJson(errorHash)).build();
//			}
//			options.setFileLocation(files);
//
//			// see if user also manually changed some the headers
//			String newHeadersStr = form.getFirst("newHeaders");
//			if(newHeadersStr != null) {
//				List<Map<String, Map<String, String>>> newHeaders = 
//						gson.fromJson(newHeadersStr, new TypeToken<List<Map<String, LinkedHashMap<String, String>>>>() {}.getType());
//				options.setExcelNewHeaders(newHeaders);
//			}
//			
//			// this should always be present now since we dont want to predict types twice on the BE
//			// get the data types and set them in the options
//			String headerDataTypesStr = form.getFirst("headerData");
//			if(headerDataTypesStr != null) {
//				List<Map<String, Map<String, String[]>>> headerDataTypes = 
//						gson.fromJson(headerDataTypesStr, new TypeToken<List<Map<String, LinkedHashMap<String, String[]>>>>() {}.getType());
//				options.setExcelDataTypeMap(headerDataTypes);
//				
//				// track GA data
//				String dbName = form.getFirst("dbName");
//                String fileName = files.substring(files.lastIndexOf("\\") + 1, files.lastIndexOf("."));
//                GATracker.getInstance().trackExcelUpload(dbName, fileName, headerDataTypes);
//			}
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
//			// we can't really use the options object for this :/
//			String files = form.getFirst("file");
//			if(files != null && !files.isEmpty()) {
//				deleteFilesFromServer(files.split(";"));
//			}
//			String questionFile = form.getFirst("questionFile");
//			if(questionFile != null && !questionFile.isEmpty()) {
//				deleteFilesFromServer(new String[]{questionFile});
//			}
//		}
//
//		String outputText = "Flat database loading was a success.";
//		return Response.status(200).entity(gson.toJson(outputText)).build();
//	}
//	
//	// TODO: refactor this into setDefaultOptions
//	private ImportOptions setupImportOptionsForExternalConnection(HashMap<String, String> options, HashMap<String, Object> externalMetamodel) {
//		ImportOptions importOptions = new ImportOptions();
//		importOptions.setBaseFolder(DIHelper.getInstance().getProperty("BaseFolder"));
//		importOptions.setDbType(ImportOptions.DB_TYPE.RDBMS);
//		importOptions.setImportMethod(ImportOptions.IMPORT_METHOD.CONNECT_TO_EXISTING_RDBMS);
//		importOptions.setImportType(ImportOptions.IMPORT_TYPE.EXTERNAL_RDBMS);
//		importOptions.setAutoLoad(autoLoad);
//		importOptions.setAllowDuplicates(true);
//		importOptions.setDbName(options.get("dbName"));
//		importOptions.setRDBMSDriverType(SQLQueryUtil.DB_TYPE.valueOf(options.get("driver")));
//		//importOptions.setRDBMSDriverType(SQLQueryUtil.DB_TYPE.DB2);
//		importOptions.setHost(options.get("hostname"));
//		importOptions.setPort(options.get("port"));
//		importOptions.setSchema(options.get("schema"));
//		importOptions.setUsername(options.get("username"));
//		importOptions.setPassword(options.get("password"));
//		importOptions.setAdditionalJDBCProperties(options.get("additionalParams"));
//		importOptions.setExternalMetamodel(externalMetamodel);
//		
//		return importOptions;
//	}
//	
//	@GET
//	@Path("/solr/ping")
//	@Produces("applicaiton/json")
//	public Response validSolrEngine(@QueryParam("solrURL") String solrURL , @QueryParam("coreName") String coreName) {
//		boolean isValid = SolrEngine.ping(solrURL, coreName);
//		return WebUtility.getResponse(isValid, 200);
//	}
//	
//	@POST
//	@Path("/solr/getMetamodel")
//	@Produces("applicaiton/json")
//	public Response getSolrMetadata(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
//		String solrURL = form.getFirst("solrURL");
//		String coreName = form.getFirst("coreName");
//		boolean isValid = SolrEngine.ping(solrURL, coreName);
//		if(isValid) {
//			Map<String, Object> schemaData = SolrEngine.getSchema(solrURL, coreName);
//			// we send a really weird object
//			// so going format this data into that format
//			List<String> columnHeaders = (List<String>) schemaData.get(SolrEngine.SCHEMA_HEADERS_KEY);
//			List<String> columnTypes = (List<String>) schemaData.get(SolrEngine.SCHEMA_DATA_TYPE_KEY);
//			String key = (String) schemaData.get(SolrEngine.SCHEMA_UNIQUE_HEADER_KEY);
//			String keyType = (String) schemaData.get(SolrEngine.SCHEMA_UNIQUE_HEADER_DATA_TYPE_KEY);
//
//			Map<String, Object> retMap = new HashMap<String, Object>();
//			Map<String, List<Map<String, Object>>> tableMap = new HashMap<String, List<Map<String, Object>>>();
//			retMap.put("tables", tableMap);
//			// we only have one table in our solr metamodel
//			List<Map<String, Object>> fieldList = new Vector<Map<String, Object>>();
//			// add the name of the "table" -> i.e. the unique field
//			// to all of the other fields
//			tableMap.put(key, fieldList);
//			int numFields = columnHeaders.size();
//			for(int i = 0; i < numFields; i++) {
//				Map<String, Object> fieldMap = new HashMap<String, Object>();
//				String field = columnHeaders.get(i);
//				fieldMap.put("name", field);
//				fieldMap.put("type", columnTypes.get(i));
//				if(field.equals(key)) {
//					fieldMap.put("isPK", true);
//				} else {
//					fieldMap.put("isPK", false);
//				}
//				fieldList.add(fieldMap);
//			}
//			
//			return WebUtility.getResponse(retMap, 200);
//		} else {
//			Map<String, String> errorMap = new HashMap<String, String>();
//			errorMap.put("errorMessage", "Unable to successfully connect to solr instance at " + solrURL);
//			return WebUtility.getResponse(errorMap, 400);
//		}
//	}
//	
//	@POST
//	@Path("/solr/loadSolrEngine")
//	@Produces("applicaiton/json")
//	public Response loadSolrEngine(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
//		String solrURL = form.getFirst("solrURL");
//		String coreName = form.getFirst("coreName");
//		boolean isValid = SolrEngine.ping(solrURL, coreName);
//		String appName = form.getFirst("dbName");
//		String appID = UUID.randomUUID().toString();
//		
//		if(isValid) {
//			appName = Utility.makeAlphaNumeric(appName);
//			SolrEngineConnector connector = new SolrEngineConnector();
//			try {
//				connector.processExistingSolrConnection(appName, appID, solrURL, coreName  );
//			} catch (IOException e) {
//				e.printStackTrace();
//				Map<String, Object> errorMap = new HashMap<String, Object>();
//				errorMap.put("errorMessage", e.getMessage());
//				return WebUtility.getResponse(errorMap, 400);
//			}
//			boolean success = true;
//			Map<String, Object> ret = new HashMap<String, Object>();
//			ret.put("success", success);
//			return WebUtility.getResponse(ret, 200);
//		} else {
//			Map<String, String> errorMap = new HashMap<String, String>();
//			errorMap.put("errorMessage", "Unable to successfully connect to solr instance at " + solrURL);
//			return WebUtility.getResponse(errorMap, 400);
//		}
//	}
	
	
	
	
	
}
