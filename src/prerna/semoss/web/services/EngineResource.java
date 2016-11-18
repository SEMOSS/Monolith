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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import prerna.algorithm.api.ITableDataFrame;
import prerna.auth.User;
import prerna.auth.UserPermissionsMasterDB;
import prerna.ds.QueryStruct;
import prerna.engine.api.IEngine;
import prerna.engine.api.IEngine.ENGINE_TYPE;
import prerna.engine.api.ISelectStatement;
import prerna.engine.api.ISelectWrapper;
import prerna.engine.impl.AbstractEngine;
import prerna.engine.impl.InsightsConverter;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.SEMOSSParam;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.rdf.util.RDFJSONConverter;
import prerna.semoss.web.form.FormBuilder;
import prerna.ui.components.api.IPlaySheet;
import prerna.ui.components.playsheets.datamakers.DataMakerComponent;
import prerna.ui.components.playsheets.datamakers.ISEMOSSTransformation;
import prerna.ui.components.playsheets.datamakers.JoinTransformation;
import prerna.ui.helpers.InsightCreateRunner;
import prerna.util.Constants;
import prerna.util.PlaySheetRDFMapBasedEnum;
import prerna.util.Utility;
import prerna.web.services.util.InMemoryHash;
import prerna.web.services.util.WebUtility;

public class EngineResource {

	private static final Logger logger = Logger.getLogger(EngineResource.class.getName());

	// gets everything specific to an engine
	// essentially this is a wrapper over the engine
	private IEngine coreEngine = null;

	public void setEngine(IEngine coreEngine)
	{
		System.out.println("Setting core engine to " + coreEngine);
		this.coreEngine = coreEngine;
	}

	// All playsheet specific manipulations will go through this
	@Path("p-{insightID}")
	public Object uploadFile(@PathParam("insightID") String insightID) {
		//	public Object uploadFile(@PathParam("playSheetID") String playSheetID) {

		PlaySheetResource psr = new PlaySheetResource();
		// get the playsheet from session
		//		IPlaySheet playSheet = QuestionPlaySheetStore.getInstance().get(playSheetID);
		Insight in = InsightStore.getInstance().get(insightID);
		IPlaySheet playSheet = InsightStore.getInstance().get(insightID).getPlaySheet();
		psr.setInsight(in);
		psr.setPlaySheet(playSheet);
		psr.setEngine(coreEngine);
		return psr;
	}

	/**
	 * Gets all the insights for a given perspective, instance, type, or tag (in that order) in the given engine
	 * @param type is the URI of a perspective (e.g. http://semoss.org/ontologies/Concept/Director)
	 * @param instance is the URI of an instance (e.g. http://semoss.org/ontologies/Concept/Director/Ang_Lee)
	 * @param tag currently isn't used--will be used to tag insights (TODO)
	 * @param perspective is just the name of the perspective (e.g. Movie-Perspective)
	 * @param request
	 * @return Vector of Insights where each Insight has a label and a propHash with the propHash containing order, output, engine, sparql, uri and id
	 */
	@GET
	@Path("insights")
	@Produces("application/json")
	public Response getInsights(@QueryParam("nodeType") String type, @QueryParam("nodeInstance") String instance, @QueryParam("tag") String tag,@QueryParam("perspective") String perspective, @Context HttpServletRequest request)
	{
		// if the type is null then send all the insights else only that
		Vector<String> resultInsights = null;
		if(perspective != null)
			resultInsights = coreEngine.getInsights(perspective);
		else if(type != null || instance != null) 
		{
			if(instance != null) type = Utility.getConceptType(coreEngine, instance);
			resultInsights = coreEngine.getInsight4Type(type);
		}
		else if(tag != null)
			resultInsights = coreEngine.getInsight4Tag(tag);
		else 
			resultInsights = coreEngine.getInsights();

		//Vector<Hashtable<String,String>> resultInsightObjects = coreEngine.getOutputs4Insights(resultInsights);
		Vector<Insight> resultInsightObjects = null;
		if(resultInsights!=null && !resultInsights.isEmpty())
			resultInsightObjects = ((AbstractEngine)coreEngine).getInsight(resultInsights.toArray(new String[resultInsights.size()]));

		return Response.status(200).entity(WebUtility.getSO(resultInsightObjects)).build();
	}

