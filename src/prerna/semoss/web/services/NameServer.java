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
import java.io.OutputStream;
import java.io.PrintStream;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

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

import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.openrdf.repository.RepositoryException;
import org.openrdf.rio.RDFParseException;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import prerna.auth.User;
import prerna.ds.BTreeDataFrame;
import prerna.engine.api.IEngine;
import prerna.engine.impl.AbstractEngine;
import prerna.engine.impl.rdf.RemoteSemossSesameEngine;
import prerna.insights.admin.DBAdminResource;
import prerna.nameserver.AddToMasterDB;
import prerna.nameserver.ConnectedConcepts;
import prerna.nameserver.DeleteFromMasterDB;
import prerna.nameserver.INameServer;
import prerna.nameserver.MasterDatabaseConstants;
import prerna.nameserver.NameServerProcessor;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.solr.SolrIndexEngine;
import prerna.ui.components.playsheets.datamakers.IDataMaker;
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
	@Path("/insight/upload")
	public Object uploadFile(@Context HttpServletRequest request) {
		Uploader upload = new Uploader();
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

		String wordNetDir = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER)
				+ System.getProperty("file.separator") + "WordNet-3.1";

		if (localMasterDbName == null) {
			try {
				AddToMasterDB creater = new AddToMasterDB();
				creater.setWordnetPath(wordNetDir);
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
			creater.setWordnetPath(wordNetDir);
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
	 * Search based on a string input 
	 * @param form - information passes in from the front end
	 * @return a string version of the results attained from the query search
	 */
	@GET
	@Path("central/context/getSearchInsightsResults")
	@Produces("application/json")
	public StreamingOutput getSearchInsightsResults(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String searchString = form.getFirst("searchString");
		logger.info("Searching based on input: " + searchString);
		String searchField = form.getFirst("searchField");
		logger.info("Searching field is: " + searchField);
		String filterDataStr = form.getFirst("filterData");
		Gson gson = new Gson();
		Map<String, List<String>> filterData = gson.fromJson(filterDataStr, new TypeToken<Map<String, List<String>>>() {
		}.getType());

		Map<String, Object> queryData = new HashMap<String, Object>();
		queryData.put(SolrIndexEngine.QUERY, searchString);
		queryData.put(SolrIndexEngine.SEARCH_FIELD, searchField);

		Map<String, String> filterMap = new HashMap<String, String>();
		for (String fieldName : filterData.keySet()) {
			List<String> filterValuesList = filterData.get(fieldName);
			StringBuilder filterStr = new StringBuilder();
			for (int i = 0; i < filterValuesList.size(); i++) {
				if (i == filterValuesList.size() - 1) {
					filterStr.append(filterValuesList.get(i));
				} else {
					filterStr.append(filterValuesList.get(i) + " OR ");
				}
			}
			filterMap.put(fieldName, "(" + filterStr.toString() + ")");
		}
		queryData.put(SolrIndexEngine.FITLER_QUERY, filterMap);

		SolrDocumentList results = null;
		try {
			results = SolrIndexEngine.getInstance().queryDocument(queryData);
		} catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | SolrServerException
				| IOException e) {
			e.printStackTrace();
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
	public StreamingOutput getFacetInsightsResults(MultivaluedMap<String, String> form,	@Context HttpServletRequest request) {
		String facetString = form.getFirst("facetString");
		logger.info("Faceting based on input: " + facetString);

		Map<String, Object> queryData = new HashMap<>();
		List<String> facetList = new ArrayList<>();
		facetList.add(SolrIndexEngine.CORE_ENGINE);
		facetList.add(SolrIndexEngine.LAYOUT);
		facetList.add(SolrIndexEngine.PARAMS);
		facetList.add(SolrIndexEngine.TAGS);
		facetList.add(SolrIndexEngine.ALGORITHMS);

		queryData.put(SolrIndexEngine.QUERY, facetString);
		queryData.put(SolrIndexEngine.FACET, true);
		StringBuilder facetStringBuilder = new StringBuilder();
		for (int i = 0; i < facetList.size(); i++) {
			if (i == facetList.size() - 1) {
				facetStringBuilder.append(facetList.get(i));
			} else {
				facetStringBuilder.append(facetList.get(i) + "&");
			}
		}
		queryData.put(SolrIndexEngine.FACET_FIELD, facetStringBuilder);

		Map<String, Map<String, Long>> facetFieldMap = null;
		try {
			facetFieldMap = SolrIndexEngine.getInstance().facetDocument(queryData);
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
	@GET
	@Path("central/context/getGroupInsightsResults")
	@Produces("application/json")
	public StreamingOutput getGroupInsightsResults(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String searchString = form.getFirst("searchString");
		logger.info("Searching based on input: " + searchString);

		String groupOffset = form.getFirst("groupOffset");
		logger.info("Group based on input: " + groupOffset);

		String groupLimit = form.getFirst("groupLimit");
		logger.info("Group field is: " + groupLimit);

		String groupBy = form.getFirst("groupBy");
		Gson gson = new Gson();
		Map<String, List<String>> groupByField = gson.fromJson(groupBy, new TypeToken<Map<String, List<String>>>() {
		}.getType());

		Map<String, Object> queryData = new HashMap<>();
		queryData.put(SolrIndexEngine.GROUPBY, true);
		for (String fieldName : groupByField.keySet()) {
			List<String> groupByList = groupByField.get(fieldName);
			StringBuilder fieldList = new StringBuilder();
			for (int i = 0; i < groupByList.size(); i++) {
				if (i == groupByList.size() - 1) {
					fieldList.append(groupByList.get(i));
				} else {
					fieldList.append(groupByList.get(i) + "&");
				}
				queryData.put(SolrIndexEngine.GROUP_FIELD, fieldList);
			}
		}
		queryData.put(SolrIndexEngine.QUERY, searchString);
		queryData.put(SolrIndexEngine.GROUP_LIMIT, groupLimit);
		queryData.put(SolrIndexEngine.GROUP_OFFSET, groupOffset);

		Map<String, Map<String, SolrDocumentList>> groupFieldMap = null;
		try {
			groupFieldMap = SolrIndexEngine.getInstance().groupDocument(queryData);
		} catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | SolrServerException
				| IOException e) {
			e.printStackTrace();
		}

		return WebUtility.getSO(groupFieldMap);

	}

	/**
	 * Most Like This results based on info from search. This MLT documents based on specified field 
	 * @param form - information passes in from the front end
	 * @return a string version of the results attained from the query/mlt search
	 */
	@GET
	@Path("central/context/getMLTInsightsResults")
	@Produces("application/json")
	public StreamingOutput getMLTInsightsResults(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String queryString = form.getFirst("queryString");
		logger.info("Searching based on input: " + queryString);
		String searchField = form.getFirst("searchField");
		logger.info("Searching field is: " + searchField);
		String docFreq = form.getFirst("docFreq");
		logger.info("Group based on input: " + docFreq);
		String termFreq = form.getFirst("termFreq");
		logger.info("Group based on input: " + termFreq);
		String offsetCount = form.getFirst("offsetCount");
		logger.info("Group based on input: " + offsetCount);
		String mltBy = form.getFirst("mltBy");
		Gson gson = new Gson();

		Map<String, List<String>> mltByField = gson.fromJson(mltBy, new TypeToken<Map<String, List<String>>>() {}.getType());

		Map<String, Object> queryData = new HashMap<>();
		queryData.put(SolrIndexEngine.QUERY, queryString);
		queryData.put(SolrIndexEngine.SEARCH_FIELD, searchField);
		queryData.put(SolrIndexEngine.MLT, true);
		// queryEngine.put(SET_ROWS, 2);
		for (String fieldName : mltByField.keySet()) {
			List<String> groupByList = mltByField.get(fieldName);
			StringBuilder mltList = new StringBuilder();
			for (int i = 0; i < groupByList.size(); i++) {
				if (i == groupByList.size() - 1) {
					mltList.append(groupByList.get(i));
				} else {
					mltList.append(groupByList.get(i) + "&");
				}
				queryData.put(SolrIndexEngine.MLT_FIELD, mltList);
			}
		}

		queryData.put(SolrIndexEngine.MLT_MINDF, docFreq);
		queryData.put(SolrIndexEngine.MLT_MINTF, termFreq);
		queryData.put(SolrIndexEngine.MLT_COUNT, offsetCount);

		Map<String, SolrDocumentList> mltFieldMap = null;
		try {
			mltFieldMap = SolrIndexEngine.getInstance().mltDocument(queryData);
		} catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | SolrServerException e) {
			e.printStackTrace();
		}

		return WebUtility.getSO(mltFieldMap);
	}

	@POST
	@Path("central/context/getConnectedConcepts")
	@Produces("application/json")
	public StreamingOutput getConnectedConcepts(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String conceptURI = form.getFirst("conceptURI");

		IEngine engine = (IEngine) DIHelper.getInstance().getLocalProp(form.getFirst("engine"));
		conceptURI = engine.getTransformedNodeName(conceptURI, false);

		String localMasterDbName = form.getFirst("localMasterDbName");
		logger.info("CENTRALLY have registered selected URIs as ::: " + conceptURI.toString());

		String wordNetDir = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER)
				+ System.getProperty("file.separator") + "WordNet-3.1";
		String nlpPath = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER)
				+ System.getProperty("file.separator") + "NLPartifacts" + System.getProperty("file.separator")
				+ "englishPCFG.ser";

		ConnectedConcepts results = null;
		// regardless of input master/local databases, uses the same method
		// since it only queries the master db and not the databases used to
		// create it
		if (localMasterDbName == null) {
			// this call is not local, need to get the API to run queries
			INameServer ns = new NameServerProcessor(wordNetDir, nlpPath);
			results = ns.searchConnectedConcepts(conceptURI);
		} else {
			// this call is local, grab the engine from DIHelper
			IEngine masterDB = (IEngine) DIHelper.getInstance().getLocalProp(localMasterDbName);
			INameServer ns = new NameServerProcessor(masterDB, wordNetDir, nlpPath);
			results = ns.searchConnectedConcepts(conceptURI);
		}
		return WebUtility.getSO(results.getData());
	}

	// get all insights related to a specific uri
	// preferably we would also pass vert store and edge store... the more
	// context the better. Don't have any of that for now though.
	@POST
	@Path("central/context/insights")
	@Produces("application/json")
	public StreamingOutput getCentralContextInsights(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		Gson gson = new Gson();
		ArrayList<String> selectedUris = gson.fromJson(form.getFirst("selectedURI"), ArrayList.class);

		String type = Utility.getClassName(selectedUris.get(0));
		Map<String, Object> queryMap = new HashMap<String, Object>();
		queryMap.put(SolrIndexEngine.QUERY, type);
		queryMap.put(SolrIndexEngine.SEARCH_FIELD, "all_text");

		List<Map<String, Object>> contextList = new ArrayList<Map<String, Object>>();
		SolrDocumentList results;
		try {
			results = SolrIndexEngine.getInstance().queryDocument(queryMap); // gives
			// me
			// null
			// pointer
			if (results != null) {
				for (int i = 0; i < results.size(); i++) {
					SolrDocument doc = results.get(i);
					Map<String, Object> insightHash = new HashMap<String, Object>();
					insightHash.put(MasterDatabaseConstants.QUESTION_ID, doc.get(SolrIndexEngine.CORE_ENGINE_ID));
					insightHash.put(MasterDatabaseConstants.QUESTION_KEY, doc.get(SolrIndexEngine.NAME));
					insightHash.put(MasterDatabaseConstants.VIZ_TYPE_KEY, doc.get(SolrIndexEngine.LAYOUT));

					// TODO: why does FE want this in another map???
					Map<String, String> engineHash = new HashMap<String, String>();
					engineHash.put("name", doc.get(SolrIndexEngine.CORE_ENGINE) + "");
					insightHash.put(MasterDatabaseConstants.DB_KEY, engineHash);

					// try to figure out params?
					List<String> paramTypes = (List<String>) doc.get(SolrIndexEngine.PARAMS);
					if (paramTypes != null && paramTypes.contains(type)) {
						insightHash.put(MasterDatabaseConstants.INSTANCE_KEY, "");
					} else {
						insightHash.put(MasterDatabaseConstants.INSTANCE_KEY, selectedUris.get(0));
					}

					contextList.add(insightHash);
				}
			}
		} catch (KeyManagementException e) {
			e.printStackTrace();
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		} catch (KeyStoreException e) {
			e.printStackTrace();
		} catch (SolrServerException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception ignored) {
			// do nothing for now
			// null pointer breaks it
			// so catching it
		}

		return WebUtility.getSO(contextList);
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
	public StreamingOutput getAllInsights(@QueryParam("groupBy") String groupBy, @QueryParam("orderBy") String orderBy,
			@Context HttpServletRequest request) {
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

	@GET
	@Path("/insightDetails")
	@Produces("application/json")
	public StreamingOutput getInsightDetails(@QueryParam("insight") String insight,
			@Context HttpServletRequest request) {
		NameServerProcessor ns = new NameServerProcessor();
		String user = ((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId();

		HashMap<String, Object> ret = ns.getInsightDetails(insight, user);
		return WebUtility.getSO(ret);
	}

	@Path("i-{insightID}")
	public Object getInsightDataFrame(@PathParam("insightID") String insightID, @Context HttpServletRequest request)
			throws IOException {

		// eventually I want to pick this from session
		// but for now let us pick it from the insight store
		System.out.println("Came into this point.. " + insightID);

		Insight existingInsight = null;
		if (insightID != null && !insightID.isEmpty()) {
			existingInsight = InsightStore.getInstance().get(insightID);
			if (existingInsight == null) {
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "Existing insight based on passed insightID is not found");
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
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

}
