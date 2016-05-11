package prerna.semoss.web.services;

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
import prerna.ds.TinkerFrame;
import prerna.ds.H2.TinkerH2Frame;
import prerna.equation.EquationSolver;
import prerna.om.GraphDataModel;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.SEMOSSVertex;
import prerna.sablecc.PKQLRunner;
import prerna.ui.components.playsheets.datamakers.DataMakerComponent;
import prerna.ui.components.playsheets.datamakers.IDataMaker;
import prerna.ui.components.playsheets.datamakers.ISEMOSSTransformation;
import prerna.ui.components.playsheets.datamakers.MathTransformation;
import prerna.ui.components.playsheets.datamakers.PKQLTransformation;
import prerna.util.Constants;
import prerna.web.services.util.TableDataFrameUtilities;
import prerna.web.services.util.WebUtility;


public class DataframeResource {
	Logger logger = Logger.getLogger(DataframeResource.class.getName());
	Insight insight = null;
	

	
	@Path("/analytics")
	public Object runEngineAnalytics(){
		AnalyticsResource analytics = new AnalyticsResource();
		return analytics;
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
	public Response getFilterModel(@Context HttpServletRequest request)
	{	
		IDataMaker table = insight.getDataMaker();
//		String colName = form.getFirst("col");
		Map<String, Object> retMap = new HashMap<String, Object>();

		if(table instanceof ITableDataFrame) {
			Object[] returnFilterModel = ((ITableDataFrame)table).getFilterModel();
			retMap.put("unfilteredValues", returnFilterModel[0]);
			retMap.put("filteredValues", returnFilterModel[1]);
			
			return Response.status(200).entity(WebUtility.getSO(retMap)).build();
		} 
		
		else {
			return Response.status(200).entity(WebUtility.getSO("Data Maker not instance of ITableDataFrame.  Cannot grab filter model from Data Maker.")).build();
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
		props.put(PKQLTransformation.EXPRESSION, form.getFirst("expression"));
		pkql.setProperties(props);
		PKQLRunner runner = new PKQLRunner();
		pkql.setRunner(runner);
		List<ISEMOSSTransformation> list = new Vector<ISEMOSSTransformation>();
		list.add(pkql);
		insight.processPostTransformation(list);
		insight.setPkqlRunner(runner);
		Map resultHash = insight.getPKQLData(true);

		return Response.status(200).entity(WebUtility.getSO(resultHash)).build();
	}

	@POST
	@Path("/drop")
	@Produces("application/json")
	public Response dropInsight(@Context HttpServletRequest request){
		String insightID = insight.getInsightID();
		logger.info("Dropping insight with id ::: " + insightID);
		boolean success = InsightStore.getInstance().remove(insightID);
		InsightStore.getInstance().removeFromSessionHash(request.getSession().getId(), insightID);
		if(insight.getDataMaker() instanceof TinkerH2Frame) {
			TinkerH2Frame frame = (TinkerH2Frame)insight.getDataMaker();
			frame.closeRRunner();
		}

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

		Map<String, Object> returnData = new HashMap<String, Object>();
		returnData.put("insightID", insight.getInsightID());

		List<Object[]> table = new Vector<Object[]>();
		List<String> selectors = gson.fromJson(form.getFirst("selectors"), new TypeToken<List<String>>() {}.getType());
		
		if(selectors.isEmpty()) {
			options.put(TinkerFrame.SELECTORS, Arrays.asList(dm.getColumnHeaders()));
			returnData.put("headers", dm.getColumnHeaders());
		} else {
			options.put(TinkerFrame.SELECTORS, selectors);
			returnData.put("headers", selectors);
		}
		options.put(TinkerFrame.DE_DUP, true);
		
		Iterator<Object[]> it = dm.iterator(true, options);
		while(it.hasNext()) {
			table.add(it.next());
		}
		
		returnData.put("data", table);

		return Response.status(200).entity(WebUtility.getSO(returnData)).build();
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
		List<Map<String, String>> recipeList = new ArrayList<>();
		List<DataMakerComponent> components = insight.getDataMakerComponents();
		
		String pkqlKey = "pkql";
		String otherKey = "transformation";
		for(DataMakerComponent dmc : components) {
			for(ISEMOSSTransformation ist : dmc.getPreTrans()) {
				Map<String, Object> nextIngredient = new HashMap<>();
				if(ist instanceof PKQLTransformation) {
					String pkql = ((PKQLTransformation)ist).getPkql();
					nextIngredient.put(pkqlKey, pkql);
				} else {
					Map<String, Object> properties = ist.getProperties();
					properties.put("transformationType", ist.getClass().toString());
					properties.put("stepID", ist.getId());
					nextIngredient.put(otherKey, ist.getId());
				}
			}
			
			for(ISEMOSSTransformation ist : dmc.getPreTrans()) {
				Map<String, Object> nextIngredient = new HashMap<>();
				if(ist instanceof PKQLTransformation) {
					String pkql = ((PKQLTransformation)ist).getPkql();
					nextIngredient.put(pkqlKey, pkql);
				} else {
					Map<String, Object> properties = ist.getProperties();
					properties.put("transformationType", ist.getClass().toString());
					properties.put("stepID", ist.getId());
					nextIngredient.put(otherKey, ist.getId());
				}
			}
		}
		
		retMap.put("recipe", recipeList);
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

		Iterator<Object[]> iterator = table.iterator(false);
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
	
	@POST
	@Path("/saveAsFlatDatabase")
	@Produces("application/json")
	public Response saveAsFlatDatabase(MultivaluedMap<String, String> form, @Context HttpServletRequest request) 
	{
		IDataMaker dm = insight.getDataMaker();
		if(dm instanceof TinkerFrame || dm instanceof TinkerH2Frame) {
			Map<String, Object> output = dm.getDataMakerOutput();
			List<Object[]> data = (List<Object[]>) output.get("data");
			
			return Response.status(200).entity(WebUtility.getSO("New engine is called ")).build();
		} else {
			return Response.status(400).entity(WebUtility.getSO("This insight cannot be saved as a flat table")).build();
		}
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
}
