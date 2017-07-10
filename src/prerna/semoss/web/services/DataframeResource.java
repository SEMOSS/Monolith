package prerna.semoss.web.services;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import prerna.algorithm.api.ITableDataFrame;
import prerna.auth.User;
import prerna.auth.UserPermissionsMasterDB;
import prerna.ds.AbstractTableDataFrame;
import prerna.ds.TableDataFrameFactory;
import prerna.ds.TinkerFrame;
import prerna.ds.export.gexf.IGexfIterator;
import prerna.ds.export.gexf.RdbmsGexfIterator;
import prerna.ds.export.graph.GraphExporterFactory;
import prerna.ds.export.graph.IGraphExporter;
import prerna.ds.export.graph.TinkerFrameGraphExporter;
import prerna.ds.h2.H2Frame;
import prerna.ds.nativeframe.NativeFrame;
import prerna.engine.api.IEngine;
import prerna.om.GraphDataModel;
import prerna.om.Insight;
import prerna.om.InsightMessageStore;
import prerna.om.InsightStore;
import prerna.om.OldInsight;
import prerna.poi.main.InsightFilesToDatabaseReader;
import prerna.sablecc.meta.FilePkqlMetadata;
import prerna.ui.components.playsheets.datamakers.IDataMaker;
import prerna.ui.components.playsheets.datamakers.PKQLTransformation;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

public class DataframeResource {

	private static final Logger LOGGER = LogManager.getLogger(DataframeResource.class.getName());
	
	@Context
	ServletContext context;

	Logger logger = Logger.getLogger(DataframeResource.class.getName());
	Insight insight = null;

	@Path("/analytics")
	public Object runEngineAnalytics(){
		AnalyticsResource analytics = new AnalyticsResource();
		return analytics;
	}

	@POST
	@Path("/clear")
	@Produces("application/json")
	public Response clearInsight(@Context HttpServletRequest request){
		Insight newInsight = new Insight();
		String id = insight.getInsightId();
		newInsight.setInsightId(id);
		newInsight.setEngineName(insight.getEngineName());
		newInsight.setRdbmsId(insight.getRdbmsId());
		
		List<String> newPkslList = new Vector<String>(insight.getPkslRecipe());
		newInsight.runPkql(newPkslList);
		
		//TODO:
		//TODO:
		//TODO:
		//TODO:
		//TODO:
		//TODO: clear the insight
		
//		String dmName = insight.getDataMakerName();
//		String engName = insight.getEngineName();
//		String rdbmsId = insight.getRdbmsId();
//		Insight parentInsight = insight.getParentInsight();
//		int dataId = insight.getDataMaker().getDataId();
//		
//		IEngine eng = null;
//		if(engName != null){
//			eng = Utility.getEngine(engName);//(IEngine) DIHelper.getInstance().getLocalProp(engName);
//		}
//		String layoutName = insight.getOutput();
//
//		this.insight = new Insight(eng, dmName, layoutName);
//		this.insight.getDataMaker(); // need to instatiate datamaker so next call doesn't try to get it from cache
//		this.insight.setInsightID(id);
//		if(rdbmsId != null) {
//			this.insight.setRdbmsId(rdbmsId);
//		}
//		
//		this.insight.setParentInsight(parentInsight);
//		if(this.insight.isJoined()) {
//			Dashboard dashboard = (Dashboard)parentInsight.getDataMaker();
//			List<Insight> insights = new ArrayList<>(1);
//			insights.add(this.insight);
//			dashboard.addInsights(insights);
//		}
//		
		int dataId = 0;
		while(dataId >= insight.getDataMaker().getDataId()) {
			newInsight.getDataMaker().updateDataId();
		}
		// update the id one more time
		newInsight.getDataMaker().updateDataId();

		InsightStore.getInstance().put(id, newInsight);
		return WebUtility.getResponse("Insight " + id + " has been cleared", 200);
	}

