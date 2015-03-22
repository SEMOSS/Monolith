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
import prerna.rdf.query.builder.AbstractSPARQLQueryBuilder;
import prerna.rdf.query.builder.AbstractSpecificQueryBuilder;
import prerna.rdf.query.builder.IQueryBuilder;
import prerna.rdf.query.builder.SPARQLQueryGraphBuilder;
import prerna.rdf.query.builder.SPARQLQueryTableBuilder;
import prerna.rdf.query.builder.SpecificGenericChartQueryBuilder;
import prerna.rdf.query.builder.SpecificHeatMapQueryBuilder;
import prerna.rdf.query.builder.SpecificPieChartQueryBuilder;
import prerna.rdf.query.builder.SpecificScatterPlotQueryBuilder;
import prerna.rdf.query.builder.SpecificTableQueryBuilder;
import prerna.rdf.query.util.SEMOSSQuery;
import prerna.rdf.query.util.SEMOSSQueryHelper;
import prerna.web.services.util.WebUtility;

import com.google.gson.Gson;
import com.google.gson.internal.StringMap;
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
		
		Hashtable<String, Object> dataHash = gson.fromJson(form.getFirst("QueryData"), new TypeToken<Hashtable<String, Object>>() {}.getType());
		IQueryBuilder builder = this.coreEngine.getQueryBuilder();
		builder.setJSONDataHash(dataHash); // I am not sure we have an idea for why we set this
		
		
		Object relTriples = ((StringMap)dataHash.get("QueryData")).get("relTriples");
		
		
		
//		Hashtable<String, Object> dataHash = gson.fromJson(form.getFirst("QueryData"), Hashtable.class);

//		ArrayList<Hashtable<String,String>> nodePropArray = gson.fromJson(form.getFirst("SelectedNodeProps") + "", new TypeToken<ArrayList<Hashtable<String, String>>>() {}.getType());
//		ArrayList<Hashtable<String,String>> edgePropArray = gson.fromJson(form.getFirst("SelectedEdgeProps") + "", new TypeToken<ArrayList<Hashtable<String, String>>>() {}.getType());
		ArrayList<Hashtable<String, String>> selectedVars = gson.fromJson(form.getFirst("Groupings") + "", new TypeToken<ArrayList<Hashtable<String, String>>>() {}.getType());
		ArrayList<Hashtable<String, String>> parameters = gson.fromJson(form.getFirst("Parameters") + "", new TypeToken<ArrayList<Hashtable<String, String>>>() {}.getType());
		
//		logger.info("Node Properties: " + nodePropArray);
		logger.info("Selected Vars: " + selectedVars);
		logger.info("Parameters: " + parameters);
		
		String layout = form.getFirst("SelectedLayout").replace("\"", "");
		
		AbstractSPARQLQueryBuilder customViz = null;
		if(layout.equals("ForceGraph"))
			customViz = new SPARQLQueryGraphBuilder();
		else
			customViz = new SPARQLQueryTableBuilder();
		
