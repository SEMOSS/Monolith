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

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;

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
import javax.ws.rs.core.StreamingOutput;

import org.apache.log4j.Logger;
import org.openrdf.repository.RepositoryException;
import org.openrdf.rio.RDFParseException;

import prerna.error.EngineException;
import prerna.insights.admin.DBAdminResource;
import prerna.nameserver.CreateMasterDB;
import prerna.nameserver.DeleteMasterDB;
import prerna.nameserver.SearchEngineMasterDB;
import prerna.nameserver.SearchMasterDB;
import prerna.rdf.engine.api.IEngine;
import prerna.rdf.engine.impl.RemoteSemossSesameEngine;
import prerna.rdf.query.builder.QueryBuilderHelper;
import prerna.upload.Uploader;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.PlaySheetEnum;
import prerna.web.services.util.WebUtility;

import com.google.gson.Gson;

@Path("/engine")
public class NameServer {

	@Context ServletContext context;
	Logger logger = Logger.getLogger(NameServer.class.getName());
	String output = "";
	Hashtable helpHash = null;

	// gets the engine resource necessary for all engine calls
	@Path("e-{engine}")
	public Object getLocalDatabase(@PathParam("engine") String db, @QueryParam("api") String api, @Context HttpServletRequest request) throws EngineException {
		// check if api has been passed
		// if yes:
		// 			check if remote engine has already been started and stored in context -- if so, use that engine
		// 			next check if local engine by that name has been started and stored in context
		//			finally start the remote engine and store in context with api+engine name
		// otherwise grab local engine
		System.out.println(" Getting DB... " + db);
		HttpSession session = request.getSession();
		IEngine engine = null;
		if(api != null){
			String remoteEngineKey = api+ ":" + db;
			engine = (IEngine)session.getAttribute(remoteEngineKey);
			if(engine == null)
				engine = (IEngine)session.getAttribute(db);
			if(engine == null){
				addEngine(request, api, db);
				engine = (IEngine)session.getAttribute(remoteEngineKey);
			}
		}
		else {
			engine = (IEngine)session.getAttribute(db);
		}
		if(engine == null)
			throw new EngineException("The engine " + db + " at " + api + " cannot be found");
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
		IEngine engine = (IEngine)session.getAttribute(db);
		EngineRemoteResource res = new EngineRemoteResource();
		res.setEngine(engine);
		return res;
	}

