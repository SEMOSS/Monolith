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
import java.util.Iterator;
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
import prerna.engine.impl.InsightsConverter;
import prerna.engine.impl.QuestionAdministrator;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.SEMOSSParam;
import prerna.ui.components.playsheets.datamakers.DataMakerComponent;
import prerna.ui.components.playsheets.datamakers.FilterTransformation;
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
			Vector<Map<String, String>> paramMapList = gson.fromJson(form.getFirst("parameterQueryList"), new TypeToken<Vector<Map<String, String>>>() {}.getType());
			List<SEMOSSParam> params = buildParameterList(paramMapList);
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
			Vector<Map<String, String>> paramMapList = gson.fromJson(form.getFirst("parameterQueryList"), new TypeToken<Vector<Map<String, String>>>() {}.getType());
			List<SEMOSSParam> params = buildParameterList(paramMapList);
			questionAdmin.modifyQuestion(insight.getRdbmsId(), insightName, perspective, insight.getDataMakerComponents(), layout, order, insight.getDataMakerName(), isDbQuery, dataTableAlign, params);
		}catch(RuntimeException e){
			System.out.println("caught exception while modifying question.................");
			e.printStackTrace();
			return Response.status(500).entity(WebUtility.getSO(e.toString().substring(0, (e.toString().length() < MAX_CHAR)?e.toString().length():MAX_CHAR))).build();
		}

		return Response.status(200).entity(WebUtility.getSO("Success")).build();
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
		List<String> allSheets = PlaySheetRDFMapBasedEnum.getAllSheetNames();
		String dmName = InsightsConverter.getDataMaker(layout, allSheets);
//		String dmName = "BTreeDataFrame";
//		if(layout.equals("Graph")) {
//			dmName = "GraphDataModel";
//		}
		
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
					// rdbms will be stored as type queries if it is a "concept" or property
					// in both situaitons will go through the getEntityOfType query
					if(paramParent != null) {
						String tableName = Utility.getInstanceName(paramParent);
						p.setType(paramName + "-" + tableName + ":" + paramName);
						p.setName(tableName + "__" + paramName);
					} else {
						p.setType(paramName);
						p.setName(paramName);
					}
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

}