//		customViz.setPropV(nodePropArray);
		
		customViz.setJSONDataHash(dataHash);
		customViz.buildQuery();

		logger.info("CustomViz query is: " + customViz.getQuery());
		SEMOSSQuery semossQuery = customViz.getSEMOSSQuery();

		if(layout.equals("ForceGraph")) {
			if(!parameters.isEmpty()) {
				logger.info("Adding parameters: " + parameters);
				SEMOSSQueryHelper.addParametersToQuery(parameters, semossQuery, "Main");
			}
			semossQuery.createQuery();

			query = semossQuery.getQuery();
			return Response.status(200).entity(WebUtility.getSO(query)).build();
		}
		
		//The existing retVars contains all the variables (from the selected metamodel path).  We want to clear these to build visualization-specific queries.
		if(!layout.equals("GridTable")) {
			semossQuery.removeReturnVariables();
		}
		
		LinkedHashMap<String, ArrayList<String>> colLabelHash = new LinkedHashMap<String, ArrayList<String>>();
		LinkedHashMap<String, ArrayList<String>> colMathHash = new LinkedHashMap<String, ArrayList<String>>();
		getSelectedValues(selectedVars, colLabelHash, colMathHash);
		
		AbstractSpecificQueryBuilder abstractQuery = null;
		
		if(layout.equals("HeatMap")){	
			String xAxisColName = colLabelHash.get("X-Axis").get(0);
			String yAxisColName = colLabelHash.get("Y-Axis").get(0);
			String heatName = colLabelHash.get("Heat").get(0);
			String heatMathFunc = colMathHash.get("Heat").get(0);
			
			abstractQuery = new SpecificHeatMapQueryBuilder(xAxisColName, yAxisColName, heatName, heatMathFunc, parameters, semossQuery);
		}
		else if(layout.equals("ScatterChart")){
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
			abstractQuery = new SpecificScatterPlotQueryBuilder(labelColName, xAxisColName, yAxisColName, zAxisColName, xAxisMathFunc, yAxisMathFunc, zAxisMathFunc, seriesColName, parameters, semossQuery);
		}
		else if(layout.equals("PieChart")){
			String label = colLabelHash.get("Label").get(0);
			String valueName = colLabelHash.get("Value").get(0);
			String valueMathFunc = colMathHash.get("Value").get(0);
			
			abstractQuery = new SpecificPieChartQueryBuilder (label, valueName, valueMathFunc, parameters, semossQuery);
		}
		else if(layout.equals("ColumnChart") || layout.equals("LineChart")){
			String labelColName = colLabelHash.get("Label").get(0);
			ArrayList<String> valueColNames = colLabelHash.get("Value");
			ArrayList<String> valueMathFunctions = colMathHash.get("Value");
			if(colLabelHash.get("Extra Value") != null) {
				valueColNames.addAll(colLabelHash.get("Extra Value"));
				valueMathFunctions.addAll(colMathHash.get("Extra Value"));
			}
			
			abstractQuery = new SpecificGenericChartQueryBuilder (labelColName, valueColNames, valueMathFunctions, parameters, semossQuery);
		}
		//takes care of regular select queries without math functions or changes to select vars
		else {
			//This takes in parallel coordinates, world map, grid, and parallel sets
			ArrayList<String> labelList = new ArrayList<String>();
			Set<String> keySet = colLabelHash.keySet();
			
			for(String key : keySet) {
				labelList.add(colLabelHash.get(key).get(0));
			}
			if(coreEngine.getEngineType() == IEngine.ENGINE_TYPE.SESAME || coreEngine.getEngineType() == IEngine.ENGINE_TYPE.JENA || coreEngine.getEngineType() == IEngine.ENGINE_TYPE.SEMOSS_SESAME_REMOTE)
				abstractQuery = new SpecificTableQueryBuilder(labelList, parameters, semossQuery);
			else
				abstractQuery = null;
		}
		if(coreEngine.getEngineType() == IEngine.ENGINE_TYPE.SESAME || coreEngine.getEngineType() == IEngine.ENGINE_TYPE.JENA || coreEngine.getEngineType() == IEngine.ENGINE_TYPE.SEMOSS_SESAME_REMOTE)
		{
			abstractQuery.buildQuery();
			query = abstractQuery.getQuery();
		}
		else if(abstractQuery != null && coreEngine.getEngineType() == IEngine.ENGINE_TYPE.RDBMS)
		{
			abstractQuery.addJoins((ArrayList<ArrayList<String>>)relTriples);
			abstractQuery.addParameters();
			abstractQuery.buildQueryR();
			query = abstractQuery.getQuery();
		}else
		{
			builder.buildQuery();
			query = builder.getQuery();
		}
		return Response.status(200).entity(WebUtility.getSO(query)).build();
	}
}
