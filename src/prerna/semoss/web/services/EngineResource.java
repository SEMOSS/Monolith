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

import java.io.File;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
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

import com.bigdata.rdf.model.BigdataURI;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import prerna.algorithm.api.ITableDataFrame;
import prerna.algorithm.learning.util.DuplicationReconciliation;
import prerna.auth.User;
import prerna.ds.TinkerFrame;
import prerna.engine.api.IEngine;
import prerna.engine.api.IEngine.ENGINE_TYPE;
import prerna.engine.api.ISelectStatement;
import prerna.engine.api.ISelectWrapper;
import prerna.engine.impl.AbstractEngine;
import prerna.engine.impl.InsightsConverter;
import prerna.engine.impl.rdf.RDFFileSesameEngine;
import prerna.engine.impl.rdf.SesameJenaUpdateWrapper;
import prerna.equation.EquationSolver;
import prerna.insights.admin.CacheAdmin;
import prerna.insights.admin.CacheAdmin.FileType;
import prerna.nameserver.AddToMasterDB;
import prerna.nameserver.SearchMasterDB;
import prerna.om.GraphDataModel;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.SEMOSSParam;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.rdf.query.builder.IQueryBuilder;
import prerna.rdf.query.builder.QueryBuilderData;
import prerna.rdf.query.builder.QueryBuilderHelper;
import prerna.rdf.query.util.SEMOSSQuery;
import prerna.rdf.util.AbstractQueryParser;
import prerna.rdf.util.RDFJSONConverter;
import prerna.semoss.web.form.FormBuilder;
import prerna.ui.components.api.IPlaySheet;
import prerna.ui.components.playsheets.datamakers.DataMakerComponent;
import prerna.ui.components.playsheets.datamakers.FilterTransformation;
import prerna.ui.components.playsheets.datamakers.IDataMaker;
import prerna.ui.components.playsheets.datamakers.ISEMOSSTransformation;
import prerna.ui.components.playsheets.datamakers.JoinTransformation;
import prerna.ui.components.playsheets.datamakers.MathTransformation;
import prerna.ui.helpers.InsightCreateRunner;
import prerna.ui.main.listener.impl.SPARQLExecuteFilterBaseFunction;
import prerna.ui.main.listener.impl.SPARQLExecuteFilterNoBaseFunction;
import prerna.util.ArrayUtilityMethods;
import prerna.util.Constants;
import prerna.util.PlaySheetRDFMapBasedEnum;
import prerna.util.Utility;
import prerna.web.services.util.InMemoryHash;
import prerna.web.services.util.InstanceStreamer;
import prerna.web.services.util.TableDataFrameUtilities;
import prerna.web.services.util.WebUtility;

public class EngineResource {

	
	
	// gets everything specific to an engine
	// essentially this is a wrapper over the engine
	IEngine coreEngine = null;
	byte[] output ;
	Logger logger = Logger.getLogger(EngineResource.class.getName());
	Hashtable<String, SEMOSSQuery> vizHash = new Hashtable<String, SEMOSSQuery>();
	// to send class name if error occurs
	String className = this.getClass().getName();

	
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
	 * Gets all edges and nodes from owl file to display as metamodel
	 * Sets the owl in a GraphPlaySheet and sets subclasscreate to create the metamodel
	 * @param request
	 * @return same payload as GraphPlaySheet. Returns nodes and edges and all playsheet data (id, title, playsheet name)
	 */
	@GET
	@Path("metamodel")
	@Produces("application/json")
	public Response getMetamodel(@Context HttpServletRequest request)
	{
		if(coreEngine == null) {
			Hashtable<String, String> errorHash = new Hashtable<String, String>();
			errorHash.put("Message", "No engine defined.");
			errorHash.put("Class", className);
			return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
		}

		//hard code playsheet attributes since no insight exists for this
		String sparql = "SELECT ?s ?p ?o WHERE {?s ?p ?o} LIMIT 1";
		String playSheetName = "Graph";
		String dataMakerName = "GraphDataModel";
		String title = "Metamodel";
		String id = coreEngine.getEngineName() + "-Metamodel";
		AbstractEngine eng = ((AbstractEngine)coreEngine).getBaseDataEngine();
		eng.setEngineName(id);
		eng.setBaseData((RDFFileSesameEngine) eng);
		Hashtable<String, String> filterHash = new Hashtable<String, String>();
		filterHash.put("http://semoss.org/ontologies/Relation", "http://semoss.org/ontologies/Relation");
		eng.setBaseHash(filterHash);

		Object obj = null;
		try
		{
			DataMakerComponent dmc = new DataMakerComponent(eng, sparql);
			GraphDataModel gdm = (GraphDataModel) Utility.getDataMaker(this.coreEngine, dataMakerName);
			//			GraphPlaySheet playSheet= (GraphPlaySheet) Utility.preparePlaySheet(eng, sparql, playSheetName, title, id);
			gdm.setSubclassCreate(true);
			gdm.setOverlay(false);
			gdm.processDataMakerComponent(dmc);
			//			playSheet.setDataMaker(gdm);
			obj = gdm.getDataMakerOutput();
			
			//TODO: this is really bad.. 
			//undo the setting of the eng to get gdm to run
			eng.setBaseData(null);
			eng.setBaseHash(null);
		} catch (Exception ex) { 
			ex.printStackTrace();
			Hashtable<String, String> errorHash = new Hashtable<String, String>();
			errorHash.put("Message", "Error processing query.");
			errorHash.put("Class", className);
			return Response.status(500).entity(WebUtility.getSO(errorHash)).build();
		}

		return Response.status(200).entity(WebUtility.getSO(obj)).build();
	}


	//gets all node types connected to a given node type
	@GET
	@Path("neighbors")
	@Produces("application/json")
	public Response getNeighbors(@QueryParam("nodeType") String type, @Context HttpServletRequest request)
	{
		Hashtable<String, Vector<String>> finalTypes = new Hashtable<String, Vector<String>>();
		if(coreEngine instanceof AbstractEngine){
			Vector<String> downNodes = ((AbstractEngine) coreEngine).getToNeighbors(type, 0);
			finalTypes.put("downstream", downNodes);
			Vector<String> upNodes = ((AbstractEngine) coreEngine).getFromNeighbors(type, 0);
			finalTypes.put("upstream", upNodes);
		} 
		return Response.status(200).entity(WebUtility.getSO(finalTypes)).build();
	}

	//	//gets all node types connected to a given node type along with the verbs connecting the given types
	//	@GET
	//	@Path("neighbors/verbs")
	//	@Produces("application/json")
	//	public Response getNeighborsWithVerbs(@QueryParam("nodeType") String type, @Context HttpServletRequest request)
	//	{
	//		Hashtable<String, Hashtable<String, Vector<String>>> finalTypes = new Hashtable<String, Hashtable<String, Vector<String>>>();
	//		if(coreEngine instanceof AbstractEngine){
	//			Hashtable<String, Vector<String>> downNodes = ((AbstractEngine) coreEngine).getToNeighborsWithVerbs(type, 0);
	//			finalTypes.put("downstream", downNodes);
	//			Hashtable<String, Vector<String>> upNodes = ((AbstractEngine) coreEngine).getFromNeighborsWithVerbs(type, 0);
	//			finalTypes.put("upstream", upNodes);
	//		}
	//		return Response.status(200).entity(WebUtility.getSO(finalTypes)).build();
	//	}