	@GET
	@Path("/getFilterModel")
	@Produces("application/json")
	public Response getFilterModel(@Context HttpServletRequest request) {
		IDataMaker table = insight.getDataMaker();
		//		String colName = form.getFirst("col");
		Map<String, Object> retMap = new HashMap<String, Object>();

		if(table instanceof ITableDataFrame) {
			Object[] filterModel = null;
			filterModel = ((ITableDataFrame)table).getFilterModel();
			retMap.put("unfilteredValues", filterModel[0]);
			retMap.put("filteredValues", filterModel[1]);
			retMap.put("minMax", filterModel[2]);

			return WebUtility.getResponse(retMap, 200);
		} 

		else {
			return WebUtility.getResponse("Data Maker not instance of ITableDataFrame.  Cannot grab filter model from Data Maker.", 400);
		}
	}

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
	@Path("/runPksl")
	@Produces("application/json")
	public Response runPksl(MultivaluedMap<String, String> form, @Context HttpServletRequest request){
		String pkslCmd = form.getFirst("expression");
		Map<String, Object> resultHash = null;
		synchronized(insight) {
			resultHash = insight.runPksl(pkslCmd);
		}

		//TODO: stupid stuff that was never cleaned up... 
		Map<String, Object> stupidFEObj = new HashMap<String, Object>();
		stupidFEObj.put("insights", new Object[]{resultHash});
		
		return WebUtility.getResponse(stupidFEObj, 200);
	}

