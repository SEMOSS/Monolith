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

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import prerna.engine.api.IEngine.ENGINE_TYPE;
import prerna.engine.impl.AbstractEngine;
import prerna.engine.impl.QuestionAdministrator;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.SEMOSSParam;
import prerna.ui.components.playsheets.datamakers.DataMakerComponent;
import prerna.ui.components.playsheets.datamakers.FilterTransformation;
import prerna.ui.components.playsheets.datamakers.ISEMOSSTransformation;
import prerna.ui.components.playsheets.datamakers.JoinTransformation;
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
		Map<String, String> dataTableAlign = gson.fromJson(form.getFirst("dataTableAlign"), Map.class);

		//TODO: currently not exposed through UI
		boolean isDbQuery = true;
		
		QuestionAdministrator questionAdmin = new QuestionAdministrator(this.coreEngine);
		try {
			Insight insight = InsightStore.getInstance().get(insightID);
			List<DataMakerComponent> dmcList = insight.getDataMakerComponents();
			List<SEMOSSParam> params = null;
			Vector<Map<String, String>> paramMapList = gson.fromJson(form.getFirst("parameterQueryList"), new TypeToken<Vector<Map<String, String>>>() {}.getType());
			if(paramMapList != null && !paramMapList.isEmpty()) {
				params = new Vector<SEMOSSParam>();
				appendParametersToMetaModel(paramMapList, dmcList, params);
			}
			fillInSelectedParams(params, dmcList, insight.getParamHash());
			questionAdmin.addQuestion(insightName, perspective, dmcList, layout, order, insight.getDataMakerName(), isDbQuery, dataTableAlign, params);
		}catch(RuntimeException e){
			System.out.println("caught exception while adding question.................");
			e.printStackTrace();
			return Response.status(500).entity(WebUtility.getSO(e.toString().substring(0, (e.toString().length() < MAX_CHAR)?e.toString().length():MAX_CHAR))).build();
		}

		return Response.status(200).entity(WebUtility.getSO("Success")).build();
	}
	
	@POST
	@Path("editFromAction")
	@Produces("application/json")
	public Response editInsight(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
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
		try{
			Insight insight = InsightStore.getInstance().get(insightID);
			List<DataMakerComponent> dmcList = insight.getDataMakerComponents();
			List<SEMOSSParam> params = null;
			Vector<Map<String, String>> paramMapList = gson.fromJson(form.getFirst("parameterQueryList"), new TypeToken<Vector<Map<String, String>>>() {}.getType());
			if(paramMapList != null && !paramMapList.isEmpty()) {
				params = new Vector<SEMOSSParam>();
				appendParametersToMetaModel(paramMapList, dmcList, params);
			}
			fillInSelectedParams(params, dmcList, insight.getParamHash());
			questionAdmin.modifyQuestion(insight.getRdbmsId(), insightName, perspective, insight.getDataMakerComponents(), layout, order, insight.getDataMakerName(), isDbQuery, dataTableAlign, params);
		}catch(RuntimeException e){
			System.out.println("caught exception while modifying question.................");
			e.printStackTrace();
			return Response.status(500).entity(WebUtility.getSO(e.toString().substring(0, (e.toString().length() < MAX_CHAR)?e.toString().length():MAX_CHAR))).build();
		}

		return Response.status(200).entity(WebUtility.getSO("Success")).build();
	}
	
	/**
	 * Fills in the query for selected parameters
	 * Example: if a user creates a question that takes in a parameter and wants to save the visualization created (with the selected parameter)
	 * 			We need to fill in the metamodel data/query with the selected instance to re-create it properly
	 * @param params					The SEMOSSParams that will stay as parameters for recreation of the insight
	 * @param dmcList					The list of DataMakerComponents which contain the filters
	 * @param paramHash					The selected parameters to get to the viz
	 */
	private void fillInSelectedParams(List<SEMOSSParam> params, List<DataMakerComponent> dmcList, Map<String, List<Object>> paramHash) {
		if(paramHash != null) {
			for(String selectedParamName : paramHash.keySet()) {
				boolean stillParam = false;
				// loop to make sure that the list of parameters that the user selected isn't still a parameter
				// user can select a parameter value and re-save the visualization with that column still as a parameter
				if(params != null) {
					STILL_PARAMS : for(SEMOSSParam p : params) {
						if(p.getName().equals(selectedParamName)) {
							stillParam = true;
							break STILL_PARAMS;
						}
					}
				}
				if(!stillParam) {
					for(DataMakerComponent dmc : dmcList) {
						Map<String, Object> mm = dmc.getMetamodelData();
						// if there is no metamodel data, fill in the query
						if(mm == null || mm.isEmpty()) {
							String query = dmc.getQuery();
							query = Utility.normalizeParam(query);
							query = Utility.fillParam(query, paramHash);
							dmc.setQuery(query);
						} else {
							// fill in the FilterTransformation with the selected values
							List<ISEMOSSTransformation> preTrans = dmc.getPreTrans();
							for(ISEMOSSTransformation trans : preTrans) {
								if(trans.getProperties().get(FilterTransformation.COLUMN_HEADER_KEY).equals(selectedParamName)) {
									trans.getProperties().put(FilterTransformation.VALUES_KEY, paramHash.get(selectedParamName));
									break;
								}
							}
						}
					}
				}
			}
		}
	}

	@POST
	@Path("delete")
	@Produces("application/json")
	public Response deleteInsight(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String insightID = form.getFirst("insightID");
		QuestionAdministrator questionAdmin = new QuestionAdministrator(this.coreEngine);
		try{
			questionAdmin.removeQuestion(insightID);
		}catch(RuntimeException e){
			System.out.println("caught exception while deleting question.................");
			e.printStackTrace();
			return Response.status(500).entity(WebUtility.getSO(e.toString().substring(0, (e.toString().length() < MAX_CHAR)?e.toString().length():MAX_CHAR))).build();
		}
		return Response.status(200).entity(WebUtility.getSO("Success")).build();
	}
	
	@POST
	@Path("addFromText")
	@Produces("application/json")
	public Response addInsightFromText(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		LOGGER.info("Adding question from action with following details:::: " + form.toString());
		Gson gson = new Gson();
		String perspective = form.getFirst("perspective");
		String order = form.getFirst("order");
		String insightName = form.getFirst("insightName");
		String description = form.getFirst("description");
		String layout = form.getFirst("layout");
		//TODO: currently FE only passes a single query
		String query = form.getFirst("query");
		
		//TODO: how do we determine the data maker?
		//TODO: assumption is Btree unless layout is Graph
		String dmName = "BTreeDataFrame";
		if(layout.equals("Graph")) {
			dmName = "GraphDataModel";
		}
		
		List<DataMakerComponent> dmcList = new ArrayList<DataMakerComponent>();
		DataMakerComponent dmc = new DataMakerComponent(this.coreEngine, query);
		dmcList.add(dmc);
		//TODO: is it possible for FE to pass this?
		Map<String, String> dataTableAlign = gson.fromJson(form.getFirst("dataTableAlign"), Map.class);
		//TODO: currently not exposed through UI
		boolean isDbQuery = true;
		
		// TODO: need to change the way this data is coming back.....
		Vector<String> parameterDependList = gson.fromJson(form.getFirst("parameterDependList"), Vector.class);
		Vector<String> parameterQueryList = gson.fromJson(form.getFirst("parameterQueryList"), Vector.class);
		Vector<String> parameterOptionList = gson.fromJson(form.getFirst("parameterOptionList"), Vector.class);
		Map<String, String> paramsInQuery = Utility.getParamTypeHash(query);
		// ....sad, need to define the parameter based on the engine type
		List<SEMOSSParam> params = generateSEMOSSParamObjects(parameterDependList, parameterQueryList, parameterOptionList, paramsInQuery);

		// for now use this method
		QuestionAdministrator questionAdmin = new QuestionAdministrator(this.coreEngine);
		try{
			questionAdmin.addQuestion(insightName, perspective, dmcList, layout, order, dmName, isDbQuery, dataTableAlign, params);
		}catch(RuntimeException e){
			System.out.println("caught exception while adding question.................");
			e.printStackTrace();
			return Response.status(500).entity(WebUtility.getSO(e.toString().substring(0, (e.toString().length() < MAX_CHAR)?e.toString().length():MAX_CHAR))).build();
		}

		return Response.status(200).entity(WebUtility.getSO("Success")).build();
	}
	
	/**
	 * This method appends the parameter options to the DataMakerComponent metamodel
	 * @param paramMapList				The list of parameters to save.  Comes as a map with the URI and the parent if it is a property
	 * @param dmcList					The list of the DataMakerComponents for the insight
	 * @param params					A list of SEMOSSParams to store the parameters with the correct options
	 */
	private void appendParametersToMetaModel(Vector<Map<String, String>> paramMapList, List<DataMakerComponent> dmcList, List<SEMOSSParam> params) {
		if(paramMapList != null && !paramMapList.isEmpty()) {
			for(Map<String, String> paramMap : paramMapList) {
				String paramURI = paramMap.get("value");
				String paramParent = paramMap.get("parent");

				SEMOSSParam p = new SEMOSSParam();
				String paramName = Utility.getInstanceName(paramURI);
				String varKey = "";
				// need to add parameters to the metamodel data
				// we decide which DataMakerComponent based on order
				FIND_DMC : for(int compNum = 0; compNum < dmcList.size(); compNum++) {
					DataMakerComponent dmc = dmcList.get(compNum);
					Map<String, Object> metamodel = dmc.getMetamodelData();
					Map<String, Object> queryData = (Map<String, Object>) metamodel.get("QueryData");
					List<List<Object>> relTriples = (List<List<Object>>) queryData.get("relTriples");
					List<Map<String, Object>> nodeProps = (List<Map<String, Object>>) metamodel.get("SelectedNodeProps");
					boolean containsParam =  false;
					if(relTriples != null) {
						FIND_URI : for(int j = 0; j < relTriples.size(); j++) {
							List<Object> triple = relTriples.get(j);
							for(Object uri : triple) {
								if(uri.equals(paramURI)) {
									containsParam = true;
									break FIND_URI;
								}
							}
						}
					}
					if(nodeProps != null && !containsParam) {
						FIND_NODE_URI : for(int j = 0; j < nodeProps.size(); j++) {
							Object uri = nodeProps.get(j).get("uriKey");
							if(uri != null && uri.equals(paramURI)) {
								containsParam = true;
								varKey = (String) nodeProps.get(j).get("varKey");
								break FIND_NODE_URI;
							}
						}
					}

					if(containsParam) {
						Map<String, String> paramHash = new Hashtable<String, String>();
						//TODO: Bifurcation in processing logic if it is RDBMS vs. RDF
						//Due to the fact that we do not store the parameters as metamodels but as queries
						if(this.coreEngine.getEngineType() == ENGINE_TYPE.RDBMS) {
							// rdbms will be stored as type queries if it is a "concept" or property
							// in both situaitons will go through the getEntityOfType query
							if(paramParent != null) {
								String tableName = Utility.getInstanceName(paramParent);
								paramHash.put(paramName, tableName + ":" + paramName);
								p.setType(paramName + "-" + tableName + ":" + paramName);
								p.setName(tableName + "__" + paramName);
							} else {
								paramHash.put(paramName, paramName + ":" + paramName);
								p.setType(paramName);
								p.setName(paramName);
							}
						} else {
							if(!varKey.isEmpty()) {
								paramName = varKey;
							}
							p.setName(paramName);
							if(paramParent != null) {
								// if it is a property, we need to define a unique query which pulls up values for the property based on the parent
								paramHash.put(paramName, paramURI);
								String query = "SELECT DISTINCT ?entity WHERE { {?x <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <" + paramParent + "> } "
										+ "{ ?x <" + paramURI + "> ?entity} }";
								p.setQuery(query);
							} else {
								// if it is a concept, just run getEntityOfType query with the concepts URI
								paramHash.put(paramName, paramURI);
								p.setType(paramURI);
							}
						}
						//TODO: will be linking the parameter based on the dmc component and will modify with a preTransformation
//						queryData.put("Parameters", paramHash);
						// need to adjust the join to now be an inner join if it is not
						// make this assumption since the user is defining what specific param value they want
						adjustJoinComponentForParameter(dmc);
						appendPreTransformationForParamFilter(dmc, p.getName());
						p.setComponentFilterId(Insight.COMP + compNum + ":" + Insight.PRE_TRANS + (dmc.getPreTrans().size()-1));
						break FIND_DMC;
					}
				}
				params.add(p);
			}
		}
	}
	
	private void appendPreTransformationForParamFilter(DataMakerComponent dmc, String paramName) {
		List<ISEMOSSTransformation> preTrans = dmc.getPreTrans();
		if(preTrans == null) {
			preTrans = new Vector<ISEMOSSTransformation>();
		}
		ISEMOSSTransformation pFilter = new FilterTransformation();
		Map<String, Object> props = new Hashtable<String, Object>();
		props.put(FilterTransformation.COLUMN_HEADER_KEY, paramName);
		pFilter.setProperties(props);
		preTrans.add(pFilter);
		dmc.setPreTrans(preTrans);
	}

	/**
	 * Modifies the first post transformation of a DataMakerComponent to be an inner join
	 * @param dmc				The DataMakerComponent to modify the Join Transformation
	 */
	private void adjustJoinComponentForParameter(DataMakerComponent dmc) {
		List<ISEMOSSTransformation> postTrans = dmc.getPostTrans();
		// makes the assumption that the join is the first post transformation on the dmc
		if(postTrans != null && !postTrans.isEmpty()) {
			ISEMOSSTransformation trans = postTrans.get(0);
			if(trans instanceof JoinTransformation) {
				Map<String, Object> props = trans.getProperties();
				props.put(JoinTransformation.JOIN_TYPE, "inner");
				trans.setProperties(props);
			}
		}
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

}
