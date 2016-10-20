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
import prerna.util.Constants;
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
		Gson gson = new Gson();
		String perspective = form.getFirst("perspective");
		String order = form.getFirst("order");
		String insightName = form.getFirst("insightName");
		String description = form.getFirst("description");
		String layout = form.getFirst("layout");
		String insightID = form.getFirst("insightID");
		String uiOptions = form.getFirst("uiOptions");
		Map<String, String> dataTableAlign = gson.fromJson(form.getFirst("dataTableAlign"), Map.class);
		List<String> pkqlsToAdd = gson.fromJson(form.getFirst("pkqlsToAdd"), List.class);

		Insight insight = InsightStore.getInstance().get(insightID);
//		boolean isNonDbInsight = !insight.isDbInsight();
//		
		String newInsightID = "";
//		if(isNonDbInsight) {
//			// we need to create a full db now
//			// do it based on the csv file name and the date
//			
//			// TODO: right now, we make an assumption that we are only using one csv file
//			String fileLocation = insight.getFilesUsedInInsight().get(0);
//			String origFileName = Utility.getOriginalFileName(fileLocation).replace(".csv", "");
//			// right now, we need the engine to be a valid pkql id (alphanumeric + underscore values only)
//			// clean table name does just that
//			origFileName = RDBMSEngineCreationHelper.cleanTableName(origFileName);
//			
//			List<String> engines = MasterDBHelper.getAllEngines((IEngine)DIHelper.getInstance().getLocalProp(Constants.LOCAL_MASTER_DB_NAME));
//			int counter = 1;
//			String engineName = origFileName;
//			while(engines.contains(engineName)) {
//				engineName = origFileName + "_" + counter;
//				counter++;
//			}
//			
//			InsightFilesToDatabaseReader creator = new InsightFilesToDatabaseReader();
//			IEngine newEngine = creator.processInsightFiles(insight, engineName);
//			
//			// we also need to update the receipe to now query from the new db instead of from the file
//			DataMakerComponent firstComp = insight.getDataMakerComponents().get(0);			
//			// first we need to confirm that the first thing is a pkql transformation
//			List<ISEMOSSTransformation> postTrans = firstComp.getPostTrans();
//			
//			for(int transIdx = 0; transIdx < postTrans.size(); transIdx++) {
//				ISEMOSSTransformation firstTrans = postTrans.get(transIdx);
//				if(!(firstTrans instanceof PKQLTransformation)) {
//					continue;
//				}
//				
//				// okay, so its a pkql transformation so we can now do the check
//				
//				PKQLTransformation pkqlTrans = (PKQLTransformation) firstTrans;
//				List<String> listPkqlRun = pkqlTrans.getPkql();
//				for(int pkqlIdx = 0; pkqlIdx < listPkqlRun.size(); pkqlIdx++) {
//					String pkqlExp = listPkqlRun.get(pkqlIdx);
//					pkqlExp = pkqlExp.replace(" ", "");
//					
//					// ugh, the bad string manipulation check :(
//					if(pkqlExp.startsWith("data.import(api:csvFile.query")) {
//						// we update the prop in the pkql transformation to be the engine load instead of csv file upload
//						Map<String, Object> props = pkqlTrans.getProperties();
//						props.put(PKQLTransformation.EXPRESSION, "data.import(api:" + engineName + ".query());");
//					}
//				}
//			}
//			
//			// ASSUMPTION!!!
//			// if the core engine is null
//			// it means they want to save this insight into the newly created engine
//			if(this.coreEngine == null) {
//				this.coreEngine = (AbstractEngine) newEngine;
//			}
//			
//			//TODO: assume person will not have parameters
////			newInsightID = addInsightDMCache(insight, insightName, perspective, layout, dataTableAlign, uiOptions);
////			Map<String, Object> retMap = new HashMap<String, Object>();
////			retMap.put("newInsightID", newInsightID);
////			if (newInsightID != null) {
////				return Response.status(200).entity(WebUtility.getSO(retMap)).build();
////			} else {
////				return Response.status(500).entity(WebUtility.getSO("Error adding insight")).build();
////			}
////		} else {
//		}
			Vector<Map<String, String>> paramMapList = gson.fromJson(form.getFirst("parameterQueryList"), new TypeToken<Vector<Map<String, String>>>() {}.getType());
			
			//If saving with params, FE passes a user.input PKQL that needs to be added/saved - better way to do this?
			if(pkqlsToAdd != null && !pkqlsToAdd.isEmpty()) {
				PKQLRunner runner = insight.getPKQLRunner();
				DataMakerComponent dmc;
				if(insight.getDataMaker() instanceof Dashboard) {
					dmc = insight.getDashboardDataMakerComponent();
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
			newInsightID = addInsightFromDb(insight, insightName, perspective, order, layout, uiOptions, dataTableAlign, paramMapList);
			Map<String, Object> retMap = new HashMap<String, Object>();
//			retMap.put("newInsightID", newInsightID);
			retMap.put("core_engine", this.coreEngine.getEngineName());
			retMap.put("core_engine_id", newInsightID);
			retMap.put("insightName", insightName);
			if (newInsightID != null) {
				return Response.status(200).entity(WebUtility.getSO(retMap)).build();
			} else {
				return Response.status(500).entity(WebUtility.getSO("Error adding insight")).build();
			}
//		}		
	}
	
//	private String addInsightDMCache(Insight insight, String insightName, String perspective, String layout, Map<String, String> dataTableAlign, String uiOptions) {
//		String uniqueID = UUID.randomUUID().toString();
//		insight.setDatabaseID(uniqueID);
//		insight.setInsightName(insightName);
//		insight.setOutput(layout);
//		insight.setDataTableAlign(dataTableAlign);
//		insight.setUiOptions(uiOptions);
//		insight.setIsDbInsight(true);
//		
//		String saveFileLocation = CacheFactory.getInsightCache(CacheFactory.CACHE_TYPE.CSV_CACHE).cacheInsight(insight);
//
//		Map<String, Object> solrInsights = new HashMap<>();
//		DateFormat dateFormat = SolrIndexEngine.getDateFormat();
//		Date date = new Date();
//		String currDate = dateFormat.format(date);
//		solrInsights.put(SolrIndexEngine.STORAGE_NAME, insightName);
//		solrInsights.put(SolrIndexEngine.INDEX_NAME, insightName);
//		solrInsights.put(SolrIndexEngine.TAGS, perspective);
//		solrInsights.put(SolrIndexEngine.LAYOUT, layout);
//		solrInsights.put(SolrIndexEngine.CREATED_ON, currDate);
//		solrInsights.put(SolrIndexEngine.MODIFIED_ON, currDate);
//		solrInsights.put(SolrIndexEngine.CORE_ENGINE, Constants.LOCAL_MASTER_DB_NAME);
//		solrInsights.put(SolrIndexEngine.CORE_ENGINE_ID, uniqueID);
//		solrInsights.put(SolrIndexEngine.NON_DB_INSIGHT, true);
//		Set<String> engines = new HashSet<String>();
//		engines.add(Constants.LOCAL_MASTER_DB_NAME);
//		solrInsights.put(SolrIndexEngine.ENGINES, engines);
//		solrInsights.put(SolrIndexEngine.DATAMAKER_NAME, insight.getDataMakerName());
//		
//		//TODO: need to add users
//		solrInsights.put(SolrIndexEngine.USER_ID, "default");
//		
//		try {
//			SolrIndexEngine.getInstance().addInsight(uniqueID, solrInsights);
//			saveFileLocation = saveFileLocation + "_Solr.txt";
//			File solrFile = new File(saveFileLocation);
//			SolrDocumentExportWriter writer = new SolrDocumentExportWriter(solrFile);
//			writer.writeSolrDocument(uniqueID, solrInsights);
//			writer.closeExport();
//			return uniqueID;
//		} catch (KeyManagementException e) {
//			e.printStackTrace();
//		} catch (NoSuchAlgorithmException e) {
//			e.printStackTrace();
//		} catch (KeyStoreException e) {
//			e.printStackTrace();
//		} catch (SolrServerException e) {
//			e.printStackTrace();
//		} catch (IOException e) {
//			e.printStackTrace();
//		}
//		return null;
//	}

	private String addInsightFromDb(Insight insight, String insightName, String perspective, String order, String layout, String uiOptions, Map<String, String> dataTableAlign, Vector<Map<String, String>> paramMapList) {
		//TODO: currently not exposed through UI
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
		} else if(dm instanceof Dashboard) {
			dmcList = new ArrayList<>();
			Dashboard dash = (Dashboard)dm;
			DataMakerComponent lastComponent = insight.getDashboardDataMakerComponent();
			dmcList.add(lastComponent);
			List<ISEMOSSTransformation> newPostTrans = lastComponent.getPostTrans();
			List<ISEMOSSTransformation> oldPostTrans = new Vector<ISEMOSSTransformation>(newPostTrans);

			newInsightID = questionAdmin.addQuestion(insightName, perspective, dmcList, layout, order, insight.getDataMakerName(), isDbQuery, dataTableAlign, params, uiOptions);
		}
		else {
			newInsightID = questionAdmin.addQuestion(insightName, perspective, dmcList, layout, order, insight.getDataMakerName(), isDbQuery, dataTableAlign, params, uiOptions);
		}
				
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
		solrInsights.put(SolrIndexEngine.DATAMAKER_NAME, insight.getDataMakerName());

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
		Gson gson = new Gson();
		String insightID = form.getFirst("insightID");
		String perspective = form.getFirst("perspective");
		String order = form.getFirst("order");
		String insightName = form.getFirst("insightName");
		String layout = form.getFirst("layout");
		String uiOptions = form.getFirst("uiOptions");
		Map<String, String> dataTableAlign = gson.fromJson(form.getFirst("dataTableAlign"), Map.class);
		
		Insight insight = InsightStore.getInstance().get(insightID);
//		boolean isNonDbInsight = !insight.isDbInsight();
//		if(isNonDbInsight) {
//			//TODO: assume person will not have parameters
//			editInsightDMCache(insight, insightName, perspective, layout, dataTableAlign, uiOptions);
//		} else {
			Vector<Map<String, String>> paramMapList = gson.fromJson(form.getFirst("parameterQueryList"), new TypeToken<Vector<Map<String, String>>>() {}.getType());
			editInsightFromDb(insight, insightName, perspective, order, layout, uiOptions, dataTableAlign, paramMapList);
//		}
		
		return Response.status(200).entity(WebUtility.getSO("Success")).build();
	}

