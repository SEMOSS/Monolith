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

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrServerException;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFParseException;
import org.openrdf.sail.inferencer.fc.ForwardChainingRDFSInferencer;
import org.openrdf.sail.memory.MemoryStore;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import prerna.ds.BTreeDataFrame;
import prerna.engine.api.IEngine.ENGINE_TYPE;
import prerna.engine.impl.AbstractEngine;
import prerna.engine.impl.InsightsConverter;
import prerna.engine.impl.QuestionAdministrator;
import prerna.engine.impl.rdf.InMemorySesameEngine;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.SEMOSSParam;
import prerna.solr.SolrIndexEngine;
import prerna.ui.components.playsheets.datamakers.DataMakerComponent;
import prerna.ui.components.playsheets.datamakers.FilterTransformation;
import prerna.ui.components.playsheets.datamakers.IDataMaker;
import prerna.ui.components.playsheets.datamakers.ISEMOSSTransformation;
import prerna.util.PlaySheetRDFMapBasedEnum;
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
		String engineName = coreEngine.getEngineName();
		
		LOGGER.info("Adding question from action with following details:::: " + form.toString());
		Gson gson = new Gson();
		String perspective = form.getFirst("perspective");
		String order = form.getFirst("order");
		String insightName = form.getFirst("insightName");
		String description = form.getFirst("description");
		String layout = form.getFirst("layout");
		String insightID = form.getFirst("insightID");
		Map<String, String> dataTableAlign = gson.fromJson(form.getFirst("dataTableAlign"), Map.class);

		//TODO: currently not exposed through UI
		boolean isDbQuery = true;
		
		QuestionAdministrator questionAdmin = new QuestionAdministrator(this.coreEngine);
		Insight insight = InsightStore.getInstance().get(insightID);
		List<DataMakerComponent> dmcList = insight.getDataMakerComponents();
		Vector<Map<String, String>> paramMapList = gson.fromJson(form.getFirst("parameterQueryList"), new TypeToken<Vector<Map<String, String>>>() {}.getType());
		List<SEMOSSParam> params = buildParameterList(paramMapList);
		
		//Add necessary filter transformations
		IDataMaker dm = insight.getDataMaker();
		String newInsightID = null;
		if(dm instanceof BTreeDataFrame) {
			//add list of new filter transformations to the last component
			DataMakerComponent lastComponent = dmcList.get(dmcList.size() - 1);
			List<ISEMOSSTransformation> newPostTrans = lastComponent.getPostTrans();
			List<ISEMOSSTransformation> oldPostTrans = new Vector<ISEMOSSTransformation>(newPostTrans);
			List<FilterTransformation> trans2add = flushFilterModel2Transformations((BTreeDataFrame) dm);
			newPostTrans.addAll(trans2add);

			newInsightID = questionAdmin.addQuestion(insightName, perspective, dmcList, layout, order, insight.getDataMakerName(), isDbQuery, dataTableAlign, params);

			//reset the post trans on the last component if the filter model has been flushed to it
			//we don't want the insight itself to change at all through this process
			lastComponent.setPostTrans(oldPostTrans);
		}
		else {
			newInsightID = questionAdmin.addQuestion(insightName, perspective, dmcList, layout, order, insight.getDataMakerName(), isDbQuery, dataTableAlign, params);
		}
				
		Map<String, Object> solrInsights = new HashMap<>();
		DateFormat dateFormat = SolrIndexEngine.getDateFormat();
		Date date = new Date();
		String currDate = dateFormat.format(date);
		solrInsights.put(SolrIndexEngine.NAME, insightName);
		solrInsights.put(SolrIndexEngine.TAGS, perspective);
		solrInsights.put(SolrIndexEngine.LAYOUT, layout);
		solrInsights.put(SolrIndexEngine.CREATED_ON, currDate);
		solrInsights.put(SolrIndexEngine.MODIFIED_ON, currDate);
		solrInsights.put(SolrIndexEngine.CORE_ENGINE, engineName);
		solrInsights.put(SolrIndexEngine.CORE_ENGINE_ID, Integer.parseInt(newInsightID));

		Set<String> engines = new HashSet<String>();
		for(DataMakerComponent dmc : dmcList) {
			engines.add(dmc.getEngine().getEngineName());
		}
		solrInsights.put(SolrIndexEngine.ENGINES, engines);

		//TODO: need to add users
		solrInsights.put(SolrIndexEngine.USER_ID, "default");
		
		try {
			SolrIndexEngine.getInstance().addDocument(engineName + "_" + newInsightID, solrInsights);
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
	
	@POST
	@Path("editFromAction")
	@Produces("application/json")
	public Response editInsight(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String engineName = coreEngine.getEngineName();

		LOGGER.info("Editing question from action with following details:::: " + form.toString());
		Gson gson = new Gson();
		String perspective = form.getFirst("perspective");
		String insightID = form.getFirst("insightID");
		String order = form.getFirst("order");
		String insightName = form.getFirst("insightName");
		String layout = form.getFirst("layout");
		Map<String, String> dataTableAlign = gson.fromJson(form.getFirst("dataTableAlign"), Map.class);
		
		//TODO: currently not exposed through UI
		boolean isDbQuery = true;
		
		QuestionAdministrator questionAdmin = new QuestionAdministrator(this.coreEngine);
		Insight insight = InsightStore.getInstance().get(insightID);
		String rdbmsId = insight.getRdbmsId();
		List<DataMakerComponent> dmcList = insight.getDataMakerComponents();
		
		Vector<Map<String, String>> paramMapList = gson.fromJson(form.getFirst("parameterQueryList"), new TypeToken<Vector<Map<String, String>>>() {}.getType());
		List<SEMOSSParam> params = buildParameterList(paramMapList);
		questionAdmin.modifyQuestion(rdbmsId, insightName, perspective, dmcList, layout, order, insight.getDataMakerName(), isDbQuery, dataTableAlign, params);

		DateFormat dateFormat = SolrIndexEngine.getDateFormat();
		Date date = new Date();
		String currDate = dateFormat.format(date);
		Map<String, Object> solrModifyInsights = new HashMap<>();
		solrModifyInsights.put(SolrIndexEngine.NAME, insightName);
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
			SolrIndexEngine.getInstance().modifyFields(engineName + "_" + rdbmsId, solrModifyInsights);
			//SolrIndexEngine.getInstance().modifyFields(engine.getEngineName() + "_" + insightID, solrModifyInsights);
		} catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | SolrServerException
				| IOException e) {
			e.printStackTrace();
		}
		
		return Response.status(200).entity(WebUtility.getSO("Success")).build();
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
			SolrIndexEngine.getInstance().removeDocument(removeList);
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
		boolean isDbQuery = true;

		String dmName = "";
		List<DataMakerComponent> dmcList = null;
		List<SEMOSSParam> params = null;
		// if query is defined, we are defining the insight the basic way -- just query and engine
		if(query != null && !query.isEmpty()) {
			List<String> allSheets = PlaySheetRDFMapBasedEnum.getAllSheetNames();
			dmName = InsightsConverter.getDataMaker(layout, allSheets);
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
			dmName =  form.getFirst("dmName");
			String insightMakeup = form.getFirst("insightMakeup");
			Insight in = new Insight(coreEngine, dmName, layout);
			InMemorySesameEngine myEng = buildMakeupEngine(insightMakeup);
			if(myEng == null){
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "Error parsing through N-Triples insight makeup. Please make sure it is copied correctly and each triple ends with a \".\" and a line break.");
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
			dmcList = in.digestNTriples(myEng);
			params = getParamsFromDmcList(dmcList);
		}
		Map<String, String> dataTableAlign = gson.fromJson(form.getFirst("dataTableAlign"), Map.class);

		// for now use this method
		QuestionAdministrator questionAdmin = new QuestionAdministrator(this.coreEngine);
		String newInsightID = questionAdmin.addQuestion(insightName, perspective, dmcList, layout, order, dmName, isDbQuery, dataTableAlign, params);
		
		Map<String, Object> solrInsights = new HashMap<>();
		DateFormat dateFormat = SolrIndexEngine.getDateFormat();
		Date date = new Date();
		String currDate = dateFormat.format(date);
		solrInsights.put(SolrIndexEngine.NAME, insightName);
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
			SolrIndexEngine.getInstance().addDocument(engineName + "_" + newInsightID, solrInsights);
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
		boolean isDbQuery = true;

		String dmName = "";
		List<DataMakerComponent> dmcList = null;
		List<SEMOSSParam> params = null;
		// if query is defined, we are defining the insight the basic way -- just query and engine
		if(query != null && !query.isEmpty()) {
			List<String> allSheets = PlaySheetRDFMapBasedEnum.getAllSheetNames();
			dmName = InsightsConverter.getDataMaker(layout, allSheets);
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
			dmName =  form.getFirst("dmName");
			String insightMakeup = form.getFirst("insightMakeup");
			Insight in = new Insight(coreEngine, dmName, layout);
			InMemorySesameEngine myEng = buildMakeupEngine(insightMakeup);
			if(myEng == null){
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "Error parsing through N-Triples insight makeup. Please make sure it is copied correctly and each triple ends with a \".\" and a line break.");
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
			dmcList = in.digestNTriples(myEng);
			params = getParamsFromDmcList(dmcList);
		}
		Map<String, String> dataTableAlign = gson.fromJson(form.getFirst("dataTableAlign"), Map.class);

		// for now use this method
		QuestionAdministrator questionAdmin = new QuestionAdministrator(this.coreEngine);
		questionAdmin.modifyQuestion(insightID, insightName, perspective, dmcList, layout, order, dmName, isDbQuery, dataTableAlign, params);
		
		Map<String, Object> solrInsights = new HashMap<>();
		DateFormat dateFormat = SolrIndexEngine.getDateFormat();
		Date date = new Date();
		String currDate = dateFormat.format(date);
		solrInsights.put(SolrIndexEngine.NAME, insightName);
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
			SolrIndexEngine.getInstance().modifyFields(engineName + "_" + insightID, solrInsights);
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
	private List<SEMOSSParam> buildParameterList(Vector<Map<String, String>> paramMapList) {
		List<SEMOSSParam> params = new Vector<SEMOSSParam>();
		if(paramMapList != null && !paramMapList.isEmpty()) {
			for(Map<String, String> paramMap : paramMapList) {
				String paramURI = paramMap.get("value");
				String paramParent = paramMap.get("parent");
				String paramName = paramMap.get("name");

				SEMOSSParam p = new SEMOSSParam();
				//TODO: Bifurcation in processing logic if it is RDBMS vs. RDF
				//Due to the fact that we do not store the parameters as metamodels but as queries
				if(this.coreEngine.getEngineType() == ENGINE_TYPE.RDBMS) {
					// the uri on rdbms is always in the form /Concept/Column/Table
//					String rdbmsType = Utility.getInstanceName(paramURI)+":"+Utility.getClassName(paramURI);  // THIS WILL BE TAKEN CARE OF IN THE ENGINE. we need the physical uri as the type to know which component is involved in question administrator
					p.setType(paramURI);
					p.setName(paramName);
				} else {
					p.setName(paramName);
					if(paramParent != null) {
						// if it is a property, we need to define a unique query which pulls up values for the property based on the parent
						String query = "SELECT DISTINCT ?entity WHERE { {?x <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <" + paramParent + "> } "
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
	private List<FilterTransformation> flushFilterModel2Transformations(BTreeDataFrame bTree) {
		Map<String, Object[]> filterModel = bTree.getFilterTransformationValues();
		Set<String> columns = filterModel.keySet();
		List<FilterTransformation> transformationList = new ArrayList<>(columns.size());
		for(String column : columns) {
		
			FilterTransformation filterTrans = new FilterTransformation();
			filterTrans.setTransformationType(false);
			Map<String, Object> selectedOptions = new HashMap<String, Object>();
			selectedOptions.put(FilterTransformation.COLUMN_HEADER_KEY, column);
			selectedOptions.put(FilterTransformation.VALUES_KEY, filterModel.get(column));
			filterTrans.setProperties(selectedOptions);
		
			transformationList.add(filterTrans);
		}
		
		return transformationList;
	}
	
	private InMemorySesameEngine buildMakeupEngine(String insightMakeup){
		RepositoryConnection rc = null;
		boolean correctMakeup = true;
		try {
			Repository myRepository = new SailRepository(new ForwardChainingRDFSInferencer(new MemoryStore()));
			myRepository.initialize();
			rc = myRepository.getConnection();
			rc.add(IOUtils.toInputStream(insightMakeup) , "semoss.org", RDFFormat.NTRIPLES);
		} catch(RuntimeException e) {
			e.printStackTrace();
			correctMakeup = false;
		} catch (RDFParseException e) {
			e.printStackTrace();
			correctMakeup = false;
		} catch (RepositoryException e) {
			e.printStackTrace();
			correctMakeup = false;
		} catch (IOException e) {
			e.printStackTrace();
			correctMakeup = false;
		}
		
		if(!correctMakeup) {
			LOGGER.error("Error parsing through N-Triples insight makeup. Please make sure it is copied correctly and each triple ends with a \".\" and a line break.");
			return null;
		}
		
		// set the rc in the in-memory engine
		InMemorySesameEngine myEng = new InMemorySesameEngine();
		myEng.setRepositoryConnection(rc);
		return myEng;
	}
	
	/**
	 * This method uses the dmc list to determine which paramters need to be added to the rdbms
	 * The logic is simple : 
	 * 	Any filter transformation without a list of values set needs to be stored as param
	 * 
	 * @param dmcList
	 * @return
	 */
	private List<SEMOSSParam> getParamsFromDmcList(List<DataMakerComponent> dmcList){
		List<SEMOSSParam> params = new Vector<SEMOSSParam>();
		for (DataMakerComponent dmc : dmcList){
			List<ISEMOSSTransformation> fullTrans = new Vector<ISEMOSSTransformation>();
			fullTrans.addAll(dmc.getPreTrans());
			fullTrans.addAll(dmc.getPostTrans());
			for(ISEMOSSTransformation trans : fullTrans){
				if(trans instanceof FilterTransformation){
					if(!trans.getProperties().containsKey(FilterTransformation.VALUES_KEY)){
						SEMOSSParam p = new SEMOSSParam();
						params.add(p);
						p.setName(trans.getProperties().get(FilterTransformation.COLUMN_HEADER_KEY) + "");
						LOGGER.info("adding new param with name : " + p.getName());
						
						// type needs to be gotten from the query or the metamodel data
						String query = dmc.getQuery();
						if(query!=null){
							LOGGER.info("getting param type from query : " + query);
							Map<String, String> paramMap = Utility.getParams(query);
							for(String key : paramMap.keySet()){
								LOGGER.info("checking param : " + key);
								// this key should be label-type
								String[] parts = key.split("-");
								LOGGER.info("does param type : " + parts[0] + " match with param name?");
								if(parts[0].equals(p.getName())){
									LOGGER.info("yep... setting type to  " + parts [1]);
									p.setType(parts[1]);
								}
							}
						}
						else {
							Map<String, Object> mm = dmc.getMetamodelData();
							LOGGER.info("getting param type from metamodel data : " + mm.toString());
						}
					}
				}
			}
		}
		return params;
	}

}
