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
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Vector;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.http.HttpEntity;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrDocumentList;
import org.jsoup.Jsoup;
import org.openrdf.repository.RepositoryException;
import org.openrdf.rio.RDFParseException;

import com.google.gson.Gson;
import com.google.gson.internal.StringMap;
import com.google.gson.reflect.TypeToken;

import prerna.auth.User;
import prerna.auth.UserPermissionsMasterDB;
import prerna.cache.CacheFactory;
import prerna.cache.InsightCache;
import prerna.ds.BTreeDataFrame;
import prerna.engine.api.IEngine;
import prerna.engine.api.ISelectStatement;
import prerna.engine.api.ISelectWrapper;
import prerna.engine.impl.AbstractEngine;
import prerna.engine.impl.rdf.RemoteSemossSesameEngine;
import prerna.insights.admin.DBAdminResource;
import prerna.nameserver.AddToMasterDB;
import prerna.nameserver.ConnectedConcepts;
import prerna.nameserver.DeleteFromMasterDB;
import prerna.nameserver.INameServer;
import prerna.nameserver.MasterDatabaseQueries;
import prerna.nameserver.NameServerProcessor;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.solr.SolrIndexEngine;
import prerna.solr.SolrIndexEngineQueryBuilder;
import prerna.ui.components.playsheets.datamakers.DataMakerComponent;
import prerna.ui.components.playsheets.datamakers.IDataMaker;
import prerna.ui.helpers.InsightCreateRunner;
import prerna.upload.DatabaseUploader;
import prerna.upload.FileUploader;
import prerna.upload.Uploader;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.PlaySheetRDFMapBasedEnum;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/engine")
public class NameServer {

	@Context
	ServletContext context;
	Logger logger = Logger.getLogger(NameServer.class.getName());
	String output = "";
	Hashtable helpHash = null;

	// gets the engine resource necessary for all engine calls
	@Path("e-{engine}")
	public Object getLocalDatabase(@PathParam("engine") String db, @QueryParam("api") String api,
			@Context HttpServletRequest request) throws IOException {
		// check if api has been passed
		// if yes:
		// check if remote engine has already been started and stored in context
		// -- if so, use that engine
		// next check if local engine by that name has been started and stored
		// in context
		// finally start the remote engine and store in context with api+engine
		// name
		// otherwise grab local engine
		System.out.println(" Getting DB... " + db);
		HttpSession session = request.getSession();
		IEngine engine = null;
		if (api != null) {
			String remoteEngineKey = api + ":" + db;
			engine = (IEngine) session.getAttribute(remoteEngineKey);
			if (engine == null)
				engine = (IEngine) session.getAttribute(db);
			if (engine == null) {
				addEngine(request, api, db);
				engine = (IEngine) session.getAttribute(remoteEngineKey);
			}
		} else {
			engine = (IEngine) session.getAttribute(db);
		}
		if (engine == null)
			throw new IOException("The engine " + db + " at " + api + " cannot be found");
		EngineResource res = new EngineResource();
		res.setEngine(engine);
		return res;
	}

	@Path("s-{engine}")
	public Object getEngineProxy(@PathParam("engine") String db, @Context HttpServletRequest request) {
		// this is the name server
		// this needs to return stuff
		System.out.println(" Getting DB... " + db);
		HttpSession session = request.getSession();
		IEngine engine = (IEngine) session.getAttribute(db);
		EngineRemoteResource res = new EngineRemoteResource();
		res.setEngine(engine);
		return res;
	}

	// Controls all calls controlling the central name server
	@Path("centralNameServer")
	public Object getCentralNameServer(@QueryParam("centralServerUrl") String url,
			@Context HttpServletRequest request) {
		// this is the name server
		// this needs to return stuff
		System.out.println(" Going to central name server ... " + url);
		CentralNameServer cns = new CentralNameServer();
		cns.setCentralApi(url);
		return cns;
	}

	@GET
	@Path("neighbors")
	@Produces("application/json")
	public StreamingOutput getNeighbors(@QueryParam("node") String type, @Context HttpServletRequest request) {
		return null;
	}

	// gets all the insights for a given type and tag in all the engines
	// both tag and type are optional
	@GET
	@Path("insightsByType")
	@Produces("application/json")
	public StreamingOutput getInsights(@QueryParam("node") String type, @QueryParam("tag") String tag, @Context HttpServletRequest request) {
		// if the type is null then send all the insights else only that
		// I need to do this in a cluster engine
		// for now I will do this as a running list
		return null;
	}

	// gets all the tags for a given insight across all the engines
	@GET
	@Path("tags")
	@Produces("application/json")
	public StreamingOutput getTags(@QueryParam("insight") String insight, @Context HttpServletRequest request) {
		// if the tag is empty, this will give back all the tags in the engines
		return null;
	}

	// gets a particular insight
	@GET
	@Path("tags")
	@Produces("application/xml")
	public StreamingOutput getInsight(@QueryParam("insight") String insight) {
		// returns the insight
		// typically is a JSON of the insight
		return null;
	}

