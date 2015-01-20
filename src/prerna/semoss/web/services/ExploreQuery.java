package prerna.semoss.web.services;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.log4j.Logger;

import prerna.rdf.engine.api.IEngine;
import prerna.rdf.query.builder.AbstractBaseMetaModelQueryBuilder;
import prerna.rdf.query.builder.AbstractQueryBuilder;
import prerna.rdf.query.builder.CustomVizGraphBuilder;
import prerna.rdf.query.builder.CustomVizTableBuilder;
import prerna.rdf.query.builder.GenericChartQueryBuilder;
import prerna.rdf.query.builder.GenericTableQueryBuilder;
import prerna.rdf.query.builder.HeatMapQueryBuilder;
import prerna.rdf.query.builder.PieChartQueryBuilder;
import prerna.rdf.query.builder.ScatterPlotQueryBuilder;
import prerna.rdf.query.util.SEMOSSQuery;
import prerna.rdf.query.util.SEMOSSQueryHelper;
import prerna.web.services.util.WebUtility;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class ExploreQuery {
	Logger logger = Logger.getLogger(ExploreQuery.class.getName());
	
	IEngine coreEngine;
	String output = "";
	String query = "";
	
	public ExploreQuery(IEngine coreEngine){
		this.coreEngine = coreEngine;
	}
	
	public void getSelectedValues(ArrayList<Hashtable<String, String>> selectedVars, LinkedHashMap<String, ArrayList<String>> colLabelHash, LinkedHashMap<String, ArrayList<String>> colMathFuncHash) {
		for (int i=0; i < selectedVars.size(); i++) {
			Hashtable<String, String> hash = selectedVars.get(i);
			String mathFunction = hash.get("hasGrouping");
			String selected = hash.get("selected");
			String labelType = hash.get("varLabel");
			
			if(!selected.isEmpty()){
				ArrayList<String> labelList = new ArrayList<String>();
				ArrayList<String> mathList = new ArrayList<String>();
	
				if(colLabelHash.containsKey(labelType)){
					labelList = colLabelHash.get(labelType);
					mathList = colMathFuncHash.get(labelType);
				}
				
				labelList.add(selected);
				mathList.add(mathFunction);
				
				colLabelHash.put(labelType, labelList);
				colMathFuncHash.put(labelType, mathList);
			}
		}
		logger.info("Math Functions are: "+ colMathFuncHash.toString() + "; LabelHash: " + colLabelHash.toString());
	}
	
	@POST
	@Path("exploreQuery")
	@Produces("application/json")
	public Response generateExploreQuery(MultivaluedMap<String, String> form, @Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		Hashtable<String, Object> dataHash = gson.fromJson(form.getFirst("QueryData"), Hashtable.class);

		ArrayList<Hashtable<String,String>> nodePropArray = gson.fromJson(form.getFirst("SelectedNodeProps") + "", new TypeToken<ArrayList<Hashtable<String, String>>>() {}.getType());
		ArrayList<Hashtable<String,String>> edgePropArray = gson.fromJson(form.getFirst("SelectedEdgeProps") + "", new TypeToken<ArrayList<Hashtable<String, String>>>() {}.getType());
		ArrayList<Hashtable<String, String>> selectedVars = gson.fromJson(form.getFirst("Groupings") + "", new TypeToken<ArrayList<Hashtable<String, String>>>() {}.getType());
		ArrayList<Hashtable<String, String>> parameters = gson.fromJson(form.getFirst("Parameters") + "", new TypeToken<ArrayList<Hashtable<String, String>>>() {}.getType());
		
		logger.info("Node Properties: " + nodePropArray);
		logger.info("Edge Properties: " + edgePropArray);
		logger.info("Selected Vars: " + selectedVars);
		logger.info("Parameters: " + parameters);
		
		String layout = form.getFirst("SelectedLayout").replace("\"", "");
		
		AbstractBaseMetaModelQueryBuilder customViz = null;
		if(layout.equals("Network Graph"))
			customViz = new CustomVizGraphBuilder();
		else
			customViz = new CustomVizTableBuilder();
		
		customViz.setPropV(nodePropArray, edgePropArray);
		
		customViz.setJSONDataHash(dataHash);
		customViz.setEngine(coreEngine);
		customViz.buildQuery();

		logger.info("CustomViz query is: " + customViz.getQuery());
		SEMOSSQuery semossQuery = customViz.getSEMOSSQuery();

		if(layout.equals("Network Graph")) {
			if(!parameters.isEmpty()) {
				logger.info("Adding parameters: " + parameters);
				SEMOSSQueryHelper.addParametersToQuery(parameters, semossQuery, "Main");
			}
			semossQuery.createQuery();

			query = semossQuery.getQuery();
			return Response.status(200).entity(WebUtility.getSO(query)).build();
		}
		
		//remove retVars; we don't want to use the existing retVars because it has everything selected on the metamodel path.
		semossQuery.removeReturnVariables();
		
		LinkedHashMap<String, ArrayList<String>> colLabelHash = new LinkedHashMap<String, ArrayList<String>>();
		LinkedHashMap<String, ArrayList<String>> colMathHash = new LinkedHashMap<String, ArrayList<String>>();
		getSelectedValues(selectedVars, colLabelHash, colMathHash);
		
		AbstractQueryBuilder abstractQuery = null;
		
		if(layout.equals("Heat Map")){	
			String xAxisColName = colLabelHash.get("X-Axis").get(0);
			String yAxisColName = colLabelHash.get("Y-Axis").get(0);
			String heatName = colLabelHash.get("Heat").get(0);
			String heatMathFunc = colMathHash.get("Heat").get(0);
			
			abstractQuery = new HeatMapQueryBuilder(xAxisColName, yAxisColName, heatName, heatMathFunc, parameters, semossQuery);
		}
		else if(layout.equals("Scatter Plot")){
			String frontEndxAxisName = "X-Axis";
			String frontEndyAxisName = "Y-Axis";
			String frontEndzAxisName = "Z-Axis (Optional)";
			String frontEndSeriesName = "Series (Optional)";
			String frontEndLabelName = "Label";
			
			String labelColName = colLabelHash.get(frontEndLabelName).get(0);
			String xAxisColName = colLabelHash.get(frontEndxAxisName).get(0);
			String yAxisColName = colLabelHash.get(frontEndyAxisName).get(0);
			
			String zAxisColName = null;
			if(colLabelHash.get(frontEndzAxisName) != null ){
				zAxisColName = colLabelHash.get(frontEndzAxisName).get(0);
			}
			String xAxisMathFunc = colMathHash.get(frontEndxAxisName).get(0);
			String yAxisMathFunc = colMathHash.get(frontEndyAxisName).get(0);
			
			String zAxisMathFunc = null;
			if(colLabelHash.get(frontEndzAxisName) != null){
				zAxisMathFunc = colMathHash.get(frontEndzAxisName).get(0);
			}
			String seriesColName = null;
			if(colLabelHash.get(frontEndSeriesName) != null){
				seriesColName = colLabelHash.get(frontEndSeriesName).get(0); 
			}
			abstractQuery = new ScatterPlotQueryBuilder(labelColName, xAxisColName, yAxisColName, zAxisColName, xAxisMathFunc, yAxisMathFunc, zAxisMathFunc, seriesColName, parameters, semossQuery);
		}
		else if(layout.equals("Pie Chart")){
			String label = colLabelHash.get("Label").get(0);
			String valueName = colLabelHash.get("Value").get(0);
			String valueMathFunc = colMathHash.get("Value").get(0);
			
			abstractQuery = new PieChartQueryBuilder (label, valueName, valueMathFunc, parameters, semossQuery);
		}
		else if(layout.equals("Bar Chart") || layout.equals("Line Chart")){
			String labelColName = colLabelHash.get("Label").get(0);
			ArrayList<String> valueColNames = colLabelHash.get("Value");
			ArrayList<String> valueMathFunctions = colMathHash.get("Value");
			if(colLabelHash.get("Extra Value") != null) {
				valueColNames.addAll(colLabelHash.get("Extra Value"));
				valueMathFunctions.addAll(colMathHash.get("Extra Value"));
			}
			
			abstractQuery = new GenericChartQueryBuilder (labelColName, valueColNames, valueMathFunctions, parameters, semossQuery);
		}
		else if(layout.equals("World Map")){
			String siteLabel = colLabelHash.get("Label").get(0);
			String lat = colLabelHash.get("Latitude").get(0);
			String lon = colLabelHash.get("Longitude").get(0);
			ArrayList<String> labelList = new ArrayList<String>();
			labelList.add(siteLabel);
			labelList.add(lat);
			labelList.add(lon);
			
			abstractQuery = new GenericTableQueryBuilder (labelList, parameters, semossQuery);
		}
		//takes care of regular select queries without math functions or changes to select vars
		else {
			//This takes in parallel coordinates and parallel sets
			ArrayList<String> labelList = new ArrayList<String>();
			Set<String> keySet = colLabelHash.keySet();
			
			for(String key : keySet) {
				labelList.add(colLabelHash.get(key).get(0));
			}
			
			abstractQuery = new GenericTableQueryBuilder(labelList, parameters, semossQuery);
		}
		
		abstractQuery.buildQuery();
		query = abstractQuery.getQuery();
		
		return Response.status(200).entity(WebUtility.getSO(query)).build();
	}
}