	@POST
	@Path("/drop")
	@Produces("application/json")
	public Response dropInsight(@Context HttpServletRequest request) {
		String insightID = insight.getInsightId();

		boolean isReadOnlyInsight = false;
		String inEngine = insight.getEngineName();
		String inRdbmsId = insight.getRdbmsId();
		
		if(inEngine != null && inRdbmsId != null) {
			HttpSession session = request.getSession();
			User user = ((User) session.getAttribute(Constants.SESSION_USER));
			String userId = "";
			if(user!= null) {
				userId = user.getId();
			}
			
			UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
			isReadOnlyInsight = permissions.isUserReadOnlyInsights(userId, inEngine, inRdbmsId);
		}
		
		if(!isReadOnlyInsight) {
//			logger.info("Dropping insight with id ::: " + insightID);
			boolean success = InsightStore.getInstance().remove(insightID);
//			InsightStore.getInstance().removeFromSessionHash(request.getSession().getId(), insightID);
//			IDataMaker dm = insight.getDataMaker();
//			if(dm instanceof H2Frame) {
//				H2Frame frame = (H2Frame)dm;
//				frame.closeRRunner();
//				frame.dropTable();
//				if(!frame.isInMem()) {
//					frame.dropOnDiskTemporalSchema();
//				}
//			} else if(dm instanceof RDataTable) {
//				RDataTable frame = (RDataTable)dm;
//				frame.closeConnection();
//			} else if(dm instanceof Dashboard) {
//				Dashboard dashboard = (Dashboard)dm;
//				dashboard.dropDashboard();
//			}
//			
//			// also see if other variables in runner that need to be dropped
//			PKQLRunner runner = insight.getPKQLRunner();
//			runner.cleanUp();
//			
			// also delete any files that were used
			List<FilePkqlMetadata> fileData = insight.getFilesUsedInInsight();
			if(fileData != null && !fileData.isEmpty()) {
				for(int fileIdx = 0; fileIdx < fileData.size(); fileIdx++) {
					FilePkqlMetadata file = fileData.get(fileIdx);
					File f = new File(file.getFileLoc());
					f.delete();
				}
			}
			
			if(success) {
				logger.info("Succesfully dropped insight " + insightID);
				return WebUtility.getResponse("Succesfully dropped insight " + insightID, 200);
			} else {
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "Could not remove data.");
				return WebUtility.getResponse(errorHash, 400);
			}
		} else {
			return WebUtility.getResponse("Insight is a read and no need to drop insight " + insightID, 200);
		}
	}

	@POST
	@Path("/getNextTableData")
	@Produces("application/json")
	public Response getNextTable(MultivaluedMap<String, String> form,
			@QueryParam("startRow") Integer startRow,
			@QueryParam("endRow") Integer endRow,
			@Context HttpServletRequest request)
	{
		ITableDataFrame dm = (ITableDataFrame) insight.getDataMaker();

		if(dm != null) {
			if(startRow == null)
				startRow = 0;
			if(endRow == null)
				endRow = 500;
			Gson gson = new Gson();
			Map<String, String> sortModel = gson.fromJson(form.getFirst("sortModel"), new TypeToken<Map<String, String>>() {}.getType());
			String concept = null;
			String orderDirection = null;
	
			Map<String, Object> options = new HashMap<String, Object>();
			if(sortModel != null && !sortModel.isEmpty()) {
				concept = sortModel.get("colId");
				if(concept != null && !concept.isEmpty()) {
					orderDirection = sortModel.get("sort");
					if(orderDirection == null || orderDirection.isEmpty()) {
						orderDirection = "asc";
					}
					options.put(AbstractTableDataFrame.SORT_BY, concept);
					options.put(AbstractTableDataFrame.SORT_BY_DIRECTION, orderDirection);
				}
			}
			if(startRow >= 0 && endRow > startRow) {
				options.put(AbstractTableDataFrame.OFFSET, startRow);
				options.put(AbstractTableDataFrame.LIMIT, (endRow - startRow));
			}
	
			List<String> selectors = gson.fromJson(form.getFirst("selectors"), new TypeToken<List<String>>() {}.getType());
			if(selectors.isEmpty()) {
				options.put(AbstractTableDataFrame.SELECTORS, Arrays.asList(dm.getColumnHeaders()));
				selectors = Arrays.asList(dm.getColumnHeaders());
			} else {
				options.put(AbstractTableDataFrame.SELECTORS, selectors);
			}
			options.put(AbstractTableDataFrame.DE_DUP, true);
	
			Iterator<Object[]> it = dm.iterator(options);
			return Response.status(200).entity(WebUtility.getSO(insight.getInsightId(), selectors.toArray(new String[]{}), it)).build();
		} else {
			Map<String, Object> ret = new HashMap<String, Object>();
			ret.put("insightID", insight.getInsightId());
			ret.put("data", new Object[0][]);
			ret.put("headers", new String[0]);
			return Response.status(200).entity(WebUtility.getSO(ret)).build();
		}
	}

	@GET
	@Path("/getTableHeaders")
	@Produces("application/json")
	public Response getTableHeaders() {
		Map<String, Object> retMap = new HashMap<String, Object>();

		ITableDataFrame table = (ITableDataFrame) insight.getDataMaker();	
		retMap.put("insightID", insight.getInsightId());
		retMap.put("tableHeaders", table.getTableHeaderObjects());
		return WebUtility.getResponse(retMap, 200);
	}

	@GET
	@Path("/recipe")
	@Produces("application/json")
	public Response getRecipe() {
		Map<String, Object> retMap = new HashMap<String, Object>();		
		retMap.put("recipe", insight.getPkslRecipe());
		return WebUtility.getResponse(retMap, 200);
	}

	@POST
	@Path("/getGraphData")
	@Produces("application/json")
	public Response getGraphData(@Context HttpServletRequest request){
		IDataMaker maker = insight.getDataMaker();
		IGraphExporter exporter = null;
		try {
			exporter = GraphExporterFactory.getExporter(maker);
		} catch(IllegalArgumentException e) {
			Map<String, String> errorMap = new Hashtable<String, String>();
			errorMap.put("errorMessage", e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		if(exporter != null) {
			return WebUtility.getResponse(insight.getInsightId(), exporter);
		}
		// we have some stuff that hasn't been ported over yet...
		// gdm is not that big of a deal
		else if (maker instanceof GraphDataModel) {
			return WebUtility.getResponse(((GraphDataModel)maker).getDataMakerOutput(), 200);
		}
		// ... but native frame is very very bad... :(
		else if(maker instanceof NativeFrame) {
			H2Frame frame = TableDataFrameFactory.convertToH2FrameFromNativeFrame((NativeFrame)maker);
			TinkerFrame tframe = TableDataFrameFactory.convertToTinkerFrameForGraph((H2Frame)frame);
			return WebUtility.getResponse(insight.getInsightId(), new TinkerFrameGraphExporter((TinkerFrame) tframe));
		} else {
			Map<String, String> errorMap = new Hashtable<String, String>();
			errorMap.put("errorMessage", "Data type cannot yet be represented as a graph");
			return WebUtility.getResponse("Illegal data maker type ", 400);
		}
	}
	
	@POST
	@Path("/getGexf")
	@Produces("application/json")
	public Response getGexf(MultivaluedMap<String, String> form, @Context HttpServletRequest request) 
	{
		ITableDataFrame table = null;
		try {
			table = (ITableDataFrame) insight.getDataMaker();
		} catch(ClassCastException e) {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Insight data maker could not be cast to a table data frame.");
			return WebUtility.getResponse(errorHash, 400);
		}
		if(table == null) {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Could not find insight data maker.");
			return WebUtility.getResponse(errorHash, 400);
		}

		String nodes = form.getFirst("nodes");
		String edges = form.getFirst("edges");
		String aliasStr = form.getFirst("alias");
		Map<String, String> alias = null;
		if(aliasStr != null && !aliasStr.trim().isEmpty()) {
			Gson gson = new Gson();
			alias = gson.fromJson(form.getFirst("aslias"), new TypeToken<Map<String, String>>() {}.getType());
		} else {
			alias = new HashMap<String, String>();
		}
		
		IGexfIterator gexf = null;
		if(table instanceof H2Frame) {
			gexf = new RdbmsGexfIterator((H2Frame) table, nodes, edges, alias);
		}
		
		return Response.status(200).entity(WebUtility.getSO(insight.getInsightId(), gexf)).build();
	}

//	@POST
//	@Path("getVizTable")
//	@Produces("application/json")
//	public Response getExploreTable(
//			//@QueryParam("start") int start,
//			//@QueryParam("end") int end,
//			@Context HttpServletRequest request)
//	{
//		ITableDataFrame mainTree = (ITableDataFrame) insight.getDataMaker();		
//		if(mainTree == null) {
//			Map<String, String> errorHash = new HashMap<String, String>();
//			errorHash.put("errorMessage", "Dataframe not found within insight");
////			return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
//			return WebUtility.getResponse(errorHash, 400);
//		}
//
//		List<Object[]> table = mainTree.getData();
//		String[] headers = mainTree.getColumnHeaders();
//		Map<String, Object> returnData = new HashMap<String, Object>();
//		returnData.put("data", table);
//		returnData.put("headers", headers);
//		returnData.put("insightID", insight.getInsightID());
//		return WebUtility.getResponse(returnData, 200);
//	}
//
	/**
	 * If its a tinker, parse the meta graph to get metamodel
	 * Otherwise go through the components and either 1. parse QueryBuilderData or 2. parse query
	 * 
	 * @param insightID
	 * @return
	 */
	@POST
	@Path("/getInsightMetamodel")
	@Produces("application/json")
	public Response getInsightMetamodel(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		ITableDataFrame dataFrame = (ITableDataFrame) this.insight.getDataMaker();
		if (dataFrame == null) {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Dataframe not found within insight");
//			return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			return WebUtility.getResponse(errorHash, 400);
		}
		Map<String, Object> returnHash = null;
		try {
			returnHash = insight.getInsightMetaModel();
		} catch(IllegalArgumentException e) {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", e.getMessage());
			return WebUtility.getResponse(errorHash, 400);
		}
		return WebUtility.getResponse(returnHash, 200);
	}

	@POST
	@Path("/hasDuplicates")
	@Produces("application/json")
	public Response hasDuplicates(MultivaluedMap<String, String> form,
			@Context HttpServletRequest request) 
	{
		ITableDataFrame table = null;
		try {
			table = (ITableDataFrame) insight.getDataMaker();
		} catch(ClassCastException e) {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Insight data maker could not be cast to a table data frame.");
			return WebUtility.getResponse(errorHash, 400);
		}
		if(table == null) {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Could not find insight data maker.");
			return WebUtility.getResponse(errorHash, 400);
		}

		Gson gson = new Gson();
		String[] columns = gson.fromJson(form.getFirst("concepts"), String[].class);
		String[] columnHeaders = table.getColumnHeaders();
		Map<String, Integer> columnMap = new HashMap<>();
		for(int i = 0; i < columnHeaders.length; i++) {
			columnMap.put(columnHeaders[i], i);
		}

		Iterator<Object[]> iterator = table.iterator();
		int numRows = table.getNumRows();
		Set<String> comboSet = new HashSet<String>(numRows);
		int rowCount = 1;
		while(iterator.hasNext()) {
			Object[] nextRow = iterator.next();
			String comboValue = "";
			for(String c : columns) {
				int i = columnMap.get(c);
				comboValue = comboValue + nextRow[i];
			}
			comboSet.add(comboValue);

			if(comboSet.size() < rowCount) {
//				return Response.status(200).entity(WebUtility.getSO(true)).build();
				return WebUtility.getResponse(true, 200);
			}

			rowCount++;
		}
		boolean hasDuplicates = comboSet.size() != numRows;
		return WebUtility.getResponse(hasDuplicates, 200);
	}


	//for handling playsheet specific tool calls
	@POST
	@Path("do-{method}")
	@Produces("application/json")
	public Response doMethod(@PathParam("method") String method, MultivaluedMap<String, String> form, @Context HttpServletRequest request)
	{    	
		Gson gson = new Gson();
		Hashtable<String, Object> hash = gson.fromJson(form.getFirst("data"), new TypeToken<Hashtable<String, Object>>() {}.getType());
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
	
	@POST
	@Path("/saveFilesInInsightAsDb")
	@Produces("application/json")
	public Response saveFilesUsedInInsightIntoDb(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		// we need to create a full db now
		// do it based on the csv file name and the date
		logger.info("Start loading files in insight into database");
		
		String engineName = form.getFirst("engineName");
		IEngine createdEng = null;
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

		List<FilePkqlMetadata> filesMetadata = insight.getFilesUsedInInsight();
		
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

		// if we loaded a file
		// it has a prim key as a header
		// will need to modify the header to be that of the table so the FE
		// can properly create pkqls using the insight metamodel
		ITableDataFrame dataframe = (ITableDataFrame) this.insight.getDataMaker();
		Map<String, Set<String>> edgeHash = dataframe.getEdgeHash();

		// need to update the recipe now
		for(FilePkqlMetadata fileMeta : filesMetadata) {

			// this is the pkql string we need to update
			String pkqlStrToFind = fileMeta.getPkqlStr();

			// this is the transformation that invoked the file load
			// need to update its pkql recipe
			PKQLTransformation pkqlTrans = fileMeta.getInvokingPkqlTransformation();
			// since expression may contain multiple pkql statements
			// need to look at each and consolidate appropriately as to not lose
			// any statements
			List<String> listPkqlRun = pkqlTrans.getPkql();

			// keep track of all statements
			// only used if updatePkqlExpression boolean becomes true
			List<String> newPkqlRun = new Vector<String>();

			// this is the list of headers that were uploaded into the frame
			List<String> selectorsToMatch = fileMeta.getSelectors();

			// need to iterate through and update the correct pkql
			for(int pkqlIdx = 0; pkqlIdx < listPkqlRun.size(); pkqlIdx++) {
				String pkqlExp = listPkqlRun.get(pkqlIdx);
				// we store the API Reactor string
				// but this will definitely be stored within a data.import
				if(pkqlExp.contains(pkqlStrToFind)) {

					// find the new table that was created from this file
					String tableToUse = null;
					TABLE_LOOP : for(String newTable : newTablesAndCols.keySet()) {
						// get the list of columns for the table that exists in the engine
						List<String> selectors = newTablesAndCols.get(newTable);

						// need to see if all selectors match
						SELECTOR_MATCH_LOOP : for(String selectorInFile : selectorsToMatch) {
							boolean selectorFound = false;
							for(String selectorInTable : selectors) {
								// we found a match, we are good
								// format of selector in table is http://semoss.org/ontologies/Relation/Contains/Rotten_Tomatoes_Audience/MOVIECSV
								if(selectorInFile.equalsIgnoreCase(Utility.getClassName(selectorInTable))) {
									selectorFound = true;
									continue SELECTOR_MATCH_LOOP;
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
						UPDATE_PRIM_KEY_LOOP : for(String parentName : edgeHash.keySet()) {
							Set<String> children = edgeHash.get(parentName);

							// if the set contains all the names of the file
							// it is the one we want to modify
							if(children.containsAll(selectorsToMatch)) {
								dataframe.modifyColumnName(parentName, tableToUse);
								parentMap.put(parentName, tableToUse);

								// need to also add the engine name for each of the nodes
								// do this for the main node
								// and for each of the children nodes
								dataframe.addEngineForColumnName(tableToUse, engineName);
								for(String child : children) {
									dataframe.addEngineForColumnName(child, engineName);
								}

								break UPDATE_PRIM_KEY_LOOP;
							}
						}

						break TABLE_LOOP;
					}

					// this will update the pkql query to run
					newPkqlRun.add(fileMeta.generatePkqlOnEngine(engineName, tableToUse));

				} else {
					newPkqlRun.add(pkqlExp);
				}
			}

			// now setting the expression in the prop to store the string combination
			// of all the pkqls with the swap we made
			// create an individual string containing the pkql
			StringBuilder newPkqlExp = new StringBuilder();
			for(String pkql : newPkqlRun) {
				newPkqlExp.append(pkql);
			}
			Map<String, Object> props = pkqlTrans.getProperties();
			props.put(PKQLTransformation.EXPRESSION, newPkqlExp);
			// update the parsed pkqls so the next time this insight instance is used it is not 
			// still assuming it is a nonDbInsight
			pkqlTrans.setPkql(newPkqlRun);
		}

		logger.info("Done modifying PKQL to query of new engine");

		// clear the files since they are now loaded into the engine
		filesMetadata.clear();

		// we will return the new insight recipe after the PKQL has been modified
		Map<String, Object> retData = new HashMap<String, Object>();

		List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
		List<String> pkqlRecipe = this.insight.getPkslRecipe();
		for(String command: pkqlRecipe) {
			Map<String, Object> retMap = new HashMap<String, Object>();
			retMap.put("command", command);
			list.add(retMap);
		}

		retData.put("parentMap", parentMap);
		retData.put("recipe", list);
		return WebUtility.getResponse(retData, 200);
	}
	
	@GET
	@Path("/getMessages")
	@Produces("application/json")
	public Response getInsightMessages(@Context HttpServletRequest request){
		/*
		 * This method is used to determine if the insight has data that has been inserted
		 * into the frame that does not currently sit in a full-fledged database.
		 * An example of this is when an insight contains data that was added via a csv file.
		 * 
		 * We refer to these insights as nonDbInsights even if they contain data that
		 * does contain some information from full dbs
		 */
		
		List<String> messages = InsightMessageStore.getInstance().getAllMessages(this.insight.getInsightId());
		if(messages == null) {
			// i guess we have no new messages
			messages = new Vector<String>();
		}
		Map<String, Object> retMap = new HashMap<String, Object>();
		retMap.put("messages", messages);
		
		return WebUtility.getResponse(retMap, 200);
	}

}
