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

import java.io.FileNotFoundException;
import java.io.IOException;
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

import prerna.algorithm.api.IMatcher;
import prerna.algorithm.api.ITableDataFrame;
import prerna.algorithm.learning.util.DuplicationReconciliation;
import prerna.auth.User;
import prerna.ds.BTreeDataFrame;
import prerna.ds.ExactStringMatcher;
import prerna.ds.ExactStringOuterJoinMatcher;
import prerna.ds.ExactStringPartialOuterJoinMatcher;
import prerna.ds.ITableStatCounter;
import prerna.ds.InfiniteScroller;
import prerna.ds.InfiniteScrollerFactory;
import prerna.ds.MultiColumnTableStatCounter;
import prerna.ds.TableDataFrameStore;
import prerna.engine.api.IEngine;
import prerna.engine.api.IEngine.ENGINE_TYPE;
import prerna.engine.api.ISelectStatement;
import prerna.engine.api.ISelectWrapper;
import prerna.engine.impl.AbstractEngine;
import prerna.engine.impl.rdf.RDFFileSesameEngine;
import prerna.engine.impl.rdf.SesameJenaSelectStatement;
import prerna.engine.impl.rdf.SesameJenaSelectWrapper;
import prerna.engine.impl.rdf.SesameJenaUpdateWrapper;
import prerna.nameserver.AddToMasterDB;
import prerna.nameserver.NameServerProcessor;
import prerna.nameserver.SearchMasterDB;
import prerna.om.Insight;
import prerna.om.SEMOSSParam;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.rdf.query.builder.AbstractQueryBuilder;
import prerna.rdf.query.builder.IQueryBuilder;
import prerna.rdf.query.builder.QueryBuilderHelper;
import prerna.rdf.query.builder.SPARQLQueryTableBuilder;
import prerna.rdf.query.util.SEMOSSQuery;
import prerna.rdf.util.AbstractQueryParser;
import prerna.rdf.util.RDFJSONConverter;
import prerna.semoss.web.form.FormBuilder;
import prerna.ui.components.ExecuteQueryProcessor;
import prerna.ui.components.api.IPlaySheet;
import prerna.ui.components.playsheets.BasicProcessingPlaySheet;
import prerna.ui.components.playsheets.GraphPlaySheet;
import prerna.ui.helpers.PlaysheetCreateRunner;
import prerna.ui.main.listener.impl.SPARQLExecuteFilterBaseFunction;
import prerna.ui.main.listener.impl.SPARQLExecuteFilterNoBaseFunction;
import prerna.util.ArrayUtilityMethods;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.PlaySheetEnum;
import prerna.util.QuestionPlaySheetStore;
import prerna.util.Utility;
//import prerna.web.services.util.ITableUtilities;
import prerna.web.services.util.InMemoryHash;
import prerna.web.services.util.InstanceStreamer;
import prerna.web.services.util.TableDataFrameUtilities;
import prerna.web.services.util.WebUtility;

import com.bigdata.rdf.model.BigdataURI;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.google.gson.internal.StringMap;
import com.google.gson.reflect.TypeToken;

public class EngineResource {

	// gets everything specific to an engine
	// essentially this is a wrapper over the engine
	IEngine coreEngine = null;
	byte[] output ;
	Logger logger = Logger.getLogger(EngineResource.class.getName());
	Hashtable<String, SEMOSSQuery> vizHash = new Hashtable<String, SEMOSSQuery>();
	// to send class name if error occurs
	String className = this.getClass().getName();

	public void setEngine(IEngine coreEngine)
	{
		System.out.println("Setting core engine to " + coreEngine);
		this.coreEngine = coreEngine;
	}

