package prerna.semoss.web.services;

import java.io.IOException;
import java.text.ParseException;
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
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import prerna.algorithm.api.ITableDataFrame;
import prerna.ds.BTreeDataFrame;
import prerna.ds.Probablaster;
import prerna.ds.TableDataFrameFactory;
import prerna.ds.TinkerFrame;
import prerna.ds.H2.H2Frame;
import prerna.engine.api.IEngine;
import prerna.equation.EquationSolver;
import prerna.om.Dashboard;
import prerna.om.GraphDataModel;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.SEMOSSVertex;
import prerna.poi.main.InsightFilesToDatabaseReader;
import prerna.sablecc.PKQLRunner;
import prerna.sablecc.meta.FilePkqlMetadata;
import prerna.ui.components.playsheets.datamakers.IDataMaker;
import prerna.ui.components.playsheets.datamakers.ISEMOSSTransformation;
import prerna.ui.components.playsheets.datamakers.MathTransformation;
import prerna.ui.components.playsheets.datamakers.PKQLTransformation;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.TableDataFrameUtilities;
import prerna.web.services.util.WebUtility;


public class DataframeResource {

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
		String id = insight.getInsightID();
		String dmName = insight.getDataMakerName();
		String engName = insight.getEngineName();
		String rdbmsId = insight.getRdbmsId();
		Insight parentInsight = insight.getParentInsight();
		int dataId = insight.getDataMaker().getDataId();
		
		IEngine eng = null;
		if(engName != null){
			eng = Utility.getEngine(engName);//(IEngine) DIHelper.getInstance().getLocalProp(engName);
		}
		String layoutName = insight.getOutput();

		this.insight = new Insight(eng, dmName, layoutName);
		this.insight.getDataMaker(); // need to instatiate datamaker so next call doesn't try to get it from cache
		this.insight.setInsightID(id);
		if(rdbmsId != null) {
			this.insight.setRdbmsId(rdbmsId);
		}
		
		this.insight.setParentInsight(parentInsight);
		if(this.insight.isJoined()) {
			Dashboard dashboard = (Dashboard)parentInsight.getDataMaker();
			List<Insight> insights = new ArrayList<>(1);
			insights.add(this.insight);
			dashboard.addInsights(insights);
		}
		
		while(dataId >= insight.getDataMaker().getDataId()) {
			this.insight.getDataMaker().updateDataId();
		}
		
		InsightStore.getInstance().put(id, insight);

