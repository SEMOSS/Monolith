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

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Hashtable;
import java.util.List;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.StreamingOutput;

import org.apache.log4j.Logger;

import prerna.om.GraphDataModel;
import prerna.om.SEMOSSVertex;
import prerna.rdf.engine.api.IEngine;
import prerna.semoss.web.services.specific.tap.AbstractControlClick;
import prerna.semoss.web.services.specific.tap.IControlClick;
import prerna.ui.components.ExecuteQueryProcessor;
import prerna.ui.components.api.IPlaySheet;
import prerna.ui.components.playsheets.AbstractRDFPlaySheet;
import prerna.ui.components.playsheets.GraphPlaySheet;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class PlaySheetResource {

	IPlaySheet playSheet;
	IEngine coreEngine;
	String output = "";
	Logger logger = Logger.getLogger(PlaySheetResource.class.getName());
	
	public void setPlaySheet(IPlaySheet playSheet){
		this.playSheet = playSheet;
	}

	public void setEngine(IEngine engine){
		this.coreEngine = engine;
	}

	// Binds the upNode currently on the graph and runs traversal query. Can take multiple instances and one type or multiple types and one instance
	@POST
	@Path("extend/downstream/instance")
	@Produces("application/json")
	public Object createDownstreamInstanceTraversal(MultivaluedMap<String, String> form, @Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		List<String> downNodeTypes = gson.fromJson(form.getFirst("downNodeTypes"), List.class);
		List<String> upNodeList = gson.fromJson(form.getFirst("upNode"), List.class);
		logger.info("Processing downstream traversal for node instance " + upNodeList.toString() + " to types " + downNodeTypes.toString());
		
		//get the query
		String prefix = "";
		String filterValues = "";
		String sparql = "";
		String downNodeType = "";
		if(downNodeTypes.size() == 1) 
		{
			sparql = DIHelper.getInstance().getProperty(Constants.TRAVERSE_FREELY_QUERY + prefix);
	
			//create bindings string using upNode list because there is only one type
			for(String uri : upNodeList){
				filterValues = filterValues + "(<" + uri + ">)";
			}
			downNodeType = downNodeTypes.get(0);
		}
		else if (upNodeList.size() == 1)//down node types has more than 1 so we need to put the types in the bindings
		{
			sparql = DIHelper.getInstance().getProperty(Constants.TRAVERSE_FREELY_QUERY_MULTI_TYPE + prefix);
	
			//create bindings string using upNode list because there is only one type
			for(String uri : downNodeTypes){
				downNodeType = downNodeType + "(<" + uri + ">)";
			}
			filterValues = upNodeList.get(0);
		}
		else 
		{
			logger.error("Too many arguments passed to traverse freely");
			return WebUtility.getSO("Too many arguments passed to traverse freely");
		}
		
		//process traversal
		Object obj = runPlaySheetTraversal(sparql, "", downNodeType, filterValues);

		// put the playsheet back in session
		storePlaySheet(request);
		
		return WebUtility.getSO(obj);
	}	

	// Binds the downNode currently on the graph and runs traversal query. Can take multiple instances and one type or multiple types and one instance
	@POST
	@Path("extend/upstream/instance")
	@Produces("application/json")
	public Object createUpstreamInstanceTraversal(MultivaluedMap<String, String> form, @Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		List<String> upNodeTypes = gson.fromJson(form.getFirst("upNodeTypes"), List.class);
		List<String> downNodeList = gson.fromJson(form.getFirst("downNode"), List.class);
		logger.info("Processing upstream traversal for node instances " + downNodeList.toString());
		
		//get the query
		String prefix = "_2";
		String filterValues = "";
		String sparql = "";
		String upNodeType = "";
		if(upNodeTypes.size() == 1) 
		{
			sparql = DIHelper.getInstance().getProperty(Constants.TRAVERSE_FREELY_QUERY + prefix);
	
			//create bindings string using downNode list because there is only one type
			for(String uri : downNodeList){
				filterValues = filterValues + "(<" + uri + ">)";
			}
			upNodeType = upNodeTypes.get(0);
		}
		else if (downNodeList.size() == 1)//up node types has more than 1 so we need to put the types in the bindings
		{
			sparql = DIHelper.getInstance().getProperty(Constants.TRAVERSE_FREELY_QUERY_MULTI_TYPE + prefix);
	
			//create bindings string using upNode list because there is only one type
			for(String uri : upNodeTypes){
				upNodeType = upNodeType + "(<" + uri + ">)";
			}
			filterValues = downNodeList.get(0);
		}
		else 
		{
			logger.error("Too many arguments passed to traverse freely");
			return WebUtility.getSO("Too many arguments passed to traverse freely");
		}

		//process traversal
		Object obj = runPlaySheetTraversal(sparql, upNodeType, "", filterValues);

		// put the playsheet back in session
		storePlaySheet(request);
		
		return WebUtility.getSO(obj);
	}	

//	// Binds all nodes of upNodeType currently on the graph and runs traversal query
//	@GET
//	@Path("extend/downstream/type")
//	@Produces("application/json")
//	public Object createDownstreamTypeTraversal( 
//			@QueryParam("upNode") String upNodeUri, 
//			@QueryParam("downNodeType") String downNodeType,
//			@Context HttpServletRequest request)
//	{
//		String upNodeType = Utility.getConceptType(coreEngine, upNodeUri);
//		logger.info("Processing downstream traversal for node type " + upNodeType);
//		
//		//get the query
//		String prefix = "";
//		String sparql = DIHelper.getInstance().getProperty(Constants.TRAVERSE_FREELY_QUERY + prefix);
//
//		//get necessary info about params passed in
//		//need to get the type from the node because the queried type will failed if ActiveSystem (because stored on the node is just type of System)
//		String searchType = upNodeType;
//		if(playSheet instanceof GraphPlaySheet){
//			SEMOSSVertex vert = ((GraphPlaySheet)playSheet).getGraphData().getVertStore().get(upNodeUri);
//			searchType = vert.getProperty(Constants.VERTEX_TYPE)+"";
//		}
//		String filterValues = getNodesOfType(searchType);
//		
//		//process traversal
//		Object obj = runPlaySheetTraversal(sparql, upNodeType, downNodeType, filterValues);
//
//		// put the playsheet back in session
//		storePlaySheet(request);
//		
//		return getSO(obj);
//	}	
//
//	// Binds all nodes of downNodeType currently on the graph and runs traversal query
//	@GET
//	@Path("extend/upstream/type")
//	@Produces("application/json")
//	public Object createUpstreamTypeTraversal( 
//			@QueryParam("upNodeType") String upNodeType, 
//			@QueryParam("downNode") String downNodeUri,
//			@Context HttpServletRequest request)
//	{
//		String downNodeType = Utility.getConceptType(coreEngine, downNodeUri);
//		logger.info("Processing upstream traversal for node type " + downNodeType);
//		
//		//get the query
//		String prefix = "_2";
//		String sparql = DIHelper.getInstance().getProperty(Constants.TRAVERSE_FREELY_QUERY + prefix);
//
//		//get necessary info about params passed in
//		//need to get the type from the node because the queried type will failed if ActiveSystem (because stored on the node is just type of System)
//		String searchType = downNodeType;
//		if(playSheet instanceof GraphPlaySheet){
//			SEMOSSVertex vert = ((GraphPlaySheet)playSheet).getGraphData().getVertStore().get(downNodeUri);
//			searchType = vert.getProperty(Constants.VERTEX_TYPE)+"";
//		}
//		String filterValues = getNodesOfType(searchType);
//		
//		//process traversal
//		Object obj = runPlaySheetTraversal(sparql, upNodeType, downNodeType, filterValues);
//
//		// put the playsheet back in session
//		storePlaySheet(request);
//		
//		return getSO(obj);
//	}	

	// temporary function for getting chart it data
	// will be replaced with query builder
	@GET
	@Path("overlay")
	@Produces("application/json")
	public StreamingOutput createPlaySheetOverlay(
			@QueryParam("insight") String insight, 
			@QueryParam("params") String params, 
			@Context HttpServletRequest request)
	{
		// executes the output and gives the data
		// executes the create runner
		// once complete, it would plug the output into the session
		// need to find a way where I can specify if I want to keep the result or not
		// params are typically passed on as
		// pairs like this
		// key$value~key2:value2 etc
		System.out.println("Params is " + params);
		Hashtable<String, Object> paramHash = Utility.getParamsFromString(params);
		
		ExecuteQueryProcessor exQueryProcessor = new ExecuteQueryProcessor();
		exQueryProcessor.setAppendBoolean(true);
		exQueryProcessor.setPlaySheet(playSheet);
		exQueryProcessor.processQuestionQuery(coreEngine.getEngineName(), insight, paramHash);
		
		playSheet = exQueryProcessor.getPlaySheet();
		
		Object obj = runPlaySheetOverlay();
		return WebUtility.getSO(obj);
	}
//
//	//gets all node types connected to a specific node instance
//	@GET
//	@Path("neighbors/type")
//	@Produces("application/json")
//	public StreamingOutput getNeighborsInstance(@QueryParam("node") String uri, @Context HttpServletRequest request)
//	{
//		Hashtable<String, Vector<String>> finalTypes = new Hashtable<String, Vector<String>>();
//		if(coreEngine instanceof AbstractEngine){
//			AbstractEngine engine = (AbstractEngine) coreEngine;
//			//get node type
//			String type = Utility.getConceptType(coreEngine, uri);
//			
//			//DOWNSTREAM PROCESSING
//			//get node types connected to this type
//			Vector<String> downNodeTypes = engine.getToNeighbors(type, 0);
//			
//			//for each available type, ensure each type has at least one instance connected to a node of the original node's type
//			//need to get the type from the node because the queried type will failed if ActiveSystem (because stored on the node is just type of System)
//			String searchType = type;
//			if(playSheet instanceof GraphPlaySheet){
//				SEMOSSVertex vert = ((GraphPlaySheet)playSheet).getGraphData().getVertStore().get(uri);
//				searchType = vert.getProperty(Constants.VERTEX_TYPE)+"";
//			}
//			String filterValues = getNodesOfType(searchType);
//			Vector<String> validDownTypes = new Vector<String>();
//			if(!filterValues.isEmpty())//empty bindings acts as no bindings at all, so need to have this check
//			{
//				String downAskQuery = "ASK { "
//						+ "{?connectedNode a <@NODE_TYPE@>} "
//						+ "{?nodes ?rel ?connectedNode}"
//						+ "}" 
//						+ "BINDINGS ?nodes {" + filterValues + "}";
//				for (String connectedType : downNodeTypes){
//					String filledDownAskQuery = downAskQuery.replace("@NODE_TYPE@", connectedType);
//					logger.info("Checking type " + connectedType + " with query " + filledDownAskQuery);
//					if(engine.execAskQuery(filledDownAskQuery))
//						validDownTypes.add(connectedType);
//				}
//				finalTypes.put("downstream", validDownTypes);
//				
//				//UPSTREAM PROCESSING
//				//get node types connected to this type
//				Vector<String> upNodeTypes = engine.getFromNeighbors(type, 0);
//				
//				//for each available type, ensure each type has at least one instance connected to original node
//				String upAskQuery = "ASK { "
//						+ "{?connectedNode a <@NODE_TYPE@>} "
//						+ "{?connectedNode ?rel ?nodes}"
//						+ "}" 
//						+ "BINDINGS ?nodes {" + filterValues + "}";
//				Vector<String> validUpTypes = new Vector<String>();
//				for (String connectedType : upNodeTypes){
//					String filledUpAskQuery = upAskQuery.replace("@NODE_TYPE@", connectedType);
//					logger.info("Checking type " + connectedType + " with query " + filledUpAskQuery);
//					if(engine.execAskQuery(filledUpAskQuery))
//						validUpTypes.add(connectedType);
//				}
//				finalTypes.put("upstream", validUpTypes);
//			}
//		}
//		return getSO(finalTypes);
//	}
//	
	// temporary function for getting chart it data
	// will be replaced with query builder
	@GET
	@Path("chartData")
	@Produces("application/json")
	public StreamingOutput getPlaySheetChartData(
			@Context HttpServletRequest request)
	{
		Hashtable<String, Vector<SEMOSSVertex>> typeHash = new Hashtable<String, Vector<SEMOSSVertex>>();
		if(playSheet instanceof GraphPlaySheet){
			Hashtable<String, SEMOSSVertex> nodeHash = ((GraphPlaySheet)playSheet).getGraphData().getVertStore();
			// need to create type hash... its the way chartit wants the data..
			logger.info("creating type hash...");
			for( SEMOSSVertex vert : nodeHash.values()){
				String type = vert.getProperty(Constants.VERTEX_TYPE) + "";
				Vector<SEMOSSVertex> typeVert = typeHash.get(type);
				if(typeVert == null)
					typeVert = new Vector<SEMOSSVertex>();
				typeVert.add(vert);
				typeHash.put(type, typeVert);
			}
		}
		else
			logger.error("Currently cannot chart it from playsheets other than graph play sheet");
		
		Hashtable retHash = new Hashtable();
		retHash.put("Nodes", typeHash);
		return WebUtility.getSO(retHash);
	}

	// temporary function for getting chart it data
	// will be replaced with query builder
	@GET
	@Path("undo")
	@Produces("application/json")
	public StreamingOutput undo(
			@Context HttpServletRequest request)
	{
		Object obj = null;
		if ( playSheet instanceof GraphPlaySheet){
			GraphPlaySheet gps = (GraphPlaySheet)playSheet;
			GraphDataModel gdm = gps.getGraphData();
			gdm.setUndo(true);
			gdm.undoData();
			gdm.fillStoresFromModel();
			gps.setAppend(true);
			obj = gps.getData();
		}

		// put the playsheet back in session
		storePlaySheet(request);
		return WebUtility.getSO(obj);
	}

	// temporary function for getting chart it data
	// will be replaced with query builder
	@GET
	@Path("redo")
	@Produces("application/json")
	public StreamingOutput redo(
			@Context HttpServletRequest request)
	{
		Object obj = null;
		if ( playSheet instanceof GraphPlaySheet){
			GraphPlaySheet gps = (GraphPlaySheet)playSheet;
			gps.setAppend(true);
			GraphDataModel gdm = gps.getGraphData();
			gdm.redoData();
			gdm.fillStoresFromModel();
			obj = gps.getData();
		}

		// put the playsheet back in session
		storePlaySheet(request);
		return WebUtility.getSO(obj);
	}
	
	// fills the traversal query and calls overlay
	private Object runPlaySheetTraversal(String sparql, String upNodeType, String downNodeType, String filterValues){
		//fill the sparql
		sparql = sparql.replace("@FILTER_VALUES@", filterValues).replace("@SUBJECT_TYPE@", upNodeType).replace("@OBJECT_TYPE@", downNodeType);
		playSheet.setQuery(sparql);
		Object obj = runPlaySheetOverlay();
		return obj;
	}

	//basic overlay processing
	//sparql query must already be set on the playsheet
	private Object runPlaySheetOverlay(){
		Object obj = null;

		try
		{
			// this check probably isn't needed... for the time being, though, if the ps is not in session, create a new graph play sheet
			if(playSheet == null)
				playSheet = (IPlaySheet)Class.forName("prerna.ui.components.playsheets.GraphPlaySheet").newInstance();
			
			if(playSheet instanceof AbstractRDFPlaySheet)
				((AbstractRDFPlaySheet)playSheet).setAppend(true);
			
			playSheet.setRDFEngine(coreEngine);
			playSheet.createData();
			playSheet.runAnalytics();
			
			obj = playSheet.getData();
				
		}catch(Exception ex)
		{
			ex.printStackTrace();
		}
		return obj;
	}

	
	private void storePlaySheet(HttpServletRequest request){
		// put the playsheet back in session
		request.getSession(false).setAttribute(playSheet.getQuestionID(), playSheet);
	}
	
	//returns a string ready for the BINDINGS of a query that has all nodes of the type targetType
	private String getNodesOfType(String nodeType){
		String targetType = Utility.getInstanceName(nodeType);
		String filterValues = "";
		//get necessary info about params passed in
		if(playSheet instanceof GraphPlaySheet){
			Hashtable<String, SEMOSSVertex> nodeHash = ((GraphPlaySheet)playSheet).getGraphData().getVertStore();
			// need to create type hash... its the way chartit wants the data..
			logger.info("Creating filter values with nodes of type " +targetType);
			for( SEMOSSVertex vert : nodeHash.values()){
				String vertType = vert.getProperty(Constants.VERTEX_TYPE) + "";
				if(vertType.equals(targetType))
					filterValues = filterValues + "(<" + vert.getProperty(Constants.URI) + ">)";
			}
		}
		return filterValues;
	}
	    
    //for handling playsheet specific tool calls
    @Path("f-{functionPackageID}-{functionsID}")
    public IControlClick register(@PathParam("functionPackageID") String functionPackageID, @PathParam("functionsID") String functionsID, @Context HttpServletRequest request)
    {    	
    	IControlClick acc = null;
		try {
			Field f = Constants.class.getDeclaredField(functionPackageID);
			f.setAccessible(true);
			acc = (IControlClick) Class.forName(f.get(functionPackageID) + functionsID).getConstructor(null).newInstance(null);
		} catch (InstantiationException | IllegalAccessException
				| IllegalArgumentException | InvocationTargetException
				| NoSuchMethodException | SecurityException
				| ClassNotFoundException | NoSuchFieldException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    	acc.setEngine(this.coreEngine);
    	acc.setPlaySheet(this.playSheet);  
    	return acc;
    }  
}