	// All playsheet specific manipulations will go through this
	@Path("p-{playSheetID}")
	public Object uploadFile(@PathParam("playSheetID") String playSheetID) {
		PlaySheetResource psr = new PlaySheetResource();
		// get the playsheet from session
		IPlaySheet playSheet = QuestionPlaySheetStore.getInstance().get(playSheetID);
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

		ExecuteQueryProcessor exQueryProcessor = new ExecuteQueryProcessor();
		//hard code playsheet attributes since no insight exists for this
		String sparql = "SELECT ?s ?p ?o WHERE {?s ?p ?o} LIMIT 1";
		String playSheetName = "prerna.ui.components.playsheets.GraphPlaySheet";
		String title = "Metamodel";
		String id = coreEngine.getEngineName() + "-Metamodel";
		AbstractEngine eng = ((AbstractEngine)coreEngine).getBaseDataEngine();
		eng.setEngineName(id);
		eng.setBaseData((RDFFileSesameEngine) eng);
		Hashtable<String, String> filterHash = new Hashtable<String, String>();
		filterHash.put("http://semoss.org/ontologies/Relation", "http://semoss.org/ontologies/Relation");
		eng.setBaseHash(filterHash);

		exQueryProcessor.prepareQueryOutputPlaySheet(eng, sparql, playSheetName, title, id);
		Object obj = null;
		try
		{
			GraphPlaySheet playSheet= (GraphPlaySheet) exQueryProcessor.getPlaySheet();
			playSheet.getGraphData().setSubclassCreate(true);//this makes the base queries use subclass instead of type--necessary for the metamodel query
			playSheet.createData();
			playSheet.runAnalytics();

			obj = playSheet.getData();

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
				Vector<String> types = Utility.getVectorOfReturn(uniqueTypesQuery, coreEngine);

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
				String camelClassName = Utility.toCamelCase(modClassName);
				// replace it
				className.replace(modClassName, camelClassName);
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
			resultInsightObjects = ((AbstractEngine)coreEngine).getInsight2(resultInsights.toArray(new String[resultInsights.size()]));

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
		Insight in = ((AbstractEngine)coreEngine).getInsight2(insight).get(0);
		System.out.println("Insight is " + in);
		System.out.println(in.getOutput());
		Hashtable outputHash = new Hashtable<String, Hashtable>();
		outputHash.put("result", in);


		Vector <SEMOSSParam> paramVector = coreEngine.getParams(insight);
		System.err.println("Params are " + paramVector);
		Hashtable optionsHash = new Hashtable();
		Hashtable paramsHash = new Hashtable();

		for(int paramIndex = 0;paramIndex < paramVector.size();paramIndex++)
		{
			SEMOSSParam param = paramVector.elementAt(paramIndex);
			String paramURI = param.getUri();
			if(param.isDepends().equalsIgnoreCase("false"))
			{
				optionsHash.put(param.getName(), this.coreEngine.getParamOptions(paramURI));
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
			else
				optionsHash.put(param.getName(), "");
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
		String insight = form.getFirst("insight");
		String userId = ((User)request.getSession().getAttribute(Constants.SESSION_USER)).getId();
		AddToMasterDB addMasterDB = new AddToMasterDB(Constants.LOCAL_MASTER_DB_NAME);
		SearchMasterDB searchMasterDB = new SearchMasterDB(Constants.LOCAL_MASTER_DB_NAME);

		//Get the Insight, grab its ID
		Insight insightObj = ((AbstractEngine)coreEngine).getInsight2(insight).get(0);
		String insightID = null;

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
			String playsheet = form.getFirst("playsheet");
			String sparql = form.getFirst("sparql");
			//check for sparql and playsheet; if not null then parameters have been passed in for preview functionality
			if(sparql != null && playsheet != null){

				ExecuteQueryProcessor exQueryProcessor = new ExecuteQueryProcessor();
				Object obj = null;
				try {
					QuestionPlaySheetStore.getInstance().idCount++;
					insightID = QuestionPlaySheetStore.getInstance().getIDCount() + "";
					// This will store the playsheet in QuesitonPlaySheetStore
					exQueryProcessor.prepareQueryOutputPlaySheet(coreEngine, sparql, playsheet, coreEngine.getEngineName() + ": " + insightID, insightID);
					IPlaySheet playSheet = exQueryProcessor.getPlaySheet();
					playSheet.setQuestionID(insightID);
					QuestionPlaySheetStore.getInstance().addToSessionHash(request.getSession().getId(), insightID);

					//					User userData = (User) request.getSession().getAttribute(Constants.SESSION_USER);
					//					if(userData!=null)
					//						playSheet.setUserData(userData);

					PlaysheetCreateRunner playRunner = new PlaysheetCreateRunner(playSheet);
					playRunner.runWeb();

					obj = playSheet.getData();
				} catch (Exception ex) { //need to specify the different exceptions 
					ex.printStackTrace();
					Hashtable<String, String> errorHash = new Hashtable<String, String>();
					errorHash.put("Message", "Error occured processing question.");
					errorHash.put("Class", className);
					return Response.status(500).entity(WebUtility.getSO(errorHash)).build();
				}

				//Increment the insight's execution count for the logged in user\
				addMasterDB.processInsightExecutionForUser(userId, insightObj);
				String visibility = searchMasterDB.getVisibilityForInsight(userId, insightObj.getId());
				Hashtable ret = (Hashtable) obj;
				if(ret != null) {
					ret.put("query", insightObj.getSparql());
					ret.put("insightId", insightID);
					ret.put("visibility", visibility);
				}
				return Response.status(200).entity(WebUtility.getSO(ret)).build();
			}
			else{
				Hashtable<String, String> errorHash = new Hashtable<String, String>();
				errorHash.put("Message", "No question defined.");
				errorHash.put("Class", className);
				//			return getSO(errorHash);
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
		}

		String params = form.getFirst("params");
		System.out.println("Params is " + params);
		Hashtable<String, Object> paramHash = Utility.getParamsFromString(params);

		ExecuteQueryProcessor exQueryProcessor = new ExecuteQueryProcessor();
		// This will store the playsheet in QuesitonPlaySheetStore
		exQueryProcessor.processQuestionQuery(coreEngine, insight, paramHash);
		Object obj = null;
		try {
			IPlaySheet playSheet= exQueryProcessor.getPlaySheet();
			insightID = playSheet.getQuestionID();
			QuestionPlaySheetStore.getInstance().addToSessionHash(request.getSession().getId(), insightID);

			//			User userData = (User) request.getSession().getAttribute(Constants.SESSION_USER);
			//			if(userData!=null)
			//				playSheet.setUserData(userData);
			//			if(playSheet instanceof IStreamable){
			//				ServletOutputStream stream = response.getOutputStream();
			//				((IStreamable) playSheet).setOutputStream(stream);
			//			}

			PlaysheetCreateRunner playRunner = new PlaysheetCreateRunner(playSheet);
			playRunner.runWeb();
			obj = playSheet.getData();
		} catch (Exception ex) { //need to specify the different exceptions 
			ex.printStackTrace();
			Hashtable<String, String> errorHash = new Hashtable<String, String>();
			errorHash.put("Message", "Error occured processing question.");
			errorHash.put("Class", className);
			//			return getSO(errorHash);
			return Response.status(500).entity(WebUtility.getSO(errorHash)).build();
		}

		//Increment the insight's execution count for the logged in user
		addMasterDB.processInsightExecutionForUser(userId, insightObj);
		String visibility = searchMasterDB.getVisibilityForInsight(userId, insightObj.getId());
		Hashtable ret = (Hashtable) obj;
		if(ret !=  null) {
			ret.put("query", insightObj.getSparql());
			ret.put("insightId", insightID);
			ret.put("visibility", visibility);
		}
		return Response.status(200).entity(WebUtility.getSO(ret)).build();
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
		System.out.println(form.getFirst("query"));
		return Response.status(200).entity(WebUtility.getSO(RDFJSONConverter.getSelectAsJSON(form.getFirst("query")+"", coreEngine))).build();
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
		// need discussion witht he team
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

	// gets a particular insight
	// not sure if I should keep it as it is or turn this into a post because of the query
	// Can give a set of nodeids
	@GET
	@Path("properties")
	@Produces("application/json")
	public StreamingOutput getProperties(@QueryParam("node") String nodeURI)
	{
		// returns the insight
		// based on the current ID get the data
		// typically is a JSON of the insight
		// this will also cache it
		return null;
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
		return Response.status(200).entity(WebUtility.getSO(Utility.getVectorOfReturn(query, coreEngine))).build();
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
		return Response.status(200).entity(WebUtility.getSO(Utility.getVectorOfReturn(query, coreEngine))).build();
	}	

	@POST
	@Path("customVizTable")
	@Produces("application/json")
	public Response getVizTable(MultivaluedMap<String, String> form, @Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		Hashtable<String, Object> dataHash = gson.fromJson(form.getFirst("QueryData"), new TypeToken<Hashtable<String, Object>>() {}.getType());

		IQueryBuilder builder = this.coreEngine.getQueryBuilder();
		builder.setJSONDataHash(dataHash);
		builder.buildQuery();
		String query = builder.getQuery();

		System.out.println(query);
		Object obj = null;
		try
		{
			String playSheetClassName = PlaySheetEnum.getClassFromName(PlaySheetEnum.Grid.getSheetName());
			IPlaySheet playSheet = (IPlaySheet) Class.forName(playSheetClassName).getConstructor(null).newInstance(null);
			playSheet.setQuery(query);
			playSheet.setRDFEngine(coreEngine);
			//should through what questionID this should be
			playSheet.setQuestionID("VizBuilder");
			playSheet.createData();
			playSheet.runAnalytics();
			obj = playSheet.getData();

		} catch(Exception ex) {
			ex.printStackTrace();
			Hashtable<String, String> errorHash = new Hashtable<String, String>();
			errorHash.put("Message", "Error occured processing query.");
			errorHash.put("Class", className);
			return Response.status(500).entity(WebUtility.getSO(errorHash)).build();
		}

		List<Hashtable<String, String>> varObjVector = builder.getHeaderArray();
		((Hashtable)obj).put("variableHeaders", varObjVector);
		
//		//still need filter queries for RDF... not sure what this is going to look like in the future yet
//		if(builder instanceof SPARQLQueryTableBuilder) {
//			ArrayList<Hashtable<String, String>> varObjV = ((SPARQLQueryTableBuilder)builder).getHeaderArray();
//			Collection<Hashtable<String, String>> varObjVector = varObjV;
//
//			//add variable info to return data
//			
//		}
//		if(builder instanceof SQLQueryTableBuilder) {
//			ArrayList<Hashtable<String, String>> varObjV = ((SQLQueryTableBuilder)builder).getHeaderArray();
//			Collection<Hashtable<String, String>> varObjVector = varObjV;
//
//			//add variable info to return data
//			((Hashtable)obj).put("variableHeaders", varObjVector);
//		}

		return Response.status(200).entity(WebUtility.getSO(obj)).build();
	}	

	@POST
	@Path("/removeData")
	@Produces("application/xml")
	public Response removeData(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Gson gson = new Gson();
		String tableID = form.getFirst("tableID");
		String questionID = form.getFirst("id");

		boolean isInDataFrameStore = false;
		ITableDataFrame dataFrame = null;
		
		//try to get the table using tableID
		if(tableID != null) {
			dataFrame = TableDataFrameStore.getInstance().get(tableID);
			isInDataFrameStore = true;
		} 
		//if no table ID get table using question playsheet
		else if(questionID != null) {
			IPlaySheet origPS = (IPlaySheet) QuestionPlaySheetStore.getInstance().get(questionID);
			if(origPS instanceof BasicProcessingPlaySheet) {
				dataFrame = ((BasicProcessingPlaySheet) origPS).getDataFrame();
			} else { // not necessarily backed by BTree, most likely GraphPlaySheet, but could also be highly customized report
				QuestionPlaySheetStore qStore = QuestionPlaySheetStore.getInstance();
				if(!qStore.containsKey(questionID)) {
					return Response.status(400).entity(WebUtility.getSO("Could not find data.")).build();
				}

				qStore.remove(questionID);
				qStore.removeFromSessionHash(request.getSession().getId(), questionID);
				if(qStore.containsKey(questionID)) {
					return Response.status(400).entity(WebUtility.getSO("Could not remove playsheet.")).build();
				} else {
					return Response.status(200).entity(WebUtility.getSO("Succesfully removed playsheet.")).build();
				}
			}
		}

		if(dataFrame == null) {
			return Response.status(400).entity(WebUtility.getSO("Could not find data.")).build();
		}

		//columns to be removed
		String[] removeColumns = gson.fromJson(form.getFirst("removeColumns"), String[].class);
		
		//if columns to remove are all the columns in the table then just remove table
		String[] columnHeaders = dataFrame.getColumnHeaders();
		boolean removeAll = true;
		if(removeColumns != null && removeColumns.length == columnHeaders.length) {
			for(String removeColumn : removeColumns) {
				if(!ArrayUtilityMethods.arrayContainsValueIgnoreCase(columnHeaders, Utility.cleanVariableString(removeColumn))) {
					removeAll = false;
					break;
				}
			}
		} else {
			removeAll = false;
		}
		
		//remove columns being empty/null is a trigger to delete table
		//or if remove columns represent all columns in the table
		if(removeColumns == null || removeColumns.length == 0 || removeAll) {
			boolean success = false;
			if(isInDataFrameStore) {
				success = TableDataFrameStore.getInstance().remove(tableID);
				TableDataFrameStore.getInstance().removeFromSessionHash(request.getSession().getId(), tableID);
				tableID = "";
				
				HttpSession session = request.getSession();
				if(session.getAttribute(tableID) != null) {
					session.removeAttribute(tableID);
				}
				
			} else {
				QuestionPlaySheetStore qStore = QuestionPlaySheetStore.getInstance();
				if(!qStore.containsKey(questionID)) {
					return Response.status(400).entity(WebUtility.getSO("Could not find data.")).build();
				}

				qStore.remove(questionID);
				qStore.removeFromSessionHash(request.getSession().getId(), questionID);
				if(qStore.containsKey(questionID)) {
					success = false;
				} else {
					success = true;
				}
			}

			if(success) {
				Map<String, Object> retMap = new HashMap<String, Object>();
				retMap.put("tableID", tableID);
				retMap.put("message", "Succesfully removed data");
				return Response.status(200).entity(WebUtility.getSO(retMap)).build();
			} else {
				return Response.status(400).entity(WebUtility.getSO("Could not remove data")).build();
			}
		} 
		
		//Else just remove each column one by one
		else {
			boolean removeDuplicates = true;
			if(removeColumns.length == 1) {
				removeDuplicates = ArrayUtilityMethods.arrayContainsValueAtIndexIgnoreCase(columnHeaders, Utility.cleanVariableString(removeColumns[0])) != columnHeaders.length-1;
			}
			for(String s : removeColumns) {
				dataFrame.removeColumn(Utility.cleanVariableString(s)); //TODO: need booleans to return values in map
			}
			//remove duplicate rows after removing column to maintain data consistency
			if(removeDuplicates) {
				dataFrame.removeDuplicateRows();
			}
			HttpSession session = request.getSession();
			if(session.getAttribute(tableID) != null) {
				session.setAttribute(tableID, InfiniteScrollerFactory.getInfiniteScroller(dataFrame));
			}
			
			Map<String, Object> retMap = new HashMap<String, Object>();

			retMap.put("tableID", tableID);
			retMap.put("message", "Succesfully removed the following columns: " + Arrays.toString(removeColumns));

			return Response.status(200).entity(WebUtility.getSO(retMap)).build();
		}
	}

	@POST
	@Path("/filterData")
	@Produces("application/json")
	public Response filterData(MultivaluedMap<String, String> form,
			@Context HttpServletRequest request)
	{	
		String tableID = form.getFirst("tableID");
		String insightID = form.getFirst("insightID");
		
		ITableDataFrame mainTree = TableDataFrameUtilities.getTable(tableID, insightID);
		if(mainTree == null) {
			return Response.status(400).entity(WebUtility.getSO("tableID invalid. Data not found")).build();
		}
		
		Gson gson = new Gson();
		HttpSession session = request.getSession();
		String[] columnHeaders = mainTree.getColumnHeaders();
		
		//Grab the filter model from the form data
		Map<String, List<Object>> filterModel = gson.fromJson(form.getFirst("filterValues"), new TypeToken<Map<String, List<Object>>>() {}.getType());
		String selectedColumn = "";
		
		//If the filter model has information, filter the tree
		//then set the infinite scroller with the new main tree view
		if(filterModel != null && filterModel.keySet().size() > 0) {
			selectedColumn = TableDataFrameUtilities.filterTableData(mainTree, filterModel);
			session.setAttribute(tableID, InfiniteScrollerFactory.getInfiniteScroller(mainTree));
		}
		
		//if the filtermodel is not null and contains no data then unfilter the whole tree
		//this trigger to unfilter the whole tree was decided between FE and BE for simplicity
		else if(filterModel != null && filterModel.keySet().size() == 0) {
			mainTree.unfilter();
			session.setAttribute(tableID, InfiniteScrollerFactory.getInfiniteScroller(mainTree));
		} 

		Map<String, Object> retMap = new HashMap<String, Object>();
		Map<String, Object[]> Values = new HashMap<String, Object[]>();
		Map<String, Object[]> filteredValues = new HashMap<String, Object[]>();
		
		boolean filterEnabled = Boolean.getBoolean(form.getFirst("filterEnabled"));
		if(filterEnabled) {
			Object[] valueArray = TableDataFrameUtilities.getExploreTableFilterModel(mainTree);
			
			retMap.put("unfilteredValues", valueArray[0]);
			retMap.put("filteredValues", valueArray[1]);
		} else {
			//Grab all the 'visible' or 'unfiltered' values last column
			String lastColumn = columnHeaders[columnHeaders.length - 1];
			Values.put(lastColumn, mainTree.getUniqueRawValues(lastColumn));
			filteredValues.put(lastColumn, mainTree.getFilteredUniqueRawValues(lastColumn));
			
			for(int i = 0; i < columnHeaders.length - 1; i++) {
				Values.put(columnHeaders[i], new Object[0]);
				filteredValues.put(columnHeaders[i], new Object[0]);
			}
			
			retMap.put("unfilteredValues", Values);
			retMap.put("filteredValues", filteredValues);
		}
		

		
		//return tableID for consistency
		//return filtered and unfiltered values, these values will be used to populate the values and checks in the drop down menu for each column in the table view
		retMap.put("selectedColumn", selectedColumn);
		retMap.put("tableID", tableID);
		
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}

	@GET
	@Path("/unfilterColumns")
	@Produces("application/json")
	public Response getVisibleValues(MultivaluedMap<String, String> form,
			@QueryParam("tableID") String tableID,
			@QueryParam("concept") String[] concepts)
	{
		ITableDataFrame mainTree = TableDataFrameStore.getInstance().get(tableID);		
		if(mainTree == null) {
			return Response.status(400).entity(WebUtility.getSO("tableID invalid. Data not found")).build();
		}

		for(String concept: concepts) {
			mainTree.unfilter(concept);
		}

		Map<String, Object> retMap = new HashMap<String, Object>();
		retMap.put("tableID", tableID);
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}

	@GET
	@Path("/getFilterValues")
	@Produces("application/json")
	/**
	 * 
	 * @param tableID
	 * @param concept
	 * @param request
	 * @return
	 */
	public Response getNextUniqueValues(@QueryParam("tableID") String tableID,
			@QueryParam("concept") String concept,
			@QueryParam("filterEnabled") Boolean filterEnabled,
			@Context HttpServletRequest request)
	{
		//boolean filterEnabled = false;
		ITableDataFrame mainTree = TableDataFrameStore.getInstance().get(tableID);		
		if(mainTree == null) {
			return Response.status(400).entity(WebUtility.getSO("tableID invalid. Data not found")).build();
		}
		
		Iterator<Object> uniqueValueIterator = mainTree.uniqueValueIterator(concept, true, false);
		List<Object> selectedFilterValues = new ArrayList<Object>();
		while(uniqueValueIterator.hasNext()) {
			selectedFilterValues.add(uniqueValueIterator.next());
		}
		
		String[] columnHeaders = mainTree.getColumnHeaders();
		List<Object> availableFilterValues = new ArrayList<Object>();
		if(!filterEnabled || (filterEnabled && concept.equalsIgnoreCase(columnHeaders[0]))) {
			uniqueValueIterator = mainTree.uniqueValueIterator(concept, true, true);
			while(uniqueValueIterator.hasNext()) {
				availableFilterValues.add(uniqueValueIterator.next());
			}
		} else {
			availableFilterValues = selectedFilterValues;
		}
				
		
		Map<String, Object> retMap = new HashMap<String, Object>();
		retMap.put("tableID", tableID);
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
			@QueryParam("tableID") String tableID,
			@Context HttpServletRequest request)
	{
		ITableDataFrame mainTree = TableDataFrameStore.getInstance().get(tableID);
		if (mainTree == null) {
			return Response.status(400).entity(WebUtility.getSO("tableID invalid. Data not found")).build();
		}
		
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
		
		HttpSession session = request.getSession();
		boolean first = false;
		if(session.getAttribute(tableID) == null) {
			session.setAttribute(tableID, InfiniteScrollerFactory.getInfiniteScroller(mainTree));
			first = true;
		}
		
		InfiniteScroller scroller = (InfiniteScroller)session.getAttribute(tableID);

		Map<String, Object> valuesMap = new HashMap<String, Object>();
		valuesMap.put(tableID, scroller.getNextData(concept, sort, startRow, endRow));
		
		List<Map<String, String>> headerInfo = new ArrayList<Map<String, String>>();
		String[] varKeys = mainTree.getColumnHeaders();
		String[] uriKeys = mainTree.getURIColumnHeaders();
		for(int i = 0; i < varKeys.length; i++) {
			Map<String, String> innerMap = new HashMap<String, String>();
			innerMap.put("uri", uriKeys[i]);
			innerMap.put("varKey", varKeys[i]);
			headerInfo.add(innerMap);
		}

		Map<String, Object> retMap = new HashMap<String, Object>();
		retMap.put("tableID", tableID);
		retMap.put("numRows", mainTree.getNumRows());
		retMap.put("headers", headerInfo);
		retMap.put("tableData", valuesMap);

		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}

	@GET
	@Path("/getTableHeaders")
	@Produces("application/json")
	public Response getTableHeaders(@QueryParam("tableID") String tableID) {
		ITableDataFrame table = TableDataFrameStore.getInstance().get(tableID);		
		
		List<Map<String, String>> tableHeaders = new ArrayList<Map<String, String>>();
		String[] columnHeaders = table.getColumnHeaders();
		String[] uriKeys = table.getURIColumnHeaders();
		for(int i = 0; i < columnHeaders.length; i++) {
			Map<String, String> innerMap = new HashMap<String, String>();
			innerMap.put("uri", uriKeys[i]);
			innerMap.put("varKey", columnHeaders[i]);
			tableHeaders.add(innerMap);
		}
		
		Map<String, Object> retMap = new HashMap<String, Object>();
		retMap.put("tableID", tableID);
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
			@QueryParam("tableID") String tableID,
			@Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		Hashtable<String, Object> dataHash = gson.fromJson(form.getFirst("QueryData"), new TypeToken<Hashtable<String, Object>>() {}.getType());

		ITableDataFrame existingData = TableDataFrameStore.getInstance().get(tableID);
        if(existingData != null) {
               if(currConcept != null && !currConcept.isEmpty() && !joinType.equals("outer")) {
                     List<Object> filteringValues = Arrays.asList(existingData.getUniqueRawValues(currConcept));
                     StringMap<List<Object>> stringMap;
                     if(((StringMap) dataHash.get("QueryData")).containsKey(AbstractQueryBuilder.filterKey)) {
                            stringMap = (StringMap<List<Object>>) ((StringMap) dataHash.get("QueryData")).get(AbstractQueryBuilder.filterKey);
                     } else {
                            stringMap = new StringMap<List<Object>>();
                     }
                     stringMap.put(currConcept, filteringValues);
                     ((StringMap) dataHash.get("QueryData")).put(AbstractQueryBuilder.filterKey, stringMap);
               }
        }
		
		IQueryBuilder builder = this.coreEngine.getQueryBuilder();
//		boolean isSparql = builder instanceof SPARQLQueryTableBuilder;
		builder.setJSONDataHash(dataHash);
		builder.buildQuery();
		String query = builder.getQuery();

		System.out.println(query);

		ISelectWrapper wrap = WrapperManager.getInstance().getSWrapper(this.coreEngine, query);
		String[] newNames = wrap.getVariables();
		String[] newUriNames = new String[newNames.length];

		List<Hashtable<String, String>> nodeV = builder.getNodeV();
		List<Hashtable<String, String>> nodePropV = builder.getNodePropV();
		if(nodeV != null) {
			for(int i = 0; i < nodeV.size(); i++) {
				String varKey = Utility.cleanVariableString(nodeV.get(i).get("varKey"));
				String uri = nodeV.get(i).get("uriKey");
				int uriIndex = ArrayUtilityMethods.arrayContainsValueAtIndexIgnoreCase(newNames, varKey);
				newUriNames[uriIndex] = uri;
			}
		}
		if(nodePropV != null) {
			for(int i = 0; i < nodePropV.size(); i++) {
				String varKey = Utility.cleanVariableString(nodePropV.get(i).get("varKey"));
				String uri = nodePropV.get(i).get("uriKey");
				int uriIndex = ArrayUtilityMethods.arrayContainsValueAtIndexIgnoreCase(newNames, varKey);
				newUriNames[uriIndex] = uri;
			}
		}

		// if performing a join, currently need to have it s.t. the joining column is the root
		// this will be taken care of when shifting the headers order since btree adds based on that order
		if(tableID != null && !tableID.isEmpty()) {
			int joiningConceptIndex = ArrayUtilityMethods.arrayContainsValueAtIndexIgnoreCase(newNames, equivConcept);
			if(joiningConceptIndex != 0) {
				String varPlaceHolder = newNames[0];
				String uriPlaceHolder = newUriNames[0];
				newNames[0] = newNames[joiningConceptIndex];
				newUriNames[0] = newUriNames[joiningConceptIndex];
				newNames[joiningConceptIndex] = varPlaceHolder;
				newUriNames[joiningConceptIndex] = uriPlaceHolder;
			}
		}

		// creating new dataframe from query
		ITableDataFrame newTree = new BTreeDataFrame(newNames, newUriNames);
		while (wrap.hasNext()) {
			ISelectStatement iss = wrap.next();
			Map<String, Object> cleanHash = new HashMap<String, Object>();
			Map<String, Object> rawHash = new HashMap<String, Object>();
			for(int idx = 0; idx < newNames.length; idx++) {
				cleanHash.put(newNames[idx], iss.getVar(newNames[idx]));
				rawHash.put(newNames[idx], iss.getRawVar(newNames[idx]));
			}
			newTree.addRow(cleanHash, rawHash);
		}

		if(tableID == null || tableID.isEmpty()) {
			// new table, store and return success
			System.err.println("Levels in main tree are " + Arrays.toString(newTree.getColumnHeaders()));

			tableID = TableDataFrameStore.getInstance().put(newTree);
			TableDataFrameStore.getInstance().addToSessionHash(request.getSession().getId(), tableID);

			Map<String, Object> retMap = new HashMap<String, Object>();
			retMap.put("tableID", tableID);
			return Response.status(200).entity(WebUtility.getSO(retMap)).build();

		} else {
			// grab existing dataframe
			existingData = TableDataFrameStore.getInstance().get(tableID);
			if(existingData == null) {
				return Response.status(400).entity(WebUtility.getSO("Dataframe not found")).build();
			}

			IMatcher alg = null;
			switch(joinType) {
				case "inner" : alg = new ExactStringMatcher(); 
					break;
				case "partial" : alg = new ExactStringPartialOuterJoinMatcher(); 
					break;
				case "outer" : alg = new ExactStringOuterJoinMatcher();
					break;
				default : alg = new ExactStringMatcher(); 
			}

			System.err.println("Starting Join...");
			long startJoinTime = System.currentTimeMillis();
			existingData.join(newTree, currConcept, equivConcept, 1, alg);
			System.err.println("Finished Join: " + (System.currentTimeMillis() - startJoinTime) + " ms");
			System.err.println("New levels in main tree are " + Arrays.toString(existingData.getColumnHeaders()));
			Map<String, Object> retMap = new HashMap<String, Object>();
			retMap.put("tableID", tableID);
			return Response.status(200).entity(WebUtility.getSO(retMap)).build();
		}
	}

	@POST
	@Path("customFilterOptions")
	@Produces("application/json")
	public Response getCustomFilterOptions(MultivaluedMap<String, String> form, 
			@QueryParam("existingConcept") String currConcept, 
			@QueryParam("joinType") String joinType,
			@QueryParam("tableID") String tableID,
			//@QueryParam("reset") boolean reset,
			@Context HttpServletRequest request)
	{

		// first check if existingConcept exists in infiniteScroller
		// if reset 

		Gson gson = new Gson();
		Hashtable<String, Object> dataHash = gson.fromJson(form.getFirst("QueryData"), new TypeToken<Hashtable<String, Object>>() {}.getType());

		boolean outer = false;
		boolean inner = false;
		if(joinType.equals("outer")) {
			outer = true;
		} else if(joinType.equals("inner")) {
			inner = true;
		}

		IQueryBuilder builder = this.coreEngine.getQueryBuilder();
		
		if(tableID != null && !tableID.isEmpty() && !outer) {
			// need to add bindings for query if not outer join
			ITableDataFrame existingData = TableDataFrameStore.getInstance().get(tableID);
			if(existingData == null) {
				return Response.status(400).entity(WebUtility.getSO("Dataframe not found")).build();
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
				StringMap<List<Object>> stringMap = new StringMap<List<Object>>();
				stringMap.put(currConcept, filteringValues);
				((StringMap) dataHash.get("QueryData")).put(AbstractQueryBuilder.filterKey, stringMap);
			} else {
				return Response.status(400).entity(WebUtility.getSO("Cannot perform filtering when current concept to filter on is not defined")).build();
			}
		}

		builder.setJSONDataHash(dataHash);
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
	
	@POST
	@Path("searchColumn")
	@Produces("application/json")
	public Response searchColumn(MultivaluedMap<String, String> form,
			@QueryParam("existingConcept") String currConcept,
			@QueryParam("joinType") String joinType, @QueryParam("tableID") String tableID, @QueryParam("columnHeader") String columnHeader,
			@QueryParam("searchTerm") String searchTerm, @QueryParam("limit") String limit, @QueryParam("offset") String offset,
			@Context HttpServletRequest request) {
		
		HttpSession session = request.getSession();
		// check if a result set has been cached
		if (session.getAttribute(InstanceStreamer.KEY) != null) {
			InstanceStreamer stream = (InstanceStreamer) session.getAttribute(InstanceStreamer.KEY);
			String ID = stream.getID();
			// check if appropriate set has been cached
			if (ID != null && ID.equals(Utility.getInstanceName(columnHeader)) && !columnHeader.equals("")) {
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

		boolean outer = false;
		boolean inner = false;
		if (joinType.equals("outer")) {
			outer = true;
		} else if (joinType.equals("inner")) {
			inner = true;
		}

		IQueryBuilder builder = this.coreEngine.getQueryBuilder();

		// TODO: why is rdbms uppper case for names? causes discrepancies
		if (!(builder instanceof SPARQLQueryTableBuilder)) { // if not sparql then uppercase the concept
			currConcept = currConcept.toUpperCase();
		}

		if (tableID != null && !tableID.isEmpty() && !outer) {
			// need to add bindings for query if not outer join
			ITableDataFrame existingData = TableDataFrameStore.getInstance().get(tableID);
			if (existingData == null) {
				return Response.status(400).entity(WebUtility.getSO("Dataframe not found")).build();
			}

			if (currConcept != null && !currConcept.isEmpty()) {
				List<Object> filteringValues = Arrays.asList(existingData.getUniqueRawValues(currConcept));
				// HttpSession session = request.getSession();
				// if(session.getAttribute(tableID) == null) {
				// session.setAttribute(tableID, InfiniteScrollerFactory.getInfiniteScroller(existingData));
				// }
				//
				// InfiniteScroller scroller = (InfiniteScroller)session.getAttribute(tableID);
				// List<HashMap<String, String>> filteringValues = scroller.getNextUniqueValues(currConcept);
				//
				StringMap<List<Object>> stringMap = new StringMap<List<Object>>();
				stringMap.put(currConcept, filteringValues);
				((StringMap) dataHash.get("QueryData")).put(AbstractQueryBuilder.filterKey, stringMap);
			} else {
				return Response.status(400).entity(WebUtility.getSO("Cannot perform filtering when current concept to filter on is not defined"))
						.build();
			}
		}

		builder.setJSONDataHash(dataHash);
		builder.buildQuery();
		String query = builder.getQuery();
		System.out.println(query);

		ISelectWrapper wrap = WrapperManager.getInstance().getSWrapper(this.coreEngine, query);
		String[] newNames = wrap.getVariables();
		int index = 0;
		if (newNames.length > 1) {
			int currIndexexistingConcept = 0;
			
			currIndexexistingConcept = ArrayUtilityMethods.arrayContainsValueAtIndex(newNames, currConcept);
			if (currIndexexistingConcept == 0) {
				index = 1;
			}
		}

		// creating new list of values from query
		ArrayList<Object> retList = new ArrayList<Object>();
		while (wrap.hasNext()) {
			ISelectStatement iss = wrap.next();
			Object value = iss.getRawVar(newNames[index]);
			if (inner && value.toString().isEmpty()) {
				continue; // don't add empty values as a possibility
			}
			if (value instanceof BigdataURI) {
				retList.add(((BigdataURI) value).stringValue());
			} else {
				retList.add(iss.getVar(newNames[index]));
			}
		}

		// put everything into InstanceStreamer object
		InstanceStreamer stream = new InstanceStreamer(retList);
		logger.info("Creating InstanceStreamer object with ID: "+columnHeader);
		//String ID = Utility.getInstanceName(columnHeader) + Integer.toString(stream.getSize());
		stream.setID(Utility.getInstanceName(columnHeader));

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



	@POST
	@Path("getVizTable")
	@Produces("application/json")
	public Response getExploreTable(
			@QueryParam("tableID") String tableID,
			//@QueryParam("start") int start,
			//@QueryParam("end") int end,
			@Context HttpServletRequest request)
	{
		ITableDataFrame mainTree = TableDataFrameStore.getInstance().get(tableID);		
		if(mainTree == null) {
			return Response.status(400).entity(WebUtility.getSO("tableID invalid. Data not found")).build();
		}

		List<Object[]> table = mainTree.getRawData();
		Map<String, Object> returnData = new HashMap<String, Object>();
		
		returnData.put("totalRows", table.size());
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
		returnData.put("tableID", tableID);
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
		Object obj = QueryBuilderHelper.getPropsFromPath(this.coreEngine, dataHash);

		//		SPARQLQueryTableBuilder tableViz = new SPARQLQueryTableBuilder();
		//		tableViz.setJSONDataHash(dataHash);
		//		tableViz.setEngine(coreEngine);
		//		Object obj = tableViz.getPropsFromPath();
		return Response.status(200).entity(WebUtility.getSO(obj)).build();
	}	

	@POST
	@Path("/saveForm")
	@Produces("application/json")	
	public Response saveForm(MultivaluedMap<String, String> form) 
	{
		
		String basePath = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER);
		String formName = form.getFirst("formName");
		String jsonLoc = basePath + System.getProperty("file.separator") + "Forms" + formName + ".json";
		String formData = form.getFirst("formData");
		
		try {
			FormBuilder.saveForm(formData, jsonLoc);
		} catch (IOException e) {
			return Response.status(400).entity(WebUtility.getSO(e.getMessage())).build();
				}

		return Response.status(200).entity(WebUtility.getSO("saved successfully")).build();
	}

	@POST
	@Path("/getForm")
	@Produces("application/json")	
	public Response getForm(MultivaluedMap<String, String> form) 
	{
		String basePath = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER);
		String formName = form.getFirst("formName");
		String jsonLoc = basePath + System.getProperty("file.separator") + formName + ".json";

		String formJson;
		try {
			formJson = FormBuilder.getForm(jsonLoc);
		} catch (FileNotFoundException f) {
			return Response.status(400).entity(WebUtility.getSO("File "+formName+" Not Found")).build();
		}catch (IOException e) {
			return Response.status(400).entity(WebUtility.getSO("Error getting file")).build();
		} 

		//TODO: pass string and let fron end do parsing
		JsonParser parser = new JsonParser();
		JsonArray ja = parser.parse(formJson).getAsJsonArray();
		
		return Response.status(200).entity(WebUtility.getSO((formJson))).build();
	}

	@POST
	@Path("/saveFormData")
	@Produces("application/json")
	public Response saveFormData(MultivaluedMap<String, String> form) 
	{
		//IEngine e = null;
		Gson gson = new Gson();
		try {
			FormBuilder.saveFormData(form);
		} catch(Exception e) {
			return Response.status(200).entity(WebUtility.getSO(gson.toJson("error saving data"))).build();
			}
			
		return Response.status(200).entity(WebUtility.getSO(gson.toJson("success"))).build();
	}


	@GET
	@Path("menu")
	@Produces("application/json")	
	public Response getMenu(@QueryParam("user") String user, @QueryParam("start") String starter, @Context HttpServletRequest request)
	{
		if(user == null)
			user = "All";
		if(starter == null)
			starter = "ContextMenu";

		String menuSubMenuQuery = "SELECT ?Menu ?MType ?MURL ?Submenu ?SMenuLabel ?SURL ?SType ?ChildMenu ?ChURL ?ChLabel ?CType ?MenuFiller ?SFiller ?CFiller WHERE " +
				"{BIND( <http://semoss.org/ontologies/Concept/Owner/" + user + "> AS ?User) " +
				"{?Menu <http://semoss.org/ontologies/Relation/Owner> ?User} " +
				"{?Menu <http://semoss.org/ontologies/Relation/submenu> ?Submenu} " +
				"{?Menu <http://semoss.org/ontologies/Relation/Contains/Label> ?MenuLabel} " +
				"{?Menu <http://semoss.org/ontologies/Relation/Contains/FillerName> ?MenuFiller} " +
				"{?Menu <http://semoss.org/ontologies/Relation/Contains/Type> ?MType} " +
				"{?Submenu <http://semoss.org/ontologies/Relation/Contains/Label> ?SMenuLabel} " +
				"{?Submenu <http://semoss.org/ontologies/Relation/Contains/Type> ?SType} " +
				"OPTIONAL " +
				"{" +
				"{?Submenu <http://semoss.org/ontologies/Relation/Contains/URL> ?SURL}  " +
				"{?Submenu <http://semoss.org/ontologies/Relation/Contains/FillerName> ?SFiller}  " +
				"{?Submenu <http://semoss.org/ontologies/Relation/submenu> ?ChildMenu} " +
				"{?ChildMenu <http://semoss.org/ontologies/Relation/Contains/Label> ?ChLabel}" +
				"{?ChildMenu <http://semoss.org/ontologies/Relation/Contains/Type> ?CType} " +
				"}" +
				"OPTIONAL " +
				"{" +
				"{?ChildMenu <http://semoss.org/ontologies/Relation/Contains/URL> ?ChURL;} " +
				"{?ChildMenu <http://semoss.org/ontologies/Relation/Contains/FillerName> ?CFiller;} " +
				"} " +
				"OPTIONAL " +
				"{" +
				"{?Menu <http://semoss.org/ontologies/Relation/Contains/URL> ?MURL}" +
				"}" +
				"FILTER regex(str(?MenuLabel),'" + starter + "', 'i')}";

		// menuSubMenuQuery = "SELECT ?Menu ?Submenu ?SMenuLabel ?SURL ?SType ?ChildMenu ?ChURL ?ChLabel WHERE {BIND( <http://semoss.org/ontologies/Concept/Owner/All> AS ?User) {?Menu <http://semoss.org/ontologies/Relation/Owner> ?User} }";
		//{?Menu <http://semoss.org/ontologies/Relation/submenu> ?Submenu} {?Menu <http://semoss.org/ontologies/Relation/Contains/Label> ?MenuLabel} {?Submenu <http://semoss.org/ontologies/Relation/Contains/Label> ?SMenuLabel} {?Submenu <http://semoss.org/ontologies/Relation/Contains/Type> ?SType} OPTIONAL {{?Submenu <http://semoss.org/ontologies/Relation/Contains/URL> ?SURL}  {?Submenu <http://semoss.org/ontologies/Relation/submenu> ?ChildMenu} {?ChildMenu <http://semoss.org/ontologies/Relation/Contains/Type> ?CType} {?ChildMenu <http://semoss.org/ontologies/Relation/Contains/URL> ?ChURL;} {?ChildMenu <http://semoss.org/ontologies/Relation/Contains/Label> ?ChLabel}} FILTER regex(str(?MenuLabel), 'Data', 'i')}";

		SesameJenaSelectWrapper wrapper = new SesameJenaSelectWrapper();
		wrapper.setEngine(coreEngine);
		wrapper.setQuery(menuSubMenuQuery);
		wrapper.setEngineType(IEngine.ENGINE_TYPE.SESAME);
		wrapper.executeQuery();

		System.out.println("Query.... " + menuSubMenuQuery);
		System.out.println("Variables " + wrapper.getVariables());

		Hashtable allMenu = new Hashtable();
		ArrayList<String> subMenus = new ArrayList();

		while(wrapper.hasNext())
		{
			System.out.println("New record");
			SesameJenaSelectStatement stmt = wrapper.next();
			String menu = (String)stmt.getVar("Menu");

			String menuType = ((String)stmt.getVar("MType")).replace("\"", "");
			String menuURL = ((String)stmt.getVar("MURL")).replace("\"", "").replace("*","/");
			String menuFiller = ((String)stmt.getVar("MenuFiller")).replace("\"", "");

			String subMenu = ((String)stmt.getVar("Submenu")).replace("\"", "");
			String subMenuLabel = ((String)stmt.getVar("SMenuLabel")).replace("\"", "");
			String sType = ((String)stmt.getVar("SType")).replace("\"", "");
			String surl = ((String)stmt.getVar("SURL")).replace("\"", "").replace("*","/"); // eventually it will be a * instead of a -
			String sFiller = ((String)stmt.getVar("SFiller")).replace("\"", "");

			allMenu.put("Menu", starter);
			allMenu.put("Type", menuType);
			allMenu.put("URL", menuURL);
			allMenu.put("Filler", menuFiller);


			// submenu
			Hashtable smenuHash = new Hashtable();
			if(allMenu.containsKey(subMenuLabel)) {
				smenuHash = (Hashtable)allMenu.get(subMenuLabel);
			}
			else {
				subMenus.add(subMenuLabel);
			}

			smenuHash.put("Label", subMenuLabel);
			smenuHash.put("Type", sType);
			smenuHash.put("URL", surl);
			smenuHash.put("Filler", sFiller);

			// finally the sub sub menu
			String childMenu = ((String)stmt.getVar("ChildMenu")).replace("\"", "");
			String chMenuLabel = ((String)stmt.getVar("ChLabel")).replace("\"","");
			String chURL = ((String)stmt.getVar("ChURL")).replace("\"", "").replace("*","/"); // eventually I will put this as a * so it can be replaced
			String cType = ((String)stmt.getVar("CType")).replace("\"", "");
			String cFiller = ((String)stmt.getVar("CFiller")).replace("\"", "");

			ArrayList <String> childMenus = new ArrayList();
			if(chMenuLabel != null && chMenuLabel.length() != 0)
			{
				System.out.println(" Child Menu for " + subMenuLabel + "  Child is " + childMenu);
				Hashtable childMenuHash = new Hashtable();
				if(smenuHash.containsKey(chMenuLabel))
				{
					System.out.println("Has the child menu label [" + chMenuLabel + "]");
					childMenuHash = (Hashtable)smenuHash.get(chMenuLabel);
				}
				else {
					childMenus.add(chMenuLabel);
				}
				childMenuHash.put("Label", chMenuLabel);
				childMenuHash.put("URL", chURL);
				childMenuHash.put("Type", cType);
				childMenuHash.put("Filler", cFiller);

				System.out.println("Child menus is " + childMenus);

				if(childMenus.size() > 0) {
					smenuHash.put("Submenus", childMenus);
				}
				smenuHash.put(chMenuLabel, childMenuHash);
			}

			System.out.println("Submenu " + smenuHash);
			allMenu.put("Submenus", subMenus);
			allMenu.put(subMenuLabel, smenuHash);
		}

		// master the hashtable for empty
		for(int subMenuIndex = 0;subMenuIndex < subMenus.size();subMenuIndex++)
		{
			Hashtable thisMenu = (Hashtable)allMenu.get(subMenus.get(subMenuIndex));
			if(!thisMenu.containsKey("Submenus") || ((ArrayList)thisMenu.get("Submenus")).size() == 0)
			{
				ArrayList tempList = new ArrayList();
				tempList.add("EMPTY");
				thisMenu.put("Submenus", tempList);
			}
		}
		if(subMenus.size() == 0)
		{
			subMenus.add("EMPTY");
			allMenu.put("Submenus", subMenus);
		}	

		System.out.println(">>>.... " + new GsonBuilder().setPrettyPrinting().create().toJson(allMenu));

		return Response.status(200).entity(WebUtility.getSO(allMenu)).build();
	}
	//  	
	//  	private ArrayList<Hashtable<String,String>> getHashArrayFromString(String arrayString){
	//  		System.err.println("MY STRING " + arrayString);
	//  		ArrayList<Hashtable<String,String>> retArray = new ArrayList<Hashtable<String,String>>();
	//		if(arrayString != null) {
	//			Gson gson = new Gson();
	//			ArrayList<Object> varsObjArray = gson.fromJson(arrayString, ArrayList.class);
	//			for(Object varsObj : varsObjArray){
	//				Hashtable newHash = new Hashtable();
	//				newHash.putAll((StringMap)varsObj);
	//				retArray.add(newHash);
	//			}
	//		}
	//		return retArray;
	//  	}

	@Path("/analytics")
	public Object runEngineAnalytics(){
		EngineAnalyticsResource analytics = new EngineAnalyticsResource(this.coreEngine);
		return analytics;
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

		BTreeDataFrame tree1 = null;
		String[] names1 = null;
		if(query1 != null){
			ISelectWrapper wrap1 = WrapperManager.getInstance().getSWrapper(this.coreEngine, query1);
			names1 = wrap1.getVariables();
			tree1 = new BTreeDataFrame(names1);
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

	@POST
	@Path("/publishToFeed")
	@Produces("application/xml")
	public Response publishInsight(@QueryParam("visibility") String visibility, @QueryParam("insight") String insight, @Context HttpServletRequest request) {
		boolean success = false;
		String userId = ((User)request.getSession().getAttribute(Constants.SESSION_USER)).getId();
		Insight insightObj = ((AbstractEngine)coreEngine).getInsight2(insight).get(0);

		NameServerProcessor ns = new NameServerProcessor();
		success = ns.publishInsightToFeed(userId, insightObj, visibility);

		return success ? Response.status(200).entity(WebUtility.getSO(success)).build() : Response.status(400).entity(WebUtility.getSO(success)).build();
	}
	
	@POST
	@Path("/applyColumnStats")
	@Produces("application/json")
	public Response applyColumnStats(MultivaluedMap<String, String> form,  
			@Context HttpServletRequest request)
	{
		String tableID = form.getFirst("tableID");
		String questionID = form.getFirst("questionID");

		ITableDataFrame table = TableDataFrameUtilities.getTable(tableID, questionID);
		if(table == null) {
			return Response.status(400).entity(WebUtility.getSO("Could not find data.")).build();
		}
		
		Gson gson = new Gson();
//		String groupBy = form.getFirst("groupBy");
		String[] groupByCols = gson.fromJson(form.getFirst("groupBy"), String[].class);
		Map<String, Object> functionMap = gson.fromJson(form.getFirst("mathMap"), new TypeToken<HashMap<String, Object>>() {}.getType());
		
		boolean singleColumn = groupByCols.length == 1 || (groupByCols.length == 2 && groupByCols[0].equals(groupByCols[1]));

		if(singleColumn) {
			functionMap = TableDataFrameUtilities.createColumnNamesForColumnGrouping(groupByCols[0], functionMap);
		} else {
			functionMap = TableDataFrameUtilities.createColumnNamesForColumnGrouping(groupByCols, functionMap);
		}
		
		String[] columnHeaders = table.getColumnHeaders();
		Set<String> columnSet = new HashSet<String>();
		for(String key : functionMap.keySet()) {
			Map<String, String> map = (Map)functionMap.get(key);
			String name = map.get("calcName");
			columnSet.add(name);
		}
		
		for(String name : columnSet) {
			if(ArrayUtilityMethods.arrayContainsValue(columnHeaders, name)) {
				table.removeColumn(name);
			}
		}
		
		Map<String, Object> retMap = new HashMap<String, Object>();
		//only one group by or two of the same
		if(singleColumn) {
			ITableStatCounter counter = new ITableStatCounter();
			counter.addStatsToDataFrame(table, groupByCols[0], functionMap);
		} else {
			MultiColumnTableStatCounter multiCounter = new MultiColumnTableStatCounter();
			multiCounter.addStatsToDataFrame(table, groupByCols, functionMap);
		}
		
		retMap.put("tableData", TableDataFrameUtilities.getTableData(table));
		retMap.put("mathMap", functionMap);
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}
	
	@POST
	@Path("/hasDuplicates")
	@Produces("application/json")
	public Response hasDuplicates(MultivaluedMap<String, String> form,
			@Context HttpServletRequest request) 
	{
		String tableID = form.getFirst("tableID");
		String questionID = form.getFirst("questionID");

		ITableDataFrame table = TableDataFrameUtilities.getTable(tableID, questionID);

		if(table == null) {
			return Response.status(400).entity(WebUtility.getSO("Could not find data.")).build();
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
		String tableID = form.getFirst("tableID");
		String insightID = form.getFirst("insightID");
		String query = form.getFirst("query");

		ITableDataFrame dataFrame = TableDataFrameUtilities.getTable(tableID, insightID);

		if (dataFrame == null) {
			return Response.status(400).entity(WebUtility.getSO("Could not find data.")).build();
		}

		dataFrame.refresh();
		
		if (insightID != null && !query.isEmpty()) { // Logic when coming from a preexisting insight ("Browse")
			Hashtable<String, Object> returnDataHash = new Hashtable<String, Object>();
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
				errorHash.put("Message", "This Insight is not eligible to navigate through Explore.");
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
					returnDataHash.put(key, nodeDetails);
				}
			}
			returnHash.put("nodes", returnDataHash); // Nodes that will be used to build the metamodel in Single-View
			
			ArrayList<String[]> triplesArr = (ArrayList<String[]>) queryParser.getTriplesData();
			returnDataHash = new Hashtable<String, Object>();
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
					returnDataHash.put(Integer.toString(i++), nodeTriples);
				}
			}
			returnHash.put("triples", returnDataHash);

			tableID = TableDataFrameStore.getInstance().put(dataFrame); // place btree into ITableDataFrameStore and generate a tableID so that user no longer uses insightID
		}
		
		returnHash.put("tableID", tableID);

		return Response.status(200).entity(WebUtility.getSO(returnHash)).build();
	}
}