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
package prerna.insights.admin;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrServerException;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import prerna.algorithm.api.ITableDataFrame;
import prerna.cache.CacheFactory;
import prerna.engine.api.IEngine.ENGINE_TYPE;
import prerna.engine.impl.AbstractEngine;
import prerna.engine.impl.QuestionAdministrator;
import prerna.om.Dashboard;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.SEMOSSParam;
import prerna.sablecc.PKQLRunner;
import prerna.solr.SolrIndexEngine;
import prerna.ui.components.playsheets.datamakers.DataMakerComponent;
import prerna.ui.components.playsheets.datamakers.FilterTransformation;
import prerna.ui.components.playsheets.datamakers.IDataMaker;
import prerna.ui.components.playsheets.datamakers.ISEMOSSTransformation;
import prerna.ui.components.playsheets.datamakers.PKQLTransformation;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

public class QuestionAdmin {

	Logger LOGGER = Logger.getLogger(QuestionAdmin.class.getName());
	AbstractEngine coreEngine;
	String output = "";
	final static int MAX_CHAR = 100;

	public QuestionAdmin(AbstractEngine coreEngine) {
		this.coreEngine = coreEngine;
	}

	@POST
	@Path("addFromAction")
	@Produces("application/json")
	public Response addInsight(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		LOGGER.info("Adding question from action with following details:::: " + form.toString());
		String perspective = form.getFirst("perspective");
		String order = form.getFirst("order");
		String insightName = form.getFirst("insightName");
		String description = form.getFirst("description");
		String layout = form.getFirst("layout");
		String insightID = form.getFirst("insightID");
		String uiOptions = form.getFirst("uiOptions");
		String image = form.getFirst("image");
		String tagsString = form.getFirst("tags");

		Gson gson = new Gson();
		Map<String, String> dataTableAlign = gson.fromJson(form.getFirst("dataTableAlign"), Map.class);
		List<String> pkqlsToAdd = gson.fromJson(form.getFirst("pkqlsToAdd"), List.class);
		List<String> saveRecipe = gson.fromJson(form.getFirst("saveRecipe"), List.class); //this is the recipe we want to save the insight as
		
		List<String> tags = new Vector<String>();
		if(tagsString != null && !tagsString.isEmpty()) {
			tags = gson.fromJson(tagsString, List.class);
		}
		tags.add(perspective.replace("-Perspective", ""));
		
		Insight insight = InsightStore.getInstance().get(insightID);
		String newInsightID = "";
		Vector<Map<String, String>> paramMapList = gson.fromJson(form.getFirst("parameterQueryList"), new TypeToken<Vector<Map<String, String>>>() {}.getType());

		//If saving with params, FE passes a user.input PKQL that needs to be added/saved - better way to do this?
		if(pkqlsToAdd != null && !pkqlsToAdd.isEmpty()) {
			PKQLRunner runner = insight.getPKQLRunner();
			DataMakerComponent dmc = null;
			if(insight.getDataMaker() instanceof Dashboard) {
//				dmc = insight.getDashboardDataMakerComponent();
			} else {
				dmc = insight.getDataMakerComponents().get(insight.getDataMakerComponents().size() - 1);
			}
			for(String pkqlCmd : pkqlsToAdd) {
				PKQLTransformation pkqlToAdd = new PKQLTransformation();
				Map<String, Object> props = new HashMap<String, Object>();
				props.put(PKQLTransformation.EXPRESSION, pkqlCmd);
				pkqlToAdd.setProperties(props);
				pkqlToAdd.setRunner(runner);

				dmc.addPostTrans(pkqlToAdd, 0);
			}
		}

		Insight insightToSave = getInsightToSave(insight, saveRecipe);
		newInsightID = addInsightFromDb(insightToSave, 
										insightName, 
										perspective, 
										order, 
										layout, 
										description,
										uiOptions, 
										tags, 
										dataTableAlign, 
										paramMapList, 
										image);

		insight.setRdbmsId(newInsightID);
		insight.setMainEngine(this.coreEngine);
		insight.setInsightName(insightName);
		Map<String, Object> retMap = new HashMap<String, Object>();
		//			retMap.put("newInsightID", newInsightID);
		retMap.put("core_engine", this.coreEngine.getEngineName());
		retMap.put("core_engine_id", newInsightID);
		retMap.put("insightName", insightName);
		if (newInsightID != null) {
//			return Response.status(200).entity(WebUtility.getSO(retMap)).build();
			return WebUtility.getResponse(retMap, 200);
		} else {
//			return Response.status(500).entity(WebUtility.getSO("Error adding insight")).build();
			return WebUtility.getResponse("Error adding insight", 500);
		}
	}
	