	// Controls all calls controlling the central name server
	@Path("centralNameServer")
	public Object getCentralNameServer(@QueryParam("centralServerUrl") String url, @Context HttpServletRequest request) {
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
	public StreamingOutput getNeighbors(@QueryParam("node") String type, @Context HttpServletRequest request)
	{
		return null;
	}

	// gets all the insights for a given type and tag in all the engines
	// both tag and type are optional
	@GET
	@Path("insights")
	@Produces("application/json")
	public StreamingOutput getInsights(@QueryParam("node") String type, @QueryParam("tag") String tag, @Context HttpServletRequest request)
	{
		// if the type is null then send all the insights else only that
		// I need to do this in a cluster engine
		// for now I will do this as a running list
		return null;
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
	@GET
	@Path("tags")
	@Produces("application/xml")
	public StreamingOutput getInsight(@QueryParam("insight") String insight)
	{
		// returns the insight
		// typically is a JSON of the insight
		return null;
	}	

	// gets a particular insight
	@GET
	@Path("/insight/create")
	@Produces("application/html")
	public StreamingOutput createEngine()
	{
		// this creates the HTML that needs to be uploaded
		// see FileUpload.html
		return null;
	}	

	// gets a particular insight
	@GET
	@Path("all")
	@Produces("application/json")
	public StreamingOutput printEngines(@Context HttpServletRequest request){
		// would be cool to give this as an HTML
		Hashtable<String, ArrayList<Hashtable<String,String>>> hashTable = new Hashtable<String, ArrayList<Hashtable<String,String>>>();
		//		ArrayList<String> enginesList = new ArrayList<String>();
		HttpSession session = request.getSession();
		ArrayList<Hashtable<String,String>> engines = (ArrayList<Hashtable<String,String>>)session.getAttribute(Constants.ENGINES);
		//		StringTokenizer tokens = new StringTokenizer(engines, ":");
		//		while(tokens.hasMoreTokens()) {
		//			enginesList.add(tokens.nextToken());
		//		}
		hashTable.put("engines", engines);
		return WebUtility.getSO(hashTable);
	}	


	@GET
	@Path("add")
	@Produces("application/json")
	public void addEngine(@Context HttpServletRequest request, @QueryParam("api") String api, @QueryParam("database") String database)
	{
		// would be cool to give this as an HTML
		RemoteSemossSesameEngine newEngine = new RemoteSemossSesameEngine();
		newEngine.setAPI(api);
		newEngine.setDatabase(database);
		HttpSession session = request.getSession();
		ArrayList<Hashtable<String,String>> engines = (ArrayList<Hashtable<String,String>>)session.getAttribute(Constants.ENGINES);
		// temporal
		String remoteDbKey = api + ":" + database;
		newEngine.openDB(null);
		if(newEngine.isConnected())
		{
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
	public StreamingOutput printURL(@Context HttpServletRequest request, @Context HttpServletResponse response)
	{
		// would be cool to give this as an HTML
		if(helpHash == null)
		{
			Hashtable urls = new Hashtable();
			urls.put("Help - this menu (GET)", "hostname:portname/Monolith/api/engine/help");		
			urls.put("Get All the engines (GET)", "hostname:portname/Monolith/api/engine/all");
			urls.put("Perspectives in a specific engine (GET)", "hostname:portname/Monolith/api/engine/e-{engineName}/perspectives");
			urls.put("All Insights in a engine (GET)", "hostname:portname/Monolith/api/engine/e-{engineName}/insights");
			urls.put("All Perspectives and Insights in a engine (GET)", "hostname:portname/Monolith/api/engine/e-{engineName}/pinsights");
			urls.put("Insights for specific perspective specific engine (GET)", "hostname:portname/Monolith/api/engine/e-{engineName}/insights?perspective={perspective}");
			urls.put("Insight definition for a particular insight (GET)", "hostname:portname/Monolith/api/engine/e-{engineName}/insight?insight={label of insight (NOT ID)}");
			urls.put("Execute insight without parameter (GET)", "hostname:portname/Monolith/api/engine/e-{engineName}/output?insight={label of insight (NOT ID)}");
			urls.put("Execute insight with parameter (GET)", "hostname:portname/Monolith/api/engine/e-{engineName}/output?insight={label of insight (NOT ID)}&params=key$value~key2$value2~key3$value3");
			urls.put("Execute Custom Query Select (POST)", "hostname:portname/Monolith/api/engine/e-{engineName}/querys?query={sparql query}");
			urls.put("Execute Custom Query Construct (GET)", "hostname:portname/Monolith/api/engine/e-{engineName}/queryc?query={sparql query}");
			urls.put("Execute Custom Query Insert/Delete (POST)", "hostname:portname/Monolith/api/engine/e-{engineName}/update?query={sparql query}");
			urls.put("Numeric properties of a given node type (GET)", "hostname:portname/Monolith/api/engine/e-{engineName}/properties/node/type/numeric?nodeType={URI}");
			urls.put("Fill Values for a given parameter (You already get this in insights) (GET)", "hostname:portname/Monolith/api/engine/e-{engineName}/fill?type={type}");
			urls.put("Get Neighbors of a particular node (GET)", "hostname:portname/Monolith/api/engine/e-{engineName}/neighbors/instance?node={URI}");
			urls.put("Tags for an insight (Specific Engine)", "hostname:portname/Monolith/api/engine/e-{engineName}/tags?insight={insight label}");
			urls.put("Insights for a given tag (Tag is optional) (Specific Engine) ", "hostname:portname/Monolith/api/engine/e-{engineName}/insight?tag={xyz}");
			urls.put("Neighbors of across all engine", "hostname:portname/Monolith/api/engine/neighbors?node={URI}");
			urls.put("Tags for an insight", "hostname:portname/Monolith/api/engine/tags?insight={insight label}");
			urls.put("Insights for a given tag (Tag is optional)", "hostname:portname/Monolith/api/engine/insight?tag={xyz}");		
			urls.put("Create a new engine using excel (requires form submission) (POST)", "hostname:portname/Monolith/api/engine/insight/upload/excel/upload");	
			urls.put("Create a new engine using csv (requires form submission) (POST)", "hostname:portname/Monolith/api/engine/insight/upload/csv/upload");	
			urls.put("Create a new engine using nlp (requires form submission) (POST)", "hostname:portname/Monolith/api/engine/insight/upload/nlp/upload (GET)");
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
		return upload;
	}

	// central call to store an engine in the master db
	@POST
	@Path("central/context/registerEngine")
	@Produces("application/json")
	public StreamingOutput registerEngine2MasterDatabase(
			MultivaluedMap<String, String> form, 
			@Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		ArrayList<String> dbArray = gson.fromJson(form.getFirst("dbName"), ArrayList.class);
		String baseURL = form.getFirst("baseURL");
		String localMasterDbName = form.getFirst("localMasterDbName");

		Hashtable<String, Boolean> resultHash = new Hashtable<String, Boolean>();

		ServletContext servletContext = request.getServletContext();
		String contextPath = servletContext.getRealPath(System.getProperty("file.separator"));
		String wordNet = "WEB-INF" + System.getProperty("file.separator") + "lib" + System.getProperty("file.separator") + "WordNet-3.1";
		String wordNetDir  = contextPath + wordNet;

		if(localMasterDbName==null) {
			try {
				CreateMasterDB creater = new CreateMasterDB();
				creater.setWordnetPath(wordNetDir);
				resultHash = creater.registerEngineAPI(baseURL, dbArray);
			} catch (EngineException e) {
				e.printStackTrace();
			} catch (RDFParseException e) {
				e.printStackTrace();
			} catch (RepositoryException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		else //it must be local master db thus the name of master db must have been passed 
		{
			try {
				CreateMasterDB creater = new CreateMasterDB(localMasterDbName);
				creater.setWordnetPath(wordNetDir);
				resultHash = creater.registerEngineLocal(dbArray);
			} catch (EngineException e) {
				e.printStackTrace();
			}
		}

		return WebUtility.getSO(resultHash);
	}

	// central call to remove an engine from the master db
	@POST
	@Path("central/context/unregisterEngine")
	@Produces("application/json")
	public StreamingOutput unregisterEngine2MasterDatabase(
			MultivaluedMap<String, String> form, 
			@Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		ArrayList<String> dbArray = gson.fromJson(form.getFirst("dbName")+"", ArrayList.class);
		logger.info("CENTRALLY removing dbs  ::: " + dbArray.toString());
		String localMasterDbName = form.getFirst("localMasterDbName");


		Hashtable<String, Boolean> resultHash = new Hashtable<String, Boolean>();
		if(localMasterDbName == null){
			DeleteMasterDB deleater = new DeleteMasterDB();
			resultHash = deleater.deleteEngineWeb(dbArray);
		}
		else {
			DeleteMasterDB deleater = new DeleteMasterDB(localMasterDbName);
			resultHash = deleater.deleteEngine(dbArray);
		}

		return WebUtility.getSO(resultHash);
	}
	
	// search based on a string input
	@GET
	@Path("central/context/searchEngineResults")
	@Produces("application/json")
	public StreamingOutput getSearchEngineResults(
//			MultivaluedMap<String, String> form
			@QueryParam("searchString") String searchString,
			@QueryParam("localMasterDbName") String localMasterDbName,
			@Context HttpServletRequest request)
	{
//		String searchString = form.getFirst("searchString");
		logger.info("Searching based on input: " + searchString);
//		String localMasterDbName = form.getFirst("localMasterDbName");

		ServletContext servletContext = request.getServletContext();
		String contextPath = servletContext.getRealPath(System.getProperty("file.separator"));
		String wordNet = "WEB-INF" + System.getProperty("file.separator") + "lib" + System.getProperty("file.separator") + "WordNet-3.1";
		String wordNetDir  = contextPath + wordNet;

		String nlp = "WEB-INF" + System.getProperty("file.separator") + "lib" + System.getProperty("file.separator") + "NLPartifacts" + System.getProperty("file.separator") + "englishPCFG.ser";
		String nlpPath = contextPath + nlp;
		
		List<Hashtable<String, Object>> contextList = null;
		if(localMasterDbName == null){
			SearchEngineMasterDB search = new SearchEngineMasterDB(wordNetDir, nlpPath);
			contextList = search.getWebInsightsFromSearchString(searchString);
		} else {
			SearchEngineMasterDB search = new SearchEngineMasterDB(localMasterDbName, wordNetDir, nlpPath);
			contextList = search.getLocalInsightsFromSearchString(searchString);
		}

		return WebUtility.getSO(contextList);
	}

	// get all insights related to a specific uri
	// preferably we would also pass vert store and edge store... the more context the better. Don't have any of that for now though.
	@POST
	@Path("central/context/insights")
	@Produces("application/json")
	public StreamingOutput getCentralContextInsights(
			MultivaluedMap<String, String> form, 
			@Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		ArrayList<String> selectedUris = gson.fromJson(form.getFirst("selectedURI"), ArrayList.class);
		String localMasterDbName = form.getFirst("localMasterDbName");
		logger.info("CENTRALLY have registered selected URIs as ::: " + selectedUris.toString());

		ServletContext servletContext = request.getServletContext();
		String contextPath = servletContext.getRealPath(System.getProperty("file.separator"));
		String wordNet = "WEB-INF" + System.getProperty("file.separator") + "lib" + System.getProperty("file.separator") + "WordNet-3.1";
		String wordNetDir  = contextPath + wordNet;

		String nlp = "WEB-INF" + System.getProperty("file.separator") + "lib" + System.getProperty("file.separator") + "NLPartifacts" + System.getProperty("file.separator") + "englishPCFG.ser";
		String nlpPath = contextPath + nlp;

		List<Hashtable<String, Object>> contextList = null;
		if(localMasterDbName != null) {
			SearchMasterDB searcher = new SearchMasterDB(localMasterDbName, wordNetDir, nlpPath);
			contextList = searcher.getRelatedInsights(selectedUris);
		}
		else {
			SearchMasterDB searcher = new SearchMasterDB(wordNetDir, nlpPath);
			contextList = searcher.getRelatedInsightsWeb(selectedUris);
		}
		return WebUtility.getSO(contextList);
	}

	// get all insights related to a specific uri
	// preferably we would also pass vert store and edge store... the more context the better. Don't have any of that for now though.
	//TODO: need new logic for this
	@POST
	@Path("central/context/databases")
	@Produces("application/json")
	public StreamingOutput getCentralContextDatabases(
			MultivaluedMap<String, String> form, 
			@Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		Hashtable<String, Object> dataHash = gson.fromJson(form.getFirst("QueryData"), Hashtable.class);
		String localMasterDbName = form.getFirst("localMasterDbName");
		logger.info("CENTRALLY have registered query data as ::: " + dataHash.toString());

//		SPARQLQueryTableBuilder tableViz = new SPARQLQueryTableBuilder();
//		tableViz.setJSONDataHash(dataHash);
		Hashtable parsedPath = QueryBuilderHelper.parsePath(dataHash);
//		ArrayList<Hashtable<String, String>> nodeV = tableViz.getNodeV();
//		ArrayList<Hashtable<String, String>> predV = tableViz.getPredV();

		//		SearchMasterDB searcher = new SearchMasterDB();
		//		if(localMasterDbName != null)
		//			searcher = new SearchMasterDB(localMasterDbName);
		//		
		//		for (Hashtable<String, String> nodeHash : nodeV){
		//			searcher.addToKeywordList(Utility.getInstanceName(nodeHash.get(tableViz.uriKey)));
		//		}
		//		for (Hashtable<String, String> edgeHash : predV){
		//			searcher.addToEdgeList(Utility.getInstanceName(edgeHash.get("Subject")), Utility.getInstanceName(edgeHash.get("Object")));
		//		}
		List<Hashtable<String, Object>> contextList = null;
		//		if(localMasterDbName != null)
		//			contextList = searcher.findRelatedEngines();
		//		else
		//			contextList = searcher.findRelatedEnginesWeb();
		return WebUtility.getSO(contextList);
	}

	private StreamingOutput getSOHTML()
	{
		return new StreamingOutput() {
			public void write(OutputStream outputStream) throws IOException, WebApplicationException {
				PrintStream out = new PrintStream(outputStream);
				try {
					//java.io.PrintWriter out = response.getWriter();
					out.println("<html>");
					out.println("<head>");
					out.println("<title>Servlet upload</title>");  
					out.println("</head>");
					out.println("<body>");

					Enumeration <String> keys = helpHash.keys();
					while(keys.hasMoreElements())
					{
						String key = keys.nextElement();
						String value = (String)helpHash.get(key);
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
	public StreamingOutput getPlaySheets(@Context HttpServletRequest request){
		Hashtable<String, String> hashTable = new Hashtable<String, String>();

		ArrayList<String> sheetNames = PlaySheetEnum.getAllSheetNames();
		for(int i=0; i<sheetNames.size(); i++){
			hashTable.put(sheetNames.get(i), PlaySheetEnum.getClassFromName(sheetNames.get(i)));
		}
		return WebUtility.getSO(hashTable);
	}	
  	
  	@Path("/dbAdmin")
	public Object modifyInsight(@Context HttpServletRequest request) {
  		DBAdminResource questionAdmin = new DBAdminResource();

		return questionAdmin;
	}
  	
}