	// gets all the insights for a given type and tag in all the engines
	// both tag and type are optional
	@GET
	@Path("pinsights")
	@Produces("application/json")
	public Response getPInsights(@Context HttpServletRequest request)
	{
		// if the type is null then send all the insights else only that
		Vector perspectives = null;
		Hashtable retP = new Hashtable();
		perspectives = coreEngine.getPerspectives();
		for(int pIndex = 0;pIndex < perspectives.size();pIndex++)
		{
			Vector insights = coreEngine.getInsights(perspectives.elementAt(pIndex)+"");
			if(insights != null)
				retP.put(perspectives.elementAt(pIndex), insights);
		}
		return Response.status(200).entity(WebUtility.getSO(retP)).build();
	}

	/**
	 * Gets a list of perspectives for the given engine
	 * @param request
	 * @return a hashtable with "perspectives" pointing to to array of perspectives (e.g. ["Generic-Perspective","Movie-Perspective"])
	 */
	@GET
	@Path("perspectives")
	@Produces("application/json")
	public Response getPerspectives(@Context HttpServletRequest request)
	{
		// if the type is null then send all the insights else only that
		Hashtable<String, Vector<String>> hashtable = new Hashtable<String, Vector<String>>(); 
		Vector<String> perspectivesVector = coreEngine.getPerspectives();
		hashtable.put("perspectives", perspectivesVector);
		return Response.status(200).entity(WebUtility.getSO(hashtable)).build();
	}

	// gets all the tags for a given insight across all the engines
	@GET
	@Path("tags")
	@Produces("application/json")
	public StreamingOutput getTags(@QueryParam("insight") String insight, @Context HttpServletRequest request)
	{
		// if the tag is empty, this will give back all the tags in the engines
		return null;
	}
	
	// gets a particular insight
	// not sure if I should keep it as it is or turn this into a post because of the query
	@POST
	@Path("querys")
	@Produces("application/json")
	public Response queryDataSelect(MultivaluedMap<String, String> form)
	{
		// returns the insight
		// based on the current ID get the data
		// typically is a JSON of the insight
		// this will also cache it
		Gson gson = new Gson();
		String query = form.getFirst("query");
		String[] paramBind = gson.fromJson(form.getFirst("paramBind"), new TypeToken<String[]>() {}.getType());
		String[] paramValue = gson.fromJson(form.getFirst("paramValue"), new TypeToken<String[]>() {}.getType());
		//do the query binding server side isntead of on the front end.
		if(paramBind.length > 0 && paramValue.length > 0 && (paramBind.length == paramValue.length)){
			for(int i = 0; i < paramBind.length && query.contains(paramBind[i]); i++){
//				String paramValueStr = coreEngine.getTransformedNodeName(paramValue[i], false);
				String paramValueStr = paramValue[i];
				if(coreEngine.getEngineType() == ENGINE_TYPE.RDBMS){
					String paramValueTable = Utility.getInstanceName(paramValueStr);
					String paramValueCol = Utility.getClassName(paramValueStr);

					//very risky business going on right now.... will not work on other bindings
					if(paramValueCol != null) query = query.replaceFirst(paramBind[i], paramValueCol);
					if(paramValueTable != null) query = query.replaceFirst(paramBind[i], paramValueTable);

				} else {
					query = query.replaceFirst(paramBind[i], paramValueStr);
				}
			}
		}
		System.out.println(query);
		return Response.status(200).entity(WebUtility.getSO(RDFJSONConverter.getSelectAsJSON(query, coreEngine))).build();
	}	