	private String addInsightFromDb(Insight insight, 
			String insightName, 
			String perspective, 
			String order, 
			String layout,
			String description,
			String uiOptions,
			List<String> tags,
			Map<String, String> dataTableAlign, 
			Vector<Map<String, String>> paramMapList,
			String image) {
		boolean isDbQuery = true;
		
		List<DataMakerComponent> dmcList = insight.getDataMakerComponents();
		List<SEMOSSParam> params = buildParameterList(insight, paramMapList);
		
		//Add necessary filter transformations
		IDataMaker dm = insight.getDataMaker();
		String newInsightID = null;
		String engineName = coreEngine.getEngineName();
		QuestionAdministrator questionAdmin = new QuestionAdministrator(this.coreEngine);
		if(dm instanceof ITableDataFrame) {
			//add list of new filter transformations to the last component
			List<ISEMOSSTransformation> oldPostTrans = null;
			if(dmcList.size() > 0) {
				DataMakerComponent lastComponent = dmcList.get(dmcList.size() - 1);
				List<ISEMOSSTransformation> newPostTrans = lastComponent.getPostTrans();
				oldPostTrans = new Vector<ISEMOSSTransformation>(newPostTrans);
				List<FilterTransformation> trans2add = flushFilterModel2Transformations((ITableDataFrame) dm);
				if(trans2add != null) {
					newPostTrans.addAll(trans2add);
				}
			}
			newInsightID = questionAdmin.addQuestion(insightName, perspective, dmcList, layout, order, insight.getDataMakerName(), isDbQuery, dataTableAlign, params, uiOptions);

			//reset the post trans on the last component if the filter model has been flushed to it
			//we don't want the insight itself to change at all through this process
			if(dmcList.size() > 0) {
				DataMakerComponent lastComponent = dmcList.get(dmcList.size() - 1);
				lastComponent.setPostTrans(oldPostTrans);
			}
		} 
		
//		else if(dm instanceof Dashboard) {
//			dmcList = new ArrayList<>();
//			Dashboard dash = (Dashboard)dm;
//			DataMakerComponent lastComponent = insight.getDashboardDataMakerComponent();
//			dmcList.add(lastComponent);
//			List<ISEMOSSTransformation> newPostTrans = lastComponent.getPostTrans();
//			List<ISEMOSSTransformation> oldPostTrans = new Vector<ISEMOSSTransformation>(newPostTrans);
//
//			newInsightID = questionAdmin.addQuestion(insightName, perspective, dmcList, layout, order, insight.getDataMakerName(), isDbQuery, dataTableAlign, params, uiOptions);
//		}
		else {
			newInsightID = questionAdmin.addQuestion(insightName, perspective, dmcList, layout, order, insight.getDataMakerName(), isDbQuery, dataTableAlign, params, uiOptions);
		}
				
		Map<String, Object> solrInsights = new HashMap<>();
		DateFormat dateFormat = SolrIndexEngine.getDateFormat();
		Date date = new Date();
		String currDate = dateFormat.format(date);
		solrInsights.put(SolrIndexEngine.STORAGE_NAME, insightName);
		solrInsights.put(SolrIndexEngine.INDEX_NAME, insightName);
		solrInsights.put(SolrIndexEngine.TAGS, tags);
		solrInsights.put(SolrIndexEngine.LAYOUT, layout);
		solrInsights.put(SolrIndexEngine.CREATED_ON, currDate);
		solrInsights.put(SolrIndexEngine.MODIFIED_ON, currDate);
		solrInsights.put(SolrIndexEngine.LAST_VIEWED_ON, currDate);
		solrInsights.put(SolrIndexEngine.CORE_ENGINE, engineName);
		solrInsights.put(SolrIndexEngine.DESCRIPTION, description);
		solrInsights.put(SolrIndexEngine.CORE_ENGINE_ID, Integer.parseInt(newInsightID));
		solrInsights.put(SolrIndexEngine.IMAGE, image);
		
		Set<String> engines = new HashSet<String>();
		for(DataMakerComponent dmc : dmcList) {
			engines.add(dmc.getEngine().getEngineName());
		}
		
		if(engines.isEmpty()) {
			engines.add(engineName);
		}
		solrInsights.put(SolrIndexEngine.ENGINES, engines);

		//TODO: need to add users
		solrInsights.put(SolrIndexEngine.USER_ID, "default");
		try {
			SolrIndexEngine.getInstance().addInsight(engineName + "_" + newInsightID, solrInsights);
			return newInsightID;
		} catch (KeyManagementException e) {
			e.printStackTrace();
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		} catch (KeyStoreException e) {
			e.printStackTrace();
		} catch (SolrServerException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return null;
	}
	
	@POST
	@Path("editFromAction")
	@Produces("application/json")
	public Response editInsight(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		LOGGER.info("Editing question from action with following details:::: " + form.toString());
		String insightID = form.getFirst("insightID");
		String perspective = form.getFirst("perspective");
		String order = form.getFirst("order");
		String insightName = form.getFirst("insightName");
		String layout = form.getFirst("layout");
		String description = form.getFirst("description");
		String image = form.getFirst("image");
		String uiOptions = form.getFirst("uiOptions");
		String tagsString = form.getFirst("tags");

		Gson gson = new Gson();
		Map<String, String> dataTableAlign = gson.fromJson(form.getFirst("dataTableAlign"), Map.class);
		List<String> saveRecipe = gson.fromJson(form.getFirst("saveRecipe"), List.class);
		List<String> tags = new Vector<String>();
		if(tagsString != null && !tagsString.isEmpty()) {
			tags = gson.fromJson(tagsString, List.class);
		}
		tags.add(perspective.replace("-Perspective", ""));
		
		Insight insight = InsightStore.getInstance().get(insightID);
		insight.setInsightName(insightName);
		Vector<Map<String, String>> paramMapList = gson.fromJson(form.getFirst("parameterQueryList"), new TypeToken<Vector<Map<String, String>>>() {}.getType());
		Insight insightToEdit = getInsightToSave(insight, saveRecipe);
		editInsightFromDb(insightToEdit, 
							insightName, 
							perspective, 
							order, 
							layout,
							description,
							uiOptions, 
							tags,
							dataTableAlign, 
							paramMapList, 
							image);
		
//		return Response.status(200).entity(WebUtility.getSO("Success")).build();
		return WebUtility.getResponse("Success", 200);
	}

	private void editInsightFromDb(Insight insight, 
			String insightName, 
			String perspective, 
			String order, 
			String layout,
			String description,
			String uiOptions,
			List<String> tags,
			Map<String, String> dataTableAlign, 
			Vector<Map<String, String>> paramMapList,
			String image) {
		//TODO: currently not exposed through UI
		boolean isDbQuery = true;

		// delete existing cache folder for insight if present
		CacheFactory.getInsightCache(CacheFactory.CACHE_TYPE.DB_INSIGHT_CACHE).deleteInsightCache(insight);
		
		String engineName = coreEngine.getEngineName();
		QuestionAdministrator questionAdmin = new QuestionAdministrator(this.coreEngine);
		String rdbmsId = insight.getRdbmsId();
		List<DataMakerComponent> dmcList = insight.getDataMakerComponents();

		List<SEMOSSParam> params = buildParameterList(insight, paramMapList);
		questionAdmin.modifyQuestion(rdbmsId, insightName, perspective, dmcList, layout, order, insight.getDataMakerName(), isDbQuery, dataTableAlign, params, uiOptions);

		DateFormat dateFormat = SolrIndexEngine.getDateFormat();
		Date date = new Date();
		String currDate = dateFormat.format(date);
		Map<String, Object> solrModifyInsights = new HashMap<>();
		solrModifyInsights.put(SolrIndexEngine.STORAGE_NAME, insightName);
		solrModifyInsights.put(SolrIndexEngine.INDEX_NAME, insightName);
		solrModifyInsights.put(SolrIndexEngine.TAGS, tags);
		solrModifyInsights.put(SolrIndexEngine.DESCRIPTION, description);
		solrModifyInsights.put(SolrIndexEngine.LAYOUT, layout);
		solrModifyInsights.put(SolrIndexEngine.MODIFIED_ON, currDate);
		solrModifyInsights.put(SolrIndexEngine.LAST_VIEWED_ON, currDate);
		solrModifyInsights.put(SolrIndexEngine.CORE_ENGINE, engineName);
		solrModifyInsights.put(SolrIndexEngine.IMAGE, image);

		//TODO: need to add users
		solrModifyInsights.put(SolrIndexEngine.USER_ID, "default");
		Set<String> engines = new HashSet<String>();
		for(DataMakerComponent dmc : dmcList) {
			engines.add(dmc.getEngine().getEngineName());
		}
		solrModifyInsights.put(SolrIndexEngine.ENGINES, engines);

		try {
			SolrIndexEngine.getInstance().modifyInsight(engineName + "_" + rdbmsId, solrModifyInsights);
		} catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | SolrServerException
				| IOException e) {
			e.printStackTrace();
		}
	}
	
