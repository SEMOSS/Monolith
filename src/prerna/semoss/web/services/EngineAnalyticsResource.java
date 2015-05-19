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
import java.util.List;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import prerna.algorithm.learning.similarity.ClusterRemoveDuplicates;
import prerna.engine.api.IEngine;
import prerna.engine.api.ISelectStatement;
import prerna.engine.api.ISelectWrapper;
import prerna.ui.components.playsheets.AnalyticsBasePlaySheet;
import prerna.ui.components.playsheets.ClusteringVizPlaySheet;
import prerna.ui.components.playsheets.DatasetSimilarityPlaySheet;
import prerna.ui.components.playsheets.LocalOutlierPlaySheet;
import prerna.ui.components.playsheets.MatrixRegressionVizPlaySheet;
import prerna.ui.components.playsheets.NumericalCorrelationVizPlaySheet;
import prerna.ui.components.playsheets.SelfOrganizingMap3DBarChartPlaySheet;
import prerna.ui.components.playsheets.WekaAprioriVizPlaySheet;
import prerna.ui.components.playsheets.WekaClassificationPlaySheet;
import prerna.util.ArrayListUtilityMethods;
import prerna.util.ArrayUtilityMethods;
import prerna.util.MachineLearningEnum;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

import com.google.gson.Gson;

public class EngineAnalyticsResource {
	
	private static final Logger LOGGER = LogManager.getLogger(EngineAnalyticsResource.class.getName());
	
	private IEngine engine;
	String output = "";
	
	public EngineAnalyticsResource(IEngine engine) {
		this.engine = engine;
	}
	
	// *************Machine Learning Web Services*********************
	@POST
	@Path("/algorithmOptions")
	public Response getAlgorithmOptions() {
		ArrayList<String> algorithmList = new ArrayList<String>();
		for (MachineLearningEnum algorithm : MachineLearningEnum.values()) {
			algorithmList.add(algorithm.toString());
		}
		algorithmList.toArray();
		
		return Response.status(200).entity(WebUtility.getSO(algorithmList)).build();
	}
	