	/**
	 * Uses the title of an insight to get the Insight object as well as the options and params
	 * Insight object has label (e.g. What is the list of Directors?) and propHash which contains order, output, engine, sparql, uri, id
	 * @param insight
	 * @return
	 */
	@GET
	@Path("insight")
	@Produces("application/json")
	public Response getInsightDefinition(@QueryParam("insight") String insight)
	{
		// returns the insight
		// typically is a JSON of the insight
		System.out.println("Insight is " + insight);
		Insight in = ((AbstractEngine)coreEngine).getInsight(insight).get(0);
		if(!in.isDbInsight()) {
			// data is not from engine
			// send back empties since cannot have parameters in these questions
			Hashtable outputHash = new Hashtable<String, Hashtable>();
			outputHash.put("result", in.getInsightID());
			Hashtable optionsHash = new Hashtable();
			Hashtable paramsHash = new Hashtable();
			outputHash.put("options", optionsHash);
			outputHash.put("params", paramsHash);
			return Response.status(200).entity(WebUtility.getSO(outputHash)).build();
		}
		System.out.println("Insight is " + in);
		System.out.println(in.getOutput());
		Hashtable outputHash = new Hashtable<String, Hashtable>();
		outputHash.put("result", in.getInsightID());


		//		Vector <SEMOSSParam> paramVector = coreEngine.getParams(insight);
		Vector <SEMOSSParam> paramVector = in.getInsightParameters();
		System.err.println("Params are " + paramVector);
		Hashtable optionsHash = new Hashtable();
		Hashtable paramsHash = new Hashtable();

		for(int paramIndex = 0;paramIndex < paramVector.size();paramIndex++)
		{
			SEMOSSParam param = paramVector.elementAt(paramIndex);
			if(param.isDepends().equalsIgnoreCase("false")) {
				Vector<Object> vals = this.coreEngine.getParamOptions(param.getParamID());
				Set<Object> uniqueVals = new HashSet<Object>(vals);
				optionsHash.put(param.getName(), uniqueVals);			
			}
			else {
				optionsHash.put(param.getName(), "");
			}
			paramsHash.put(param.getName(), param);
		}

		outputHash.put("options", optionsHash);
		outputHash.put("params", paramsHash);

		return Response.status(200).entity(WebUtility.getSO(outputHash)).build();
	}


	/**
	 * Method used to get the query or raw insight makeup 
	 * @param insight
	 * @return
	 */
	@GET
	@Path("insightSpecifics")
	@Produces("application/json")
	public Response getInsightSpecifics(@QueryParam("insightID") String insightID)
	{
		Insight in = ((AbstractEngine)coreEngine).getInsight(insightID).get(0);
		IEngine makeupEng = in.getMakeupEngine();

		int total = in.getNumComponents();

		String retString = "";
		boolean hasQuery = false;
		if(total == 1) {
			String query = "SELECT ?Component ?Query ?Metamodel WHERE { {?Component a <http://semoss.org/ontologies/Concept/Component>} OPTIONAL {?Component <http://semoss.org/ontologies/Relation/Contains/Query> ?Query} OPTIONAL {?Component <http://semoss.org/ontologies/Relation/Contains/Metamodel> ?Metamodel} }";
			ISelectWrapper wrapper = WrapperManager.getInstance().getSWrapper(makeupEng, query);
			String[] names = wrapper.getVariables();
			String insightMetamodel = "";
			while(wrapper.hasNext()) {
				ISelectStatement ss = wrapper.next();
				retString = ss.getVar(names[1]) + "";
				insightMetamodel = ss.getVar(names[2]) + "";
			}
			if (!retString.isEmpty()) {
				hasQuery = true;
			} 				
		} 
		if(!hasQuery){
			retString = in.getNTriples();
		}

		Map<String, Object> retMap = new HashMap<String, Object>();
		if(hasQuery) {
			retMap.put("query", retString);
		} else {
			retMap.put("insightMakeup", retString);
		}
		retMap.put("dataMakerName", in.getDataMakerName());
		retMap.put("parameters", in.getInsightParameters());
		retMap.put("dataTableAlign", in.getDataTableAlign());

		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}