	private Insight getInsightToSave(Insight insight, List<String> saveRecipe) {
		//if the saveRecipe is passed, it means save the insight with this recipe
		if(saveRecipe != null && !saveRecipe.isEmpty()) {
			
			//get a copy of the insight without the dmc components and insight id
			Insight insightCopy = insight.emptyCopyForSave();
			DataMakerComponent dmc = insightCopy.getLastComponent();
			PKQLRunner runner = insight.getPKQLRunner();
			
			//add the pkqls in the copy
			for(String recipePkql : saveRecipe) {
				PKQLTransformation newPkql = new PKQLTransformation();
				Map<String, Object> props = new HashMap<String, Object>();
				props.put(PKQLTransformation.EXPRESSION, recipePkql);
				newPkql.setProperties(props);
				newPkql.setRunner(runner);
				dmc.addPostTrans(newPkql);
			}
			
			return insightCopy;
		} else {
			return insight;
		}
	}

	@POST
	@Path("delete")
	@Produces("application/json")
	public Response deleteInsight(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String insightID = form.getFirst("insightID");
		QuestionAdministrator questionAdmin = new QuestionAdministrator(this.coreEngine);
		questionAdmin.removeQuestion(insightID);
		
		try {
			List<String> removeList = new ArrayList<String>();
			removeList.add(coreEngine.getEngineName() + "_" + insightID);
			SolrIndexEngine.getInstance().removeInsight(removeList);
		} catch (KeyManagementException e) {
			e.printStackTrace();
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		} catch (KeyStoreException e) {
			e.printStackTrace();
		} catch (SolrServerException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
//		return Response.status(200).entity(WebUtility.getSO("Success")).build();
		return WebUtility.getResponse("Success", 200);
	}
	
	@POST
	@Path("addFromText")
	@Produces("application/json")
	public Response addInsightFromText(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String engineName = coreEngine.getEngineName();

		LOGGER.info("Adding question from action with following details:::: " + form.toString());
		Gson gson = new Gson();
		String perspective = form.getFirst("perspective");
		String order = form.getFirst("order");
		String insightName = form.getFirst("insightName");
		String layout = form.getFirst("layout");
		String query = form.getFirst("query");
		String uiOptions = form.getFirst("uiOptions");
		boolean isDbQuery = true;
		
		// TODO: need to fix the UI around this
		// when adding a single query insight, the user doesn't specify this
		// but it should be specified
		// the user doesn't need to specify it when the output type is a custom playsheet
		String dmName = form.getFirst("dmName");
		// need to perform a check if it is empty AND if layout isn't a custom query
		// currently just saying if layout doesn't start with "prerna."
		if(dmName == null || dmName.isEmpty() && !layout.startsWith("prerna.")) {
			dmName = "TinkerFrame";
		} 
		
		List<DataMakerComponent> dmcList = null;
		List<SEMOSSParam> params = null;
		// if query is defined, we are defining the insight the basic way -- just query and engine
		if(query != null && !query.isEmpty()) {
//			List<String> allSheets = PlaySheetRDFMapBasedEnum.getAllSheetNames();
//			dmName = InsightsConverter.getDataMaker(layout, allSheets);
			dmcList = new ArrayList<DataMakerComponent>();
			DataMakerComponent dmc = new DataMakerComponent(this.coreEngine, query);
			dmcList.add(dmc);
			Map<String, String> paramsInQuery = Utility.getParamTypeHash(query);
			
			// TODO: need to change the way this data is coming back.....
			Vector<String> parameterDependList = gson.fromJson(form.getFirst("parameterDependList"), Vector.class);
			Vector<String> parameterQueryList = gson.fromJson(form.getFirst("parameterQueryList"), Vector.class);
			Vector<String> parameterOptionList = gson.fromJson(form.getFirst("parameterOptionList"), Vector.class);
			// ....sad, need to define the parameter based on the engine type
			params = generateSEMOSSParamObjects(parameterDependList, parameterQueryList, parameterOptionList, paramsInQuery);
		} 
		// otherwise, we are defining the complex way -- with datamaker, insight makeup, layout, etc.
		else {
			String insightID = form.getFirst("insightID");
			Insight existingIn = coreEngine.getInsight(insightID).get(0);
			dmcList = existingIn.getDataMakerComponents();
			params = existingIn.getInsightParameters();
		}
		Map<String, String> dataTableAlign = gson.fromJson(form.getFirst("dataTableAlign"), Map.class);

		// for now use this method
		QuestionAdministrator questionAdmin = new QuestionAdministrator(this.coreEngine);
		String newInsightID = questionAdmin.addQuestion(insightName, perspective, dmcList, layout, order, dmName, isDbQuery, dataTableAlign, params, uiOptions);
		
		Map<String, Object> solrInsights = new HashMap<>();
		DateFormat dateFormat = SolrIndexEngine.getDateFormat();
		Date date = new Date();
		String currDate = dateFormat.format(date);
		solrInsights.put(SolrIndexEngine.STORAGE_NAME, insightName);
		solrInsights.put(SolrIndexEngine.INDEX_NAME, insightName);
		solrInsights.put(SolrIndexEngine.TAGS, perspective);
		solrInsights.put(SolrIndexEngine.LAYOUT, layout);
		solrInsights.put(SolrIndexEngine.CREATED_ON, currDate);
		solrInsights.put(SolrIndexEngine.MODIFIED_ON, currDate);
		solrInsights.put(SolrIndexEngine.CORE_ENGINE, engineName);
		solrInsights.put(SolrIndexEngine.CORE_ENGINE_ID, Integer.parseInt(newInsightID));

		Set<String> engines = new HashSet<String>();
		for(DataMakerComponent newDmc : dmcList) {
			engines.add(newDmc.getEngine().getEngineName());
		}
		solrInsights.put(SolrIndexEngine.ENGINES, engines);

		//TODO: need to add users
		solrInsights.put(SolrIndexEngine.USER_ID, "default");
		
		try {
			SolrIndexEngine.getInstance().addInsight(engineName + "_" + newInsightID, solrInsights);
		} catch (KeyManagementException e) {
			e.printStackTrace();
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		} catch (KeyStoreException e) {
			e.printStackTrace();
		} catch (SolrServerException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}		
//		return Response.status(200).entity(WebUtility.getSO("Success")).build();
		return WebUtility.getResponse("success", 200);
	}
	
	@POST
	@Path("editFromText")
	@Produces("application/json")
	public Response editInsightFromText(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String engineName = coreEngine.getEngineName();

		LOGGER.info("Adding question from action with following details:::: " + form.toString());
		Gson gson = new Gson();
		String insightID = form.getFirst("insightID");
		String perspective = form.getFirst("perspective");
		String order = form.getFirst("order");
		String insightName = form.getFirst("insightName");
		String layout = form.getFirst("layout");
		//TODO: currently FE only passes a single query
		String query = form.getFirst("query");
		String uiOptions = form.getFirst("uiOptions");
		boolean isDbQuery = true;
		String dmName = form.getFirst("dmName");
		if(dmName == null || dmName.isEmpty()) {
			dmName = "TinkerFrame";
		} 
		
		Insight existingIn = coreEngine.getInsight(insightID).get(0);
		CacheFactory.getInsightCache(CacheFactory.CACHE_TYPE.DB_INSIGHT_CACHE).deleteInsightCache(existingIn);
		
		List<DataMakerComponent> dmcList = null;
		List<SEMOSSParam> params = null;
		// if query is defined, we are defining the insight the basic way -- just query and engine
		if(query != null && !query.isEmpty()) {
//			List<String> allSheets = PlaySheetRDFMapBasedEnum.getAllSheetNames();
//			dmName = InsightsConverter.getDataMaker(layout, allSheets);
			dmcList = new ArrayList<DataMakerComponent>();
			DataMakerComponent dmc = new DataMakerComponent(this.coreEngine, query);
			dmcList.add(dmc);
			Map<String, String> paramsInQuery = Utility.getParamTypeHash(query);
			
			// TODO: need to change the way this data is coming back.....
			Vector<String> parameterDependList = gson.fromJson(form.getFirst("parameterDependList"), Vector.class);
			Vector<String> parameterQueryList = gson.fromJson(form.getFirst("parameterQueryList"), Vector.class);
			Vector<String> parameterOptionList = gson.fromJson(form.getFirst("parameterOptionList"), Vector.class);
			// ....sad, need to define the parameter based on the engine type
			params = generateSEMOSSParamObjects(parameterDependList, parameterQueryList, parameterOptionList, paramsInQuery);
		} 
		// otherwise, we are defining the complex way -- with datamaker, insight makeup, layout, etc.
		else {
//			dmName =  form.getFirst("dmName");
			dmcList = existingIn.getDataMakerComponents();
			params = existingIn.getInsightParameters();
			
			// BELOW CODE IS FOR EDITING COMPONENTS VIA TEXT
			// CURRENTLY NOT ENABLED BECAUSE GETTING PARAMETERS FROM DMC LIST STILL NEEDS TO BE THOUGHT THROUGH
//			String insightMakeup = form.getFirst("insightMakeup");
//			Insight in = new Insight(coreEngine, dmName, layout);
//			InMemorySesameEngine myEng = buildMakeupEngine(insightMakeup);
//			if(myEng == null){
//				Map<String, String> errorHash = new HashMap<String, String>();
//				errorHash.put("errorMessage", "Error parsing through N-Triples insight makeup. Please make sure it is copied correctly and each triple ends with a \".\" and a line break.");
//				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
//			}
//			dmcList = in.digestNTriples(myEng);
//			params = getParamsFromDmcList(dmcList);
		}
		Map<String, String> dataTableAlign = gson.fromJson(form.getFirst("dataTableAlign"), Map.class);

		// for now use this method
		QuestionAdministrator questionAdmin = new QuestionAdministrator(this.coreEngine);
		questionAdmin.modifyQuestion(insightID, insightName, perspective, dmcList, layout, order, dmName, isDbQuery, dataTableAlign, params, uiOptions);
		
		Map<String, Object> solrInsights = new HashMap<>();
		DateFormat dateFormat = SolrIndexEngine.getDateFormat();
		Date date = new Date();
		String currDate = dateFormat.format(date);
		solrInsights.put(SolrIndexEngine.STORAGE_NAME, insightName);
		solrInsights.put(SolrIndexEngine.INDEX_NAME, insightName);
		solrInsights.put(SolrIndexEngine.TAGS, perspective);
		solrInsights.put(SolrIndexEngine.LAYOUT, layout);
		solrInsights.put(SolrIndexEngine.CREATED_ON, currDate);
		solrInsights.put(SolrIndexEngine.MODIFIED_ON, currDate);
		solrInsights.put(SolrIndexEngine.CORE_ENGINE, engineName);
		solrInsights.put(SolrIndexEngine.CORE_ENGINE_ID, Integer.parseInt(insightID));

		Set<String> engines = new HashSet<String>();
		for(DataMakerComponent newDmc : dmcList) {
			engines.add(newDmc.getEngine().getEngineName());
		}
		solrInsights.put(SolrIndexEngine.ENGINES, engines);

		//TODO: need to add users
		solrInsights.put(SolrIndexEngine.USER_ID, "default");
		
		try {
			SolrIndexEngine.getInstance().modifyInsight(engineName + "_" + insightID, solrInsights);
			//SolrIndexEngine.getInstance().addDocument(engine.getEngineName() + "_" + lastIDNum, solrInsights);
		} catch (KeyManagementException e) {
			e.printStackTrace();
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		} catch (KeyStoreException e) {
			e.printStackTrace();
		} catch (SolrServerException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}		
		
//		return Response.status(200).entity(WebUtility.getSO("Success")).build();
		return WebUtility.getResponse("Success", 200);
	}
	
	private List<SEMOSSParam> generateSEMOSSParamObjects(
			Vector<String> parameterDependList, 
			Vector<String> parameterQueryList, 
			Vector<String> parameterOptionList, 
			Map<String, String> paramsInQuery) 
	{
		List<SEMOSSParam> params = new ArrayList<SEMOSSParam>();
		for(String paramName : paramsInQuery.keySet()) {
			SEMOSSParam param = new SEMOSSParam();
			param.setName(paramName);
			
			for(String s : parameterDependList) {
				String[] split = s.split("_DEPEND_-_");
				if(split[0].equals(paramName)) {
					param.addDependVar(split[1]);
					param.setDepends("true");
				}
			}
			
			boolean foundInList = false;
			for(String s : parameterQueryList) {
				String[] split = s.split("_QUERY_-_");
				if(split[0].equals(paramName)) {
					param.setQuery(split[1]);
					foundInList = true;
				}
			}
			for(String s : parameterOptionList) {
				String[] split = s.split("_OPTION_-_");
				if(split[0].equals(paramName)) {
					param.setOptions(split[1]);
					foundInList = true;
				}
			}
			if(!foundInList) {
				param.setType(paramsInQuery.get(paramName));
			}
			
			params.add(param);
		}
		
		return params;
	}

	/**
	 * This method appends the parameter options to the DataMakerComponent metamodel
	 * @param paramMapList				The list of parameters to save.  Comes as a map with the URI and the parent if it is a property
	 * @param dmcList					The list of the DataMakerComponents for the insight
	 * @param params					A list of SEMOSSParams to store the parameters with the correct options
	 */
	private List<SEMOSSParam> buildParameterList(Insight insight, Vector<Map<String, String>> paramMapList) {
		if(insight.getDataMaker() instanceof Dashboard) return new Vector<SEMOSSParam>();
		Map<String, Object> metamodelData = insight.getInsightMetaModel();
//		IMetaData metaData = insight.getMetaData();
		List<SEMOSSParam> params = new Vector<SEMOSSParam>();
		if(paramMapList != null && !paramMapList.isEmpty()) {
			for(Map<String, String> paramMap : paramMapList) {
				String paramName = paramMap.get("name");
				String logicalParamURI = "http://semoss.org/ontologies/Concept/" + paramName;
//				String logicalParamURI = paramMap.get("value");
//				String paramURI = this.coreEngine.getTransformedNodeName(logicalParamURI, false);
				String paramURI = logicalParamURI;
				
				// get the paramParent if it is a property
				String paramParent = null;
				Map<String, Object> nodes = (Map<String, Object>) metamodelData.get("nodes");
				PARAM_TYPE_LOOP : for(String node : nodes.keySet()) {

					Map<String, Object> nodeMap = (Map<String, Object>) nodes.get(node);
					if(node.equals(paramName)) {
						if(nodeMap.containsKey("prop")){
							paramParent = ((ITableDataFrame)insight.getDataMaker()).getPhysicalUriForNode(nodeMap.get("prop") + "", this.coreEngine.getEngineName());
						}
						break PARAM_TYPE_LOOP;
					}
				}
					
				SEMOSSParam p = new SEMOSSParam();
				//TODO: Bifurcation in processing logic if it is RDBMS vs. RDF
				//Due to the fact that we do not store the parameters as metamodels but as queries
				if(this.coreEngine.getEngineType() == ENGINE_TYPE.RDBMS) {
					// the uri on rdbms is always in the form /Concept/Column/Table
//					String rdbmsType = Utility.getInstanceName(paramURI)+":"+Utility.getClassName(paramURI);  // THIS WILL BE TAKEN CARE OF IN THE ENGINE. we need the physical uri as the type to know which component is involved in question administrator
					p.setType(paramURI);
					if(paramParent != null) {
						p.setName(Utility.getInstanceName(paramParent) + "__" + paramName);
					} else {
						p.setName(paramName);
					}
				} else {
					p.setName(paramName);
					p.setType(paramURI);
					if(paramParent != null) {
//						String paramParentURI = this.coreEngine.getTransformedNodeName(paramParent, false);
						String paramParentURI = paramParent;
						// if it is a property, we need to define a unique query which pulls up values for the property based on the parent
						String query = "SELECT DISTINCT ?entity WHERE { {?x <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <" + paramParentURI + "> } "
								+ "{ ?x <" + paramURI + "> ?entity} }";
						p.setQuery(query);
					} else {
						// if it is a concept, just run getEntityOfType query with the concepts URI
						p.setType(paramURI);
					}
				}
				params.add(p);
				//Param still needs to have id set once we know what component it goes on
				//this will happen in question administrator
			}
		}
		return params;
	}
	
	/**
	 * 
	 * @param filterModel
	 * @return
	 * 
	 * Creates a list of filter transformations based on the filter model
	 */
	private List<FilterTransformation> flushFilterModel2Transformations(ITableDataFrame table) {
		List<FilterTransformation> transformationList = null;

		Map<String, Object[]> filterModel = table.getFilterTransformationValues();
		if(filterModel != null) {
			Set<String> columns = filterModel.keySet();
			transformationList = new ArrayList<>(columns.size());
			for(String column : columns) {
			
				FilterTransformation filterTrans = new FilterTransformation();
				filterTrans.setTransformationType(false);
				Map<String, Object> selectedOptions = new HashMap<String, Object>();
				selectedOptions.put(FilterTransformation.COLUMN_HEADER_KEY, column);
				selectedOptions.put(FilterTransformation.VALUES_KEY, filterModel.get(column));
				filterTrans.setProperties(selectedOptions);
			
				transformationList.add(filterTrans);
			}
		}
		return transformationList;

	}
	
}