		return Response.status(200).entity(WebUtility.getSO("Insight " + id + " has been cleared")).build();
	}

	/**
	 * Retrieve top executed insights.
	 * 
	 * @param engine	Optional, engine to restrict query to
	 * @param limit		Optional, number of top insights to retrieve, default is 6
	 * @param request	HttpServletRequest object
	 * 
	 * @return			Top insights and total execution count for all to be used for normalization of popularity
	 */
	@GET
	@Path("bic")
	@Produces("application/json")
	public Response runBIC(@Context HttpServletRequest request) {

		System.out.println("Failed.. after here.. ");
		String insights = "Mysterious";
		System.out.println("Running basic checks.. ");
		IDataMaker maker = insight.getDataMaker();
		if(maker instanceof TinkerFrame)
		{
			System.out.println("Hit the second bogie.. ");
			BTreeDataFrame daFrame = (BTreeDataFrame)maker;
			Probablaster pb = new Probablaster();
			pb.setDataFrame(daFrame);
			insights = pb.runBIC();
		}
		insights = "Pattern Selected..  " + insights + " !! ";

		return Response.status(200).entity(WebUtility.getSO(insights)).build();
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

			return Response.status(200).entity(WebUtility.getSO(retMap)).build();
		} 

		else {
			return Response.status(400).entity(WebUtility.getSO("Data Maker not instance of ITableDataFrame.  Cannot grab filter model from Data Maker.")).build();
		}

	}

	@POST
	@Path("/openBackDoor")
	@Produces("application/json")
	public Response openBackDoor(@Context HttpServletRequest request){
		TinkerFrame tf = (TinkerFrame) insight.getDataMaker();
		tf.openBackDoor();
		return Response.status(200).entity(WebUtility.getSO("Succesfully closed back door")).build();
	}

	@POST
	@Path("/applyCalc")
	@Produces("application/json")
	public Response applyCalculation(MultivaluedMap<String, String> form, @Context HttpServletRequest request){

		PKQLTransformation pkql = new PKQLTransformation();
		Map<String, Object> props = new HashMap<String, Object>();
		String pkqlCmd = form.getFirst("expression");
		props.put(PKQLTransformation.EXPRESSION, pkqlCmd);
		pkql.setProperties(props);
		PKQLRunner runner = insight.getPKQLRunner();
		pkql.setRunner(runner);
		List<ISEMOSSTransformation> list = new Vector<ISEMOSSTransformation>();
		list.add(pkql);

		Map resultHash = null;
		//synchronize applyCalc calls for each insight to prevent interference during calculation
		synchronized(insight) {
			insight.processPostTransformation(list);
			insight.syncPkqlRunnerAndFrame(runner);
			resultHash = insight.getPKQLData(true);
		}
		return Response.status(200).entity(WebUtility.getSO(resultHash)).build();
	}

	@POST
	@Path("/drop")
	@Produces("application/json")
	public Response dropInsight(@Context HttpServletRequest request){
		String insightID = insight.getInsightID();
//		if(insight.isJoined()){
//			insight.unJoin();
//		}
		logger.info("Dropping insight with id ::: " + insightID);
		boolean success = InsightStore.getInstance().remove(insightID);
		InsightStore.getInstance().removeFromSessionHash(request.getSession().getId(), insightID);
		IDataMaker dm = insight.getDataMaker();
		if(dm instanceof H2Frame) {
			H2Frame frame = (H2Frame)dm;
			frame.closeRRunner();
			frame.dropTable();
			if(!frame.isInMem()) {
				frame.dropOnDiskTemporalSchema();
			}
		} else if(dm instanceof Dashboard) {
			Dashboard dashboard = (Dashboard)dm;
			dashboard.dropDashboard();
		} 
		// native frame just holds a QueryStruct on an engine
		// nothing to do
//		else if(dm instanceof NativeFrame) {
//			NativeFrame frame = (NativeFrame) dm;
//			frame.close();
//		} 

		if(success) {
			logger.info("Succesfully dropped insight " + insightID);
			return Response.status(200).entity(WebUtility.getSO("Succesfully dropped insight " + insightID)).build();
		} else {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Could not remove data.");
			return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
		}
	}

	// temporary function for getting chart it data
	// will be replaced with query builder
	@GET
	@Path("chartData")
	@Produces("application/json")
	public StreamingOutput getPlaySheetChartData(@Context HttpServletRequest request) {
		Hashtable<String, Vector<SEMOSSVertex>> typeHash = new Hashtable<String, Vector<SEMOSSVertex>>();
		IDataMaker maker = this.insight.getDataMaker();
		Hashtable<String, SEMOSSVertex> nodeHash = null;
		if(maker instanceof GraphDataModel){
			nodeHash = ((GraphDataModel)maker).getVertStore();
		} else if (maker instanceof TinkerFrame){
			nodeHash = (Hashtable<String, SEMOSSVertex>) ((TinkerFrame) maker).getGraphOutput().get("nodes");
		}
		else{
			logger.error("Unable to create chart it data with current data maker");
			Hashtable<String, String> errorHash = new Hashtable<String, String>();
			errorHash.put("Message", "Unable to create chart it data with current data maker");
			return WebUtility.getSO(errorHash);
		}

		// need to create type hash... its the way chartit wants the data..
		logger.info("creating type hash...");
		for( SEMOSSVertex vert : nodeHash.values()){
			String type = vert.getProperty(Constants.VERTEX_TYPE) + "";
			Vector<SEMOSSVertex> typeVert = typeHash.get(type);
			if(typeVert == null)
				typeVert = new Vector<SEMOSSVertex>();
			typeVert.add(vert);
			typeHash.put(type, typeVert);
		}

		Hashtable retHash = new Hashtable();
		retHash.put("Nodes", typeHash);
		return WebUtility.getSO(retHash);
	}

	@POST
	@Path("/undoInsightProcess")
	@Produces("application/xml")
	public Response undoInsightProcess(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Gson gson = new Gson();
		List<String> processes = gson.fromJson(form.getFirst("processes"), List.class);
		String insightID = this.insight.getInsightID();

		if(processes.isEmpty()) {
			Map<String, Object> retMap = new HashMap<String, Object>();
			retMap.put("insightID", insightID);
			retMap.put("message", "No processes set to undo");
			return Response.status(200).entity(WebUtility.getSO(retMap)).build();
		}

		Insight in = InsightStore.getInstance().get(insightID);
		Map<String, Object> retMap = new HashMap<String, Object>();
		in.undoProcesses(processes);
		retMap.put("insightID", insightID);
		retMap.put("message", "Succesfully undone processes : " + processes);
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}

	@POST
	@Path("/filterData")
	@Produces("application/json")
	public Response filterData(MultivaluedMap<String, String> form,
			@Context HttpServletRequest request)
	{	
		ITableDataFrame mainTree = (ITableDataFrame) this.insight.getDataMaker();
		if(mainTree == null) {
			return Response.status(400).entity(WebUtility.getSO("table not found for insight id. Data not found")).build();
		}

		Gson gson = new Gson();

		//Grab the filter model from the form data
		Map<String, Map<String, Object>> filterModel = gson.fromJson(form.getFirst("filterValues"), new TypeToken<Map<String, Map<String, Object>>>() {}.getType());

		//If the filter model has information, filter the tree
		if(filterModel != null && filterModel.keySet().size() > 0) {
			TableDataFrameUtilities.filterTableData(mainTree, filterModel);
		}

		//if the filtermodel is not null and contains no data then unfilter the whole tree
		//this trigger to unfilter the whole tree was decided between FE and BE for simplicity
		//TODO: make this an explicit call
		else if(filterModel != null && filterModel.keySet().size() == 0) {
			//unfilter the tree
		} 

		Map<String, Object> retMap = new HashMap<String, Object>();

		Object[] returnFilterModel = ((ITableDataFrame)mainTree).getFilterModel();
		//		((BTreeDataFrame)mainTree).printTree();
		retMap.put("unfilteredValues", returnFilterModel[0]);
		retMap.put("filteredValues", returnFilterModel[1]);

		// update any derived columns that this graph is using
		this.insight.recalcDerivedColumns();

		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}

	@GET
	@Path("/unfilterColumns")
	@Produces("application/json")
	public Response getVisibleValues(MultivaluedMap<String, String> form,
			@QueryParam("concept") String[] concepts)
	{
		Map<String, Object> retMap = new HashMap<String, Object>();
		ITableDataFrame mainTree = (ITableDataFrame) insight.getDataMaker();

		for(String concept: concepts) {
			mainTree.unfilter(concept);
		}

		retMap.put("insightID", insight.getInsightID());
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}

	@GET
	@Path("/unfilterAll")
	@Produces("application/json")
	public Response unfilterAllValues()
	{
		Map<String, Object> retMap = new HashMap<String, Object>();
		ITableDataFrame mainTree = (ITableDataFrame) insight.getDataMaker();

		mainTree.unfilter();

		retMap.put("insightID", insight.getInsightID());
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
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
				options.put(TinkerFrame.SORT_BY, concept);
				options.put(TinkerFrame.SORT_BY_DIRECTION, orderDirection);
			}
		}
		if(startRow >= 0 && endRow > startRow) {
			options.put(TinkerFrame.OFFSET, startRow);
			options.put(TinkerFrame.LIMIT, endRow);
		}