	// gets a particular insight
	@GET
	@Path("/insight/create")
	@Produces("application/html")
	public StreamingOutput createEngine() {
		// this creates the HTML that needs to be uploaded
		// see FileUpload.html
		return null;
	}

	// gets a particular insight
	@GET
	@Path("all")
	@Produces("application/json")
	public StreamingOutput printEngines(@Context HttpServletRequest request) {
		// would be cool to give this as an HTML
		Hashtable<String, ArrayList<Hashtable<String, String>>> hashTable = new Hashtable<String, ArrayList<Hashtable<String, String>>>();
		// ArrayList<String> enginesList = new ArrayList<String>();
		HttpSession session = request.getSession();
		ArrayList<Hashtable<String, String>> engines = (ArrayList<Hashtable<String, String>>) session
				.getAttribute(Constants.ENGINES);
		// StringTokenizer tokens = new StringTokenizer(engines, ":");
		// while(tokens.hasMoreTokens()) {
		// enginesList.add(tokens.nextToken());
		// }
		hashTable.put("engines", engines);
		return WebUtility.getSO(hashTable);
	}

	@GET
	@Path("add")
	@Produces("application/json")
	public void addEngine(@Context HttpServletRequest request, @QueryParam("api") String api, @QueryParam("database") String database) {
		// would be cool to give this as an HTML
		RemoteSemossSesameEngine newEngine = new RemoteSemossSesameEngine();
		newEngine.setAPI(api);
		newEngine.setDatabase(database);
		HttpSession session = request.getSession();
		ArrayList<Hashtable<String, String>> engines = (ArrayList<Hashtable<String, String>>) session
				.getAttribute(Constants.ENGINES);
		// temporal
		String remoteDbKey = api + ":" + database;
		newEngine.openDB(null);
		if (newEngine.isConnected()) {
			Hashtable<String, String> engineHash = new Hashtable<String, String>();
			engineHash.put("name", database);
			engineHash.put("api", api);
			engines.add(engineHash);
			session.setAttribute(Constants.ENGINES, engines);
			session.setAttribute(remoteDbKey, newEngine);
			DIHelper.getInstance().setLocalProperty(remoteDbKey, newEngine);
		}

	}

	// gets a particular insight
	@GET
	@Path("help")
	@Produces("text/html")
	public StreamingOutput printURL(@Context HttpServletRequest request, @Context HttpServletResponse response) {
		// would be cool to give this as an HTML
		if (helpHash == null) {
			Hashtable urls = new Hashtable();
			urls.put("Help - this menu (GET)", "hostname:portname/Monolith/api/engine/help");
			urls.put("Get All the engines (GET)", "hostname:portname/Monolith/api/engine/all");
			urls.put("Perspectives in a specific engine (GET)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/perspectives");
			urls.put("All Insights in a engine (GET)", "hostname:portname/Monolith/api/engine/e-{engineName}/insights");
			urls.put("All Perspectives and Insights in a engine (GET)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/pinsights");
			urls.put("Insights for specific perspective specific engine (GET)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/insights?perspective={perspective}");
			urls.put("Insight definition for a particular insight (GET)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/insight?insight={label of insight (NOT ID)}");
			urls.put("Execute insight without parameter (GET)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/output?insight={label of insight (NOT ID)}");
			urls.put("Execute insight with parameter (GET)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/output?insight={label of insight (NOT ID)}&params=key$value~key2$value2~key3$value3");
			urls.put("Execute Custom Query Select (POST)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/querys?query={sparql query}");
			urls.put("Execute Custom Query Construct (GET)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/queryc?query={sparql query}");
			urls.put("Execute Custom Query Insert/Delete (POST)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/update?query={sparql query}");
			urls.put("Numeric properties of a given node type (GET)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/properties/node/type/numeric?nodeType={URI}");
			urls.put("Fill Values for a given parameter (You already get this in insights) (GET)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/fill?type={type}");
			urls.put("Get Neighbors of a particular node (GET)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/neighbors/instance?node={URI}");
			urls.put("Tags for an insight (Specific Engine)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/tags?insight={insight label}");
			urls.put("Insights for a given tag (Tag is optional) (Specific Engine) ",
					"hostname:portname/Monolith/api/engine/e-{engineName}/insight?tag={xyz}");
			urls.put("Neighbors of across all engine", "hostname:portname/Monolith/api/engine/neighbors?node={URI}");
			urls.put("Tags for an insight", "hostname:portname/Monolith/api/engine/tags?insight={insight label}");
			urls.put("Insights for a given tag (Tag is optional)",
					"hostname:portname/Monolith/api/engine/insight?tag={xyz}");
			urls.put("Create a new engine using excel (requires form submission) (POST)",
					"hostname:portname/Monolith/api/engine/insight/upload/excel/upload");
			urls.put("Create a new engine using csv (requires form submission) (POST)",
					"hostname:portname/Monolith/api/engine/insight/upload/csv/upload");
			urls.put("Create a new engine using nlp (requires form submission) (POST)",
					"hostname:portname/Monolith/api/engine/insight/upload/nlp/upload (GET)");
			helpHash = urls;
		}
		return getSOHTML();
	}