	//gets all node types connected to a specific node instance
	@POST
	@Path("neighbors/instance")
	@Produces("application/json")
	//	@Consumes(MediaType.APPLICATION_JSON)
	public Response getNeighborsInstance(MultivaluedMap<String, String> form, @Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		List<String> uriArray = gson.fromJson(form.getFirst("node"), List.class); // comes in with the uri that was first selected i.e. the instance
		uriArray = Utility.getTransformedNodeNamesList(coreEngine, uriArray, false);

		boolean isRDF = (coreEngine.getEngineType() == IEngine.ENGINE_TYPE.SESAME || coreEngine.getEngineType() == IEngine.ENGINE_TYPE.JENA || 
				coreEngine.getEngineType() == IEngine.ENGINE_TYPE.SEMOSS_SESAME_REMOTE);


		Hashtable<String, Vector<String>> finalTypes = new Hashtable<String, Vector<String>>();

		if(isRDF)
		{
			if(coreEngine instanceof AbstractEngine){
				AbstractEngine engine = (AbstractEngine) coreEngine;

				//create bindings string
				String bindingsString = "";
				for(String uri : uriArray){
					bindingsString = bindingsString + "(<" + uri + ">)";
				}
				logger.info("bindings string = " + bindingsString); 
				// gets a clean read on the type instead of predicting it based on URI
				// In the case of RDBMS we can just assume it

				String uniqueTypesQuery = "SELECT DISTINCT ?entity WHERE { { ?subject <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ?entity}   FILTER NOT EXISTS { { ?subject <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ?subtype} {?subtype <http://www.w3.org/2000/01/rdf-schema#subClassOf> ?entity} }} BINDINGS ?subject {"+bindingsString+"}";

				//get node types
				Vector<String> types = Utility.getVectorOfReturn(uniqueTypesQuery, coreEngine, true);

				//DOWNSTREAM PROCESSING
				//get node types connected to this type
				Vector<String> downNodeTypes = new Vector<String>();
				for(String type : types){
					downNodeTypes.addAll(engine.getToNeighbors(type, 0));
				}

				// this query needs to change for RDBMS based on the traverse query
				//for each available type, ensure each type has at least one instance connected to original node
				String downAskQuery = "ASK { "
						+ "{?connectedNode a <@NODE_TYPE@>} "
						+ "{?node ?rel ?connectedNode}"
						+ "} BINDINGS ?node {"+bindingsString+"}" ;
				Vector<String> validDownTypes = new Vector<String>();
				for (String connectedType : downNodeTypes){
					String filledDownAskQuery = downAskQuery.replace("@NODE_TYPE@", connectedType);
					logger.info("Checking type " + connectedType + " with query " + filledDownAskQuery);
					if((boolean) engine.execQuery(filledDownAskQuery))	
						validDownTypes.add(connectedType);
				}
				validDownTypes = (Vector<String>) Utility.getTransformedNodeNamesList(coreEngine, validDownTypes, true);
				finalTypes.put("downstream", validDownTypes);

				//UPSTREAM PROCESSING
				//get node types connected to this type
				Vector<String> upNodeTypes = new Vector<String>();
				for(String type : types){
					upNodeTypes.addAll(engine.getFromNeighbors(type, 0));
				}

				//for each available type, ensure each type has at least one instance connected to original node
				String upAskQuery = "ASK { "
						+ "{?connectedNode a <@NODE_TYPE@>} "
						+ "{?connectedNode ?rel ?node}"
						+ "} BINDINGS ?node {"+bindingsString+"}" ;
				Vector<String> validUpTypes = new Vector<String>();
				for (String connectedType : upNodeTypes){
					String filledUpAskQuery = upAskQuery.replace("@NODE_TYPE@", connectedType);
					logger.info("Checking type " + connectedType + " with query " + filledUpAskQuery);
					if((boolean) engine.execQuery(filledUpAskQuery))
						validUpTypes.add(connectedType);
				}
				validUpTypes = (Vector<String>) Utility.getTransformedNodeNamesList(coreEngine, validUpTypes, true);
				finalTypes.put("upstream", validUpTypes);
			}
		}
		else if(coreEngine.getEngineType() == IEngine.ENGINE_TYPE.RDBMS)
		{
			// get the type.. see how easy it is on RDBMS.. let me show this
			// do the camel casing
			// almost never will I have more than one
			// but as a good developer.. I will collect all the classes
			Vector <String> types = new Vector<String>();

			for(int inputIndex =0;inputIndex < uriArray.size();inputIndex++)
			{
				String uri = uriArray.get(inputIndex);
				String className = Utility.getQualifiedClassName(uri); // gets you everything but the instance
				String modClassName = Utility.getInstanceName(className); // since it gets me the last one , this is really the className

				if(!types.contains(className))
					types.add(className);
			}

			// ok now get the types
			Vector <String> validUpTypes = new Vector<String>();
			Vector <String> validDownTypes = new Vector<String>();
			for(int typeIndex = 0;typeIndex < types.size();typeIndex++)
			{
				// get the to neighbors
				validDownTypes.addAll((coreEngine.getToNeighbors(types.get(typeIndex), 0)));
				validUpTypes.addAll((coreEngine.getFromNeighbors(types.get(typeIndex), 0)));			
			}

			// no check for data yet.. will get to it later

			validUpTypes = (Vector<String>) Utility.getTransformedNodeNamesList(coreEngine, validUpTypes, true);
			validDownTypes = (Vector<String>) Utility.getTransformedNodeNamesList(coreEngine, validDownTypes, true);
			
			finalTypes.put("upstream", validUpTypes);
			finalTypes.put("downstream", validDownTypes);

			// and action..

		}		
		return Response.status(200).entity(WebUtility.getSO(finalTypes)).build();
	}

	/**
	 * Gets all the insights for a given perspective, instance, type, or tag (in that order) in the given engine
	 * 
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
			//				// do the logic to get the stuff
			//				String query = param.getQuery();
			//				// do a bifurcation based on the engine
			//				if(coreEngine.getEngineType() == IEngine.ENGINE_TYPE.SESAME || coreEngine.getEngineType() == IEngine.ENGINE_TYPE.JENA || coreEngine.getEngineType() == IEngine.ENGINE_TYPE.SEMOSS_SESAME_REMOTE)
			//					optionsHash.put(param.getName(), coreEngine.getParamValues(param.getName(), param.getType(), in.getId(), query));
			//				else if(coreEngine.getEngineType() == IEngine.ENGINE_TYPE.RDBMS)
			//				{
			//					String type = param.getType();
			//					type = type.substring(type.lastIndexOf(":") + 1);
			//					optionsHash.put(param.getName(), coreEngine.getParamValues(param.getName(), type, in.getId(), query));
			//				}
			//			}
			else {
				optionsHash.put(param.getName(), "");
			}
			paramsHash.put(param.getName(), param);
		}


		// OLD LOGIC
		// get the sparql parameters now
		/*Hashtable paramHash = Utility.getParamTypeHash(in.getSparql());
		Iterator <String> keys = paramHash.keySet().iterator();
		Hashtable newHash = new Hashtable();
		while(keys.hasNext())
		{
			String paramName = keys.next();
			String paramType = paramHash.get(paramName) + "";
			newHash.put(paramName, coreEngine.getParamValues(paramName, paramType, in.getId()));
		}*/
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
	
