/*******************************************************************************
 * Copyright 2015 SEMOSS.ORG
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
import java.util.Arrays;
import java.util.Collection;
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
import org.openrdf.model.URI;

import prerna.algorithm.api.IAnalyticRoutine;
import prerna.algorithm.api.ITableDataFrame;
import prerna.algorithm.impl.ExactStringMatcher;
import prerna.algorithm.impl.ExactStringOuterJoinMatcher;
import prerna.algorithm.impl.ExactStringPartialOuterJoinMatcher;
import prerna.algorithm.learning.unsupervised.outliers.FastOutlierDetection;
import prerna.auth.User;
import prerna.ds.BTreeDataFrame;
import prerna.ds.ITableDataFrameStore;
import prerna.engine.api.IEngine;
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
import prerna.rdf.query.builder.SQLQueryTableBuilder;
import prerna.rdf.query.util.SEMOSSQuery;
import prerna.rdf.util.RDFJSONConverter;
import prerna.ui.components.ExecuteQueryProcessor;
import prerna.ui.components.api.IPlaySheet;
import prerna.ui.components.playsheets.BasicProcessingPlaySheet;
import prerna.ui.components.playsheets.GraphPlaySheet;
import prerna.ui.helpers.PlaysheetCreateRunner;
import prerna.ui.main.listener.impl.SPARQLExecuteFilterBaseFunction;
import prerna.ui.main.listener.impl.SPARQLExecuteFilterNoBaseFunction;
import prerna.util.Constants;
import prerna.util.PlaySheetEnum;
import prerna.util.QuestionPlaySheetStore;
import prerna.util.Utility;
import prerna.web.services.util.InMemoryHash;
import prerna.web.services.util.WebUtility;

import com.bigdata.rdf.model.BigdataValue;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
				
				//Increment the insight's execution count for the logged in user
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

		/*Hashtable<String, Object> dataHash = gson.fromJson(form.getFirst("QueryData"), Hashtable.class);
		Integer items = 100;
		if (form.containsKey("ItemCount"))
			items = gson.fromJson(form.getFirst("ItemCount"), Integer.class);
		Integer pageNumber = null;
		if (form.containsKey("PageNumber"))
			pageNumber = gson.fromJson(form.getFirst("PageNumber"), Integer.class);
		SPARQLQueryTableBuilder tableViz = new SPARQLQueryTableBuilder();

		ArrayList<Hashtable<String,String>> nodePropArray = getHashArrayFromString(form.getFirst("SelectedNodeProps") + "");
		ArrayList<Hashtable<String,String>> edgePropArray = getHashArrayFromString(form.getFirst("SelectedEdgeProps") + "");
		tableViz.setPropV(nodePropArray, edgePropArray);

		tableViz.setJSONDataHash(dataHash);
		tableViz.setEngine(coreEngine);
		tableViz.buildQuery();
		SEMOSSQuery semossQuery = tableViz.getSEMOSSQuery();

		//get header array before adding pagination stuff
		ArrayList<Hashtable<String, String>> varObjV = tableViz.getHeaderArray();
		Collection<Hashtable<String, String>> varObjVector = varObjV;

		//add pagination information
		Hashtable limitHash = new Hashtable();
		int fullTableRowNum = 100000;//tableViz.runCountQuery();

//		semossQuery.addAllVarToOrderBy();// necessary for pagination
		limitHash.put("fullSize", fullTableRowNum);

		if(items!= null){
			int limitSize = items;
			semossQuery.setLimit(limitSize);
		}
		if(pageNumber != null) {
			int offset = items * (pageNumber - 1);
			semossQuery.setOffset(offset);
		}

		semossQuery.createQuery();
		String query = semossQuery.getQuery();*/

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

		//still need filter queries for RDF... not sure what this is going to look like in the future yet
		if(builder instanceof SPARQLQueryTableBuilder) {
			ArrayList<Hashtable<String, String>> varObjV = ((SPARQLQueryTableBuilder)builder).getHeaderArray();
			Collection<Hashtable<String, String>> varObjVector = varObjV;

			//add variable info to return data
			((Hashtable)obj).put("variableHeaders", varObjVector);
		}

		if(builder instanceof SQLQueryTableBuilder) {
			ArrayList<Hashtable<String, String>> varObjV = ((SQLQueryTableBuilder)builder).getHeaderArray();
			Collection<Hashtable<String, String>> varObjVector = varObjV;

			//add variable info to return data
			((Hashtable)obj).put("variableHeaders", varObjVector);
		}

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
		if(tableID != null) {
			dataFrame = ITableDataFrameStore.getInstance().get(tableID);
			isInDataFrameStore = true;
		} else if(questionID != null) {
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
		
		String[] removeColumns = gson.fromJson(form.getFirst("removeColumns"), String[].class);
		if(removeColumns == null || removeColumns.length == 0) {
			boolean success = false;
			if(isInDataFrameStore) {
				success = ITableDataFrameStore.getInstance().remove(tableID);
				ITableDataFrameStore.getInstance().removeFromSessionHash(request.getSession().getId(), tableID);
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
				return Response.status(200).entity(WebUtility.getSO("Succesfully removed data.")).build();
			} else {
				return Response.status(400).entity(WebUtility.getSO("Could not remove data")).build();
			}
		} else {
			for(String s : removeColumns) {
				dataFrame.removeColumn(s); //TODO: need booleans to return values in map
			}
			return Response.status(200).entity(WebUtility.getSO("Succesfully removed the following columns: " + Arrays.toString(removeColumns))).build();
		}
	}
	
	@POST
	@Path("/filterData")
	@Produces("application/json")
	public Response filterData(MultivaluedMap<String, String> form, 
			@QueryParam("concept") String concept, 
			@QueryParam("filterValues") String filterValues, 
			@QueryParam("tableID") String tableID,
			@Context HttpServletRequest request)
	{
		ITableDataFrame mainTree = ITableDataFrameStore.getInstance().get(tableID);		
		if(mainTree == null) {
			return Response.status(400).entity(WebUtility.getSO("tableID invalid. Data not found")).build();
		}	
		
		Gson gson = new Gson();
		List<Object> filterValuesArr = gson.fromJson(filterValues, List.class);
		mainTree.filter(concept, filterValuesArr);
		
		Map<String, Object> retMap = new HashMap<String, Object>();
		retMap.put("tableID", tableID);
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}
	
	@POST
	@Path("/addData")
	@Produces("application/json")
	public Response addData(MultivaluedMap<String, String> form, 
			@QueryParam("existingConcept") String currConcept, 
			@QueryParam("joinConcept") String equivConcept, 
			@QueryParam("newConcept") String newConcept, 
			@QueryParam("joinType") String joinType,
			@QueryParam("tableID") String tableID,
			@Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		Hashtable<String, Object> dataHash = gson.fromJson(form.getFirst("QueryData"), new TypeToken<Hashtable<String, Object>>() {}.getType());

		IQueryBuilder builder = this.coreEngine.getQueryBuilder();
		builder.setJSONDataHash(dataHash);
		builder.buildQuery();
		String query = builder.getQuery();

		System.out.println(query);
		
		ISelectWrapper wrap = WrapperManager.getInstance().getSWrapper(this.coreEngine, query);
		String[] newNames = wrap.getVariables();
		
		// creating new dataframe from query
		ITableDataFrame newTree = new BTreeDataFrame(newNames);
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
			
			tableID = ITableDataFrameStore.getInstance().put(newTree);
			ITableDataFrameStore.getInstance().addToSessionHash(request.getSession().getId(), tableID);
			
			Map<String, Object> retMap = new HashMap<String, Object>();
			retMap.put("tableID", tableID);
			return Response.status(200).entity(WebUtility.getSO(retMap)).build();
			
		} else {
			// grab existing dataframe
			ITableDataFrame existingData = ITableDataFrameStore.getInstance().get(tableID);
			if(existingData == null) {
				return Response.status(400).entity(WebUtility.getSO("Dataframe not found")).build();
			}
			
			IAnalyticRoutine alg = null;
			switch(joinType) {
				case "inner" : alg = new ExactStringMatcher(); 
					break;
				case "partial" : alg = new ExactStringPartialOuterJoinMatcher(); 
					break;
				case "full" : alg = new ExactStringOuterJoinMatcher();
					break;
				default : alg = new ExactStringMatcher(); 
			}
			
			existingData.join(newTree, currConcept, equivConcept, 1, alg);
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
			@Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		Hashtable<String, Object> dataHash = gson.fromJson(form.getFirst("QueryData"), new TypeToken<Hashtable<String, Object>>() {}.getType());

		boolean outer = false;
		boolean inner = false;
		if(joinType.equals("full")) {
			outer = true;
		} else if(joinType.equals("inner")) {
			inner = true;
		}
		
		IQueryBuilder builder = this.coreEngine.getQueryBuilder();
		if( (tableID != null && !tableID.isEmpty()) || !outer) {
			// need to add bindings for query if not outer join
			ITableDataFrame existingData = ITableDataFrameStore.getInstance().get(tableID);
			if(existingData == null) {
				return Response.status(400).entity(WebUtility.getSO("Dataframe not found")).build();
			}
			
			List<Object> filteringValues = Arrays.asList(existingData.getUniqueRawValues(currConcept));
			dataHash.put(AbstractQueryBuilder.filterKey, filteringValues);
		}
		builder.setJSONDataHash(dataHash);
		builder.buildQuery();
		String query = builder.getQuery();

		System.out.println(query);
		
		ISelectWrapper wrap = WrapperManager.getInstance().getSWrapper(this.coreEngine, query);
		String[] newNames = wrap.getVariables();
		int index = 0;
		if(newNames.length > 1) {
			// this means there is no bindings performed
			index = 1;
		} 
		
		// creating new list of values from query
		Set<Object> retList = new HashSet<Object>();
		while (wrap.hasNext()) {
			ISelectStatement iss = wrap.next();
			Object value = iss.getRawVar(newNames[index]);
			if(inner && value.toString().isEmpty()) {
				continue; // don't add empty values as a possibility
			}
			if(value instanceof BigdataValue) {
				retList.add(iss.getVar(newNames[index]));
			} else if(value instanceof URI) {
				retList.add( ((URI)value).stringValue());
			} else {
				retList.add(value);
			}
		}
		
		return Response.status(200).entity(WebUtility.getSO(retList)).build();
	}
	
	@POST
	@Path("customVizTableFilterOptions")
	@Produces("application/json")
	public Response getVizTableFilterOptions(MultivaluedMap<String, String> form, 
			@QueryParam("returnColumn") Boolean returnColumn, 
			@QueryParam("existingConcept") String currConcept, 
			@QueryParam("joinConcept") String equivConcept, 
			@QueryParam("newConcept") String newConcept, 
			@QueryParam("blankSelected") Boolean blankSelected,
			@QueryParam("tableID") String tableID,
			@Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		Hashtable<String, Object> dataHash = gson.fromJson(form.getFirst("QueryData"), new TypeToken<Hashtable<String, Object>>() {}.getType());

		IQueryBuilder builder = this.coreEngine.getQueryBuilder();
		builder.setJSONDataHash(dataHash);
		builder.buildQuery();
		String query = builder.getQuery();

		System.out.println(query);

		ISelectWrapper wrap = WrapperManager.getInstance().getSWrapper(this.coreEngine, query);
		String[] newNames = wrap.getVariables();
		String[] finalNewNames = new String[newNames.length];
		finalNewNames[0] = currConcept;
		
		// get preexisting table and order the new table names to correctly join
		ITableDataFrame mainTree = null;
		if( finalNewNames.length > 1 ) // length will be either one or two....
		{ 
			finalNewNames[0] = equivConcept;
			finalNewNames[1] = newConcept;
			if(!newNames[1].equalsIgnoreCase(newConcept)){ // make sure the new names are in the right order for join
				String temp = newNames[0];
				newNames[0] = newNames[1];
				newNames[1] = temp;
			}
			mainTree = ITableDataFrameStore.getInstance().get(tableID);
			System.err.println("Current levels in main tree are " + Arrays.toString(mainTree.getColumnHeaders()));
			System.err.println("Removing col " + finalNewNames[1]);
			mainTree.removeColumn(finalNewNames[1]); // need to make sure the column doesn't already exist (metamodel click vs. instances click)
			System.err.println("Levels in main tree are " + Arrays.toString(mainTree.getColumnHeaders()));
		}
		
		// fill the new tree
		ITableDataFrame newTree = new BTreeDataFrame(finalNewNames);
		while (wrap.hasNext()){
			ISelectStatement iss = wrap.next();
			Map<String, Object> cleanHash = new HashMap<String, Object>();
			Map<String, Object> rawHash = new HashMap<String, Object>();
			for(int idx = 0; idx < newNames.length; idx++) {
				cleanHash.put(finalNewNames[idx], iss.getVar(newNames[idx]));
				rawHash.put(finalNewNames[idx], iss.getRawVar(newNames[idx]));
			}
			newTree.addRow(cleanHash, rawHash);
		}
		
		// perform the join
		if( newNames.length > 1 ) // not the first click on the metamodel page so we need to join with previous tree
		{
				System.err.println("Main tree has levels : " + Arrays.toString(mainTree.getColumnHeaders()) + " and I am joining with " + Arrays.toString(newTree.getColumnHeaders()));
				IAnalyticRoutine alg = null;
				if(blankSelected) {
					alg = new ExactStringPartialOuterJoinMatcher();
				} else {
					alg = new ExactStringMatcher();
				}
				mainTree.join(newTree, currConcept, equivConcept, 1, alg);
				System.err.println("New levels in main tree are " + Arrays.toString(mainTree.getColumnHeaders()));
		}
		else {
			mainTree = newTree;
		}
		
		// get the new column
		Map<String, Object> retMap = new HashMap<String, Object>();
		retMap.put("result", "success");
		Object values;
		if(returnColumn){
			if(newNames.length > 1) {
				values = mainTree.getRawColumn(finalNewNames[1]); // this will be the new column that got added
			} else {
				values = mainTree.getRawColumn(finalNewNames[0]); // the first column that gets added
			}
			retMap.put("values", values);
		}
		
		if(tableID.isEmpty()) {
			tableID = ITableDataFrameStore.getInstance().put(mainTree);
			ITableDataFrameStore.getInstance().addToSessionHash(request.getSession().getId(), tableID);
		} else {
			ITableDataFrameStore.getInstance().put(tableID, mainTree);
			ITableDataFrameStore.getInstance().addToSessionHash(request.getSession().getId(), tableID);
		}

		retMap.put("tableID", tableID);
		
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}
	
	@POST
	@Path("getVizTable")
	@Produces("application/json")
	public Response getExploreTable(
			@QueryParam("tableID") String tableID,
			@Context HttpServletRequest request)
	{
		ITableDataFrame mainTree = ITableDataFrameStore.getInstance().get(tableID);		
		if(mainTree == null) {
			return Response.status(400).entity(WebUtility.getSO("tableID invalid. Data not found")).build();
		}
		
		List<Object[]> table = mainTree.getRawData();
		Map<String, Object> returnData = new HashMap<String, Object>();
		returnData.put("data", table);
		returnData.put("headers", mainTree.getColumnHeaders());
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
		
		long start = System.currentTimeMillis();
//		String query1 = "SELECT DISTINCT ?Title ?DomesticRevenue ?InternationalRevenue ?Budget ?RottenTomatoesCritics ?RottenTomatoesAudience WHERE {{?Title <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Title>}{?Title <http://semoss.org/ontologies/Relation/Contains/Revenue-Domestic> ?DomesticRevenue }{?Title <http://semoss.org/ontologies/Relation/Contains/Revenue-International> ?InternationalRevenue}{?Title <http://semoss.org/ontologies/Relation/Contains/MovieBudget> ?Budget}{?Title <http://semoss.org/ontologies/Relation/Contains/RottenTomatoes-Critics> ?RottenTomatoesCritics } {?Title <http://semoss.org/ontologies/Relation/Contains/RottenTomatoes-Audience> ?RottenTomatoesAudience }{?Director <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Director>}{?Genre <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Genre>}{?Nominated <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Nominated>}{?Studio <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Studio>}{?Title <http://semoss.org/ontologies/Relation/DirectedBy> ?Director}{?Title <http://semoss.org/ontologies/Relation/BelongsTo> ?Genre}{?Title <http://semoss.org/ontologies/Relation/Was> ?Nominated}{?Title <http://semoss.org/ontologies/Relation/DirectedAt> ?Studio}} ORDER BY ?Title";
//		String query1 = "SELECT DISTINCT ?ICD ?Source ?Target ?DataObject ?Format ?Frequency ?Protocol WHERE { {?ICD <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/InterfaceControlDocument>} {?Source <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/System>} {?Source <http://semoss.org/ontologies/Relation/Provide> ?ICD} {?Target <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/System>} {?ICD <http://semoss.org/ontologies/Relation/Consume> ?Target} {?DataObject <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/DataObject>} {?carries <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> <http://semoss.org/ontologies/Relation/Payload>} {?carries <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> <http://semoss.org/ontologies/Relation/Payload>} {?ICD ?carries ?DataObject} {?carries <http://semoss.org/ontologies/Relation/Contains/Protocol> ?Protocol} {?carries <http://semoss.org/ontologies/Relation/Contains/Format> ?Format} {?carries <http://semoss.org/ontologies/Relation/Contains/Frequency> ?Frequency} } ORDER BY ?ICD";

		String query1 = "SELECT DISTINCT ?Title ?Studio ?DomesticRevenue ?InternationalRevenue ?Budget ?RottenTomatoesCritics ?RottenTomatoesAudience WHERE {{?Title <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Title>}{?Title <http://semoss.org/ontologies/Relation/Contains/Revenue-Domestic> ?DomesticRevenue }{?Title <http://semoss.org/ontologies/Relation/Contains/Revenue-International> ?InternationalRevenue}{?Title <http://semoss.org/ontologies/Relation/Contains/MovieBudget> ?Budget}{?Title <http://semoss.org/ontologies/Relation/Contains/RottenTomatoes-Critics> ?RottenTomatoesCritics } {?Title <http://semoss.org/ontologies/Relation/Contains/RottenTomatoes-Audience> ?RottenTomatoesAudience }{?Director <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Director>}{?Genre <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Genre>}{?Nominated <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Nominated>}{?Studio <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Studio>}{?Title <http://semoss.org/ontologies/Relation/DirectedBy> ?Director}{?Title <http://semoss.org/ontologies/Relation/BelongsTo> ?Genre}{?Title <http://semoss.org/ontologies/Relation/Was> ?Nominated}{?Title <http://semoss.org/ontologies/Relation/DirectedAt> ?Studio}} ORDER BY ?Title";

		//run query 1
		HttpSession session = request.getSession(false);
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
		long end = System.currentTimeMillis();
		System.out.println("Construction time = " + ((end-start)/1000) );
		
		start = System.currentTimeMillis();
		tree1.performAction(new FastOutlierDetection());
		end = System.currentTimeMillis();
		System.out.println("Algorithm time = " + ((end-start)/1000) );

		tree1.setColumnsToSkip(new ArrayList<String>());
		List<Object[]> flatData = tree1.getData();
		
		for(Object[] row: flatData) {
			for(Object instance: row) {
				System.out.print(instance.toString()+" ");
			}
			System.out.println();
		}
		
		// or get it from session
//		else{
//			tree1 = (BTreeDataFrame) session.getAttribute("testTree");
//			names1 = tree1.getColumnHeaders();
//		}
//		
//		//run query 2
//		ISelectWrapper wrap2 = WrapperManager.getInstance().getSWrapper(this.coreEngine, query2);
//		String[] names2 = wrap2.getVariables();
//		BTreeDataFrame tree2 = new BTreeDataFrame(names2);
//		int count = 0;
//		while(wrap2.hasNext()){
//			ISelectStatement iss = wrap2.next();
//			tree2.addRow(iss.getPropHash(), iss.getRPropHash());
////			System.out.println(" putting into tree " + iss.getPropHash().toString());//
//			System.out.println(count++);
//		}
		
//		Object[] col = tree1.getColumn("Title");
//		logger.info("starting join");
//		try {
//			tree1.join(tree2, names1[names1.length-1], names2[0], 1, new ExactStringMatcher());
//		} catch (Exception e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
//		logger.info("setting into session");
//		session.setAttribute("testTree", tree1);
//		logger.info("begining to flatten");
//		List<Object[]> data = tree1.getData();
//		
//		logger.info("done flattening");
//		logger.info("size is  " + data.size());
		//return WebUtility.getSO(data.size());
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
}