	// uploader functionality
	@Path("/uploadDatabase")
	public Uploader uploadDatabase(@Context HttpServletRequest request) {
		Uploader upload = new DatabaseUploader();
		String filePath = context.getInitParameter("file-upload");
		upload.setFilePath(filePath);
		String tempFilePath = context.getInitParameter("temp-file-upload");
		upload.setTempFilePath(tempFilePath);
		upload.setSecurityEnabled(Boolean.parseBoolean(context.getInitParameter(Constants.SECURITY_ENABLED)));
		return upload;
	}
	
	@Path("/uploadFile")
	public Uploader uploadFile(@Context HttpServletRequest request) {
		Uploader upload = new FileUploader();
		String filePath = context.getInitParameter("file-upload");
		upload.setFilePath(filePath);
		String tempFilePath = context.getInitParameter("temp-file-upload");
		upload.setTempFilePath(tempFilePath);
		upload.setSecurityEnabled(Boolean.parseBoolean(context.getInitParameter(Constants.SECURITY_ENABLED)));
		return upload;
	}
	
	// central call to store an engine in the master db
	@POST
	@Path("central/context/registerEngine")
	@Produces("application/json")
	public StreamingOutput registerEngine2MasterDatabase(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		Gson gson = new Gson();
		ArrayList<String> dbArray = gson.fromJson(form.getFirst("dbName"), ArrayList.class);
		String baseURL = form.getFirst("baseURL");
		String localMasterDbName = form.getFirst("localMasterDbName");

		Hashtable<String, Boolean> resultHash = new Hashtable<String, Boolean>();

		if (localMasterDbName == null) {
			try {
				AddToMasterDB creater = new AddToMasterDB();
				resultHash = creater.registerEngineAPI(baseURL, dbArray);
			} catch (RDFParseException e) {
				e.printStackTrace();
			} catch (RepositoryException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else // it must be local master db thus the name of master db must
				// have been passed
		{
			AddToMasterDB creater = new AddToMasterDB(localMasterDbName);
			resultHash = creater.registerEngineLocal(dbArray);
		}

		return WebUtility.getSO(resultHash);
	}
	// central call to remove an engine from the master db
	@POST
	@Path("central/context/unregisterEngine")
	@Produces("application/json")
	public StreamingOutput unregisterEngine2MasterDatabase(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		Gson gson = new Gson();
		ArrayList<String> dbArray = gson.fromJson(form.getFirst("dbName") + "", ArrayList.class);
		logger.info("CENTRALLY removing dbs  ::: " + dbArray.toString());
		String localMasterDbName = form.getFirst("localMasterDbName");

		Hashtable<String, Boolean> resultHash = new Hashtable<String, Boolean>();
		if (localMasterDbName == null) {
			DeleteFromMasterDB deleater = new DeleteFromMasterDB();
			resultHash = deleater.deleteEngineWeb(dbArray);
		} else {
			DeleteFromMasterDB deleater = new DeleteFromMasterDB(localMasterDbName);
			resultHash = deleater.deleteEngine(dbArray);
		}

		return WebUtility.getSO(resultHash);
	}

	
	/**
	 * Complete user search based on string input
	 * @return
	 */
	@GET
	@Path("central/context/getAutoCompleteResults")
	@Produces("application/json")
	public StreamingOutput getAutoCompleteResults(@QueryParam("completeTerm") String searchString, @Context HttpServletRequest request) {
		List<String> results = null;
		try {
			results = SolrIndexEngine.getInstance().executeAutoCompleteQuery(searchString);
		} catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | SolrServerException
				| IOException e) {
			e.printStackTrace();
		}
		return WebUtility.getSO(results);
	}
	
	/**
	 * Search based on a string input 
	 * @param form - information passes in from the front end
	 * @return a string version of the results attained from the query search
	 */
	@POST
	@Path("central/context/getSearchInsightsResults")
	@Produces("application/json")
	public StreamingOutput getSearchInsightsResults(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		//text searched in search bar
		String searchString = form.getFirst("searchString");
		logger.info("Searching based on input: " + searchString);
		
		//sort (based on relevance, asc, desc)
		String sortString = form.getFirst("sortString");
		logger.info("Sorting by: " + sortString);
		
		//offset for call
		String offset = form.getFirst("offset");
		logger.info("Offset is: " + offset);
		
		//offset for call
		String limit = form.getFirst("limit");
		logger.info("Limit is: " + limit);

		Integer offsetInt = null;
		Integer limitInt = null;
		if(offset != null && !offset.isEmpty()) {
			offsetInt = Integer.parseInt(offset);
		}
		if(limit != null && !limit.isEmpty()) {
			limitInt = Integer.parseInt(limit);
		}
		
		//filter based on the boxes checked in the facet filter (filtered with an exact filter)
		String filterDataStr = form.getFirst("filterData");
		Gson gson = new Gson();
		Map<String, List<String>> filterData = gson.fromJson(filterDataStr, new TypeToken<Map<String, List<String>>>() {}.getType());
		
		//If security is enabled, remove the engines in the filters that aren't accessible - if none in filters, add all accessible engines to filter list
		if(Boolean.parseBoolean(context.getInitParameter(Constants.SECURITY_ENABLED))) {
			HttpSession session = request.getSession(true);
			User user = ((User) session.getAttribute(Constants.SESSION_USER));
			String userId = "";
			if(user!= null) {
				userId = user.getId();
			}
			UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
			HashSet<String> userEngines = permissions.getUserAccessibleEngines(userId);
			ArrayList<String> filterEngines = new ArrayList<String>();
			if(filterData.get("core_engine") != null) {
				filterEngines.addAll(filterData.get("core_engine"));
			}
			if(filterEngines.size() > 0) {
				for(String s : filterEngines) {
					if(!userEngines.contains(s)) {
						filterData.get("core_engine").remove(s);
					}
				}
			} else {
				filterEngines.addAll(userEngines);
				if(filterEngines.size() > 0) 
					filterData.put("core_engine", filterEngines);
			}
		}
		
		Map<String, Object> results = null;
		try {
			results = SolrIndexEngine.getInstance().executeSearchQuery(searchString, sortString, offsetInt, limitInt, filterData);
		} catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | SolrServerException | IOException e1) {
			e1.printStackTrace();
			return WebUtility.getSO("Error executing solr query");
		}
		
		return WebUtility.getSO(results);
	}