//	private void editInsightDMCache(Insight insight, String insightName, String perspective, String layout, Map<String, String> dataTableAlign, String uiOptions) {
//		String uniqueID = insight.getDatabaseID();
//		
//		// delete existing cache
//		// do this before overriding with new insight metadata
//		InsightCache inCache = CacheFactory.getInsightCache(CacheFactory.CACHE_TYPE.CSV_CACHE);
//		inCache.deleteCacheFiles(insight);
//		
//		insight.setInsightName(insightName);
//		insight.setOutput(layout);
//		insight.setDataTableAlign(dataTableAlign);
//		insight.setUiOptions(uiOptions);
//		insight.setIsDbInsight(false);
//
//		// save new cache
//		String saveFileLocation = inCache.cacheInsight(insight);
//
//		DateFormat dateFormat = SolrIndexEngine.getDateFormat();
//		Date date = new Date();
//		String currDate = dateFormat.format(date);
//		Map<String, Object> solrModifyInsights = new HashMap<>();
//		solrModifyInsights.put(SolrIndexEngine.STORAGE_NAME, insightName);
//		solrModifyInsights.put(SolrIndexEngine.INDEX_NAME, insightName);
//		solrModifyInsights.put(SolrIndexEngine.TAGS, perspective);
//		solrModifyInsights.put(SolrIndexEngine.LAYOUT, layout);
//		solrModifyInsights.put(SolrIndexEngine.MODIFIED_ON, currDate);
//		solrModifyInsights.put(SolrIndexEngine.CORE_ENGINE, Constants.LOCAL_MASTER_DB_NAME);
//		solrModifyInsights.put(SolrIndexEngine.CORE_ENGINE_ID, uniqueID);
//		solrModifyInsights.put(SolrIndexEngine.NON_DB_INSIGHT, true);
//		Set<String> engines = new HashSet<String>();
//		engines.add(Constants.LOCAL_MASTER_DB_NAME);
//		solrModifyInsights.put(SolrIndexEngine.ENGINES, engines);
//		//TODO: need to add users
//		solrModifyInsights.put(SolrIndexEngine.USER_ID, "default");
//
//		try {
//			solrModifyInsights = SolrIndexEngine.getInstance().modifyInsight(uniqueID, solrModifyInsights);
//			saveFileLocation = saveFileLocation + "_Solr.txt";
//			File solrFile = new File(saveFileLocation);
//			if(solrFile.exists()) {
//				FileUtils.forceDelete(solrFile);
//			}
//			SolrDocumentExportWriter writer = new SolrDocumentExportWriter(solrFile);
//			writer.writeSolrDocument(uniqueID, solrModifyInsights);
//			writer.closeExport();
//		} catch (KeyManagementException e) {
//			e.printStackTrace();
//		} catch (NoSuchAlgorithmException e) {
//			e.printStackTrace();
//		} catch (KeyStoreException e) {
//			e.printStackTrace();
//		} catch (SolrServerException e) {
//			e.printStackTrace();
//		} catch (IOException e) {
//			e.printStackTrace();
//		}
//		
//	}

	private void editInsightFromDb(Insight insight, String insightName, String perspective, String order, String layout, String uiOptions, Map<String, String> dataTableAlign, Vector<Map<String, String>> paramMapList) {
		//TODO: currently not exposed through UI
		boolean isDbQuery = true;

		// delete existing cache folder for insight if present
		CacheFactory.getInsightCache(CacheFactory.CACHE_TYPE.DB_INSIGHT_CACHE).deleteCacheFolder(insight);
		
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
		solrModifyInsights.put(SolrIndexEngine.TAGS, perspective);
		solrModifyInsights.put(SolrIndexEngine.LAYOUT, layout);
		solrModifyInsights.put(SolrIndexEngine.MODIFIED_ON, currDate);
		solrModifyInsights.put(SolrIndexEngine.CORE_ENGINE, engineName);

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
		return Response.status(200).entity(WebUtility.getSO("Success")).build();
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
			
//			dmName =  form.getFirst("dmName");
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
		solrInsights.put(SolrIndexEngine.DATAMAKER_NAME, dmName);

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
		return Response.status(200).entity(WebUtility.getSO("Success")).build();
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
		CacheFactory.getInsightCache(CacheFactory.CACHE_TYPE.DB_INSIGHT_CACHE).deleteCacheFolder(existingIn);
		
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
		
		return Response.status(200).entity(WebUtility.getSO("Success")).build();
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
				String logicalParamURI = Constants.DISPLAY_URI + paramName;
//				String logicalParamURI = paramMap.get("value");
				String paramURI = this.coreEngine.getTransformedNodeName(logicalParamURI, false);
				
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
						String paramParentURI = this.coreEngine.getTransformedNodeName(paramParent, false);
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
	
//	private InMemorySesameEngine buildMakeupEngine(String insightMakeup){
//		RepositoryConnection rc = null;
//		boolean correctMakeup = true;
//		try {
//			Repository myRepository = new SailRepository(new ForwardChainingRDFSInferencer(new MemoryStore()));
//			myRepository.initialize();
//			rc = myRepository.getConnection();
//			rc.add(IOUtils.toInputStream(insightMakeup) , "semoss.org", RDFFormat.NTRIPLES);
//		} catch(RuntimeException e) {
//			e.printStackTrace();
//			correctMakeup = false;
//		} catch (RDFParseException e) {
//			e.printStackTrace();
//			correctMakeup = false;
//		} catch (RepositoryException e) {
//			e.printStackTrace();
//			correctMakeup = false;
//		} catch (IOException e) {
//			e.printStackTrace();
//			correctMakeup = false;
//		}
//		
//		if(!correctMakeup) {
//			LOGGER.error("Error parsing through N-Triples insight makeup. Please make sure it is copied correctly and each triple ends with a \".\" and a line break.");
//			return null;
//		}
//		
//		// set the rc in the in-memory engine
//		InMemorySesameEngine myEng = new InMemorySesameEngine();
//		myEng.setRepositoryConnection(rc);
//		return myEng;
//	}
	
//	/**
//	 * This method uses the dmc list to determine which paramters need to be added to the rdbms
//	 * The logic is simple : 
//	 * 	Any filter transformation without a list of values set needs to be stored as param
//	 * 
//	 * @param dmcList
//	 * @return
//	 */
//	private List<SEMOSSParam> getParamsFromDmcList(List<DataMakerComponent> dmcList){
//		List<SEMOSSParam> params = new Vector<SEMOSSParam>();
//		for (DataMakerComponent dmc : dmcList){
//			List<ISEMOSSTransformation> fullTrans = new Vector<ISEMOSSTransformation>();
//			fullTrans.addAll(dmc.getPreTrans());
//			fullTrans.addAll(dmc.getPostTrans());
//			for(ISEMOSSTransformation trans : fullTrans){
//				if(trans instanceof FilterTransformation){
//					if(!trans.getProperties().containsKey(FilterTransformation.VALUES_KEY)){
//						SEMOSSParam p = new SEMOSSParam();
//						params.add(p);
//						p.setName(trans.getProperties().get(FilterTransformation.COLUMN_HEADER_KEY) + "");
//						LOGGER.info("adding new param with name : " + p.getName());
//						
//						// type needs to be gotten from the query or the metamodel data
//						String query = dmc.getQuery();
//						if(query!=null){
//							LOGGER.info("getting param type from query : " + query);
//							Map<String, String> paramMap = Utility.getParams(query);
//							for(String key : paramMap.keySet()){
//								LOGGER.info("checking param : " + key);
//								// this key should be label-type
//								String[] parts = key.split("-");
//								LOGGER.info("does param type : " + parts[0] + " match with param name?");
//								if(parts[0].equals(p.getName())){
//									LOGGER.info("yep... setting type to  " + parts [1]);
//									p.setType(parts[1]);
//								}
//							}
//						}
//						else {
//							Map<String, Object> mm = dmc.getMetamodelData();
//							LOGGER.info("getting param type from metamodel data : " + mm.toString());
//						}
//					}
//				}
//			}
//		}
//		return params;
//	}

}