	@POST
	@Path("/runAlgorithm")
	public Response runAlgorithm(MultivaluedMap<String, String> form) {
		String algorithm = form.getFirst("algorithm");
		
		Gson gson = new Gson();
		// TODO: move to keeping state on back-end
		String query = form.getFirst("query");
		if (query.contains("+++")) {
			query = query.substring(0, query.indexOf("+++"));
		}
		
		// TODO: add to interface to work for any column instance is located
		Boolean[] includeColArr = gson.fromJson(form.getFirst("filterParams"), Boolean[].class);
		
		String instanceIDString = form.getFirst("instanceID");
		int instanceID = 0;
		if (instanceIDString != null && (algorithm.equals("Clustering") || algorithm.equals("SOM") || algorithm.equals("Outliers"))) {
			instanceID = gson.fromJson(form.getFirst("instanceID"), Integer.class) + 1;
			String select = query;
			String[] selectSplit = select.split("\\?");
			if (instanceID != 0) {
				// swap location of instanceID to be first output in return
				String newInstance = selectSplit[instanceID];
				String temp = selectSplit[1];
				selectSplit[1] = newInstance;
				selectSplit[instanceID] = temp;
				query = selectSplit[0] + " ";
				for (int i = 1; i < selectSplit.length; i++) {
					query = query + " ?" + selectSplit[i];
				}
				
				// also need to update the boolean array with new format
				// instance must always be present
				// only need to check if we include the column being changed with instance
				includeColArr[instanceID - 1] = includeColArr[0];
				includeColArr[0] = true;
			}
		}
		
		// TODO: shift all of this to IAnalaytics interface
		ISelectWrapper sjsw = Utility.processQuery(engine, query);
		String[] names = sjsw.getVariables();
		int numCol = names.length;
		ArrayList<Object[]> list = new ArrayList<Object[]>();
		while (sjsw.hasNext()) {
			ISelectStatement sjss = sjsw.next();
			Object[] vals = new Object[numCol];
			for (int i = 0; i < numCol; i++) {
				vals[i] = sjss.getVar(names[i]);
			}
			list.add(vals);
		}
		String[] filteredNames = ArrayUtilityMethods.filterArray(names, includeColArr);
		ArrayList<Object[]> filteredList = ArrayListUtilityMethods.filterList(list, includeColArr);
		
		ArrayList<String> configParameters = gson.fromJson(form.getFirst("parameters"), ArrayList.class);
		Hashtable<String, Object> data = new Hashtable<String, Object>();
		Hashtable<String, String> errorHash = new Hashtable<String, String>();
		
		if (algorithm.equals("Clustering")) {
			// format the data before sending into algorithm
			ClusterRemoveDuplicates formatter = new ClusterRemoveDuplicates(filteredList, filteredNames);
			ArrayList<Object[]> formattedList = formatter.getRetMasterTable();
			String[] formattedNames = formatter.getRetVarNames();
			
			ClusteringVizPlaySheet ps = new ClusteringVizPlaySheet();
			
			errorHash.put("Message", "Cannot cluster using specified categories.");
			errorHash.put("Class", ps.getClass().getName());
			ps.setRDFEngine(engine);
			ps.setQuery(query);
			if (configParameters.size() == 1) {
				Integer numClusters = Integer.parseInt(configParameters.get(0));
				ps.setNumClusters(numClusters);
			}
			
			try {
				ps.setMasterList(formattedList);
				ps.setMasterNames(formattedNames);
				ps.setList(formattedList);
				ps.setNames(formattedNames);
				ps.runAlgorithm();
				ps.processQueryData();
			} catch (NullPointerException e) {
				e.printStackTrace();
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
			String title = "Cluster by " + ps.getNames()[0];
			String[] headers = { "ClusterID" };
			int[] tempClusterAssignment = ps.getClusterAssignment();
			int[][] clusterAssignment = new int[tempClusterAssignment.length][1];
			for (int i = 0; i < tempClusterAssignment.length; i++) {
				clusterAssignment[i][0] = tempClusterAssignment[i];
			}
			Hashtable<String, Hashtable<String, Hashtable<String, Object>>[]> specificData = new Hashtable<String, Hashtable<String, Hashtable<String, Object>>[]>();
			specificData.put("barData", ps.getBarData());
			
			data.put("title", title);
			data.put("headers", headers);
			data.put("data", clusterAssignment);
			data.put("specificData", specificData);
			
			LOGGER.info("Running Clustering on " + engine.getEngineName() + "...");
			return Response.status(200).entity(WebUtility.getSO(data)).build(); // send front end int[]
			
		} else if (algorithm.equals("AssociationLearning")) {
			WekaAprioriVizPlaySheet ps = new WekaAprioriVizPlaySheet();
			if (configParameters.get(0) != null && !configParameters.get(0).isEmpty()) {
				Integer numRules = Integer.parseInt(configParameters.get(0));
				ps.setNumRules(numRules);
			}
			if (configParameters.get(1) != null && !configParameters.get(1).isEmpty()) {
				Double confPer = Double.parseDouble(configParameters.get(1));
				ps.setConfPer(confPer);
			}
			if (configParameters.get(2) != null && !configParameters.get(2).isEmpty()) {
				Double minSupport = Double.parseDouble(configParameters.get(2));
				ps.setMinSupport(minSupport);
			}
			if (configParameters.get(3) != null && !configParameters.get(3).isEmpty()) {
				Double maxSupport = Double.parseDouble(configParameters.get(3));
				ps.setMaxSupport(maxSupport);
			}
			ps.setRDFEngine(engine);
			ps.setQuery(query);
			ps.setList(filteredList);
			ps.setNames(filteredNames);
			ps.processQueryData();
			data = (Hashtable) ps.getData();
			if(data.get("headers") == null || data.get("data") == null) {
				errorHash.put("Message", "No results found from algorithm");
				errorHash.put("Class", ps.getClass().getName());
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
			data.remove("id");
			data.put("title", "Association Learning: Apriori Algorithm");
			
			LOGGER.info("Running Association Learning on " + engine.getEngineName() + "...");
			return Response.status(200).entity(WebUtility.getSO(data)).build();
			
		} else if (algorithm.equals("Classify")) {
			WekaClassificationPlaySheet ps = new WekaClassificationPlaySheet();
			// instance id is the prop being classified for
			instanceID = gson.fromJson(form.getFirst("instanceID"), Integer.class);
			String propName = names[instanceID];
			int classColumn = ArrayUtilityMethods.arrayContainsValueAtIndex(filteredNames, propName);
			if (classColumn == -1) {
				errorHash.put("Message", "Must select column " + propName + " in filter param list to run classificaiton on it.");
				errorHash.put("Class", ps.getClass().getName());
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
			ps.setClassColumn(classColumn);
			ps.setRDFEngine(engine);
			ps.setQuery(query);
			ps.setList(filteredList);
			ps.setNames(filteredNames);
			ps.runAlgorithm();
			ps.processQueryData();
			data = (Hashtable) ps.getData();
			data.remove("id");
			data.put("title", "Classification Algorithm: For variable " + ps.getNames()[ps.getClassColumn()]);
			LOGGER.info("Running Classify on " + engine.getEngineName() + "...");
			return Response.status(200).entity(WebUtility.getSO(data)).build();
			
		} else if (algorithm.equals("Outliers")) {
			LocalOutlierPlaySheet ps = new LocalOutlierPlaySheet();
			if (configParameters.size() == 1) {
				Integer k = Integer.parseInt(configParameters.get(0));
				ps.setKNeighbors(k);
			}
			errorHash.put("Message", "Cannot run outlier algorithm on specified categories.");
			errorHash.put("Class", ps.getClass().getName());
			ps.setRDFEngine(engine);
			ps.setQuery(query);
			try {
				ps.setList(filteredList);
				ps.setNames(filteredNames);
				ps.runAnalytics();
			} catch (NullPointerException e) {
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			} catch (ArrayIndexOutOfBoundsException e) {
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
			
			String title = "Outliers on " + ps.getNames()[0];
			String[] headers = { "LOP" };
			double[] tempLop = ps.getLop();
			double[][] lop = new double[tempLop.length][1];
			for (int i = 0; i < tempLop.length; i++) {
				lop[i][0] = tempLop[i];
			}
			
			data.put("title", title);
			data.put("headers", headers);
			data.put("data", lop);
			Hashtable<String, String> specificData = new Hashtable<String, String>();
			specificData.put("x-axis", "LOP");
			specificData.put("z-axis", "COUNT");
			data.put("specificData", specificData);
			
			LOGGER.info("Running Outliers on " + engine.getEngineName() + "...");
			return Response.status(200).entity(WebUtility.getSO(data)).build(); // send front end double[]
		} else if (algorithm.equals("Similarity")) {
			DatasetSimilarityPlaySheet ps = new DatasetSimilarityPlaySheet();
			ps.setRDFEngine(engine);
			ps.setQuery(query);
			
			errorHash.put("Message", "Cannot run similarity algorithm on specified categories.");
			errorHash.put("Class", ps.getClass().getName());
			
			try {
				ps.setList(filteredList);
				ps.setNames(filteredNames);
				ps.runAnalytics();
			} catch (NullPointerException e) {
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
			
			String label = ps.getNames()[0];
			String title = "Similarity on " + label;
			String[] headers = { "SimValues" };
			double[] tempSimValues = ps.getSimValues();
			double[][] simValues = new double[tempSimValues.length][1];
			for (int i = 0; i < tempSimValues.length; i++) {
				simValues[i][0] = (double) Math.round(tempSimValues[i] * 100000) / 100000;
			}
			
			Hashtable<String, String> dataTableAlign = new Hashtable<String, String>();
			dataTableAlign.put("label", label);
			dataTableAlign.put("value 1", "SimValues");
			
			data.put("title", title);
			data.put("headers", headers);
			data.put("data", simValues);
			data.put("dataTableAlign", dataTableAlign);
			
			LOGGER.info("Running Similarity on " + engine.getEngineName() + "...");
			return Response.status(200).entity(WebUtility.getSO(data)).build();
		} else if (algorithm.equals("MatrixRegression")) {
			MatrixRegressionVizPlaySheet ps = new MatrixRegressionVizPlaySheet();
			// instance id is the prop being approximated for
			instanceID = gson.fromJson(form.getFirst("instanceID"), Integer.class);
			String propName = names[instanceID];
			int bColumnIndex = ArrayUtilityMethods.arrayContainsValueAtIndex(filteredNames, propName);
			if (bColumnIndex == -1) {
				errorHash.put("Message", "Must select column " + propName + " in filter param list to run classificaiton on it.");
				errorHash.put("Class", ps.getClass().getName());
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
			ps.setbColumnIndex(bColumnIndex);
			ps.setIncludesInstance(false);
			ps.setRDFEngine(engine);
			ps.setQuery(query);
			ps.setList(filteredList);
			ps.setNames(filteredNames);
			ps.processQueryData();
			data = (Hashtable) ps.getData();
			data.remove("id");
			data.put("title", "Matrix Regression Algorithm: For variable " + ps.getNames()[ps.getbColumnIndex()]);

			LOGGER.info("Running Matrix Regression on " + engine.getEngineName() + "...");
			return Response.status(200).entity(WebUtility.getSO(data)).build();
		} else if (algorithm.equals("NumericalCorrelation")) {
			NumericalCorrelationVizPlaySheet ps = new NumericalCorrelationVizPlaySheet();
			ps.setIncludesInstance(false);
			ps.setRDFEngine(engine);
			ps.setQuery(query);
			ps.setList(filteredList);
			ps.setNames(filteredNames);
			ps.processQueryData();
			data = (Hashtable) ps.getData();
			data.remove("id");
			data.put("title", "Numerical Correlation Algorithm");
			LOGGER.info("Running Numerical Correlation on " + engine.getEngineName() + "...");
			return Response.status(200).entity(WebUtility.getSO(data)).build();
		} else if (algorithm.equals("SOM")) {
			LOGGER.info("Running SOM on " + engine.getEngineName() + "...");
			SelfOrganizingMap3DBarChartPlaySheet ps = new SelfOrganizingMap3DBarChartPlaySheet();
			ps.setRDFEngine(engine);
			ps.setQuery(query);
			ps.setList(filteredList);
			ps.setNames(filteredNames);
			ps.runAlgorithm();
			ps.processQueryData();
			data = (Hashtable) ps.getData();
			data.remove("id");
			data.put("title", "SOM Algorithm");
			return Response.status(200).entity(WebUtility.getSO(data)).build();
		} else {
			String errorMessage = "Selected algorithm does not exist";
			LOGGER.info("Selected algorithm does not exist...");
			return Response.status(400).entity(WebUtility.getSO(errorMessage)).build();
		}
		// TODO decide on format of data to be returned
	}
	
	// MachineLearningEnd
	
	@POST
	@Path("/scatter")
	public Response generateScatter() {
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		LOGGER.info("Creating scatterplot for " + engine.getEngineName() + "'s base page...");
		return Response.status(200).entity(WebUtility.getSO(ps.generateScatter(engine))).build();
	}
	
	@POST
	@Path("/questions")
	public Response getQuestions(@QueryParam("typeURI") String typeURI) {
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		if (typeURI == null) {
			LOGGER.info("Creating generic question list...");
			List<Hashtable<String, String>> questionList = ps.getQuestionsWithoutParams(engine);
			if (questionList.isEmpty()) {
				String errorMessage = "No insights exist that do not contain a paramter input.";
				return Response.status(400).entity(WebUtility.getSO(errorMessage)).build();
			} else {
				return Response.status(200).entity(WebUtility.getSO(questionList)).build();
			}
		} else {
			LOGGER.info("Creating question list with parameter of type " + typeURI + "...");
			List<Hashtable<String, String>> questionList = ps.getQuestionsForParam(engine, typeURI);
			if (questionList.isEmpty()) {
				String errorMessage = "No insights exist that contain " + Utility.getInstanceName(typeURI) + " as a paramter input.";
				return Response.status(400).entity(WebUtility.getSO(errorMessage)).build();
			} else {
				return Response.status(200).entity(WebUtility.getSO(questionList)).build();
			}
		}
	}
	
	@POST
	@Path("/influentialInstances")
	public Response getMostInfluentialInstances(@QueryParam("typeURI") String typeURI) {
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		if (typeURI == null) {
			LOGGER.info("Creating list of instances with most edge connections across all concepts...");
			return Response.status(200).entity(WebUtility.getSO(ps.getMostInfluentialInstancesForAllTypes(engine))).build();
		} else {
			LOGGER.info("Creating list of instances with most edge connections of type " + typeURI + "...");
			return Response.status(200).entity(WebUtility.getSO(ps.getMostInfluentialInstancesForSpecificTypes(engine, typeURI))).build();
		}
	}
	
	@POST
	@Path("/outliers")
	public Response getLargestOutliers(@QueryParam("typeURI") String typeURI) {
		if (typeURI == null) {
			String errorMessage = "No typeURI provided";
			return Response.status(400).entity(WebUtility.getSO(errorMessage)).build();
		}
		
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		LOGGER.info("Running outlier algorithm for instances of type " + typeURI + "...");
		List<Hashtable<String, Object>> results = ps.getLargestOutliers(engine, typeURI);
		
		if (results == null) {
			String errorMessage = "No properties or edge connections to determine outliers among concepts of type ".concat(Utility
					.getInstanceName(typeURI));
			return Response.status(400).entity(WebUtility.getSO(errorMessage)).build();
		}
		if (results.isEmpty()) {
			String errorMessage = "Insufficient sample size of instances of type ".concat(Utility.getInstanceName(typeURI).concat(
					" to determine outliers"));
			return Response.status(400).entity(WebUtility.getSO(errorMessage)).build();
		}
		return Response.status(200).entity(WebUtility.getSO(results)).build();
	}
	
	@POST
	@Path("/connectionMap")
	public Response getConnectionMap(@QueryParam("instanceURI") String instanceURI) {
		if (instanceURI == null) {
			String errorMessage = "No instanceURI provided";
			return Response.status(400).entity(WebUtility.getSO(errorMessage)).build();
		}
		
		LOGGER.info("Creating instance mapping to concepts...");
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		return Response.status(200).entity(WebUtility.getSO(ps.getConnectionMap(engine, instanceURI))).build();
	}
	
	@POST
	@Path("/properties")
	public Response getPropertiesForInstance(@QueryParam("instanceURI") String instanceURI) {
		if (instanceURI == null) {
			String errorMessage = "No instanceURI provided";
			return Response.status(400).entity(WebUtility.getSO(errorMessage)).build();
		}
		
		LOGGER.info("Creating list of properties for " + instanceURI + "...");
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		return Response.status(200).entity(WebUtility.getSO(ps.getPropertiesForInstance(engine, instanceURI))).build();
	}
	
	// TODO: getting questions from master db is web specific and should not be semoss playsheet
	// public Hashtable<String, Object> getQuestionsWithoutParamsFromMasterDB(IEngine engine, String engineName, boolean isEngineMaster) {
	// final String getInsightsWithoutParamsFromMasterDBQuery =
	// "SELECT DISTINCT ?questionDescription ?timesClicked WHERE { BIND(@ENGINE_NAME@ AS ?engine) {?engineInsight <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> <http://semoss.org/ontologies/Relation/Engine:Insight>} {?engine ?engineInsight ?insight} {?insight <http://semoss.org/ontologies/Relation/Contains/Description> ?questionDescription} {?insight <http://semoss.org/ontologies/Relation/PartOf> ?userInsight} {?userInsight <http://semoss.org/ontologies/Relation/Contains/TimesClicked> ?timesClicked} MINUS{?insight <PARAM:TYPE> ?entity} } ORDER BY ?timesClicked";
	//
	// List<Object[]> questionSet = new ArrayList<Object[]>();
	//
	// RDFFileSesameEngine insightEngine = ((AbstractEngine)engine).getInsightBaseXML();
	// String query = getInsightsWithoutParamsFromMasterDBQuery.replace("@ENGINE_NAME@",
	// "http://semoss.org/ontologies/Concept/Engine/".concat(engineName));
	//
	// SesameJenaSelectWrapper sjsw = Utility.processQuery(insightEngine, query);
	// String[] names = sjsw.getVariables();
	// String param1 = names[0];
	// while(sjsw.hasNext()) {
	// SesameJenaSelectStatement sjss = sjsw.next();
	// String[] question = new String[]{sjss.getVar(param1).toString()};
	// questionSet.add(question);
	// }
	//
	// Hashtable<String, Object> retHash = new Hashtable<String, Object>();
	// retHash.put("data", questionSet);
	// retHash.put("headers", new String[]{"Questions"});
	//
	// return retHash;
	// }
	
}