	/**
	 * Facet count based on info from search. This faceted instance count based on specified field 
	 * @param form - information passes in from the front end
	 * @return a string version of the results attained from the query/facet search
	 */
	// facet
	@GET
	@Path("central/context/getFacetInsightsResults")
	@Produces("application/json")
	public StreamingOutput getFacetInsightsResults(@QueryParam("searchTerm") String searchString, @Context HttpServletRequest request) {
		logger.info("Searching based on input: " + searchString);

		List<String> facetList = new ArrayList<>();
		facetList.add(SolrIndexEngine.CORE_ENGINE);
		facetList.add(SolrIndexEngine.LAYOUT);
		facetList.add(SolrIndexEngine.PARAMS);
		facetList.add(SolrIndexEngine.TAGS);
		facetList.add(SolrIndexEngine.ALGORITHMS);

		Map<String, Map<String, Long>> facetFieldMap = null;
		try {
			facetFieldMap = SolrIndexEngine.getInstance().executeQueryFacetResults(searchString, facetList);
		} catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | SolrServerException e) {
			e.printStackTrace();
		}
		return WebUtility.getSO(facetFieldMap);

	}

	/**
	 * GroupBy based based on info from search. This groups documents based on specified field 
	 * @param form - information passes in from the front end
	 * @return a string version of the results attained from the query/group by search
	 */
	// group based on info from the search
	@POST
	@Path("central/context/getGroupInsightsResults")
	@Produces("application/json")
	public StreamingOutput getGroupInsightsResults(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		//text searched in search bar
		String searchString = form.getFirst("searchString");
		logger.info("Searching based on input: " + searchString);
				
		//specifies the starting number for the list of insights to return
		String groupOffset = form.getFirst("groupOffset");
		logger.info("Group offset is: " + groupOffset);
		
		//specifies the number of insights to return
		String groupLimit = form.getFirst("groupLimit");
		logger.info("Group limit is: " + groupLimit);

		String groupSort = form.getFirst("groupSort");
		logger.info("Group sort is: " + groupSort);
		
		//specifies the single field to group by
		String groupByField = form.getFirst("groupBy");
		logger.info("Group field is: " + groupByField);

		Integer groupLimitInt = null;
		Integer groupOffsetInt = null;
		if(groupLimit != null && !groupLimit.isEmpty()) {
			groupLimitInt = Integer.parseInt(groupLimit);
		}
		if(groupOffset != null && !groupOffset.isEmpty()) {
			groupOffsetInt = Integer.parseInt(groupOffset);
		}
		
		//filter based on the boxes checked in the facet filter (filtered with an exact filter)
		String filterDataStr = form.getFirst("filterData");
		Gson gson = new Gson();
		Map<String, List<String>> filterData = gson.fromJson(filterDataStr, new TypeToken<Map<String, List<String>>>() {}.getType());
		
		//If security is enabled, remove the engines in the filters that aren't accessible - if none in filters, add all accessible engines to filter list
		if(Boolean.parseBoolean(context.getInitParameter(Constants.SECURITY_ENABLED))) {
			HttpSession session = request.getSession(true);
			User user = ((User) session.getAttribute(Constants.SESSION_USER));
			String userId = "";
			if(user!= null) {
				userId = user.getId();
			}
			UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
			HashSet<String> userEngines = permissions.getUserAccessibleEngines(userId);
			ArrayList<String> filterEngines = new ArrayList<String>();
			if(filterData.get("core_engine") != null) {
				filterEngines.addAll(filterData.get("core_engine"));
			}
			if(filterEngines.size() > 0) {
				for(String s : filterEngines) {
					if(!userEngines.contains(s)) {
						filterData.get("core_engine").remove(s);
					}
				}
			} else {
				filterEngines.addAll(userEngines);
				if(filterEngines.size() > 0) 
					filterData.put("core_engine", filterEngines);
			}
		}
		
		Map<String, Object> groupFieldMap = null;
		try {
			groupFieldMap = SolrIndexEngine.getInstance().executeQueryGroupBy(searchString, groupOffsetInt, groupLimitInt, groupByField, groupSort, filterData);
		} catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | SolrServerException | IOException e) {
			e.printStackTrace();
		}

		return WebUtility.getSO(groupFieldMap);
	}

	@POST
	@Path("central/context/getConnectedConcepts")
	@Produces("application/json")
	public StreamingOutput getConnectedConcepts(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String conceptURI = form.getFirst("conceptURI");
		
		String localMasterDbName = form.getFirst("localMasterDbName");
		logger.info("CENTRALLY have registered selected URIs as ::: " + conceptURI.toString());
		IEngine masterDB = (IEngine) DIHelper.getInstance().getLocalProp(localMasterDbName);

		INameServer ns = null;
		if(!conceptURI.startsWith("http://")) {
			ns = new NameServerProcessor(masterDB);
			conceptURI = ns.findMostSimilarKeyword(conceptURI);
			if(conceptURI == null) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("errorMessage", "No similar concepts found");
//				return WebUtility.getSO(errorMap);
				return WebUtility.getSO("");
			}
			//Need to get rid of keyword portion to create concept uri in owl
			conceptURI = conceptURI.replace("Keyword/", "");
		} else {
			IEngine engine = (IEngine) DIHelper.getInstance().getLocalProp(form.getFirst("engine"));
			conceptURI = engine.getTransformedNodeName(conceptURI, false);
		}
		
		ConnectedConcepts results = null;
		// regardless of input master/local databases, uses the same method
		// since it only queries the master db and not the databases used to
		// create it
		if (localMasterDbName == null) {
			// this call is not local, need to get the API to run queries
			ns = new NameServerProcessor();
			results = ns.searchConnectedConcepts(conceptURI);
		} else {
			// this call is local, grab the engine from DIHelper
			if(ns ==  null) {
				ns = new NameServerProcessor(masterDB);
			}
			results = ns.searchConnectedConcepts(conceptURI);
		}
		return WebUtility.getSO(results.getData());
	}
	
	
	@POST
	@Path("central/context/getAllConcepts")
	@Produces("application/json")
	public Response getAllConceptsFromEngines(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String localMasterDbName = form.getFirst("localMasterDbName");
		IEngine masterDB = (IEngine) DIHelper.getInstance().getLocalProp(localMasterDbName);
		
		Map<String, Map<String, Map<String, String>>> retMap = new TreeMap<String, Map<String, Map<String, String>>>();
		
		ISelectWrapper wrapper = WrapperManager.getInstance().getSWrapper(masterDB, MasterDatabaseQueries.GET_ALL_KEYWORDS_AND_ENGINES);
		String[] names = wrapper.getDisplayVariables();
		while(wrapper.hasNext()) {
			ISelectStatement ss = wrapper.next();
			String engine = ss.getVar(names[0]) + "";
			String keywordURI = ss.getRawVar(names[1]) + "";
			String conceptURI = keywordURI.replace("Keyword/", "");
			
			IEngine eng = (IEngine) DIHelper.getInstance().getLocalProp(engine);
			String returnURI = eng.getTransformedNodeName(conceptURI, true);
			
			String instanceName = conceptURI.replaceAll(".*/Concept/", "");;
			
			Map<String, Map<String, String>> conceptSet = null;
			if(retMap.containsKey(engine)) {
				conceptSet = retMap.get(engine);
			} else {
				conceptSet = new TreeMap<String, Map<String, String>>();
			}
			
			String parent = null;
			if(instanceName.contains("/")) {
				// this is for properties that are also concepts
				String propName = instanceName.substring(0, instanceName.lastIndexOf("/"));
				String conceptName = instanceName.substring(instanceName.lastIndexOf("/") + 1, instanceName.length());
				if(!propName.equals(conceptName)) {
					instanceName = propName;
					parent = conceptName;
				} else {
					instanceName = conceptName;
				}
			}
			Map<String, String> nodeMap = new Hashtable<String, String>();
			nodeMap.put("physicalName", instanceName);
			if(parent != null) {
				nodeMap.put("parent", parent);
			}
			
			conceptSet.put(returnURI, nodeMap);
			retMap.put(engine, conceptSet);
		}
		
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}

	// get all insights related to a specific uri
	// preferably we would also pass vert store and edge store... the more
	// context the better. Don't have any of that for now though.
	@POST
	@Path("central/context/insights")
	@Produces("application/json")
	public Response getCentralContextInsights(MultivaluedMap<String, String> form, @Context HttpServletRequest request)
			throws KeyManagementException, NoSuchAlgorithmException, KeyStoreException, SolrServerException, IOException {
		Gson gson = new Gson();
		ArrayList<String> selectedUris;
		try {
			selectedUris = gson.fromJson(form.getFirst("selectedURI"), ArrayList.class);
		} catch (ClassCastException e) {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Can only run related insights on concepts and properties!");
			return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
		}

		// TODO: need to change the format for this call!!!!!!!!!!
		String type = Utility.getClassName(selectedUris.get(0));
		if (type == null) {
			// this occurs when a column is artificially created in a query
			type = selectedUris.get(0);
		}
		
		SolrIndexEngineQueryBuilder queryBuilder = new SolrIndexEngineQueryBuilder();
		queryBuilder.setSearchString(type);
		//TODO: should the params be different for this than the default search?
		//TODO: need to test this out more
		queryBuilder.setDefaultDisMaxWeighting();
		// facet still requires a df
		queryBuilder.setDefaultSearchField(SolrIndexEngine.INDEX_NAME);

		List<String> facetList = new ArrayList<>();
		facetList.add(SolrIndexEngine.CORE_ENGINE);
		facetList.add(SolrIndexEngine.LAYOUT);
		facetList.add(SolrIndexEngine.PARAMS);
		facetList.add(SolrIndexEngine.TAGS);
		facetList.add(SolrIndexEngine.ALGORITHMS);
		queryBuilder.setFacetField(facetList);
		
		// offset for call
		String offset = form.getFirst("offset");
		logger.info("Offset is: " + offset);

		// offset for call
		String limit = form.getFirst("limit");
		logger.info("Limit is: " + limit);

		Integer offsetInt = null;
		Integer limitInt = null;
		if (offset != null && !offset.isEmpty()) {
			offsetInt = Integer.parseInt(offset);
			queryBuilder.setOffset(offsetInt);
		}
		if (limit != null && !limit.isEmpty()) {
			limitInt = Integer.parseInt(limit);
			queryBuilder.setLimit(limitInt);
		}

		// filter based on the boxes checked in the facet filter (filtered with an exact filter)
		String filterDataStr = form.getFirst("filterData");
		Gson gsonVar = new Gson();
		Map<String, List<String>> filterData = gsonVar.fromJson(filterDataStr, new TypeToken<Map<String, List<String>>>() {}.getType());
		if (filterData != null && !filterData.isEmpty()) {
			queryBuilder.setFilterOptions(filterData);
		}
		
		// always sort by score and name desc
		queryBuilder.setSort(SolrIndexEngine.SCORE, SolrIndexEngine.DESC);
		queryBuilder.setSort(SolrIndexEngine.STORAGE_NAME, SolrIndexEngine.ASC);
		
		SolrDocumentList results = SolrIndexEngine.getInstance().queryDocument(queryBuilder);
		// throw an error if there are no results
		if (results == null) {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "No related insights found!");
			return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
		}
		// query for facet results
		Map<String, Map<String, Long>> facetCount = SolrIndexEngine.getInstance().executeQueryFacetResults(type, facetList);

		Map<String, Object> retMap = new HashMap<String, Object>();
		retMap.put(SolrIndexEngine.NUM_FOUND, results.getNumFound());
		retMap.put("results", results);
		retMap.put("facet", facetCount);
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}

	// get all insights related to a specific uri
	// preferably we would also pass vert store and edge store... the more
	// context the better. Don't have any of that for now though.
	// TODO: need new logic for this
	@POST
	@Path("central/context/databases")
	@Produces("application/json")
	public StreamingOutput getCentralContextDatabases(MultivaluedMap<String, String> form,
			@Context HttpServletRequest request) {
		Gson gson = new Gson();
		Hashtable<String, Object> dataHash = gson.fromJson(form.getFirst("QueryData"), Hashtable.class);
		String localMasterDbName = form.getFirst("localMasterDbName");
		logger.info("CENTRALLY have registered query data as ::: " + dataHash.toString());

		// SPARQLQueryTableBuilder tableViz = new SPARQLQueryTableBuilder();
		// tableViz.setJSONDataHash(dataHash);
		// Hashtable parsedPath = QueryBuilderHelper.parsePath(dataHash);
		// ArrayList<Hashtable<String, String>> nodeV = tableViz.getNodeV();
		// ArrayList<Hashtable<String, String>> predV = tableViz.getPredV();

		// SearchMasterDB searcher = new SearchMasterDB();
		// if(localMasterDbName != null)
		// searcher = new SearchMasterDB(localMasterDbName);
		//
		// for (Hashtable<String, String> nodeHash : nodeV){
		// searcher.addToKeywordList(Utility.getInstanceName(nodeHash.get(tableViz.uriKey)));
		// }
		// for (Hashtable<String, String> edgeHash : predV){
		// searcher.addToEdgeList(Utility.getInstanceName(edgeHash.get("Subject")),
		// Utility.getInstanceName(edgeHash.get("Object")));
		// }
		List<Hashtable<String, Object>> contextList = null;
		// if(localMasterDbName != null)
		// contextList = searcher.findRelatedEngines();
		// else
		// contextList = searcher.findRelatedEnginesWeb();
		return WebUtility.getSO(contextList);
	}

	private StreamingOutput getSOHTML() {
		return new StreamingOutput() {
			public void write(OutputStream outputStream) throws IOException, WebApplicationException {
				PrintStream out = new PrintStream(outputStream);
				try {
					// java.io.PrintWriter out = response.getWriter();
					out.println("<html>");
					out.println("<head>");
					out.println("<title>Servlet upload</title>");
					out.println("</head>");
					out.println("<body>");

					Enumeration<String> keys = helpHash.keys();
					while (keys.hasMoreElements()) {
						String key = keys.nextElement();
						String value = (String) helpHash.get(key);
						out.println("<em>" + key + "</em>");
						out.println("<a href='#'>" + value + "</a>");
						out.println("</br>");
					}

					out.println("</body>");
					out.println("</html>");
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		};
	}

	@GET
	@Path("playsheets")
	@Produces("application/json")
	public StreamingOutput getPlaySheets(@Context HttpServletRequest request) {
		Hashtable<String, String> hashTable = new Hashtable<String, String>();

		List<String> sheetNames = PlaySheetRDFMapBasedEnum.getAllSheetNames();
		for (int i = 0; i < sheetNames.size(); i++) {
			hashTable.put(sheetNames.get(i), PlaySheetRDFMapBasedEnum.getClassFromName(sheetNames.get(i)));
		}
		return WebUtility.getSO(hashTable);
	}

	@Path("/dbAdmin")
	public Object modifyInsight(@Context HttpServletRequest request) {
		DBAdminResource questionAdmin = new DBAdminResource();
		questionAdmin.setSecurityEnabled(Boolean.parseBoolean(context.getInitParameter(Constants.SECURITY_ENABLED)));
		return questionAdmin;
	}

	@GET
	@Path("insights")
	@Produces("application/json")
	//TODO: need to delete this method once we shift to using solr to get all insights
	public StreamingOutput getAllInsights(@QueryParam("groupBy") String groupBy, @QueryParam("orderBy") String orderBy, @Context HttpServletRequest request) {
		// TODO: this will need to be switched to go through solr

		List<Hashtable<String, String>> engineList = (List<Hashtable<String, String>>) request.getSession().getAttribute(Constants.ENGINES);
		Map<String, Object> ret = new Hashtable<String, Object>();
		Map<String, Object> dataMap = new Hashtable<String, Object>();

		for (Hashtable<String, String> engineMap : engineList) {
			String engineName = engineMap.get("name");
			System.out.println("Engine insights for : " + engineName);
			AbstractEngine engine = (AbstractEngine) DIHelper.getInstance().getLocalProp(engineName);
			try {
				List<Map<String, Object>> insightsList = engine.getAllInsightsMetaData();
				Map<String, Object> dbMap = new Hashtable<String, Object>();
				// TODO: not tracking count for insight views in rdbms
				dbMap.put("insights", insightsList);
				dbMap.put("totalCount", 0);
				dbMap.put("maxCount", 0);
				dataMap.put(engineName, dbMap);
			} catch (NullPointerException e) {
				logger.error("Null pointer----UNABLE TO LOAD INSIGHTS FOR " + engine.getEngineName());
				e.printStackTrace();
			} catch (RuntimeException e) {
				logger.error("Runtime Exception----UNABLE TO LOAD INSIGHTS FOR " + engine.getEngineName());
				e.printStackTrace();
			}
		}

		Map<String, Object> settingsMap = new Hashtable<String, Object>();
		settingsMap.put("orderBy", "popularity");
		settingsMap.put("groupBy", "database");
		ret.put("settings", settingsMap);
		ret.put("data", dataMap);

		return WebUtility.getSO(ret);
	}

//	@GET
//	@Path("/insightDetails")
//	@Produces("application/json")
//	public StreamingOutput getInsightDetails(@QueryParam("insight") String insight,
//			@Context HttpServletRequest request) {
//		NameServerProcessor ns = new NameServerProcessor();
//		String user = ((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId();
//
//		HashMap<String, Object> ret = ns.getInsightDetails(insight, user);
//		return WebUtility.getSO(ret);
//	}

	@Path("i-{insightID}")
	public Object getInsightDataFrame(@PathParam("insightID") String insightID, @Context HttpServletRequest request){

		// eventually I want to pick this from session
		// but for now let us pick it from the insight store
		System.out.println("Came into this point.. " + insightID);

		Insight existingInsight = null;
		if (insightID != null && !insightID.isEmpty() && !insightID.equals("new")) {
			existingInsight = InsightStore.getInstance().get(insightID);
			if (existingInsight == null) {				
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "Existing insight based on passed insightID is not found");
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			} else if(!existingInsight.hasInstantiatedDataMaker()) {
				synchronized(existingInsight) {
					if(!existingInsight.hasInstantiatedDataMaker()) {
						IDataMaker dm = null;
						// check if the insight is from a csv
						if(existingInsight.isNonDbInsight()) {
							// it better end up being created here since it must be serialized as a tinker
							InsightCache inCache = CacheFactory.getInsightCache(CacheFactory.CACHE_TYPE.CSV_CACHE);
							dm = inCache.getDMCache(existingInsight);
							DataMakerComponent dmc = new DataMakerComponent(inCache.getDMFilePath(existingInsight));
							
							Vector<DataMakerComponent> dmcList = new Vector<DataMakerComponent>();
							dmcList.add(dmc);
							existingInsight.setDataMakerComponents(dmcList);
						} else {
							// otherwise, grab the serialization if it is there
							dm = CacheFactory.getInsightCache(CacheFactory.CACHE_TYPE.DB_INSIGHT_CACHE).getDMCache(existingInsight);
						}
						
						if(dm != null) {
							// this means the serialization was good and pushing it into the insight object
							existingInsight.setDataMaker(dm);
						} else {
							// this means the serialization has never occurred
							// could be because hasn't happened, or could be because it is not a tinker frame
							InsightCreateRunner run = new InsightCreateRunner(existingInsight);
							Map<String, Object> webData = run.runWeb();
							// try to serialize
							// this will do nothing if not a tinker frame
							CacheFactory.getInsightCache(CacheFactory.CACHE_TYPE.DB_INSIGHT_CACHE).cacheInsight(existingInsight, webData);
						}
					}
				}
			}
		}
		else
		{
			existingInsight = new Insight(null, "TinkerFrame", "Grid");
			InsightStore.getInstance().put(existingInsight);
		}

		
		DataframeResource dfr = new DataframeResource();
		dfr.insight = existingInsight;

		return dfr;
	}

	@GET
	@Path("trees")
	@Produces("text/html")

	public StreamingOutput getAllInsights() throws IOException {

		// gets you all the inghts as a list / href
		// eventually I want to pick this from session
		// but for now let us pick it from the insight store
		String output = "<html><body>";
		Enumeration keys = InsightStore.getInstance().keys();
		while (keys.hasMoreElements()) {
			Insight thisInsight = InsightStore.getInstance().get(keys.nextElement());
			IDataMaker maker = thisInsight.getDataMaker();
			String colN = "";
			if (maker instanceof BTreeDataFrame) {
				BTreeDataFrame frame = (BTreeDataFrame) maker;
				String[] cols = frame.getColumnHeaders();

				for (int colIndex = 0; colIndex < cols.length; colN = colN + "  " + cols[colIndex], colIndex++)
					;

				output = output + "<br/>" + "<a href=http://localhost:9080/MonolithDev2/api/engine/i-"
						+ thisInsight.getInsightID() + "/bic>" + colN + "</a>";
			}
		}
		output = output + "</body></html>";
		return WebUtility.getSO(output);
	}
	
	/**
	 * Executes search on MediaWiki/Wikipedia for a given search term and returns the top results.
	 * 
	 * @param searchTerm	Search term to be queried against endpoint
	 * @return	ret			Map<ProductOntology URL, Short description of entity>
	 */
	@GET
	@Path("mediawiki/tags")
	@Produces("application/json")
	public StreamingOutput getMediaWikiTagsForSearchTerm(@QueryParam("searchTerm") String searchTerm, @QueryParam("numResults") int numResults) {
		String MEDAWIKI_ENDPOINT = "https://en.wikipedia.org/w/api.php?action=query&srlimit=" + numResults + "&list=search&format=json&utf8=1&srprop=snippet&srsearch=";
		String PRODUCT_ONTOLOGY_PREFIX = "http://www.productontology.org/id/";
		StringMap<String> ret = new StringMap<String>();
		
		if(searchTerm != null && !searchTerm.isEmpty()) {
			try {
				CloseableHttpClient httpClient = null;
				CloseableHttpResponse response = null;
				try {
					httpClient = HttpClients.createDefault();
					HttpGet http = new HttpGet(MEDAWIKI_ENDPOINT + URLEncoder.encode(searchTerm));
					response = httpClient.execute(http);
	
					HttpEntity entity = response.getEntity();
					if (entity != null) {
						InputStream is = entity.getContent();
						if (is != null) {
							String resp = EntityUtils.toString(entity);
							Gson gson = new Gson();
							HashMap<String, StringMap<List<StringMap<String>>>> k = gson.fromJson(resp, HashMap.class);;
							List<StringMap<String>> mapsList = (List<StringMap<String>>)k.get("query").get("search");
	
							for(StringMap<String> s : mapsList) {
								ret.put(PRODUCT_ONTOLOGY_PREFIX + s.get("title"), Jsoup.parse(s.get("snippet")).text());
							}
						}
					}
				} catch (ClientProtocolException e) {
					e.printStackTrace();
				} finally {
					httpClient.close();
					response.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		return WebUtility.getSO(ret);
	}
	
}
