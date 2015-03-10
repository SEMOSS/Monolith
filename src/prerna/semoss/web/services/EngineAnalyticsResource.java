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

import prerna.rdf.engine.api.IEngine;
import prerna.ui.components.playsheets.AnalyticsBasePlaySheet;
import prerna.ui.components.playsheets.ClusteringVizPlaySheet;
import prerna.ui.components.playsheets.DatasetSimilarityPlaySheet;
import prerna.ui.components.playsheets.LocalOutlierPlaySheet;
import prerna.ui.components.playsheets.MatrixRegressionVizPlaySheet;
import prerna.ui.components.playsheets.NumericalCorrelationVizPlaySheet;
import prerna.ui.components.playsheets.WekaAprioriPlaySheet;
import prerna.ui.components.playsheets.WekaClassificationPlaySheet;
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
		Gson gson = new Gson();
		String query = form.getFirst("query");
		String algorithm = form.getFirst("algorithm");
		ArrayList<String> configParameters = gson.fromJson(form.getFirst("parameters"), ArrayList.class);
		Hashtable<String, Object> data = new Hashtable<String, Object>();
		
		if (algorithm.equals("Clustering")) {
			ClusteringVizPlaySheet ps = new ClusteringVizPlaySheet();
			ps.setRDFEngine(engine);
			ps.setQuery(query);
			if (configParameters.size() == 1) {
				Integer numClusters = Integer.parseInt(configParameters.get(0));
				ps.setNumClusters(numClusters);
			}
			ps.createData();
			
			String[] headers = { "clusterAssignment" };
			int[][] clusterAssignment = { ps.getClusterAssignment() };
			data.put("headers", headers);
			data.put("dataSeries", clusterAssignment);
			
			LOGGER.info("Running Clustering on " + engine.getEngineName() + "...");
			return Response.status(200).entity(WebUtility.getSO(data)).build(); // send front end int[]
			
		} else if (algorithm.equals("AssociationLearning")) {
			WekaAprioriPlaySheet ps = new WekaAprioriPlaySheet();
			if (configParameters.get(0) != null) {
				Integer numRules = Integer.parseInt(configParameters.get(0));
				ps.setNumRules(numRules);
			}
			if (configParameters.get(1) != null) {
				Double confPer = Double.parseDouble(configParameters.get(1));
				ps.setConfPer(confPer);
			}
			if (configParameters.get(2) != null) {
				Double minSupport = Double.parseDouble(configParameters.get(2));
				ps.setMinSupport(minSupport);
			}
			if (configParameters.get(3) != null) {
				Double maxSupport = Double.parseDouble(configParameters.get(3));
				ps.setMaxSupport(maxSupport);
			}
			
			ps.setRDFEngine(engine);
			ps.setQuery(query);
			ps.createData();
			
			// String[] headers = { "" }; //TODO figure out what is returned
			// data.put("headers", headers);
			// data.put("dataSeries", );
			
			LOGGER.info("Running Association Learning on " + engine.getEngineName() + "...");
			return Response.status(200).entity(WebUtility.getSO(data)).build();
			
		} else if (algorithm.equals("Classify")) {
			WekaClassificationPlaySheet ps = new WekaClassificationPlaySheet();
			ps.setRDFEngine(engine);
			ps.setQuery(query);
			ps.createData();
			
			// String[] headers = { "" }; //TODO figure out what is returned
			// data.put("headers", headers);
			// data.put("dataSeries", );
			LOGGER.info("Running Classify on " + engine.getEngineName() + "...");
			return Response.status(200).entity(WebUtility.getSO(data)).build();
			
		} else if (algorithm.equals("Outliers")) {
			LocalOutlierPlaySheet ps = new LocalOutlierPlaySheet();
			if (configParameters.size() == 1) {
				Integer k = Integer.parseInt(configParameters.get(0));
				ps.setKNeighbors(k);
			}
			ps.setRDFEngine(engine);
			ps.setQuery(query);
			ps.createData();
			ps.runAnalytics();
			String[] headers = { "count", "lop" };
			Object[][] dataArray = new Object[ps.getLop().length][2];
			int[] count = { 1 };
			
			for (int i = 0; i < dataArray.length; i++) {
				dataArray[i][0] = count;
				double[] tempLop = { ps.getLop()[i] };
				dataArray[i][1] = tempLop;
			}
			data.put("headers", headers);
			data.put("dataSeries", dataArray);
			
			LOGGER.info("Running Outliers on " + engine.getEngineName() + "...");
			return Response.status(200).entity(WebUtility.getSO(data)).build(); // send front end double[]
		} else if (algorithm.equals("Similarity")) {
			DatasetSimilarityPlaySheet ps = new DatasetSimilarityPlaySheet();
			ps.setRDFEngine(engine);
			ps.setQuery(query);
			ps.createData();
			ps.runAnalytics();
			String[] headers = { "simValues" };
			double[][] simValues = { ps.getSimValues() };
			data.put("headers", headers);
			data.put("dataSeries", simValues);
			
			LOGGER.info("Running Similarity on " + engine.getEngineName() + "...");
			return Response.status(200).entity(WebUtility.getSO(data)).build();
			
		} else if (algorithm.equals("MatrixRegression")) {
			MatrixRegressionVizPlaySheet ps = new MatrixRegressionVizPlaySheet();
			ps.setRDFEngine(engine);
			ps.createData();
			
			// String[] headers = { "" }; //TODO figure out what is returned
			// data.put("headers", headers);
			// data.put("dataSeries", );
			LOGGER.info("Running Matrix Regression on " + engine.getEngineName() + "...");
			return Response.status(200).entity(WebUtility.getSO(data)).build();
			
		} else if (algorithm.equals("NumericalCorrelation")) {
			NumericalCorrelationVizPlaySheet ps = new NumericalCorrelationVizPlaySheet();
			ps.setRDFEngine(engine);
			ps.createData();
			
			// String[] headers = { "" }; //TODO figure out what is returned
			// data.put("headers", headers);
			// data.put("dataSeries", );
			LOGGER.info("Running Numerical Correlation on " + engine.getEngineName() + "...");
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
