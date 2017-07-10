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
import prerna.algorithm.learning.weka.WekaAprioriAlgorithm;
import prerna.algorithm.learning.weka.WekaClassification;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.ui.components.playsheets.datamakers.ISEMOSSAction;
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
		
//		return Response.status(200).entity(WebUtility.getSO(algorithmList)).build();
		return WebUtility.getResponse(algorithmList, 200);
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
//				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
				return WebUtility.getResponse(errorHash, 400);
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
		if (algorithm.equals("AssociationLearning")) {
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
				return WebUtility.getResponse(errorHash, 400);
			}
			try {
				if (configParameters != null && !configParameters.isEmpty() && configParameters.get(3) != null && !configParameters.get(3).isEmpty()) {
					confPer = Double.parseDouble(configParameters.get(3))/100.0;
				}
			} catch(NumberFormatException e) {
				errorHash.put("errorMessage", "Invalid input for 'Confidence Value (%)': " + configParameters.get(3));
				return WebUtility.getResponse(errorHash, 400);
			}
			try {
				if (configParameters != null && !configParameters.isEmpty() && configParameters.get(1) != null && !configParameters.get(1).isEmpty()) {
					minSupport = Double.parseDouble(configParameters.get(1))/100.0;
				}
			} catch(NumberFormatException e) {
				errorHash.put("errorMessage", "Invalid input for 'Minimum Support (%)': " + configParameters.get(1));
				return WebUtility.getResponse(errorHash, 400);
			}
			try {
				if (configParameters != null && !configParameters.isEmpty() && configParameters.get(2) != null && !configParameters.get(2).isEmpty()) {
					maxSupport = Double.parseDouble(configParameters.get(2))/100.0;
				}
			} catch(NumberFormatException e) {
				errorHash.put("errorMessage", "Invalid input for 'Maximum Support (%)': " + configParameters.get(2));
				return WebUtility.getResponse(errorHash, 400);
			}
			if(numRules == null || confPer == null || minSupport == null || maxSupport == null) {
				errorHash.put("errorMessage", "Not all parameters are being set for Association Learning Algorithm");
				return WebUtility.getResponse(errorHash, 400);
			}
			if(minSupport > maxSupport) {
				errorHash.put("errorMessage", "Minimum Support value must be lower than Maximum Support Value");
				return WebUtility.getResponse(errorHash, 400);
			}
			
			selectedOptions.put(WekaAprioriAlgorithm.NUM_RULES, numRules); 
			selectedOptions.put(WekaAprioriAlgorithm.CONFIDENCE_LEVEL, confPer); 
			selectedOptions.put(WekaAprioriAlgorithm.MIN_SUPPORT, minSupport); 
			selectedOptions.put(WekaAprioriAlgorithm.MAX_SUPPORT, maxSupport); 
			selectedOptions.put(WekaAprioriAlgorithm.SKIP_ATTRIBUTES, skipAttributes); 
			selectedOptions.put(AlgorithmTransformation.ALGORITHM_TYPE, AlgorithmAction.ASSOCATION_LEARNING); 
			
		} else if (algorithm.equals("Classify")) {
			// model name is not exposed to users
			// other models do not work that well... just stick with J48 as it gives best results
			selectedOptions.put(WekaClassification.MODEL_NAME, "J48"); 
			selectedOptions.put(WekaClassification.CLASS_NAME, instanceName);
			selectedOptions.put(WekaAprioriAlgorithm.SKIP_ATTRIBUTES, skipAttributes); 
			selectedOptions.put(AlgorithmTransformation.ALGORITHM_TYPE, AlgorithmAction.J48_CLASSIFICATION); 
			
		} else if (algorithm.equals("MatrixRegression")) {
			selectedOptions.put(MatrixRegressionAlgorithm.INCLUDE_INSTANCES, false); 
			selectedOptions.put(MatrixRegressionAlgorithm.B_INDEX, instanceIndex); // instance index is used for column to approximate
			selectedOptions.put(MatrixRegressionAlgorithm.SKIP_ATTRIBUTES, skipAttributes); 
			selectedOptions.put(AlgorithmTransformation.ALGORITHM_TYPE, AlgorithmAction.MATRIX_REGRESSION); 
			
		} else if (algorithm.equals("NumericalCorrelation")) {
			selectedOptions.put(NumericalCorrelationAlgorithm.INCLUDE_INSTANCES, false); 
			selectedOptions.put(NumericalCorrelationAlgorithm.SKIP_ATTRIBUTES, skipAttributes); 
			selectedOptions.put(AlgorithmTransformation.ALGORITHM_TYPE, AlgorithmAction.NUMERICAL_CORRELATION); 
		} else {
			String errorMessage = "Selected algorithm does not exist";
			LOGGER.info("Selected algorithm does not exist...");
//			return Response.status(400).entity(WebUtility.getSO(errorMessage)).build();
			return WebUtility.getResponse(errorMessage, 400);
		}
		
		Map retMap = new Hashtable();
		retMap.put("insightID", insightID);
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
			return WebUtility.getResponse(errorHash, 400);
		}
		
		// TODO: need to figure out how to undo the columns to skip after algorithms are run
		// get data should return everything even if some parameters not used in algorithm
		dataFrame.setColumnsToSkip(new ArrayList<String>());
		
//		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
		return WebUtility.getResponse(retMap, 200);
	}
}
