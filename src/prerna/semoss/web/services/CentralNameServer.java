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

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
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
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrServerException;

import com.google.gson.Gson;

import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

public class CentralNameServer {

	@Context 
	private ServletContext context;
	
	private static final Logger logger = Logger.getLogger(CentralNameServer.class.getName());
	private String centralApi = "";
	private List<String> localDb = Arrays.asList(Constants.LOCAL_MASTER_DB_NAME);
	
	public void setCentralApi(String centralApi){
		this.centralApi = centralApi;
	}

	// local call to get all insights related to a specific uri
	// preferably we would also pass vert store and edge store... the more context the better. Don't have any of that for now though.
	@POST
	@Path("context/insights")
	@Produces("application/json")
	public Response getContextInsights(
			MultivaluedMap<String, String> form, 
			@Context HttpServletRequest request) throws KeyManagementException, NoSuchAlgorithmException, KeyStoreException, SolrServerException, IOException
	{
		String selectedUris = form.getFirst("selectedURI");
		logger.info("LOCALLY have registered selected URIs as ::: " + selectedUris.toString());

		// if we are going to a remote name server
		if(centralApi!=null){
			Hashtable params = new Hashtable();
			params.put("selectedURI", selectedUris);
//			return Response.status(200).entity(WebUtility.getSO(Utility.retrieveResult(centralApi + "/api/engine/central/context/insights", params))).build();
			return WebUtility.getResponse(Utility.retrieveResult(centralApi + "/api/engine/central/context/insights", params), 200);
		}
		else {
			NameServer ns = new NameServer();
			form.put("localMasterDbName", localDb);
			return ns.getCentralContextInsights(form, request);
		}
	}	

	@POST
	@Path("context/getConnectedConcepts")
	public StreamingOutput getConnectedConcepts(
			MultivaluedMap<String, String> form, 
			@Context HttpServletRequest request)
	{
		String conceptURI = form.getFirst("conceptURI");		
		logger.info("LOCALLY have concept selected as ::: " + conceptURI);

		if(centralApi!=null) {
			Hashtable params = new Hashtable();
			params.put("conceptURI", conceptURI);
			return WebUtility.getSO(Utility.retrieveResult(centralApi + "/api/engine/central/context/getConnectedConcepts2", params));
		} else {
			NameServer ns = new NameServer();
			form.put("localMasterDbName", localDb);
			return ns.getConnectedConcepts(form, request);
		}
	}
	
	@POST
	@Path("central/context/insights")
	@Produces("application/json")
	public StreamingOutput getCentralContextInsights(
			MultivaluedMap<String, String> form, 
			@Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		ArrayList<String> selectedUris = gson.fromJson(form.getFirst("selectedURI"), ArrayList.class);
		logger.info("LOCALLY have instances selected for search ::: " + selectedUris);

		if(centralApi!=null) {
			Hashtable params = new Hashtable();
			params.put("selectedURI", selectedUris);
			return WebUtility.getSO(Utility.retrieveResult(centralApi + "/api/engine/central/context/getConnectedConcepts2", params));
		} else {
			NameServer ns = new NameServer();
			form.put("localMasterDbName", localDb);
			return ns.getConnectedConcepts(form, request);
		}
	}
}