	@GET
	@Path("getValuesOfType")
	@Produces("application/json")
	public Response getListOfValues(@QueryParam("nodeUri") String nodeUri, @QueryParam("parentUri") String parentUri)
	{
		Vector<Object> retList = null;
		if(this.coreEngine.getEngineType().equals(ENGINE_TYPE.RDBMS)) {
			String type = Utility.getInstanceName(nodeUri);
			if(parentUri == null || parentUri.isEmpty()) {
				// the nodeUri is a concept
				retList = this.coreEngine.getEntityOfType(type);
			} else {
				String parent = Utility.getInstanceName(parentUri);
				type += ":" + parent;
				retList = this.coreEngine.getEntityOfType(type);
			}
		} else {
			// it is a sparql query
			if(parentUri == null || parentUri.isEmpty()) {
				// the nodeUri is a concept
				retList = this.coreEngine.getEntityOfType(nodeUri);
			} else {
				// the nodeUri is a property
				// need to get all property values that pertain to a concept
				String query = "SELECT DISTINCT ?ENTITY WHERE {?P <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ," +
							parentUri + ">} {?P <" + nodeUri + "> ?ENTITY} }";
				// getCleanSelect is not on interface, but only on abstract engine
				retList = ((AbstractEngine) this.coreEngine).getCleanSelect(query);
			}
		}
		
		return Response.status(200).entity(WebUtility.getSO(retList)).build();
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

				Object obj = null;
				try {
					List<String> allSheets = PlaySheetRDFMapBasedEnum.getAllSheetNames();
					String dmName = InsightsConverter.getDataMaker(playsheet, allSheets);
					Insight in = new Insight(coreEngine, dmName, playsheet);
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
					errorHash.put("Class", className);
					return Response.status(500).entity(WebUtility.getSO(errorHash)).build();
				}
				return Response.status(200).entity(WebUtility.getSO(obj)).build();
			}
			else{
				Hashtable<String, String> errorHash = new Hashtable<String, String>();
				errorHash.put("Message", "No question defined.");
				errorHash.put("Class", className);
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
		}
		else {
			//Get the Insight, grab its ID
			Insight insightObj = ((AbstractEngine)coreEngine).getInsight(insight).get(0);
			Map<String, List<Object>> params = gson.fromJson(form.getFirst("params"), new TypeToken<Map<String, List<Object>>>() {}.getType());
			params = Utility.getTransformedNodeNamesMap(coreEngine, params, false);
			insightObj.setParamHash(params);

			// check if the insight has already been cached
			System.out.println("Params is " + params);
			String fileName = CacheAdmin.getFileName(coreEngine.getEngineName(), insightObj.getDatabaseID(), insight, params, FileType.VIZ_DATA);
			File f = new File(fileName);
			Object obj = null;
			if(f.exists() && !f.isDirectory()) {
				// insight has been cached, send it to the FE with a new insight id
				String id = InsightStore.getInstance().put(insightObj);
				String s = CacheAdmin.readFromFileString(fileName);
				Map<String, Object> uploaded = gson.fromJson(s, new TypeToken<Map<String, Object>>() {}.getType());
				uploaded.put("insightID", id);
				return Response.status(200).entity(WebUtility.getSO(uploaded)).build();
			} else {
				// insight visualization data has not been cached, run the insight
				try {
					InsightStore.getInstance().put(insightObj);
					InsightCreateRunner run = new InsightCreateRunner(insightObj);
					obj = run.runWeb();
					// save the insight data to cache it for faster retrieval
					CacheAdmin.createDirectory(coreEngine.getEngineName(), insight);
					Map saveObj = new HashMap();
					saveObj.putAll((Map)obj);
					saveObj.put("insightID", null);
					CacheAdmin.writeToFile(fileName, saveObj);
					// now cache the graph of the insight for faster retrieval
					IDataMaker dataTable = insightObj.getDataMaker();
					if(dataTable instanceof TinkerFrame) {
						String file = CacheAdmin.getFileName(insightObj.getEngineName(), insightObj.getDatabaseID(), insightObj.getRdbmsId(), insightObj.getParamHash(), FileType.GRAPH_DATA);
						((TinkerFrame)dataTable).save(file);
					}
				} catch (Exception ex) { //need to specify the different exceptions 
					ex.printStackTrace();
					Hashtable<String, String> errorHash = new Hashtable<String, String>();
					errorHash.put("Message", "Error occured processing question.");
					errorHash.put("Class", className);
					return Response.status(500).entity(WebUtility.getSO(errorHash)).build();
				}
			}

			return Response.status(200).entity(WebUtility.getSO(obj)).build();
		}
	}
	
	@POST
	@Path("createDataFrame")
	@Produces("application/json")
	public Response createDataFrame(MultivaluedMap<String, String> form, @Context HttpServletRequest request, @Context HttpServletResponse response)
	{
		String insightId = form.getFirst("insightId");
		String origInsight = form.getFirst("origId");
		// grab the cached insight object from insight store
		if(InsightStore.getInstance().get(insightId) != null) {
			Insight insightObj = InsightStore.getInstance().get(insightId);
			if(insightObj != null) {
				// get the params to run the insight with
				Map<String, List<Object>> params = insightObj.getParamHash();
				System.out.println("Params is " + params);
				//grab the file name associated with the tinker graph
				String fileName = CacheAdmin.getFileName(coreEngine.getEngineName(), insightObj.getDatabaseID(), origInsight, params, FileType.GRAPH_DATA);
				File f = new File(fileName);
				// if that graph cache exists load it and sent to the FE
				if(f.exists() && !f.isDirectory()) {
					insightObj.setParamHash(params);
					insightObj.setDataMaker(TinkerFrame.open(fileName));
				} 
				// the graph is not cached, so create it from dm components 
				else {
					try {
						insightObj.setParamHash(params);
						InsightCreateRunner run = new InsightCreateRunner(insightObj);
						run.runWeb();
						// now cache the graph of the insight for faster retrieval
						IDataMaker dataTable = insightObj.getDataMaker();
						if(dataTable instanceof TinkerFrame) {
							String file = CacheAdmin.getFileName(insightObj.getEngineName(), insightObj.getDatabaseID(), insightObj.getRdbmsId(), insightObj.getParamHash(), FileType.GRAPH_DATA);
							((TinkerFrame)dataTable).save(file);
						}
					} catch (Exception ex) {
						ex.printStackTrace();
						Hashtable<String, String> errorHash = new Hashtable<String, String>();
						errorHash.put("Message", "Error occured processing question.");
						errorHash.put("Class", className);
						return Response.status(500).entity(WebUtility.getSO(errorHash)).build();
					}
				}
			}
		}
		return Response.status(200).entity(WebUtility.getSO(insightId)).build();
	}
	
	// executes a particular insight
	@GET
	@Path("outputs")
	@Produces("application/json")
	public StreamingOutput listOutputs()
	{
		// pulls the list of outputs and gives it back
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
			for(int i = 0; i < paramBind.length; i++){
				String paramValueStr = coreEngine.getTransformedNodeName(paramValue[i], false);
				if(coreEngine.getEngineType() == ENGINE_TYPE.RDBMS){
					paramValueStr = Utility.getInstanceName(paramValueStr);
				}
				query = query.replaceAll(paramBind[i], paramValueStr);
			}
		}
		System.out.println(query);
		return Response.status(200).entity(WebUtility.getSO(RDFJSONConverter.getSelectAsJSON(query, coreEngine))).build();
	}	

	// runs a query against the engine while filtering out everything included in baseHash
	@POST
	@Path("querys/filter/noBase")
	@Produces("application/json")
	public Response queryDataSelectWithoutBase(MultivaluedMap<String, String> form)
	{
		// create and set the filter class
		// send the query
		SPARQLExecuteFilterNoBaseFunction filterFunction = new SPARQLExecuteFilterNoBaseFunction();
		filterFunction.setEngine(coreEngine);
		if(coreEngine instanceof AbstractEngine)
			filterFunction.setFilterHash(((AbstractEngine)coreEngine).getBaseHash());
		System.out.println(form.getFirst("query"));
		return Response.status(200).entity(WebUtility.getSO(filterFunction.process(form.getFirst("query")+""))).build();
	}	

	// runs a query against the engine while filtering out everything included in baseHash
	@POST
	@Path("querys/filter/onlyBase")
	@Produces("application/json")
	public Response queryDataSelectOnlyBase(MultivaluedMap<String, String> form)
	{
		// create and set the filter class
		// send the query
		SPARQLExecuteFilterBaseFunction filterFunction = new SPARQLExecuteFilterBaseFunction();
		filterFunction.setEngine(coreEngine);
		if(coreEngine instanceof AbstractEngine)
			filterFunction.setFilterHash(((AbstractEngine)coreEngine).getBaseHash());
		System.out.println(form.getFirst("query"));

		return Response.status(200).entity(WebUtility.getSO(filterFunction.process(form.getFirst("query")+""))).build();
	}	

	// gets a particular insight
	// not sure if I should keep it as it is or turn this into a post because of the query
	@GET
	@Path("queryc")
	@Produces("application/json")
	public StreamingOutput queryDataConstruct(@QueryParam("query") String query)
	{
		// returns the insight
		// based on the current ID get the data
		// typically is a JSON of the insight
		// this will also cache it
		// now should I assume the dude is always trying to get a graph
		// need discussion with the team
		return null;
	}	

	@POST
	@Path("update")
	@Produces("application/json")
	public Response insertData2DB(MultivaluedMap<String, String> form)
	{
		// returns the insight
		// based on the current ID get the data
		// typically is a JSON of the insight
		// this will also cache it
		SesameJenaUpdateWrapper wrapper = new SesameJenaUpdateWrapper();
		wrapper.setEngine(coreEngine);
		wrapper.setQuery(form.getFirst("query")+"");
		boolean success = wrapper.execute();
		if(success) {
			return Response.status(200).entity(WebUtility.getSO("success")).build();
		} else {
			Hashtable<String, String> errorHash = new Hashtable<String, String>();
			errorHash.put("Message", "Error processing query.");
			errorHash.put("Class", className);
			return Response.status(500).entity(WebUtility.getSO(errorHash)).build();
		}
	}	

	// gets all numeric properties associated with a specific node type
	@GET
	@Path("properties/node/type/numeric")
	@Produces("application/json")
	public Response getNumericNodeProperties(
			@QueryParam("nodeType")  String nodeUri)
	{
		String nodePropQuery = "SELECT DISTINCT ?entity WHERE {{?source <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <@NODE_TYPE_URI@>} {?entity <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Relation/Contains>} {?source ?entity ?prop } FILTER(ISNUMERIC(?prop))}";

		//fill the query
		String query = nodePropQuery.replace("@NODE_TYPE_URI@", nodeUri);
		logger.info("Running node property query " + query);
		return Response.status(200).entity(WebUtility.getSO(Utility.getVectorOfReturn(query, coreEngine, true))).build();
	}	

	// gets all numeric edge properties for a specific edge type
	@GET
	@Path("properties/edge/type/numeric")
	@Produces("application/json")
	public Response getNumericEdgeProperties(
			@QueryParam("source")  String sourceTypeUri,
			@QueryParam("target")  String targetTypeUri,
			@QueryParam("verb")  String verbTypeUri)
	{
		String edgePropQuery = "SELECT DISTINCT ?entity WHERE {{?source <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <@SOURCE_TYPE@>} {?target <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <@TARGET_TYPE@>} {?verb <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> <@VERB_TYPE@>}{?source ?verb ?target;} {?entity <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Relation/Contains>} {?verb ?entity ?prop } FILTER(ISNUMERIC(?prop))}";

		//fill the query
		String query = edgePropQuery.replace("@SOURCE_TYPE@", sourceTypeUri).replace("@TARGET_TYPE@", targetTypeUri).replace("@VERB_TYPE@", verbTypeUri);
		logger.info("Running edge property query " + query);
		return Response.status(200).entity(WebUtility.getSO(Utility.getVectorOfReturn(query, coreEngine, true))).build();
	}

	@POST
	@Path("/undoInsightProcess")
	@Produces("application/xml")
	public Response undoInsightProcess(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Gson gson = new Gson();
		String insightID = form.getFirst("insightID");
		List<String> processes = gson.fromJson(form.getFirst("processes"), List.class);

		if(insightID == null || insightID.isEmpty()) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("errorMessage", "Could not find data.");
			return Response.status(400).entity(WebUtility.getSO(errorMap)).build();
		}
		
		if(processes.isEmpty()) {
			Map<String, Object> retMap = new HashMap<String, Object>();
			retMap.put("insightID", insightID);
			retMap.put("message", "Succesfully undone processes : " + processes);
			return Response.status(200).entity(WebUtility.getSO(retMap)).build();
		}

		Insight in = InsightStore.getInstance().get(insightID);
		Map<String, Object> retMap = new HashMap<String, Object>();
		in.undoProcesses(processes);
		retMap.put("insightID", insightID);
		retMap.put("message", "Succesfully undone processes : " + processes);
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}

	@POST
	@Path("/filterData")
	@Produces("application/json")
	public Response filterData(MultivaluedMap<String, String> form,
			@Context HttpServletRequest request)
	{	
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

		ITableDataFrame mainTree = (ITableDataFrame) existingInsight.getDataMaker();
		if(mainTree == null) {
			return Response.status(400).entity(WebUtility.getSO("table not found for insight id. Data not found")).build();
		}

		Gson gson = new Gson();

		//Grab the filter model from the form data
		Map<String, Map<String, Object>> filterModel = gson.fromJson(form.getFirst("filterValues"), new TypeToken<Map<String, Map<String, Object>>>() {}.getType());
		
		//If the filter model has information, filter the tree
		if(filterModel != null && filterModel.keySet().size() > 0) {
			TableDataFrameUtilities.filterTableData(mainTree, filterModel);
		}

		//if the filtermodel is not null and contains no data then unfilter the whole tree
		//this trigger to unfilter the whole tree was decided between FE and BE for simplicity
		//TODO: make this an explicit call
		else if(filterModel != null && filterModel.keySet().size() == 0) {
			//unfilter the tree
		} 

		Map<String, Object> retMap = new HashMap<String, Object>();

		Object[] returnFilterModel = ((TinkerFrame)mainTree).getFilterModel();
//		((BTreeDataFrame)mainTree).printTree();
		retMap.put("unfilteredValues", returnFilterModel[0]);
		retMap.put("filteredValues", returnFilterModel[1]);
		
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}


	
	@GET
	@Path("/unfilterColumns")
	@Produces("application/json")
	public Response getVisibleValues(MultivaluedMap<String, String> form,
			@QueryParam("insightID") String insightID,
			@QueryParam("concept") String[] concepts)
	{
		Insight insight = InsightStore.getInstance().get(insightID);
		Map<String, Object> retMap = new HashMap<String, Object>();
		if(insight == null) {
			retMap.put("errorMessage", "Invalid insight ID. Data not found");
			return Response.status(400).entity(WebUtility.getSO(retMap)).build();
		}
		ITableDataFrame mainTree = (ITableDataFrame) insight.getDataMaker();

		for(String concept: concepts) {
			mainTree.unfilter(concept);
		}

		retMap.put("insightID", insightID);
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}

	@GET
	@Path("/getFilterValues")
	@Produces("application/json")
	public Response getNextUniqueValues(@QueryParam("insightID") String insightID,
			@QueryParam("concept") String concept,
			@QueryParam("filterEnabled") String FilterEnabled,
			@Context HttpServletRequest request)
	{
		Insight insight = InsightStore.getInstance().get(insightID);
		Map<String, Object> retMap = new HashMap<String, Object>();
		if(insight == null) {
			retMap.put("errorMessage", "Invalid insight ID. Data not found");
			return Response.status(400).entity(WebUtility.getSO(retMap)).build();
		}
		ITableDataFrame mainTree = (ITableDataFrame) insight.getDataMaker();
		Iterator<Object> uniqueValueIterator = mainTree.uniqueValueIterator(concept, true, false);
		List<Object> selectedFilterValues = new ArrayList<Object>();
		while(uniqueValueIterator.hasNext()) {
			selectedFilterValues.add(uniqueValueIterator.next());
		}

		String[] columnHeaders = mainTree.getColumnHeaders();
		List<Object> availableFilterValues = new ArrayList<Object>();

		//TODO: why is this always true?
		boolean filterEnabled = true;
		if(!filterEnabled || (filterEnabled && concept.equalsIgnoreCase(columnHeaders[0]))) {
			uniqueValueIterator = mainTree.uniqueValueIterator(concept, true, true);
			while(uniqueValueIterator.hasNext()) {
				availableFilterValues.add(uniqueValueIterator.next());
			}
		} else {
			availableFilterValues = selectedFilterValues;
		}

		retMap.put("insightID", insightID);
		retMap.put("selectedFilterValues", selectedFilterValues);
		retMap.put("availableFilterValues", availableFilterValues);

		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}

	@POST
	@Path("/getNextTableData")
	@Produces("application/json")
	public Response getNextTable(MultivaluedMap<String, String> form,
			@QueryParam("startRow") Integer startRow,
			@QueryParam("endRow") Integer endRow,
			@QueryParam("insightID") String insightID,
			@Context HttpServletRequest request)
	{
		Insight insight = InsightStore.getInstance().get(insightID);
		Map<String, Object> retMap = new HashMap<String, Object>();
		if(insight == null) {
			retMap.put("errorMessage", "Invalid insight ID. Data not found");
			return Response.status(400).entity(WebUtility.getSO(retMap)).build();
		}
		ITableDataFrame mainTree = (ITableDataFrame) insight.getDataMaker();

		Gson gson = new Gson();
		String concept = null;
		String sort = null;

		try {
			Map<String, String> sortModel = gson.fromJson(form.getFirst("sortModel"), new TypeToken<Map<String, String>>() {}.getType());
			concept = sortModel.get("colId");
			sort = sortModel.get("sort");
		} catch (Exception e) {
			sort = "asc";
		}

		Map<String, Object> valuesMap = new HashMap<String, Object>();
		List<HashMap<String, Object>> tableData = TableDataFrameUtilities.getTableData(mainTree, concept, sort, startRow, endRow);
		valuesMap.put(insightID, tableData);

		List<Map<String, String>> headerInfo = new ArrayList<Map<String, String>>();
		String[] varKeys = mainTree.getColumnHeaders();
		String[] uriKeys = mainTree.getURIColumnHeaders();
		for(int i = 0; i < varKeys.length; i++) {
			Map<String, String> innerMap = new HashMap<String, String>();
			innerMap.put("uri", uriKeys[i]);
			innerMap.put("varKey", varKeys[i]);
			headerInfo.add(innerMap);
		}

		retMap.put("insightID", insightID);
		retMap.put("numRows", tableData.size());
		retMap.put("headers", headerInfo);
		retMap.put("tableData", valuesMap);

		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}

	@POST
	@Path("/derivedColumn")
	@Produces("application/json")
	public Response derivedColumn(MultivaluedMap<String, String> form,
			@QueryParam("insightID") String insightID,
			@QueryParam("expressionString") String expressionString,
			@QueryParam("columnName") String columnName,
			@Context HttpServletRequest request)
	{
		String expString = form.getFirst("expressionString");
		String colName = form.getFirst("columnName");
		
		Insight insight = InsightStore.getInstance().get(insightID);
		Map<String, Object> retMap = new HashMap<String, Object>();
		if(insight == null) {
			retMap.put("errorMessage", "Invalid insight ID. Data not found");
			return Response.status(400).entity(WebUtility.getSO(retMap)).build();
		}
		ITableDataFrame mainTree = (ITableDataFrame) insight.getDataMaker();

		// return new column
		EquationSolver solver;
		try { solver = new EquationSolver(mainTree, expString); } 
		catch (ParseException e) {
			retMap.put("errorMessage", e);
			return Response.status(400).entity(WebUtility.getSO(retMap)).build();
		}
		
		String returnMsg = solver.crunch(colName);
		
		retMap.put("status", returnMsg);
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}
	
	@GET
	@Path("/getTableHeaders")
	@Produces("application/json")
	public Response getTableHeaders(@QueryParam("insightID") String insightID) {
		Insight insight = InsightStore.getInstance().get(insightID);
		Map<String, Object> retMap = new HashMap<String, Object>();
		if(insight == null) {
			retMap.put("errorMessage", "Invalid insight ID. Data not found");
			return Response.status(400).entity(WebUtility.getSO(retMap)).build();
		}
		ITableDataFrame table = (ITableDataFrame) insight.getDataMaker();	

		List<Map<String, String>> tableHeaders = new ArrayList<Map<String, String>>();
		String[] columnHeaders = table.getColumnHeaders();
		String[] uriKeys = table.getURIColumnHeaders();
		for(int i = 0; i < columnHeaders.length; i++) {
			Map<String, String> innerMap = new HashMap<String, String>();
			innerMap.put("uri", uriKeys[i]);
			innerMap.put("varKey", columnHeaders[i]);
			tableHeaders.add(innerMap);
		}

		retMap.put("insightID", insightID);
		retMap.put("tableHeaders", tableHeaders);
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}

	@POST
	@Path("/addData")
	@Produces("application/json")
	public Response addData(MultivaluedMap<String, String> form, 
			@QueryParam("existingConcept") String currConcept, 
			@QueryParam("joinConcept") String equivConcept, 
			@QueryParam("joinType") String joinType,
			@QueryParam("insightID") String insightID,
			@Context HttpServletRequest request)
	{
		equivConcept = Utility.getInstanceName(equivConcept);
		Gson gson = new Gson();
		Hashtable<String, Object> dataHash = gson.fromJson(form.getFirst("QueryData"), new TypeToken<Hashtable<String, Object>>() {}.getType());
		QueryBuilderData data = new QueryBuilderData(dataHash);

		// Very simply, here is the logic:
		// 1. If no insight ID is passed in, we create a new Insight and put in the store. Also, if new insight, we know there are no transformations
		// 2. Else, we get the insight from session (if the insight isn't in session, we are done--throw an error)
		// 2. a. If its not an outer join, add a filter transformation with all instances from other column in order to speed up join
		// 2. b. Add join transformation since we know a tree already exists and we will have to join to it
		// 3. Process the component

		// get the insight if an id has been passed
		Insight insight = null;
		DataMakerComponent dmc = new DataMakerComponent(this.coreEngine, data);
		
		// put join concept into dataHash so we know which varible needs to be first in the return
		// this stems from the fact that btree can only join left to right.
//		List<String> retOrder = new ArrayList<String>();
//		//I need the physical name to be put into the retOrder, so append the displayname uri and assume that the value in equivConcept is potentially a display name, if its not we'll still get the physical name back...
//		String physicalEquivConcept = Utility.getInstanceName(this.coreEngine.getTransformedNodeName(Constants.DISPLAY_URI + equivConcept , false));
//		retOrder.add(physicalEquivConcept);
//		data.setVarReturnOrder(retOrder);
		// Shouldn't this just be logical name...?
		data.setVarReturnOrder(equivConcept, 0);
		
		// need to remove filter and add that as a pretransformation. Otherwise our metamodel data is not truly clean metamodel data
		Map<String, List<Object>> filters = data.getFilterData();
		
		if(filters != null){
			for(String filterCol : filters.keySet()){
				Map<String, Object> transProps = new HashMap<String, Object>();
				transProps.put(FilterTransformation.COLUMN_HEADER_KEY, filterCol);
				transProps.put(FilterTransformation.VALUES_KEY, Utility.getTransformedNodeNamesList(this.coreEngine, filters.get(filterCol), false));
				ISEMOSSTransformation filterTrans = new FilterTransformation();
				filterTrans.setProperties(transProps);
				dmc.addPreTrans(filterTrans);
			}
		}

		ISEMOSSTransformation joinTrans = null;
		// 1. If no insight ID is passed in, we create a new Insight and put in the store. Also, if new insight, we know there are no transformations
		if(insightID == null || insightID.isEmpty()) {
			insight = new Insight(this.coreEngine, "TinkerFrame", PlaySheetRDFMapBasedEnum.getSheetName("Grid")); // TODO: this needs to be an enum or grabbed from rdf map somehow
			insightID = InsightStore.getInstance().put(insight);
		} 

		// 2. Else, we get the insight from session (if the insight isn't in session, we are done--throw an error)
		else {
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
		if(joinTrans==null) {
			retMap.put("stepID", dmc.getId());
		} else {
			retMap.put("stepID", joinTrans.getId());
		}
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}

	@POST
	@Path("customFilterOptions")
	@Produces("application/json")
	public Response getCustomFilterOptions(MultivaluedMap<String, String> form, 
			@QueryParam("existingConcept") String currConcept, 
			@QueryParam("joinType") String joinType,
			@QueryParam("insightID") String insightID,
			//@QueryParam("reset") boolean reset,
			@Context HttpServletRequest request)
	{

		// first check if existingConcept exists in infiniteScroller
		// if reset 

		Gson gson = new Gson();
		Map<String, Object> dataHash = gson.fromJson(form.getFirst("QueryData"), new TypeToken<Map<String, Object>>() {}.getType());
		QueryBuilderData data = new QueryBuilderData(dataHash);
		
		boolean outer = false;
		boolean inner = false;
		if(joinType.equals("outer")) {
			outer = true;
		} else if(joinType.equals("inner")) {
			inner = true;
		}

		IQueryBuilder builder = this.coreEngine.getQueryBuilder();
		Insight existingInsight = null;
		if(insightID != null && !insightID.isEmpty() && !outer) {
			existingInsight = InsightStore.getInstance().get(insightID);
			Map<String, String> errorHash = new HashMap<String, String>();
			if(existingInsight == null) {
				errorHash.put("errorMessage", "Existing insight based on passed insightID is not found");
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}

			// need to add bindings for query if not outer join
			ITableDataFrame existingData = (ITableDataFrame) existingInsight.getDataMaker();
			if(existingData == null) {
				errorHash.put("errorMessage", "Dataframe not found within insight");
				return Response.status(400).entity(errorHash).build();
			}

			if(currConcept != null && !currConcept.isEmpty()) {
				List<Object> filteringValues = Arrays.asList(existingData.getUniqueRawValues(currConcept));
				//				HttpSession session = request.getSession();
				//				if(session.getAttribute(tableID) == null) {
				//					session.setAttribute(tableID, InfiniteScrollerFactory.getInfiniteScroller(existingData));
				//				}
				//				
				//				InfiniteScroller scroller = (InfiniteScroller)session.getAttribute(tableID);
				//				List<HashMap<String, String>> filteringValues = scroller.getNextUniqueValues(currConcept);
				//								
				Map<String, List<Object>> stringMap = new HashMap<String, List<Object>>();
				stringMap.put(currConcept, filteringValues);
				data.setFilterData(stringMap);
			} else {
				errorHash.put("errorMessage", "Cannot perform filtering when current concept to filter on is not defined");
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
		}

		builder.setBuilderData(data);
		builder.buildQuery();
		String query = builder.getQuery();

		System.out.println(query);

		ISelectWrapper wrap = WrapperManager.getInstance().getSWrapper(this.coreEngine, query);
		String[] newNames = wrap.getVariables();
		int index = 0;
		if(newNames.length > 1) {
			int currIndexexistingConcept = 0;

			currIndexexistingConcept = ArrayUtilityMethods.arrayContainsValueAtIndexIgnoreCase(newNames, currConcept);
			if(currIndexexistingConcept == 0) {
				index = 1;
			}
		} 

		// creating new list of values from query
		Set<Object> retList = new HashSet<Object>();
		while (wrap.hasNext()) {
			ISelectStatement iss = wrap.next();
			Object value = iss.getRawVar(newNames[index]);
			if(inner && value.toString().isEmpty()) {
				continue; // don't add empty values as a possibility
			}
			if(value instanceof BigdataURI) {
				retList.add( ((BigdataURI)value).stringValue());
			} else {
				retList.add(iss.getVar(newNames[index]));
			}
		}

		return Response.status(200).entity(WebUtility.getSO(retList)).build();
	}

	// author: jason
	@POST
	@Path("searchColumn")
	@Produces("application/json")
	public Response searchColumn(MultivaluedMap<String, String> form,
			@QueryParam("existingConcept") String currConcept,
			@QueryParam("joinConcept") String equivConcept, 
			@QueryParam("columnHeader") String newConcept,
			@QueryParam("joinType") String joinType, 
			@QueryParam("insightID") String insightID, 
			@QueryParam("searchTerm") String searchTerm, 
			@QueryParam("limit") String limit, 
			@QueryParam("offset") String offset,
			@Context HttpServletRequest request) {
		
		equivConcept = Utility.getInstanceName(equivConcept);
		newConcept = Utility.getInstanceName(newConcept);
		HttpSession session = request.getSession();
		// check if a result set has been cached
		if (session.getAttribute(InstanceStreamer.KEY) != null) {
			InstanceStreamer stream = (InstanceStreamer) session.getAttribute(InstanceStreamer.KEY);
			String ID = stream.getID();
			// check if appropriate set has been cached
			if (ID != null && ID.equals(Utility.getInstanceName(newConcept)) && !newConcept.equals("")) {
				logger.info("Fetching cached results for ID: "+stream.getID());
				if (!searchTerm.equals("") && searchTerm != null) {

					logger.info("Searching cached results for searchTerm: "+searchTerm);
					ArrayList<Object> results = stream.search(searchTerm);
					stream = new InstanceStreamer(results);
					logger.info(Integer.toString(stream.getSize())+" results found.");
				}

				logger.info("Returning items "+offset+" through "+Integer.toString(Integer.parseInt(offset) + Integer.parseInt(limit))+".");
				ArrayList<Object>  uniqueResults = stream.getUnique(Integer.parseInt(offset), (Integer.parseInt(offset) + Integer.parseInt(limit)));
				Map<String, Object> returnData = new HashMap<String, Object>();
				returnData.put("data", uniqueResults);
				returnData.put("size", stream.getSize());
				return Response.status(200).entity(WebUtility.getSO(returnData)).build();
			}
		}
		
		Gson gson = new Gson();
		Hashtable<String, Object> dataHash = gson.fromJson(form.getFirst("QueryData"), new TypeToken<Hashtable<String, Object>>() {}.getType());
		QueryBuilderData data = new QueryBuilderData(dataHash);
		data.setVarReturnOrder(newConcept, 0);
		data.setLimitReturnToVarsList(true);

		// Very simply, here is the logic:
		// 1. If no insight ID is passed in, we create a new Insight and put in the store. Also, if new insight, we know there are no transformations
		// 2. Else, we get the insight from session (if the insight isn't in session, we are done--throw an error)
		// 2. a. If its not an outer join, add a filter transformation with all instances from other column in order to speed up join
		// 2. b. Add join transformation since we know a tree already exists and we will have to join to it
		// 3. Process the component

		// get the insight if an id has been passed
		Insight insight = null;
		DataMakerComponent dmc = new DataMakerComponent(this.coreEngine, data);

		ISEMOSSTransformation joinTrans = null;
		// 1. If no insight ID is passed in, we create a new Insight and put in the store. Also, if new insight, we know there are no transformations
		if(insightID == null || insightID.isEmpty()) {
			insight = new Insight(this.coreEngine, "TinkerFrame", PlaySheetRDFMapBasedEnum.getSheetName("Grid")); // TODO: this needs to be an enum or grabbed from rdf map somehow
		} 

		// 2. Else, we get the insight from session (if the insight isn't in session, we are done--throw an error)
		else {
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
		
		ITableDataFrame table = (ITableDataFrame) insight.getDataMaker();
		table.processPreTransformations(dmc, dmc.getPreTrans());
		String query = dmc.fillQuery();
		
		System.out.println("FINAL SEARCH COLUMN QUERY ::: " + query);

		ISelectWrapper wrap = WrapperManager.getInstance().getSWrapper(this.coreEngine, query);
		String[] displayNames = wrap.getDisplayVariables();

		// creating new list of values from query
		ArrayList<Object> retList = new ArrayList<Object>();
		while (wrap.hasNext()) {
			ISelectStatement iss = wrap.next();
			Object value = iss.getRawVar(displayNames[0]);
			if (value instanceof BigdataURI) {
				retList.add(((BigdataURI) value).stringValue());
			} else {
				retList.add(value);//retList.add(iss.getVar(newNames[index]));
			}
		}

		// put everything into InstanceStreamer object
		InstanceStreamer stream = new InstanceStreamer(retList);
		logger.info("Creating InstanceStreamer object with ID: "+newConcept);
		//String ID = Utility.getInstanceName(columnHeader) + Integer.toString(stream.getSize());
		stream.setID(Utility.getInstanceName(newConcept));

		// set InstanceStreamer object
		session.setAttribute(InstanceStreamer.KEY, stream);
		logger.info("Searching column for searchTerm: "+searchTerm);
		if (!searchTerm.equals("") && searchTerm != null) {
			ArrayList<Object> results = stream.search(searchTerm);
			stream = new InstanceStreamer(results);
			logger.info(Integer.toString(stream.getSize())+" results found.");
		}

		logger.info("Returning items "+offset+" through "+Integer.toString(Integer.parseInt(offset) + Integer.parseInt(limit))+".");
		ArrayList<Object>  uniqueResults = stream.getUnique(Integer.parseInt(offset), (Integer.parseInt(offset) + Integer.parseInt(limit)));

		Map<String, Object> returnData = new HashMap<String, Object>();
		returnData.put("data", uniqueResults);
		returnData.put("size", stream.getSize());
		return Response.status(200).entity(WebUtility.getSO(returnData)).build();
	}


	@POST
	@Path("clearCache")
	@Produces("application/json")
	public Response clearCache(@Context HttpServletRequest request) {

		HttpSession session = request.getSession();

		if (session.getAttribute(InstanceStreamer.KEY) != null) {
			InstanceStreamer stream = (InstanceStreamer) session.getAttribute(InstanceStreamer.KEY);
			stream.setID(null);
		}

		Map<String, Object> returnData = new HashMap<String, Object>();
		returnData.put("success", "yes");

		return Response.status(200).entity(WebUtility.getSO(returnData)).build();
	}

	// author: jason
	@POST
	@Path("loadAllFromCache")
	@Produces("application/json")
	public Response loadAllFromCache(
			@QueryParam("searchTerm") String searchTerm, 
			@Context HttpServletRequest request) {

		HttpSession session = request.getSession();

		if (session.getAttribute(InstanceStreamer.KEY) != null) {
			InstanceStreamer stream = (InstanceStreamer) session.getAttribute(InstanceStreamer.KEY);

			if (!searchTerm.equals("") && searchTerm != null) {
				logger.info("Searching column for searchTerm: "+searchTerm);
				ArrayList<Object> results = stream.search(searchTerm);
				stream = new InstanceStreamer(results);
				logger.info(Integer.toString(stream.getSize())+" results found.");
			}

			ArrayList<Object> cachedList = stream.getList();

			Map<String, Object> returnData = new HashMap<String, Object>();
			returnData.put("data", cachedList);
			return Response.status(200).entity(WebUtility.getSO(returnData)).build();
		}
		else {
			return Response.status(400).entity(WebUtility.getSO("Could not find cache")).build();
		}
	}



	@POST
	@Path("getVizTable")
	@Produces("application/json")
	public Response getExploreTable(
			@QueryParam("insightID") String insightID,
			//@QueryParam("start") int start,
			//@QueryParam("end") int end,
			@Context HttpServletRequest request)
	{
		Insight existingInsight = null;
		if(insightID != null && !insightID.isEmpty()) {
			existingInsight = InsightStore.getInstance().get(insightID);
			if(existingInsight == null) {
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "Existing insight based on passed insightID is not found");
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
		}

		ITableDataFrame mainTree = (ITableDataFrame) existingInsight.getDataMaker();		
		if(mainTree == null) {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Dataframe not found within insight");
			return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
		}

		List<HashMap<String, Object>> table = TableDataFrameUtilities.getTableData(mainTree);
//		List<Object[]> table = mainTree.getRawData();
		Map<String, Object> returnData = new HashMap<String, Object>();
		returnData.put("data", table);

		List<Map<String, String>> headerInfo = new ArrayList<Map<String, String>>();
		String[] varKeys = mainTree.getColumnHeaders();
		String[] uriKeys = mainTree.getURIColumnHeaders();
		for(int i = 0; i < varKeys.length; i++) {
			Map<String, String> innerMap = new HashMap<String, String>();
			innerMap.put("uri", uriKeys[i]);
			innerMap.put("varKey", varKeys[i]);
			headerInfo.add(innerMap);
		}
		returnData.put("headers", headerInfo);
		returnData.put("insightID", insightID);
		return Response.status(200).entity(WebUtility.getSO(returnData)).build();
	}

	@GET
	@Path("customVizPathProperties")
	@Produces("application/json")
	public Response getPathProperties(@QueryParam("QueryData") String pathObject, @Context HttpServletRequest request)
	{
		logger.info("Getting properties for path");
		Gson gson = new Gson();
		Hashtable<String, Object> dataHash = gson.fromJson(pathObject, Hashtable.class);
		QueryBuilderData data = new QueryBuilderData(dataHash);
		Object obj = QueryBuilderHelper.getPropsFromPath(this.coreEngine, data);

		//		SPARQLQueryTableBuilder tableViz = new SPARQLQueryTableBuilder();
		//		tableViz.setJSONDataHash(dataHash);
		//		tableViz.setEngine(coreEngine);
		//		Object obj = tableViz.getPropsFromPath();
		return Response.status(200).entity(WebUtility.getSO(obj)).build();
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

	@Path("/generateInsights")
	public Object runAutoGeneratedInsights(){
		AutoGeneratedInsights AutoGeneratedInsights = new AutoGeneratedInsights(this.coreEngine);

		return AutoGeneratedInsights;
	}

	@Path("/explore")
	public Object generateQuery(@Context HttpServletRequest request)
	{
		ExploreQuery exploreQuery = new ExploreQuery(this.coreEngine);

		return exploreQuery;
	}

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

	@POST
	@Path("/btreeTester")
	@Produces("application/xml")
	public StreamingOutput btreeTester(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		//String query1 = form.getFirst("query1");
		//String query2 = form.getFirst("query2");

		String query1 = "SELECT DISTINCT CAPITAL.CAPITAL, LOWERCASE.LOWERCASE, NUMBER.NUMBER FROM CAPITAL, LOWERCASE, NUMBER WHERE CAPITAL.LOWERCASE_FK=LOWERCASE.LOWERCASE AND LOWERCASE.NUMBER_FK=NUMBER.NUMBER;";

		// Movie_DB Query
		// String query1 =
		// "SELECT DISTINCT ?Title ?DomesticRevenue ?InternationalRevenue ?Budget ?RottenTomatoesCritics ?RottenTomatoesAudience WHERE {{?Title <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Title>}{?Title <http://semoss.org/ontologies/Relation/Contains/Revenue-Domestic> ?DomesticRevenue }{?Title <http://semoss.org/ontologies/Relation/Contains/Revenue-International> ?InternationalRevenue}{?Title <http://semoss.org/ontologies/Relation/Contains/MovieBudget> ?Budget}{?Title <http://semoss.org/ontologies/Relation/Contains/RottenTomatoes-Critics> ?RottenTomatoesCritics } {?Title <http://semoss.org/ontologies/Relation/Contains/RottenTomatoes-Audience> ?RottenTomatoesAudience }{?Director <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Director>}{?Genre <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Genre>}{?Nominated <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Nominated>}{?Studio <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Studio>}{?Title <http://semoss.org/ontologies/Relation/DirectedBy> ?Director}{?Title <http://semoss.org/ontologies/Relation/BelongsTo> ?Genre}{?Title <http://semoss.org/ontologies/Relation/Was> ?Nominated}{?Title <http://semoss.org/ontologies/Relation/DirectedAt> ?Studio}} ORDER BY ?Title";

		// TAP_Core_Data Query
		//		String query1 = "SELECT DISTINCT ?ICD ?Source ?Target ?DataObject ?Format ?Frequency ?Protocol WHERE { {?ICD <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/InterfaceControlDocument>} {?Source <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/System>} {?Source <http://semoss.org/ontologies/Relation/Provide> ?ICD} {?Target <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/System>} {?ICD <http://semoss.org/ontologies/Relation/Consume> ?Target} {?DataObject <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/DataObject>} {?carries <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> <http://semoss.org/ontologies/Relation/Payload>} {?carries <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> <http://semoss.org/ontologies/Relation/Payload>} {?ICD ?carries ?DataObject} {?carries <http://semoss.org/ontologies/Relation/Contains/Protocol> ?Protocol} {?carries <http://semoss.org/ontologies/Relation/Contains/Format> ?Format} {?carries <http://semoss.org/ontologies/Relation/Contains/Frequency> ?Frequency} } ORDER BY ?ICD";

		//run query 1
		HttpSession session = request.getSession(false);
		float start = System.currentTimeMillis();

		TinkerFrame tree1 = null;
		String[] names1 = null;
		if(query1 != null){
			ISelectWrapper wrap1 = WrapperManager.getInstance().getSWrapper(this.coreEngine, query1);
			names1 = wrap1.getVariables();
			tree1 = new TinkerFrame(names1);
			while(wrap1.hasNext()) {
				ISelectStatement iss1 = wrap1.next();
				tree1.addRow(iss1.getPropHash(), iss1.getRPropHash());
			}
		}

		float end = System.currentTimeMillis();
		tree1.removeValue("1", "http://semoss.org/ontologies/Concept/Number/1", "NUMBER");
		System.out.println("Construction time = " + ((end-start)/1000) );

		Iterator<Object> capIterator = tree1.uniqueValueIterator("CAPITAL", false, true); // Change for each query
		while (capIterator.hasNext()) {
			System.out.println(capIterator.next().toString());
		}
		Iterator<Object> lowerIterator = tree1.uniqueValueIterator("LOWERCASE", false, true); // Change for each query
		while (lowerIterator.hasNext()) {
			System.out.println(lowerIterator.next().toString());
		}
		Iterator<Object> numIterator = tree1.uniqueValueIterator("NUMBER", false, true); // Change for each query
		while (numIterator.hasNext()) {
			System.out.println(numIterator.next().toString());
		}
		System.out.println("Done");


		String[] columnHeaders = tree1.getColumnHeaders();
		boolean[] isNumeric = tree1.isNumeric();
		DuplicationReconciliation duprec = new DuplicationReconciliation(DuplicationReconciliation.ReconciliationMode.MEAN);
		DuplicationReconciliation duprecMed = new DuplicationReconciliation(DuplicationReconciliation.ReconciliationMode.MEDIAN);
		for(String s : columnHeaders) {

		}

		return null;
	}

	//	@POST
	//	@Path("/publishToFeed")
	//	@Produces("application/xml")
	//	public Response publishInsight(@QueryParam("visibility") String visibility, @QueryParam("insight") String insight, @Context HttpServletRequest request) {
	//		boolean success = false;
	//		String userId = ((User)request.getSession().getAttribute(Constants.SESSION_USER)).getId();
	//		Insight insightObj = ((AbstractEngine)coreEngine).getInsight(insight).get(0);
	//
	//		NameServerProcessor ns = new NameServerProcessor();
	//		success = ns.publishInsightToFeed(userId, insightObj, visibility);
	//
	//		return success ? Response.status(200).entity(WebUtility.getSO(success)).build() : Response.status(400).entity(WebUtility.getSO(success)).build();
	//	}

	@POST
	@Path("/applyColumnStats")
	@Produces("application/json")
	public Response applyColumnStats(MultivaluedMap<String, String> form,  
			@Context HttpServletRequest request)
	{
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

		Gson gson = new Gson();
		//		String groupBy = form.getFirst("groupBy");
		List groupByCols = gson.fromJson(form.getFirst("groupBy"), List.class);
		Map<String, Object> functionMap = gson.fromJson(form.getFirst("mathMap"), new TypeToken<HashMap<String, Object>>() {}.getType());

		// Run math transformation
		ISEMOSSTransformation mathTrans = new MathTransformation();
		Map<String, Object> selectedOptions = new HashMap<String, Object>();
		// groupByCols = columns to look at
		selectedOptions.put(MathTransformation.GROUPBY_COLUMNS, groupByCols);
		// debug through this. also comes from frontend
		selectedOptions.put(MathTransformation.MATH_MAP, functionMap);
		// dont worry about this yet
		mathTrans.setProperties(selectedOptions);
		// just one transformation at a time. for math transformation just one thing
		List<ISEMOSSTransformation> postTrans = new Vector<ISEMOSSTransformation>();
		postTrans.add(mathTrans);
		existingInsight.processPostTransformation(postTrans);

		ITableDataFrame table = (ITableDataFrame) existingInsight.getDataMaker();
		Map<String, Object> retMap = new HashMap<String, Object>();

		retMap.put("tableData", TableDataFrameUtilities.getTableData(table));
		retMap.put("mathMap", functionMap);
		retMap.put("insightID", insightID);
		retMap.put("stepID", mathTrans.getId());
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}

	@POST
	@Path("/hasDuplicates")
	@Produces("application/json")
	public Response hasDuplicates(MultivaluedMap<String, String> form,
			@Context HttpServletRequest request) 
	{
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
		ITableDataFrame table = null;
		try {
			table = (ITableDataFrame) existingInsight.getDataMaker();
		} catch(ClassCastException e) {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Insight data maker could not be cast to a table data frame.");
			return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
		}
		if(table == null) {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Could not find insight data maker.");
			return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
		}

		Gson gson = new Gson();
		String[] columns = gson.fromJson(form.getFirst("concepts"), String[].class);
		String[] columnHeaders = table.getColumnHeaders();
		Map<String, Integer> columnMap = new HashMap<>();
		for(int i = 0; i < columnHeaders.length; i++) {
			columnMap.put(columnHeaders[i], i);
		}

		Iterator<Object[]> iterator = table.iterator(false);
		int numRows = table.getNumRows();
		Set<String> comboSet = new HashSet<String>(numRows);
		int rowCount = 1;
		while(iterator.hasNext()) {
			Object[] nextRow = iterator.next();
			String comboValue = "";
			for(String c : columns) {
				int i = columnMap.get(c);
				comboValue = comboValue + nextRow[i];
			}
			comboSet.add(comboValue);

			if(comboSet.size() < rowCount) {
				return Response.status(200).entity(WebUtility.getSO(true)).build();
			}

			rowCount++;
		}
		boolean hasDuplicates = comboSet.size() != numRows;
		return Response.status(200).entity(WebUtility.getSO(hasDuplicates)).build();
	}
	//	public static void main(String[] args) {
	//		String query1 = "SELECT DISTINCT ?Title  ?DomesticRevenue  WHERE {{?Title <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Title>}{?Title <http://semoss.org/ontologies/Relation/Contains/Revenue-Domestic> ?DomesticRevenue }{?Title <http://semoss.org/ontologies/Relation/Contains/Revenue-International> ?InternationalRevenue}{?Title <http://semoss.org/ontologies/Relation/Contains/MovieBudget> ?Budget}{?Title <http://semoss.org/ontologies/Relation/Contains/RottenTomatoes-Critics> ?RottenTomatoesCritics } {?Title <http://semoss.org/ontologies/Relation/Contains/RottenTomatoes-Audience> ?RottenTomatoesAudience }{?Director <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Director>}{?Genre <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Genre>}{?Nominated <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Nominated>}{?Studio <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Studio>}{?Title <http://semoss.org/ontologies/Relation/DirectedBy> ?Director}{?Title <http://semoss.org/ontologies/Relation/BelongsTo> ?Genre}{?Title <http://semoss.org/ontologies/Relation/Was> ?Nominated}{?Title <http://semoss.org/ontologies/Relation/DirectedAt> ?Studio}} ORDER BY ?Title";
	//
	//		//run query 1
	//		//HttpSession session = request.getSession(false);
	//		BTreeDataFrame tree1 = null;
	//		String[] names1 = null;
	//		if(query1 != null){
	//			ISelectWrapper wrap1 = WrapperManager.getInstance().getSWrapper(this.coreEngine, query1);
	//			names1 = wrap1.getVariables();
	//			tree1 = new BTreeDataFrame(names1);
	//			while(wrap1.hasNext()){
	//				ISelectStatement iss1 = wrap1.next();//
	//				tree1.addRow(iss1.getPropHash(), iss1.getRPropHash());
	//			}
	//		}
	//		
	//		tree1.performAction(new ClusteringRoutine());
	//		List<Object[]> flatData = tree1.getData();
	//		
	//		for(Object[] row: flatData) {
	//			for(Object instance: row) {
	//				System.out.print(instance.toString()+" ");
	//			}
	//			System.out.println();
	//		}
	//	}

	/**
	 * Finds BTree corresponding to either tableID or questionID (insightID) and calls refresh method
	 * 
	 * @param tableID
	 * @param questionID
	 * @param request
	 * @return
	 */
	@POST
	@Path("/sendToExplore")
	@Produces("application/json")
	public Response sendToExplore(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Hashtable<String, Object> returnHash = new Hashtable<String, Object>();
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
		if (dataFrame == null) {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Dataframe not found within insight");
			return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
		}
		dataFrame.refresh();

		List<DataMakerComponent> comps = existingInsight.getDataMakerComponents();
		Map<String, Object> nodesHash = new Hashtable<String, Object>();
		Map<String, Object> triplesHash = new Hashtable<String, Object>();
		for (DataMakerComponent comp : comps){
			String query = comp.getQuery();
			if(query == null){
				QueryBuilderData data = comp.getBuilderData();
				IEngine compEng = comp.getEngine();
				List<List<String>> rels = data.getRelTriples();
				System.out.println(rels);
				for(List<String> rel: rels){
					if(rel.size()>1){
						// store the triple
						Map<String, String> nodeTriples = new Hashtable<String, String>();
						nodeTriples.put("fromNode", rel.get(0));
						nodeTriples.put("relationshipTriple", rel.get(1));
						nodeTriples.put("toNode", rel.get(2));
						triplesHash.put(triplesHash.size()+"", nodeTriples);
						
						//store the object node
						String node2 = rel.get(2);
						String node2Name = Utility.getInstanceName(node2);
						if(!nodesHash.containsKey(node2Name)){
							List<String> node2Props = new Vector<String>();
							Map<String, Object> node2Details = new HashMap<String, Object>();
							node2Details.put("selectedProperties", node2Props);
							node2Details.put("uri", node2);
							node2Details.put("engineName", compEng.getEngineName());
							node2Details.put("engineType", compEng.getEngineType());
							nodesHash.put(node2Name, node2Details);
						}
					}
					//store the subject node
					String node1 = rel.get(0);
					String nodeName = Utility.getInstanceName(node1);
					if(!nodesHash.containsKey(nodeName)){
						List<String> nodeProps = new Vector<String>();
						Map<String, Object> nodeDetails = new HashMap<String, Object>();
						nodeDetails.put("selectedProperties", nodeProps);
						nodeDetails.put("uri", node1);
						nodeDetails.put("engineName", compEng.getEngineName());
						nodeDetails.put("engineType", compEng.getEngineType());
						nodesHash.put(nodeName, nodeDetails);
					}
				}
				
				List<Map<String, String>> nodeProps = data.getNodeProps();
				System.out.println(nodeProps);
				for(Map<String, String> propMap : nodeProps){
					String subjectVar = propMap.get("SubjectVar");
					List<String> nodeProps1 = (List<String>) ((Map<String, Object>) nodesHash.get(subjectVar)).get("selectedProperties");
					nodeProps1.add(Utility.getTransformedNodeName(compEng, (String) propMap.get("uriKey"), true));
				}
			}
			else { // Logic when coming from a preexisting insight that does not have metamodel data
				Hashtable<String, String> nodeTriples;
				Hashtable<String, Object> nodeDetails;
	
				//get the btree headers for comparison against what you want to display back to the user
				List<String> columnHeaders = new ArrayList<String>();
				Collections.addAll(columnHeaders, dataFrame.getColumnHeaders()); 
				//for RDBMS everything is uppercase in the btree
				boolean useUpperCase = false;
				if(ENGINE_TYPE.RDBMS == coreEngine.getEngineType()){
					useUpperCase = true;
				}
				AbstractQueryParser queryParser = ((AbstractEngine) coreEngine).getQueryParser();
				queryParser.setQuery(query);
				queryParser.parseQuery();
				//aggregate functions not eligible for nagivation back through explore
				if(queryParser.hasAggregateFunction()){
					Hashtable<String, String> errorHash = new Hashtable<String, String>();
					errorHash.put("Message", "This Insight is not eligible to navigate through Explore. The query uses aggregation.");
					return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
				}
				Hashtable<String, String> nodes = queryParser.getNodesFromQuery();
				for (String key : nodes.keySet()) {
					String keyNode = key;
					if(useUpperCase){
						keyNode = key.toUpperCase();
					}
					nodeDetails = new Hashtable<String, Object>();
	
					Hashtable<String, Hashtable<String, String>> selectedPropsHash = queryParser.getReturnVariables();
					Hashtable<String, String> selectedProperties = (Hashtable<String,String>) selectedPropsHash.get(key);
					ArrayList<String> nodeProps = new ArrayList<String>();
					if (selectedProperties != null) {
						//selectedProperties is a map of the column alias and its column name the keyset has the alias.  
						for (String singleProperty : selectedProperties.keySet()) {
							nodeProps.add(selectedProperties.get(singleProperty));
						}
					}
					nodeDetails.put("selectedProperties", nodeProps);
					nodeDetails.put("uri", nodes.get(key));
	
					if(!columnHeaders.contains(keyNode)){
						//if the node doesnt exist in the btree, thats fine we just wont add it to the ui, so take no action
						//BUT if the node properties were included in the btree then we should error out
						if(nodeProps.size()>0){
							Hashtable<String, String> errorHash = new Hashtable<String, String>();
							errorHash.put("Message", "This Insight is not eligible to navigate through Explore.");
							return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
						}
					} else {
						nodesHash.put(key, nodeDetails);
					}
				}
	
				ArrayList<String[]> triplesArr = (ArrayList<String[]>) queryParser.getTriplesData();
				int i = 0;
				for (String[] triples : triplesArr) {
					nodeTriples = new Hashtable<String, String>();
					nodeTriples.put("fromNode", triples[0]);
					nodeTriples.put("relationshipTriple", triples[1]);
					nodeTriples.put("toNode", triples[2]);
	
					//get instance from triple and capitalize it for rdbms since btree stores uppercase for rdbms
					String fromNodeInstance = Utility.getInstanceName(triples[0]);
					String toNodeInstance = Utility.getInstanceName(triples[2]);
					if(useUpperCase){
						fromNodeInstance = fromNodeInstance.toUpperCase();
						toNodeInstance = toNodeInstance.toUpperCase();
					}
					if(columnHeaders.contains(fromNodeInstance) && columnHeaders.contains(toNodeInstance)){
						triplesHash.put(Integer.toString(i++), nodeTriples);
					}
				}
	
				// don't need this anymore as insights and tables are not stored separately
//				insightID = InsightStore.getInstance().put(existingInsight); // place btree into ITableDataFrameStore and generate a tableID so that user no longer uses insightID
			}
		}
		returnHash.put("nodes", nodesHash); // Nodes that will be used to build the metamodel in Single-View
		returnHash.put("triples", triplesHash);

		returnHash.put("insightID", existingInsight.getInsightID());

		return Response.status(200).entity(WebUtility.getSO(returnHash)).build();
	}
	
	@GET
    @Path("conceptProperties")
    @Produces("application/json")
    public Response getConceptProperties(@QueryParam("nodeUri") String nodeUri, @Context HttpServletRequest request)
    {
           logger.info("Getting properties for node : " + nodeUri);
           List<String> props = this.coreEngine.getProperties4Concept(nodeUri, true);
           
           return Response.status(200).entity(WebUtility.getSO(props)).build();
    }

}