/*******************************************************************************
 * Copyright 2015 Defense Health Agency (DHA)
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
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.google.gson.Gson;

import prerna.algorithm.api.ITableDataFrame;
import prerna.algorithm.learning.AlgorithmAction;
import prerna.algorithm.learning.AlgorithmTransformation;
import prerna.algorithm.learning.supervized.MatrixRegressionAlgorithm;
import prerna.algorithm.learning.supervized.NumericalCorrelationAlgorithm;
import prerna.algorithm.learning.unsupervised.clustering.AbstractClusteringRoutine;
import prerna.algorithm.learning.unsupervised.clustering.MultiClusteringRoutine;
import prerna.algorithm.learning.unsupervised.outliers.FastOutlierDetection;
import prerna.algorithm.learning.unsupervised.outliers.LOF;
import prerna.algorithm.learning.unsupervised.som.SOMRoutine;
import prerna.algorithm.learning.weka.WekaAprioriAlgorithm;
import prerna.algorithm.learning.weka.WekaClassification;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.ui.components.playsheets.datamakers.ISEMOSSAction;
import prerna.ui.components.playsheets.datamakers.ISEMOSSTransformation;
import prerna.util.ArrayUtilityMethods;
import prerna.util.MachineLearningEnum;
import prerna.web.services.util.WebUtility;

public class AnalyticsResource {
	
	private static final Logger LOGGER = LogManager.getLogger(AnalyticsResource.class.getName());
	
	String output = "";
	
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
	
	//TODO: need all of these algorithms to be transformations
	@POST
	@Path("/runAlgorithm")
	public Response runAlgorithm(MultivaluedMap<String, String> form) {
		Gson gson = new Gson();

		String algorithm = form.getFirst("algorithm");
		String insightID = form.getFirst("insightID");
		
		Insight existingInsight = null;
		if(insightID != null && !insightID.isEmpty()) {
			existingInsight = InsightStore.getInstance().get(insightID);
			if(existingInsight == null) {
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "Existing insight based on passed insightID is not found");
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
		}
		
		ITableDataFrame dataFrame = (ITableDataFrame) existingInsight.getDataMaker();
		String[] columnHeaders = dataFrame.getColumnHeaders();
		Map<String, Boolean> includeColMap = gson.fromJson(form.getFirst("filterParams"), Map.class);

		// clear any skip columns
		dataFrame.setColumnsToSkip(new ArrayList<String>());
		
		List<String> skipAttributes = new ArrayList<String>();
		for(String header : columnHeaders) {
			if(!includeColMap.containsKey(header)) {
				//this is annoying, need it for prim key
				skipAttributes.add(header);
			} else if(!includeColMap.get(header)){
				skipAttributes.add(header);
			}
		}

		int instanceIndex = 0;
		String instanceName = form.getFirst("instanceID");
		if(instanceName != null) {
			instanceIndex = ArrayUtilityMethods.arrayContainsValueAtIndex(columnHeaders, instanceName);
		}
		
		// need to adjust the instance index based on the skipping of other columns
		int origIndex = instanceIndex;
		for(int i = 0; i < columnHeaders.length; i++) {
			if(i < origIndex) {
				if(skipAttributes.contains(columnHeaders[i])) {
					instanceIndex--;
				}
			} else {
				break;
			}
		}
		dataFrame.setColumnsToSkip(skipAttributes);
		columnHeaders = dataFrame.getColumnHeaders();
		
		//TODO: need to figure out why all of these values come back as strings..
		List<String> configParameters = gson.fromJson(form.getFirst("parameters"), ArrayList.class);
		
		HashMap<String, Object> selectedOptions = new HashMap<String, Object>();
		Map<String, Object> errorHash = new HashMap<String, Object>();
		boolean isAction = false;
		if (algorithm.equals("Clustering")) {
			Integer numClusters = null;
			// this is the normal clustering routine with a set number of clusters
			if (configParameters != null && !configParameters.isEmpty() && configParameters.get(0) != null && !configParameters.get(0).isEmpty()) {
				try {
					numClusters = Integer.parseInt(configParameters.get(0));
				} catch(NumberFormatException e) {
					errorHash.put("errorMessage", "Invalid input for 'Number of Clusers': " + configParameters.get(0));
					return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
				}
				if(numClusters <= 1) {
					errorHash.put("errorMessage", "Number of clusters must be larger than 2");
					return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
				}
				selectedOptions.put(AbstractClusteringRoutine.INSTANCE_INDEX_KEY, instanceIndex); 
				selectedOptions.put(AbstractClusteringRoutine.NUM_CLUSTERS_KEY, numClusters); 
				selectedOptions.put(AbstractClusteringRoutine.SKIP_ATTRIBUTES, skipAttributes); 
				selectedOptions.put(AlgorithmTransformation.ALGORITHM_TYPE, AlgorithmTransformation.CLUSTERING); 

				// currently not exposed to user
				// selectedOptions.put(AbstractClusteringRoutine.DISTANCE_MEASURE, numClusters); 
			} 
			// this is the clustering routine which determines the number of clusters through an optimization routine
			else {
				selectedOptions.put(MultiClusteringRoutine.INSTANCE_INDEX_KEY, instanceIndex); 
				selectedOptions.put(MultiClusteringRoutine.SKIP_ATTRIBUTES, skipAttributes); 
				// currently not exposed to user, hard code min and max possible clusters
				selectedOptions.put(MultiClusteringRoutine.MIN_NUM_CLUSTERS, 2); 
				selectedOptions.put(MultiClusteringRoutine.MAX_NUM_CLUSTERS, 50); 
				//selectedOptions.put(AbstractClusteringRoutine.DISTANCE_MEASURE, numClusters); 
				selectedOptions.put(AlgorithmTransformation.ALGORITHM_TYPE, AlgorithmTransformation.MULTI_CLUSTERING); 
			}
			
		} else if (algorithm.equals("AssociationLearning")) {
			isAction = true;
			
			Integer numRules = null;
			Double confPer = null;
			Double minSupport = null;
			Double maxSupport = null;
			try {
				if (configParameters != null && !configParameters.isEmpty() && configParameters.get(0) != null && !configParameters.get(0).isEmpty()) {
					numRules = Integer.parseInt(configParameters.get(0));
				}
			} catch(NumberFormatException e) {
				errorHash.put("errorMessage", "Invalid input for 'Number of Rules': " + configParameters.get(0));
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
			try {
				if (configParameters != null && !configParameters.isEmpty() && configParameters.get(1) != null && !configParameters.get(1).isEmpty()) {
					confPer = Double.parseDouble(configParameters.get(1));
				}
			} catch(NumberFormatException e) {
				errorHash.put("errorMessage", "Invalid input for 'Confidence Value (%)': " + configParameters.get(1));
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
			try {
				if (configParameters != null && !configParameters.isEmpty() && configParameters.get(2) != null && !configParameters.get(2).isEmpty()) {
					minSupport = Double.parseDouble(configParameters.get(2));
				}
			} catch(NumberFormatException e) {
				errorHash.put("errorMessage", "Invalid input for 'Minimum Support (%)': " + configParameters.get(2));
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
			try {
				if (configParameters != null && !configParameters.isEmpty() && configParameters.get(3) != null && !configParameters.get(3).isEmpty()) {
					maxSupport = Double.parseDouble(configParameters.get(3));
				}
			} catch(NumberFormatException e) {
				errorHash.put("errorMessage", "Invalid input for 'Maximum Support (%)': " + configParameters.get(3));
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
			if(numRules == null || confPer == null || minSupport == null || maxSupport == null) {
				errorHash.put("errorMessage", "Not all parameters are being set for Association Learning Algorithm");
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
			if(minSupport > maxSupport) {
				errorHash.put("errorMessage", "Minimum Support value must be lower than Maximum Support Value");
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
			
			selectedOptions.put(WekaAprioriAlgorithm.NUM_RULES, numRules); 
			selectedOptions.put(WekaAprioriAlgorithm.CONFIDENCE_LEVEL, confPer); 
			selectedOptions.put(WekaAprioriAlgorithm.MIN_SUPPORT, minSupport); 
			selectedOptions.put(WekaAprioriAlgorithm.MAX_SUPPORT, maxSupport); 
			selectedOptions.put(WekaAprioriAlgorithm.SKIP_ATTRIBUTES, skipAttributes); 
			selectedOptions.put(AlgorithmTransformation.ALGORITHM_TYPE, AlgorithmAction.ASSOCATION_LEARNING); 
			
		} else if (algorithm.equals("Classify")) {
			isAction = true;

			// model name is not exposed to users
			// other models do not work that well... just stick with J48 as it gives best results
			selectedOptions.put(WekaClassification.MODEL_NAME, "J48"); 
			selectedOptions.put(WekaClassification.CLASS_NAME, instanceName);
			selectedOptions.put(WekaAprioriAlgorithm.SKIP_ATTRIBUTES, skipAttributes); 
			selectedOptions.put(AlgorithmTransformation.ALGORITHM_TYPE, AlgorithmAction.J48_CLASSIFICATION); 
			
		} else if (algorithm.equals("Outliers")) {
			Integer k = null;
			if (configParameters != null && !configParameters.isEmpty() && configParameters.get(0) != null && !configParameters.get(0).isEmpty()) {
				try {
					k = Integer.parseInt(configParameters.get(0));
				} catch(NumberFormatException e) {
					errorHash.put("errorMessage", "Invalid input for 'K-Value': " + configParameters.get(0));
					return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
				}
			} 
			
			if(k == null) {
				errorHash.put("errorMessage", "No 'K-Value' defined.");
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
			
			selectedOptions.put(LOF.INSTANCE_INDEX, instanceIndex); 
			selectedOptions.put(LOF.K_NEIGHBORS, k);
			selectedOptions.put(LOF.SKIP_ATTRIBUTES, skipAttributes); 
			selectedOptions.put(AlgorithmTransformation.ALGORITHM_TYPE, AlgorithmTransformation.LOCAL_OUTLIER_FACTOR); 
			
		} else if (algorithm.equals("FastOutliers")) {
			Integer numSubsetSize = null;
			Integer numIterations = null;
			
			if (configParameters != null && !configParameters.isEmpty()) {
				if(configParameters.get(0) != null && !configParameters.get(0).isEmpty()) {
					try {
						numSubsetSize = Integer.parseInt(configParameters.get(0));
					} catch(NumberFormatException e) {
						errorHash.put("errorMessage", "Invalid input for 'Subset Size': " + configParameters.get(0));
						return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
					}
				}
				if(configParameters.get(1) != null && !configParameters.get(1).isEmpty()) {
					try {
						numIterations = Integer.parseInt(configParameters.get(1));
					} catch(NumberFormatException e) {
						errorHash.put("errorMessage", "Invalid input for 'Number of Runs': " + configParameters.get(1));
						return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
					}
				}
			}
			
			if(numSubsetSize == null) {
				errorHash.put("errorMessage", "No 'Subset Size' defined.");
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
			if(numIterations == null) {
				errorHash.put("errorMessage", "No 'Number of Runs' defined.");
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
			
			selectedOptions.put(FastOutlierDetection.INSTANCE_INDEX, instanceIndex); 
			selectedOptions.put(FastOutlierDetection.NUM_SAMPLE_SIZE, numSubsetSize); 
			selectedOptions.put(FastOutlierDetection.NUMBER_OF_RUNS, numIterations); 
			selectedOptions.put(FastOutlierDetection.SKIP_ATTRIBUTES, skipAttributes); 
			selectedOptions.put(AlgorithmTransformation.ALGORITHM_TYPE, AlgorithmTransformation.FAST_OUTLIERS); 

		} else if (algorithm.equals("Similarity")) {
			selectedOptions.put(FastOutlierDetection.INSTANCE_INDEX, instanceIndex); 
			selectedOptions.put(FastOutlierDetection.SKIP_ATTRIBUTES, skipAttributes); 
			selectedOptions.put(AlgorithmTransformation.ALGORITHM_TYPE, AlgorithmTransformation.SIMILARITY); 
			
		} else if (algorithm.equals("MatrixRegression")) {
			isAction = true;

			selectedOptions.put(MatrixRegressionAlgorithm.INCLUDE_INSTANCES, false); 
			selectedOptions.put(MatrixRegressionAlgorithm.B_INDEX, instanceIndex); // instance index is used for column to approximate
			selectedOptions.put(MatrixRegressionAlgorithm.SKIP_ATTRIBUTES, skipAttributes); 
			selectedOptions.put(AlgorithmTransformation.ALGORITHM_TYPE, AlgorithmAction.MATRIX_REGRESSION); 
			
		} else if (algorithm.equals("NumericalCorrelation")) {
			isAction = true;

			selectedOptions.put(NumericalCorrelationAlgorithm.INCLUDE_INSTANCES, false); 
			selectedOptions.put(NumericalCorrelationAlgorithm.SKIP_ATTRIBUTES, skipAttributes); 
			selectedOptions.put(AlgorithmTransformation.ALGORITHM_TYPE, AlgorithmAction.NUMERICAL_CORRELATION); 

		} else if (algorithm.equals("SOM")) {
			//TODO: currently only exposed 2 parameters 
			selectedOptions.put(SOMRoutine.INSTANCE_INDEX_KEY, instanceIndex); 
			selectedOptions.put(SOMRoutine.SKIP_ATTRIBUTES, skipAttributes); 
			selectedOptions.put(AlgorithmTransformation.ALGORITHM_TYPE, AlgorithmTransformation.SELF_ORGANIZING_MAP); 
			// hard-coded at the moment
			selectedOptions.put(SOMRoutine.INITIAL_RADIUS, 2.0);
			selectedOptions.put(SOMRoutine.LEARNING_RATE, 0.07);
			selectedOptions.put(SOMRoutine.TAU, 7.5);
			selectedOptions.put(SOMRoutine.MAXIMUM_ITERATIONS, 15);
			// THESE WILL BE DYNAMICALLY CREATED
//			selectedOptions.put(SOMRoutine.GRID_WIDTH, 15);
//			selectedOptions.put(SOMRoutine.GRID_LENGTH, 15);

		} else {
			String errorMessage = "Selected algorithm does not exist";
			LOGGER.info("Selected algorithm does not exist...");
			return Response.status(400).entity(WebUtility.getSO(errorMessage)).build();
		}
		
		Map retMap = new Hashtable();
		retMap.put("insightID", insightID);
		if(isAction) {
			List<ISEMOSSAction> actions = new Vector<ISEMOSSAction>();
			AlgorithmAction action = new AlgorithmAction();
			actions.add(action);
			action.setProperties(selectedOptions);
			try {
				Object actionObj = existingInsight.processActions(actions).get(0);
				retMap.put("actionData", actionObj);
				retMap.put("stepID", action.getId());
			} catch(Exception ex) {
				ex.printStackTrace();
				dataFrame.setColumnsToSkip(new ArrayList<String>());
				errorHash.put("errorMessage", ex.getMessage());
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
		} else {
			List<ISEMOSSTransformation> postTrans = new Vector<ISEMOSSTransformation>();
			AlgorithmTransformation transformation = new AlgorithmTransformation();
			postTrans.add(transformation);
			transformation.setProperties(selectedOptions);
			// don't need the DataMakerComponent or another IDataMaker in this case to run the transformation
			try {
				existingInsight.processPostTransformation(postTrans);
				retMap.put("addedColumns", transformation.getAddedColumns());
				retMap.put("stepID", transformation.getId());
			} catch(Exception ex) {
				ex.printStackTrace();
				dataFrame.setColumnsToSkip(new ArrayList<String>());
				errorHash.put("errorMessage", ex.getMessage());
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
		}
		
		// TODO: need to figure out how to undo the columns to skip after algorithms are run
		// get data should return everything even if some parameters not used in algorithm
		dataFrame.setColumnsToSkip(new ArrayList<String>());
		
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}
	
//	@POST
//	@Path("/scatter")
//	public Response generateScatter() {
//		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
//		LOGGER.info("Creating scatterplot for " + engine.getEngineName() + "'s base page...");
//		return Response.status(200).entity(WebUtility.getSO(ps.generateScatter(engine))).build();
//	}
//	
//	@POST
//	@Path("/questions")
//	public Response getQuestions(@QueryParam("typeURI") String typeURI) {
//		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
//		if (typeURI == null) {
//			LOGGER.info("Creating generic question list...");
//			List<Hashtable<String, String>> questionList = ps.getQuestionsWithoutParams(engine);
//			if (questionList.isEmpty()) {
//				String errorMessage = "No insights exist that do not contain a paramter input.";
//				return Response.status(400).entity(WebUtility.getSO(errorMessage)).build();
//			} else {
//				return Response.status(200).entity(WebUtility.getSO(questionList)).build();
//			}
//		} else {
//			LOGGER.info("Creating question list with parameter of type " + typeURI + "...");
//			List<Hashtable<String, String>> questionList = ps.getQuestionsForParam(engine, typeURI);
//			if (questionList.isEmpty()) {
//				String errorMessage = "No insights exist that contain " + Utility.getInstanceName(typeURI) + " as a paramter input.";
//				return Response.status(400).entity(WebUtility.getSO(errorMessage)).build();
//			} else {
//				return Response.status(200).entity(WebUtility.getSO(questionList)).build();
//			}
//		}
//	}
//	
//	@POST
//	@Path("/influentialInstances")
//	public Response getMostInfluentialInstances(@QueryParam("typeURI") String typeURI) {
//		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
//		if (typeURI == null) {
//			LOGGER.info("Creating list of instances with most edge connections across all concepts...");
//			return Response.status(200).entity(WebUtility.getSO(ps.getMostInfluentialInstancesForAllTypes(engine))).build();
//		} else {
//			LOGGER.info("Creating list of instances with most edge connections of type " + typeURI + "...");
//			return Response.status(200).entity(WebUtility.getSO(ps.getMostInfluentialInstancesForSpecificTypes(engine, typeURI))).build();
//		}
//	}
//	
//	@POST
//	@Path("/outliers")
//	public Response getLargestOutliers(@QueryParam("typeURI") String typeURI) {
//		if (typeURI == null) {
//			String errorMessage = "No typeURI provided";
//			return Response.status(400).entity(WebUtility.getSO(errorMessage)).build();
//		}
//		
//		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
//		LOGGER.info("Running outlier algorithm for instances of type " + typeURI + "...");
//		List<Hashtable<String, Object>> results = ps.getLargestOutliers(engine, typeURI);
//		
//		if (results == null) {
//			String errorMessage = "No properties or edge connections to determine outliers among concepts of type ".concat(Utility
//					.getInstanceName(typeURI));
//			return Response.status(400).entity(WebUtility.getSO(errorMessage)).build();
//		}
//		if (results.isEmpty()) {
//			String errorMessage = "Insufficient sample size of instances of type ".concat(Utility.getInstanceName(typeURI).concat(
//					" to determine outliers"));
//			return Response.status(400).entity(WebUtility.getSO(errorMessage)).build();
//		}
//		return Response.status(200).entity(WebUtility.getSO(results)).build();
//	}
//	
//	@POST
//	@Path("/connectionMap")
//	public Response getConnectionMap(@QueryParam("instanceURI") String instanceURI) {
//		if (instanceURI == null) {
//			String errorMessage = "No instanceURI provided";
//			return Response.status(400).entity(WebUtility.getSO(errorMessage)).build();
//		}
//		
//		LOGGER.info("Creating instance mapping to concepts...");
//		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
//		return Response.status(200).entity(WebUtility.getSO(ps.getConnectionMap(engine, instanceURI))).build();
//	}
//	
//	@POST
//	@Path("/properties")
//	public Response getPropertiesForInstance(@QueryParam("instanceURI") String instanceURI) {
//		if (instanceURI == null) {
//			String errorMessage = "No instanceURI provided";
//			return Response.status(400).entity(WebUtility.getSO(errorMessage)).build();
//		}
//		
//		LOGGER.info("Creating list of properties for " + instanceURI + "...");
//		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
//		return Response.status(200).entity(WebUtility.getSO(ps.getPropertiesForInstance(engine, instanceURI))).build();
//	}
	
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