//		Map<String, Object> returnData = new HashMap<String, Object>();
//		returnData.put("insightID", insight.getInsightID());

//		List<Object[]> table = new Vector<Object[]>();
		List<String> selectors = gson.fromJson(form.getFirst("selectors"), new TypeToken<List<String>>() {}.getType());

		if(selectors.isEmpty()) {
			options.put(TinkerFrame.SELECTORS, Arrays.asList(dm.getColumnHeaders()));
//			returnData.put("headers", dm.getColumnHeaders());
			selectors = Arrays.asList(dm.getColumnHeaders());
		} else {
			options.put(TinkerFrame.SELECTORS, selectors);
//			returnData.put("headers", selectors);
		}
		options.put(TinkerFrame.DE_DUP, true);

		Iterator<Object[]> it = dm.iterator(options);
		//while(it.hasNext()) {
		//	table.add(it.next());
		//}

		//returnData.put("data", table);

//		return Response.status(200).entity(WebUtility.getSO(returnData)).build();
		return Response.status(200).entity(WebUtility.getSO(insight.getInsightID(), selectors.toArray(new String[]{}), it)).build();
	}

	@POST
	@Path("/derivedColumn")
	@Produces("application/json")
	public Response derivedColumn(MultivaluedMap<String, String> form,
			@QueryParam("expressionString") String expressionString,
			@QueryParam("columnName") String columnName,
			@Context HttpServletRequest request)
	{
		String expString = form.getFirst("expressionString");
		String colName = form.getFirst("columnName");

		Map<String, Object> retMap = new HashMap<String, Object>();
		ITableDataFrame mainTree = (ITableDataFrame) insight.getDataMaker();

		// return new column
		EquationSolver solver;
		try { solver = new EquationSolver(mainTree, expString); } 
		catch (ParseException e) {
			retMap.put("errorMessage", e);
			return Response.status(400).entity(WebUtility.getSO(retMap)).build();
		}

		String returnMsg = solver.crunch(colName);

		retMap.put("status", returnMsg);
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}

	@GET
	@Path("/getTableHeaders")
	@Produces("application/json")
	public Response getTableHeaders() {
		Map<String, Object> retMap = new HashMap<String, Object>();

		ITableDataFrame table = (ITableDataFrame) insight.getDataMaker();	
		retMap.put("insightID", insight.getInsightID());
		retMap.put("tableHeaders", table.getTableHeaderObjects());
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}

	@GET
	@Path("/recipe")
	@Produces("application/json")
	public Response getRecipe() {

		Map<String, Object> retMap = new HashMap<String, Object>();		
		retMap.put("recipe", insight.getRecipe());
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}

	@POST
	@Path("getVizTable")
	@Produces("application/json")
	public Response getExploreTable(
			//@QueryParam("start") int start,
			//@QueryParam("end") int end,
			@Context HttpServletRequest request)
	{
		ITableDataFrame mainTree = (ITableDataFrame) insight.getDataMaker();		
		if(mainTree == null) {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Dataframe not found within insight");
			return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
		}

		List<Object[]> table = mainTree.getRawData();
		String[] headers = mainTree.getColumnHeaders();
		Map<String, Object> returnData = new HashMap<String, Object>();
		returnData.put("data", table);
		returnData.put("headers", headers);
		returnData.put("insightID", insight.getInsightID());
		return Response.status(200).entity(WebUtility.getSO(returnData)).build();
	}

	@POST
	@Path("/getGraphData")
	@Produces("application/json")
	public Response getGraphData(@Context HttpServletRequest request){
		IDataMaker maker = insight.getDataMaker();
		if(maker instanceof TinkerFrame){ 
			return Response.status(200).entity(WebUtility.getSO(((TinkerFrame)maker).getGraphOutput())).build();
		}
		else if (maker instanceof GraphDataModel){
			return Response.status(200).entity(WebUtility.getSO(((GraphDataModel)maker).getDataMakerOutput())).build();
		} else if(maker instanceof H2Frame) {
			//convert to tinker then return
			TinkerFrame tframe = TableDataFrameFactory.convertToTinkerFrameForGraph((H2Frame)maker);
			return Response.status(200).entity(WebUtility.getSO(tframe.getGraphOutput())).build();
		}
		else
			return Response.status(400).entity(WebUtility.getSO("Illegal data maker type ")).build();
	}

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
			return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
		}
		Map<String, Object> returnHash = null;
		try {
			returnHash = insight.getInsightMetaModel();
		} catch(IllegalArgumentException e) {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", e.getMessage());
			return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
		}
		return Response.status(200).entity(WebUtility.getSO(returnHash)).build();
	}

	@POST
	@Path("/applyColumnStats")
	@Produces("application/json")
	public Response applyColumnStats(MultivaluedMap<String, String> form,  
			@Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		//		String groupBy = form.getFirst("groupBy");
		List groupByCols = gson.fromJson(form.getFirst("groupBy"), List.class);
		Map<String, Object> functionMap = gson.fromJson(form.getFirst("mathMap"), new TypeToken<HashMap<String, Object>>() {}.getType());

		// Run math transformation
		ISEMOSSTransformation mathTrans = new MathTransformation();
		Map<String, Object> selectedOptions = new HashMap<String, Object>();
		// groupByCols = columns to look at
		selectedOptions.put(MathTransformation.GROUPBY_COLUMNS, groupByCols);
		// debug through this. also comes from frontend
		selectedOptions.put(MathTransformation.MATH_MAP, functionMap);
		// dont worry about this yet
		mathTrans.setProperties(selectedOptions);
		// just one transformation at a time. for math transformation just one thing
		List<ISEMOSSTransformation> postTrans = new Vector<ISEMOSSTransformation>();
		postTrans.add(mathTrans);
		try {
			insight.processPostTransformation(postTrans);
		} catch(RuntimeException e) {
			e.printStackTrace();
			Map<String, String> retMap = new HashMap<String, String>();
			retMap.put("errorMessage", e.getMessage());
			return Response.status(400).entity(WebUtility.getSO(retMap)).build();
		}
		Map<String, Object> retMap = new HashMap<String, Object>();

		// FE now uses getNextTable call to get the information back
		//		ITableDataFrame table = (ITableDataFrame) insight.getDataMaker();
		//		retMap.put("tableData", TableDataFrameUtilities.getTableData(table));
		retMap.put("mathMap", functionMap);
		retMap.put("insightID", insight.getInsightID());
		retMap.put("stepID", mathTrans.getId());
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
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
			return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
		}
		if(table == null) {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Could not find insight data maker.");
			return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
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
				return Response.status(200).entity(WebUtility.getSO(true)).build();
			}

			rowCount++;
		}
		boolean hasDuplicates = comboSet.size() != numRows;
		return Response.status(200).entity(WebUtility.getSO(hasDuplicates)).build();
	}


	//for handling playsheet specific tool calls
	@POST
	@Path("do-{method}")
	@Produces("application/json")
	public Response doMethod(@PathParam("method") String method, MultivaluedMap<String, String> form, @Context HttpServletRequest request)
	{    	
		Gson gson = new Gson();
		Hashtable<String, Object> hash = gson.fromJson(form.getFirst("data"), new TypeToken<Hashtable<String, Object>>() {}.getType());
		Object ret = this.insight.getPlaySheet().doMethod(method, hash);
		return Response.status(200).entity(WebUtility.getSO(ret)).build();
	}