	/**
	 * Executes a particular insight or runs a custom query on the specified playsheet
	 * To run custom query: must pass playsheet and sparql
	 * To run stored insight: must pass insight (the actual question), and a string of params
	 * @param form
	 * @param request
	 * @param response
	 * @return playsheet.getData()--it depends on the playsheet
	 */
	@POST
	@Path("output")
	@Produces("application/json")
	public Response createOutput(MultivaluedMap<String, String> form, @Context HttpServletRequest request, @Context HttpServletResponse response)
	{
		Gson gson = new Gson();
		String insight = form.getFirst("insight");
		UserPermissionsMasterDB tracker = new UserPermissionsMasterDB();
		HttpSession session = request.getSession();

		// executes the output and gives the data
		// executes the create runner
		// once complete, it would plug the output into the session
		// need to find a way where I can specify if I want to keep the result or not
		// params are typically passed on as
		// pairs like this
		// key$value~key2:value2 etc
		// need to find a way to handle other types than strings

		// if insight, playsheet and sparql are null throw bad data exception
		if(insight == null) {
			String playsheet = form.getFirst("layout");
			String sparql = form.getFirst("sparql");
			//check for sparql and playsheet; if not null then parameters have been passed in for preview functionality
			if(sparql != null && playsheet != null){
				Insight in = null;
				Object obj = null;
				try {
					List<String> allSheets = PlaySheetRDFMapBasedEnum.getAllSheetNames();
					String dmName = InsightsConverter.getDataMaker(playsheet, allSheets);
					in = new Insight(coreEngine, dmName, playsheet);
					Vector<DataMakerComponent> dmcList = new Vector<DataMakerComponent>();
					DataMakerComponent dmc = new DataMakerComponent(coreEngine, sparql);
					dmcList.add(dmc);
					in.setDataMakerComponents(dmcList);
					InsightStore.getInstance().put(in);
					InsightCreateRunner insightRunner = new InsightCreateRunner(in);
					obj = insightRunner.runWeb();
				} catch (Exception ex) { //need to specify the different exceptions 
					ex.printStackTrace();
					Hashtable<String, String> errorHash = new Hashtable<String, String>();
					errorHash.put("Message", "Error occured processing question.");
					errorHash.put("Class", this.getClass().getName());
					return Response.status(500).entity(WebUtility.getSO(errorHash)).build();
				}

				return Response.status(200).entity(WebUtility.getSO(obj)).build();
			}
			else{
				Hashtable<String, String> errorHash = new Hashtable<String, String>();
				errorHash.put("Message", "No question defined.");
				errorHash.put("Class", this.getClass().getName());
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
		}
		else {
			//Get the Insight, grab its ID
			Insight insightObj = ((AbstractEngine)coreEngine).getInsight(insight).get(0);
			// set the user id into the insight
			insightObj.setUserID( ((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId() );
			Map<String, List<Object>> params = gson.fromJson(form.getFirst("params"), new TypeToken<Map<String, List<Object>>>() {}.getType());
			//			params = Utility.getTransformedNodeNamesMap(coreEngine, params, false);
			insightObj.setParamHash(params);

			//			if(!insightObj.isDbInsight()) {
			//				String vizData = CacheFactory.getInsightCache(CacheFactory.CACHE_TYPE.CSV_CACHE).getVizData(insightObj);
			//				if(vizData != null) {
			//					// insight has been cached, send it to the FE with a new insight id
			//					String id = InsightStore.getInstance().put(insightObj);
			//					Map<String, Object> uploaded = gson.fromJson(vizData, new TypeToken<Map<String, Object>>() {}.getType());
			//					uploaded.put("insightID", id);
			//
			//					tracker.trackInsightExecution(((User)session.getAttribute(Constants.SESSION_USER)).getId(), coreEngine.getEngineName(), id, session.getId());
			//					return Response.status(200).entity(WebUtility.getSO(uploaded)).build();
			//				} 
			//				//				Should we just get the cached DM if the Viz has been deleted and send that to the FE?
			//				//				
			//				//				ITableDataFrame dataFrame = CacheFactory.getInsightCache(CacheFactory.CACHE_TYPE.CSV_CACHE).getDMCache(insightObj);
			//				//				if(dataFrame != null) {
			//				//					insightObj.setDataMaker(dataFrame);
			//				//					String id = InsightStore.getInstance().put(insightObj);
			//				//					InsightCreateRunner run = new InsightCreateRunner(insightObj);
			//				//					Map<String, Object> obj = run.runWeb();
			//				//					obj.put("insightID", id);
			//				//					
			//				//					// cahce json for future
			//				//					CacheFactory.getInsightCache(CacheFactory.CACHE_TYPE.CSV_CACHE).cacheInsight(insightObj, (Map<String, Object>) obj);
			//				//					
			//				//					return Response.status(200).entity(WebUtility.getSO(obj)).build();
			//				//				}
			//				else {
			//					Hashtable<String, String> errorHash = new Hashtable<String, String>();
			//					errorHash.put("Message", "Error getting data for saved insight via csv.");
			//					errorHash.put("Class", className);
			//					return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			//				}
			//			}

			// check if the insight has already been cached
			System.out.println("Params is " + params);
			//			String vizData = CacheFactory.getInsightCache(CacheFactory.CACHE_TYPE.DB_INSIGHT_CACHE).getVizData(insightObj);
			String vizData = null;

			Object obj = null;
			if(vizData != null) {
				// insight has been cached, send it to the FE with a new insight id
				String id = InsightStore.getInstance().put(insightObj);
				Map<String, Object> uploaded = gson.fromJson(vizData, new TypeToken<Map<String, Object>>() {}.getType());
				uploaded.put("insightID", id);

				tracker.trackInsightExecution(((User)session.getAttribute(Constants.SESSION_USER)).getId(), coreEngine.getEngineName(), id, session.getId());
				return Response.status(200).entity(WebUtility.getSO(uploaded)).build();
			} else {
				// insight visualization data has not been cached, run the insight
				try {
					InsightStore.getInstance().put(insightObj);
					InsightCreateRunner run = new InsightCreateRunner(insightObj);
					obj = run.runWeb();

					//					//Don't cache dashboards for now...too many issues with that
					//					//need to resolve updating insight ID for dashboards, as well as old insight IDs of insights stored in varMap
					//					if(!(insightObj.getDataMaker() instanceof Dashboard)) {
					//						String saveFileLocation = CacheFactory.getInsightCache(CacheFactory.CACHE_TYPE.DB_INSIGHT_CACHE).cacheInsight(insightObj, (Map<String, Object>) obj);
					//
					//						if(saveFileLocation != null) {
					//							saveFileLocation = saveFileLocation + "_Solr.txt";
					//							File solrFile = new File(saveFileLocation);
					//							String solrId = SolrIndexEngine.getSolrIdFromInsightEngineId(insightObj.getEngineName(), insightObj.getRdbmsId());
					//							SolrDocumentExportWriter writer = new SolrDocumentExportWriter(solrFile);
					//							writer.writeSolrDocument(SolrIndexEngine.getInstance().getInsight(solrId));
					//							writer.closeExport();
					//						}
					//					}
				} catch (Exception ex) { //need to specify the different exceptions 
					ex.printStackTrace();
					Hashtable<String, String> errorHash = new Hashtable<String, String>();
					errorHash.put("Message", "Error occured processing question.");
					errorHash.put("Class", this.getClass().getName());
					return Response.status(500).entity(WebUtility.getSO(errorHash)).build();
				}

				tracker.trackInsightExecution(((User)session.getAttribute(Constants.SESSION_USER)).getId(), coreEngine.getEngineName(), insightObj.getInsightID(), session.getId());
			}

			return Response.status(200).entity(WebUtility.getSO(obj)).build();
		}
	}

	@POST
	@Path("/addData")
	@Produces("application/json")
	@Deprecated
	/**
	 * THIS IS THE OLD WAY OF ADDING DATA ONTO THE DATAFRAME!!!!
	 * SADLY, FORMS IS NOT PUSHED INTO PKQL SO THEY USE THIS TO GET THE DATA
	 * WILL DELETE THIS ONCE THEY SHIFT OVER!!!!
	 * @return
	 */
	public Response addData(MultivaluedMap<String, String> form, 
			@QueryParam("existingConcept") String currConcept, 
			@QueryParam("joinConcept") String equivConcept, 
			@QueryParam("joinType") String joinType,
			@QueryParam("insightID") String insightID,
			@Context HttpServletRequest request)
	{
		equivConcept = Utility.getInstanceName(equivConcept);
		Gson gson = new Gson();
		//		Hashtable<String, Object> dataHash = gson.fromJson(form.getFirst("QueryData"), new TypeToken<Hashtable<String, Object>>() {}.getType());
		//		QueryBuilderData data = new QueryBuilderData(dataHash);
		//		QueryBuilderHelper.parsePath(data, this.coreEngine);
		//		QueryStruct qs = data.getQueryStruct(false);

		QueryStruct qs = gson.fromJson(form.getFirst("QueryData"), new QueryStruct().getClass());

		// Very simply, here is the logic:
		// 1. If no insight ID is passed in, we create a new Insight and put in the store. Also, if new insight, we know there are no transformations
		// 2. Else, we get the insight from session (if the insight isn't in session, we are done--throw an error)
		// 2. a. If its not an outer join, add a filter transformation with all instances from other column in order to speed up join
		// 2. b. Add join transformation since we know a tree already exists and we will have to join to it
		// 3. Process the component

		// get the insight if an id has been passed
		Insight insight = null;
		DataMakerComponent dmc = new DataMakerComponent(this.coreEngine, qs);

		// need to remove filter and add that as a pretransformation. Otherwise our metamodel data is not truly clean metamodel data
		//		Map<String, List<Object>> filters = data.getFilterData();

		//		if(filters != null){
		//			for(String filterCol : filters.keySet()){
		//				Map<String, Object> transProps = new HashMap<String, Object>();
		//				transProps.put(FilterTransformation.COLUMN_HEADER_KEY, filterCol);
		//				transProps.put(FilterTransformation.VALUES_KEY, Utility.getTransformedNodeNamesList(this.coreEngine, filters.get(filterCol), false));
		//				ISEMOSSTransformation filterTrans = new FilterTransformation();
		//				filterTrans.setProperties(transProps);
		//				dmc.addPreTrans(filterTrans);
		//			}
		//		}

		ISEMOSSTransformation joinTrans = null;
		// 1. If no insight ID is passed in, we create a new Insight and put in the store. Also, if new insight, we know there are no transformations
		if(insightID == null || insightID.isEmpty()) {
			String datatype = "TinkerFrame";
			String type;
			if(form.get("dataFrameType") != null && (type = form.get("dataFrameType").get(0)) != null) {
				if(type.equalsIgnoreCase("H2")) {
					datatype = "H2Frame";
				}
			}
			insight = new Insight(this.coreEngine, datatype, PlaySheetRDFMapBasedEnum.getSheetName("Grid")); // TODO: this needs to be an enum or grabbed from rdf map somehow
			insightID = InsightStore.getInstance().put(insight);
		} 

		// 2. Else, we get the insight from session (if the insight isn't in session, we are done--throw an error)
		else {
			NameServer ns = new NameServer();
			ns.getInsightDataFrame(insightID, null, request);
			insight = InsightStore.getInstance().get(insightID);
			if(insight == null) {
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "Existing insight based on passed insightID is not found");
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}

			// 2. b. Add join transformation since we know a tree already exists and we will have to join to it
			joinTrans = new JoinTransformation();
			Map<String, Object> selectedOptions = new HashMap<String, Object>();
			selectedOptions.put(JoinTransformation.COLUMN_ONE_KEY, currConcept);
			selectedOptions.put(JoinTransformation.COLUMN_TWO_KEY, equivConcept);
			selectedOptions.put(JoinTransformation.JOIN_TYPE, joinType);
			joinTrans.setProperties(selectedOptions);
			//			dmc.addPostTrans(joinTrans);
			dmc.addPreTrans(joinTrans);
		}

		// 3. Process the component
		System.err.println("Starting component processing...");
		long startJoinTime = System.currentTimeMillis();
		insight.processDataMakerComponent(dmc);
		System.err.println("Finished processing component: " + (System.currentTimeMillis() - startJoinTime) + " ms");
		Map<String, Object> retMap = new HashMap<String, Object>();
		retMap.put("insightID", insightID);

		//get the last added column
		ITableDataFrame df = (ITableDataFrame) insight.getDataMaker();
		String[] headerList = df.getColumnHeaders();
		String lastAddedColumn = headerList[headerList.length - 1];

		retMap.put("logicalName", lastAddedColumn);
		if(joinTrans==null) {
			retMap.put("stepID", dmc.getId());
		} else {
			retMap.put("stepID", joinTrans.getId());
		}
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}

	@POST
	@Path("/commitFormData")
	@Produces("application/json")
	public Response commitFormData(MultivaluedMap<String, String> form, @Context HttpServletRequest request) 
	{
		Gson gson = new Gson();
		try {
			String formData = form.getFirst("formData");
			Map<String, Object> engineHash = gson.fromJson(formData, new TypeToken<Map<String, Object>>() {}.getType());
			FormBuilder.commitFormData(this.coreEngine, engineHash);
		} catch(Exception e) {
			e.printStackTrace();
			return Response.status(400).entity(WebUtility.getSO(gson.toJson(e.getMessage()))).build();
		}

		return Response.status(200).entity(WebUtility.getSO(gson.toJson("success"))).build();
	}

	@POST
	@Path("/getAuditLogForEngine")
	@Produces("application/json")
	public Response getAuditLogForEngine(MultivaluedMap<String, String> form, @Context HttpServletRequest request) 
	{
		Gson gson = new Gson();
		Map<String, Object> auditInfo = null;
		try {
			auditInfo = FormBuilder.getAuditDataForEngine(this.coreEngine.getEngineName());
		} catch(Exception e) {
			e.printStackTrace();
			return Response.status(400).entity(WebUtility.getSO(gson.toJson(e.getMessage()))).build();
		}

		return Response.status(200).entity(WebUtility.getSO(gson.toJson(auditInfo))).build();
	}




	public static void main(String[] args) {

		Gson gson = new GsonBuilder().disableHtmlEscaping().serializeSpecialFloatingPointValues().setPrettyPrinting().create();
		Map<String, Object> testMap = new HashMap<String, Object>();
		Map<String, Object> innerMap = new HashMap<String, Object>();
		innerMap.put("object1", "value1");
		innerMap.put("object2", "value2");

		String bytes = gson.toJson(innerMap);

		testMap.put("object", "value");
		testMap.put("innerMap", bytes);

		String s = gson.toJson(testMap);
		Map<String, Object> newMap = gson.fromJson(s, new TypeToken<Map<String, Object>>() {}.getType());
	}


	// TESTING FOR JOB-ID THREADS
	// TESTING FOR JOB-ID THREADS
	// TESTING FOR JOB-ID THREADS
	// TESTING FOR JOB-ID THREADS
	// TESTING FOR JOB-ID THREADS
	// TESTING FOR JOB-ID THREADS

	// test
	@GET
	@Path("comet")
	@Produces("text/plain")
	public void cometTry(final @Suspended AsyncResponse response,  @Context HttpServletRequest request) {
		System.out.println("Dropped in here >>>>>> 2" + "comet");
		/*
		   Thread t = new Thread()
		      {
		         @Override
		         public void run()
		         {
		            try
		            {
		            	System.out.println("Came into thread ");
		               Response jaxrs = Response.ok("Funny... ", "basic").type(MediaType.TEXT_PLAIN).build();
		               //jaxrs.
		               Thread.sleep(1000);
		               response.resume(jaxrs);
		            }
		            catch (Exception e)
		            {
		               e.printStackTrace();
		            }
		         }
		      };
		      t.start();*/
		HttpSession session = request.getSession();
		InMemoryHash.getInstance().put("respo", response);
		response.setTimeout(1000, TimeUnit.MINUTES);
		final AsyncResponse myResponse = (AsyncResponse)InMemoryHash.getInstance().get("respo");
		System.out.println("Is the response done..  ? " + myResponse.isDone());

		//myResponse.resume("Hello");
		System.err.println("Put the response here");

	}   

	@GET
	@Path("/trigger")
	@Produces("application/xml")
	public String trigger(@Context HttpServletRequest request) {
		System.out.println("Dropped in here >>>>>> 2" + "trigger");
		HttpSession session = request.getSession();
		final AsyncResponse myResponse = (AsyncResponse)InMemoryHash.getInstance().get("respo");

		if(myResponse != null) {
			System.out.println("Is the response done..  ? " + myResponse.isDone());
			myResponse.resume("Hello2222");
			myResponse.resume("Hola again");
			System.out.println("MyResponse is not null");
			/*
			   Thread t = new Thread()
			      {
			         @Override
			         public void run()
			         {
			            try
			            {
			            	System.out.println("Came into thread ");
			               Response jaxrs = Response.ok("Funny... ", "basic").type(MediaType.TEXT_PLAIN).build();
			               //jaxrs.
			               Thread.sleep(1000);
			               myResponse.resume(jaxrs);
			            }
			            catch (Exception e)
			            {
			               e.printStackTrace();
			            }
			         }
			      };
			      t.start();*/
		}
		//Response jaxrs = Response.ok("Funny... ", "basic").type(MediaType.TEXT_PLAIN).build();
		//myResponse.resume(jaxrs);
		return "Returned.. ";
	}  

}