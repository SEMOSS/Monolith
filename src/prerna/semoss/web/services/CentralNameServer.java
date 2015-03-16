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
import java.util.Hashtable;
import java.util.List;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.StreamingOutput;

import org.apache.log4j.Logger;

import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

import com.google.gson.Gson;

public class CentralNameServer {

	@Context ServletContext context;
	Logger logger = Logger.getLogger(CentralNameServer.class.getName());
	String output = "";
	String centralApi = "";
	List<String> localDb = Arrays.asList(Constants.LOCAL_MASTER_DB_NAME);
	
	public void setCentralApi(String centralApi){
		this.centralApi = centralApi;
	}

	// local call to get all insights related to a specific uri
	// preferably we would also pass vert store and edge store... the more context the better. Don't have any of that for now though.
	@POST
	@Path("context/insights")
	@Produces("application/json")
	public StreamingOutput getContextInsights(
			MultivaluedMap<String, String> form, 
			@Context HttpServletRequest request)
	{
		String selectedUris = form.getFirst("selectedURI");
		logger.info("LOCALLY have registered selected URIs as ::: " + selectedUris.toString());

		// if we are going to a remote name server
		if(centralApi!=null){
			Hashtable params = new Hashtable();
			params.put("selectedURI", selectedUris);
			return WebUtility.getSO(Utility.retrieveResult(centralApi + "/api/engine/central/context/insights", params));
		}
		else {
			NameServer ns = new NameServer();
			form.put("localMasterDbName", localDb);
			return ns.getCentralContextInsights(form, request);
		}
	}	

	// local call to get all engines related to a metamodel path
	// expecting vert store and edge store of metamodel level data
	@POST
	@Path("context/databases")
	@Produces("application/json")
	public StreamingOutput getContextDatabases(
			MultivaluedMap<String, String> form, 
			@Context HttpServletRequest request)
	{
		String queryData = form.getFirst("QueryData");
		logger.info("LOCALLY have registered selected URIs as ::: " + queryData.toString());

		// if we are going to a remote name server
		if(centralApi!=null){
			Hashtable params = new Hashtable();
			params.put("QueryData", queryData);
			
			return WebUtility.getSO(Utility.retrieveResult(centralApi + "/api/engine/central/context/databases", params));
		}
		else {
			NameServer ns = new NameServer();
			form.put("localMasterDbName", localDb);
			return ns.getCentralContextDatabases(form, request);
		}
	}	
	
	// local call to register an engine to the central name server and master db
	@POST
	@Path("context/registerEngine")
	@Produces("application/json")
	public StreamingOutput registerEngineApi(
			MultivaluedMap<String, String> form, 
			@Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		String engineApi = form.getFirst("dbName");
		ArrayList<String> dbArray = gson.fromJson(engineApi, ArrayList.class);
		logger.info("LOCALLY have engineAPI  ::: " + engineApi.toString());
		
		// if we are going to a remote name server
		if(centralApi!=null){
			String baseURL = request.getRequestURL().toString();
			baseURL = baseURL.substring(0,baseURL.indexOf("/api/engine/")) + "/api/engine";
			Hashtable params = new Hashtable();
			params.put("dbName", engineApi);
			params.put("baseURL", baseURL);
			return WebUtility.getSO(Utility.retrieveResult(centralApi + "/api/engine/central/context/registerEngine", params));
		}
		else{
			logger.info("LOCALLY registering engineAPI  ::: " + dbArray.toString());
			NameServer ns = new NameServer();
			form.put("localMasterDbName", localDb);
			return ns.registerEngine2MasterDatabase(form, request);
		}
	}
	
	// local call to UNregister an engine to the central name server and master db
	@POST
	@Path("context/unregisterEngine")
	@Produces("application/json")
	public StreamingOutput unregisterEngineApi(
			MultivaluedMap<String, String> form, 
			@Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		String engineApi = form.getFirst("dbName");
		ArrayList<String> dbArray = gson.fromJson(engineApi, ArrayList.class);
		logger.info("LOCALLY have engineAPI  ::: " + dbArray.toString());

		// if we are going to a remote name server
		if(centralApi!=null){
			Hashtable params = new Hashtable();
			params.put("dbName", engineApi);
			String result = Utility.retrieveResult(centralApi + "/api/engine/central/context/unregisterEngine", params);
			return WebUtility.getSO(result);
		}
		else{
			logger.info("LOCALLY removing engineAPI  ::: " + dbArray.toString());
			NameServer ns = new NameServer();
			form.put("localMasterDbName", localDb);
			return ns.unregisterEngine2MasterDatabase(form, request);
		}
	}
}