/*	*//**
	 * Method used to get the structure for a specific PKQL command 
	 *//*
	@GET
	@Path("/predictPKQL")
	@Produces("application/json")
	public Response getPredictedPKQLs(@QueryParam("selectedPKQL") String selectedPKQL, @Context HttpServletRequest request){

		ITableDataFrame dm = (ITableDataFrame) insight.getDataMaker();
		
		Map<String, Object> returnMap = new HashMap<String, Object>();
		Map<String, Object> pkqlMap = new HashMap<String, Object>();
		AbstractReactor thisReactor = null;
		List<HashMap<String, Object>> input;
		
		switch(selectedPKQL){
		case "Split a column by delimiter": thisReactor = new ColSplitReactor();
											pkqlMap = thisReactor.getPKQLMetaData();
											input = (List<HashMap<String, Object>>) pkqlMap.get("input");
											
											LOOP:
											for(int i=0; i<input.size(); i++){
												Set<String> inputSet = input.get(i).keySet();
												for(String key: inputSet){
													if(key.equals("values")){
														Map<String, Object> valuesMap = new HashMap<String, Object>();
														valuesMap.put("headers", dm.getColumnHeaders());
														Map<String, Object> options = new HashMap<String, Object>();
														List<Object[]> data = new Vector<Object[]>();														
														options.put(TinkerFrame.SELECTORS, Arrays.asList(dm.getColumnHeaders()));
														options.put(TinkerFrame.DE_DUP, true);//no duplicates
														Iterator<Object[]> it = dm.iterator(true, options);
														while(it.hasNext()) {
															data.add(it.next());
														}
														valuesMap.put("data", data);														
														input.get(i).put(key, valuesMap);
														pkqlMap.put("input", input);
														break LOOP;
													}
												}
											}break;
											
		case "Add a new column": thisReactor = new ColAddReactor();
								 pkqlMap = thisReactor.getPKQLMetaData();
								 input = (List<HashMap<String, Object>>) pkqlMap.get("input");
		
								 LOOP:
								 for(int i=0; i<input.size(); i++){
									 Set<String> inputSet = input.get(i).keySet();
									 for(String key: inputSet){
										 if(key.equals("values")){
											 Map<String, Object> valuesMap = new HashMap<String, Object>();					
											 input.get(i).put(key, valuesMap);//sending empty values for add new col pkql
											 pkqlMap.put("input", input);
											 break LOOP;
										 }
									 }
								 }break;
								 
		case "Filter data in a column": thisReactor = new ColFilterReactor();
										pkqlMap = thisReactor.getPKQLMetaData();
										input = (List<HashMap<String, Object>>) pkqlMap.get("input");
		
										LOOP:
										for(int i=0; i<input.size(); i++){
											Set<String> inputSet = input.get(i).keySet();
											for(String key: inputSet){
												if(key.equals("values")){
													Map<String, Object> valuesMap = new HashMap<String, Object>();					
													Object[] filterColumn = dm.getFilterModel();
													valuesMap.put("headers", dm.getColumnHeaders());
													valuesMap.put("unfilteredValues", filterColumn[0]);																			
													input.get(i).put(key, valuesMap);
													pkqlMap.put("input", input);
													break LOOP;
												}
											}
										}break;
										
		case "Unfilter data in a column": thisReactor = new ColUnfilterReactor();
										  pkqlMap = thisReactor.getPKQLMetaData();
										  input = (List<HashMap<String, Object>>) pkqlMap.get("input");

										  LOOP:
										  for(int i=0; i<input.size(); i++){
											  Set<String> inputSet = input.get(i).keySet();
											  for(String key: inputSet){
												  if(key.equals("values")){
													  Map<String, Object> valuesMap = new HashMap<String, Object>();
													  valuesMap.put("headers", dm.getColumnHeaders());																	
													  input.get(i).put(key, valuesMap);
													  pkqlMap.put("input", input);
													  break LOOP;
												  }
											  }
										  }break;
		 default:break;
											
		}
		returnMap.put("pkql", pkqlMap);

		return Response.status(200).entity(WebUtility.getSO(returnMap)).build();
	}
	
	*//**
	 * Method used to get the list of all available PKQL
	 * @param 
	 * @return
	 *//*
	@GET
	@Path("/allPKQLs")
	@Produces("application/json")
	public Response getListOfAllPKQLs(@Context HttpServletRequest request){

		//fetch info from reactors - AbstractReactor
		System.out.println("Fetching list of all PKQL commands");

		Map<String, Object> returnMap = new HashMap<String, Object>();
		List pkqls = new ArrayList();
		IScriptReactor thisReactor = null;	

		//get the datamaker for current insight, and fetch all the reactors for it
		Map<String, String> reactors = this.insight.getDataMaker().getScriptReactors();

		for(String reactor: reactors.keySet()){
			String reactorName = reactors.get(reactor);
			try {
				thisReactor = (IScriptReactor)Class.forName(reactorName).newInstance();
				if(((AbstractReactor) thisReactor).getPKQLMetaData() != null)
					pkqls.add(((AbstractReactor) thisReactor).getPKQL());
			} catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
				System.out.println("Exception in instantiating " +reactorName);
				e.printStackTrace();
			}				
		}
		returnMap.put("pkql", pkqls);
		return Response.status(200).entity(WebUtility.getSO(returnMap)).build();
	}*/
	
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
		retMap.put("isDbInsight", insight.isDbInsight());
		
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}
	
	@POST
	@Path("/saveFilesInInsightAsDb")
	@Produces("application/json")
	public Response saveFilesUsedInInsightIntoDb(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		// we need to create a full db now
		// do it based on the csv file name and the date
					
		String engineName = form.getFirst("engineName");
		IEngine createdEng = null;
		InsightFilesToDatabaseReader creator = new InsightFilesToDatabaseReader();
		try {
			createdEng = creator.processInsightFiles(insight, engineName);
		} catch (IOException e) {
			e.printStackTrace();
			Map<String, String> errorMap = new HashMap<String, String>();
			String errorMessage = "Data loading was not successful";
			if(e.getMessage() != null && !e.getMessage().isEmpty()) {
				errorMessage = e.getMessage();
			}
			errorMap.put("errorMessage", errorMessage);
			return Response.status(200).entity(WebUtility.getSO(errorMap)).build();
		}
		
		List<FilePkqlMetadata> filesMetadata = insight.getFilesMetadata();
		
		// need to align each file to the table that was created from it
		Set<String> newTables = creator.getNewTables();
		Map<String, List<String>> newTablesAndCols = new Hashtable<String, List<String>>();
		for(String newTable : newTables) {
			List<String> props = createdEng.getProperties4Concept2(newTable, true);
			newTablesAndCols.put(newTable, props);
		}
		
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
			
			// need to iterate through and update the correct pkql
			for(int pkqlIdx = 0; pkqlIdx < listPkqlRun.size(); pkqlIdx++) {
				String pkqlExp = listPkqlRun.get(pkqlIdx);
				// we store the API Reactor string
				// but this will definitely be stored within a data.import
				if(pkqlExp.contains(pkqlStrToFind)) {

					// find the new table that was created from this file
					List<String> selectorsToMatch = fileMeta.getSelectors();
					String tableToUse = null;
					TABLE_LOOP : for(String newTable : newTablesAndCols.keySet()) {
						List<String> selectors = newTablesAndCols.get(newTable);

						// need to see if all selectors match
						FILE_LOOP : for(String selectorInFile : selectorsToMatch) {
							for(String selectorInTable : selectors) {

								// we found a match, we are good
								if(selectorInFile.equalsIgnoreCase(Utility.getInstanceName(selectorInTable))) {
									continue FILE_LOOP;
								}
							}
							// if we hit this point, then there was a selector
							// in selectorsToMatch that wasn't found in the tableSelectors
							// lets look at next table
							continue TABLE_LOOP;
							
						} // end file loop
						
						// if we hit this point, then everything matched!
						tableToUse = newTable;
						break TABLE_LOOP;
					}

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

		// clear the files since they are now loaded into the engine
		filesMetadata.clear();
		
		String outputText = "Data Loading was a success.";
		return Response.status(200).entity(WebUtility.getSO(outputText)).build();
	}
	
	
}
