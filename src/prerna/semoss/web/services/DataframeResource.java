package prerna.semoss.web.services;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import prerna.algorithm.api.ITableDataFrame;
import prerna.ds.h2.H2Frame;
import prerna.engine.api.IEngine;
import prerna.om.Insight;
import prerna.om.OldInsight;
import prerna.poi.main.InsightFilesToDatabaseReader;
import prerna.query.querystruct.selectors.IQuerySelector;
import prerna.query.querystruct.selectors.QueryColumnSelector;
import prerna.sablecc2.reactor.imports.FileMeta;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

public class DataframeResource {

	private static final Logger LOGGER = LogManager.getLogger(DataframeResource.class.getName());
	
	@Context
	ServletContext context;

	Logger logger = Logger.getLogger(DataframeResource.class.getName());
	Insight insight = null;

//	@POST
//	@Path("/openBackDoor")
//	@Produces("application/json")
//	public Response openBackDoor(@Context HttpServletRequest request){
//		TinkerFrame tf = (TinkerFrame) insight.getDataMaker();
//		tf.openBackDoor();
//		return WebUtility.getResponse("Successfully closed back door", 200);
//	}

	@POST
	@Path("/applyCalc")
	@Produces("application/json")
	public Response applyCalculation(MultivaluedMap<String, String> form, @Context HttpServletRequest request){
		String pkqlCmd = form.getFirst("expression");
		Map<String, Object> resultHash = null;
		synchronized(insight) {
			resultHash = insight.runPkql(pkqlCmd);
		}

		//TODO: stupid stuff that was never cleaned up... 
		Map<String, Object> stupidFEObj = new HashMap<String, Object>();
		stupidFEObj.put("insights", new Object[]{resultHash});
		
		return WebUtility.getResponse(stupidFEObj, 200);
	}
	
	@POST
	@Path("/runPksl2")
	@Produces("application/json")
	public Response runPixel(MultivaluedMap<String, String> form, @Context HttpServletRequest request){
		String pixelCmd = form.getFirst("expression");
		Map<String, Object> resultHash = null;
		synchronized(insight) {
			resultHash = insight.runPixel(pixelCmd);
		}

		return WebUtility.getResponse(resultHash, 200);
	}

	//for handling playsheet specific tool calls
	@POST
	@Path("do-{method}")
	@Produces("application/json")
	public Response doMethod(@PathParam("method") String method, MultivaluedMap<String, String> form, @Context HttpServletRequest request)
	{    	
		Gson gson = new Gson();
		Map<String, Object> hash = gson.fromJson(form.getFirst("data"), new TypeToken<Map<String, Object>>() {}.getType());
		if(insight instanceof OldInsight) {
			Object ret = ((OldInsight) this.insight).getPlaySheet().doMethod(method, hash);
			return WebUtility.getResponse(ret, 200);
		} else {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Rest call is not applicable for this insight");
			return WebUtility.getResponse(errorHash, 200);
		}
	}

	@GET
	@Path("/isDbInsight")
	@Produces("application/json")
	public Response isDbInsight(@Context HttpServletRequest request){
		/*
		 * This method is used to determine if the insight has data that has been inserted
		 * into the frame that does not currently sit in a full-fledged database.
		 * An example of this is when an insight contains data that was added via a csv file.
		 * 
		 * We refer to these insights as nonDbInsights even if they contain data that
		 * does contain some information from full dbs
		 */
		
		Map<String, Object> retMap = new HashMap<String, Object>();
		if(insight.getFilesUsedInInsight() == null || insight.getFilesUsedInInsight().isEmpty()) {
			retMap.put("isDbInsight", true);
		} else {
			retMap.put("isDbInsight", false);
		}
		return WebUtility.getResponse(retMap, 200);
	}
	
	/*
	 * Ideally this should go into the data frame
	 */
	private void generateFile(ITableDataFrame dataFrame, String engineName)
	{
		if(dataFrame instanceof H2Frame)
		{
			try {
				H2Frame h2Frame = (H2Frame)dataFrame;
				Connection conn = h2Frame.getBuilder().getConnection();
				
				FileMeta origFileMeta = insight.getFilesUsedInInsight().get(0);
				String fileLoc = origFileMeta.getFileLoc();
				
				String mainFileName = Utility.getOriginalFileName(fileLoc);
				String baseFolder = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER);
				
				makeDataDirectory(engineName);
				String dataLoc = baseFolder + "/" + "db" + "/" + engineName + "/" + "data";
				String thisFile = dataLoc + "/"  + mainFileName;
				String h2TableName = h2Frame.getBuilder().getTableName();
				conn.createStatement().execute("CALL CSVWrite('" + thisFile + "', 'SELECT * FROM " + h2TableName + "')");
				
				// changing this to the basefolder
				thisFile = "@BaseFolder@" + "/" + "db" + "/" + "@ENGINE@" + "/" + "data" + "/" + mainFileName;
				FileMeta fileMeta = new FileMeta();
				fileMeta.setFileLoc(thisFile);
				fileMeta.setOriginalFile(fileLoc); // this is the original file that came in
				fileMeta.setType(FileMeta.FILE_TYPE.CSV);
				// need to also create a QueryStruct2 object for the file
				// so i know how to create the new pksl string to replace this one
				List<String> selectors = h2Frame.getSelectors();
				List<IQuerySelector> newSelectors = new Vector<IQuerySelector>();
				Map<String, String> dataTypeMap = new HashMap<String, String>();
				for(int i = 0; i < selectors.size(); i++) {
					QueryColumnSelector newCol = new QueryColumnSelector(selectors.get(i));
					newSelectors.add(newCol);
					// upper case in the data-type map because the headers are upper case
					// when we do the CSVWrite method in sql
					dataTypeMap.put(newCol.getColumn().toUpperCase(), h2Frame.getMetaData().getHeaderTypeAsString(selectors.get(i)));
				}
				fileMeta.setSelectors(newSelectors);
				fileMeta.setDataMap(dataTypeMap);
				fileMeta.setNewHeaders(origFileMeta.getNewHeaders());
				Vector <FileMeta> files = new Vector<FileMeta>();
				files.add(fileMeta);
				insight.setFilesUsedInInsight(files);
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
	private void makeDataDirectory(String engineName)
	{
		String baseFolder = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER);
		String engineFolder = baseFolder + "/"  + "db" + "/" + engineName;
		File file = new File(engineFolder);
		if(!file.exists())
			file.mkdir();
		String dataLoc = engineFolder + "/" + "data"; 
		file = new File(dataLoc); // make the data folder
		if(!file.exists())
			file.mkdir();				
		
	}
	
	private void moveFileToDB(String engineName)
	{
		try{
			String baseFolder = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER);
			makeDataDirectory(engineName);
			FileMeta origFileMeta = insight.getFilesUsedInInsight().get(0);
			String originalFileLoc = origFileMeta.getFileLoc();
			
			String originalMainFileName = Utility.getOriginalFileName(originalFileLoc);
			String newLoc = baseFolder + "/" + "db" + "/" + engineName + "/" + "data" + "/" + originalMainFileName;
			
			File file = new File(originalFileLoc);
			// move the file to the data location
			FileUtils.copyFile(file, new File(newLoc));

			file.delete();
			FileMeta fileMeta = new FileMeta();
			
			// set the selectors used from the previous file meta to the new one
			fileMeta.setSelectors(origFileMeta.getSelectors());
			fileMeta.setFileLoc(newLoc);
			fileMeta.setOriginalFile(originalFileLoc); // this is the original file that came in
			fileMeta.setType(FileMeta.FILE_TYPE.CSV);
			// need to also create a QueryStruct2 object for the file
			// so i know how to create the new pksl string to replace this one
			String[] selectors = ((ITableDataFrame) this.insight.getDataMaker()).getQsHeaders();
			List<IQuerySelector> newSelectors = new Vector<IQuerySelector>();
			for(int i = 0; i < selectors.length; i++) {
				newSelectors.add(new QueryColumnSelector(selectors[i]));
			}
			fileMeta.setSelectors(newSelectors);
			fileMeta.setDataMap(origFileMeta.getDataMap());
			fileMeta.setNewHeaders(origFileMeta.getNewHeaders());

			Vector <FileMeta> files = new Vector<FileMeta>();
			files.add(fileMeta);
			this.insight.setFilesUsedInInsight(files);
		}catch(Exception ex)
		{
			ex.printStackTrace();
		}
	}

	
	@POST
	@Path("/saveFilesInInsightAsDb")
	@Produces("application/json")
	public Response saveFilesUsedInInsightIntoDb(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		// we need to create a full db now
		// do it based on the csv file name and the date
		logger.info("Start loading files in insight into database");
		// we need to create a full db now
		// do it based on the csv file name and the date
		
		// couple of things to do here
		// need to take the dataframe and persist it into a file space
		// and use that file instead of the core file

		ITableDataFrame dataframe = (ITableDataFrame) this.insight.getDataMaker();

		String originalFile = form.getFirst("ORIGINAL_FILE");
		
		String engineName = form.getFirst("engineName");
		IEngine createdEng = null;
		if(originalFile == null && dataframe instanceof H2Frame) {
			generateFile(dataframe, engineName);
		} else {// leave the original file as is and move it to the database
			moveFileToDB(engineName);
		}
		
		InsightFilesToDatabaseReader creator = new InsightFilesToDatabaseReader();
		
		try { 
			createdEng = creator.processInsightFiles(insight, engineName);
			logger.info("Done loading files in insight into database");
		} catch (IOException e) {
			e.printStackTrace();
			Map<String, String> errorMap = new HashMap<String, String>();
			String errorMessage = "Data loading was not successful";
			if(e.getMessage() != null && !e.getMessage().isEmpty()) {
				errorMessage = e.getMessage();
			}
			errorMap.put("errorMessage", errorMessage);
			return WebUtility.getResponse(errorMap, 200);
		}

		logger.info("Start modifying PKQL to query of new engine");

		List<FileMeta> filesMetadata = insight.getFilesUsedInInsight();
		
		// need to align each file to the table that was created from it
		Set<String> newTables = creator.getNewTables();
		Map<String, List<String>> newTablesAndCols = new Hashtable<String, List<String>>();
		for(String newTable : newTables) {
			List<String> props = createdEng.getProperties4Concept(newTable, true);
			newTablesAndCols.put(newTable, props);
		}
		
		// this will be used to keep track of the old parent to the new parent
		// this is needed so the FE can properly create parameters
		Map<String, String> parentMap = new HashMap<String, String>();

		// need to update the recipe now
		for(FileMeta fileMeta : filesMetadata) {

			// this is the pkql string we need to update
			String pkqlStrToFind = fileMeta.getOriginalFile();
			List<String> listPkslStrings = this.insight.getPixelRecipe();

			// keep track of all statements
			// only used if updatePkqlExpression boolean becomes true
			List<String> newPkqlRun = new Vector<String>();

			// this is the list of headers that were uploaded into the frame
			List<IQuerySelector> selectorsToMatch = fileMeta.getSelectors();

			// need to iterate through and update the correct pkql
			for(int pkqlIdx = 0; pkqlIdx < listPkslStrings.size(); pkqlIdx++) {
				String pkslExpr = listPkslStrings.get(pkqlIdx);
				// we store the API Reactor string
				// but this will definitely be stored within a data.import
				if(pkslExpr.contains(pkqlStrToFind)) {

					// find the new table that was created from this file
					String tableToUse = null;
					TABLE_LOOP : for(String newTable : newTablesAndCols.keySet()) {
						// get the list of columns for the table that exists in the engine
						List<String> selectors = newTablesAndCols.get(newTable);

						// need to see if all selectors match
						SELECTOR_MATCH_LOOP : for(IQuerySelector selectorInFile : selectorsToMatch) {
							boolean selectorFound = false;
							if(selectorInFile.getSelectorType() == IQuerySelector.SELECTOR_TYPE.COLUMN) {
								for(String selectorInTable : selectors) {
									// we found a match, we are good
									// format of selector in table is http://semoss.org/ontologies/Relation/Contains/Rotten_Tomatoes_Audience/MOVIECSV
									QueryColumnSelector cQuerySelector = (QueryColumnSelector) selectorInFile;
									String compare = cQuerySelector.getColumn();
									if(compare.equalsIgnoreCase(Utility.getClassName(selectorInTable))) {
										selectorFound = true;
										continue SELECTOR_MATCH_LOOP;
									}
								}
							}
							if(selectorFound == false) {
								// if we hit this point, then there was a selector
								// in selectorsToMatch that wasn't found in the tableSelectors
								// lets look at next table
								continue TABLE_LOOP;
							}
						} // end SELECTOR_MATCH_LOOP

						// if we hit this point, then everything matched!
						tableToUse = newTable;

						// lets update the prim key name from its currently random name
						// to the name of the table
//						UPDATE_PRIM_KEY_LOOP : for(String parentName : edgeHash.keySet()) {
//							Set<String> children = edgeHash.get(parentName);
//
//							// if the set contains all the names of the file
//							// it is the one we want to modify
//							if(children.containsAll(selectorsToMatch)) {
//								dataframe.modifyColumnName(parentName, tableToUse);
//								parentMap.put(parentName, tableToUse);
//
//								// need to also add the engine name for each of the nodes
//								// do this for the main node
//								// and for each of the children nodes
//								dataframe.addEngineForColumnName(tableToUse, engineName);
//								for(String child : children) {
//									dataframe.addEngineForColumnName(child, engineName);
//								}
//
//								break UPDATE_PRIM_KEY_LOOP;
//							}
//						}

						break TABLE_LOOP;
					}

					// this will update the pkql query to run
					newPkqlRun.add(fileMeta.generatePixelOnEngine(engineName, tableToUse));

				} else {
					newPkqlRun.add(pkslExpr);
				}
			}
			insight.setPixelRecipe(newPkqlRun);

			// now setting the expression in the prop to store the string combination
			// of all the pkqls with the swap we made
			// create an individual string containing the pkql
//			StringBuilder newPkqlExp = new StringBuilder();
//			for(String pkql : newPkqlRun) {
//				newPkqlExp.append(pkql);
//			}
//			Map<String, Object> props = pkqlTrans.getProperties();
//			props.put(PKQLTransformation.EXPRESSION, newPkqlExp);
//			// update the parsed pkqls so the next time this insight instance is used it is not 
//			// still assuming it is a nonDbInsight
//			pkqlTrans.setPkql(newPkqlRun);
		}
		

		logger.info("Done modifying PKQL to query of new engine");

		// clear the files since they are now loaded into the engine
		filesMetadata.clear();

		// we will return the new insight recipe after the PKQL has been modified
		Map<String, Object> retData = new HashMap<String, Object>();

		List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
		List<String> pkqlRecipe = this.insight.getPixelRecipe();
		for(String command: pkqlRecipe) {
			Map<String, Object> retMap = new HashMap<String, Object>();
			retMap.put("command", command);
			list.add(retMap);
		}

		retData.put("parentMap", parentMap);
		retData.put("recipe", list);
		return WebUtility.getResponse(retData, 200);
	}
}
