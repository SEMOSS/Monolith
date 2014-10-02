package prerna.semoss.web.services;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
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
import javax.ws.rs.core.StreamingOutput;

import org.apache.log4j.Logger;

import prerna.algorithm.impl.CreateMasterDB;
import prerna.algorithm.impl.SearchMasterDB;
import prerna.om.GraphDataModel;
import prerna.om.SEMOSSVertex;
import prerna.rdf.engine.api.IEngine;
import prerna.rdf.engine.impl.RemoteSemossSesameEngine;
import prerna.ui.components.playsheets.GraphPlaySheet;
import prerna.upload.Uploader;
import prerna.util.Utility;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ibm.icu.util.StringTokenizer;

public class CentralNameServer {

	@Context ServletContext context;
	Logger logger = Logger.getLogger(CentralNameServer.class.getName());
	String output = "";
	String centralApi = "";
	
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
		
		Hashtable params = new Hashtable();
		params.put("selectedURI", selectedUris);
		
		String contextList = Utility.retrieveResult(centralApi + "/api/engine/central/context/insights", params);
		
		return getSO(contextList);
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
		
		Hashtable params = new Hashtable();
		params.put("QueryData", queryData);
		
		String contextList = Utility.retrieveResult(centralApi + "/api/engine/central/context/databases", params);
		
		return getSO(contextList);
	}	
	
	// local call to register an engine to the central name server and master db
	@POST
	@Path("context/registerEngine")
	@Produces("application/json")
	public StreamingOutput registerEngineApi(
			MultivaluedMap<String, String> form, 
			@Context HttpServletRequest request)
	{
		String engineApi = form.getFirst("dbName");
		logger.info("LOCALLY registering engineAPI  ::: " + engineApi.toString());
		
		String baseURL = request.getRequestURL().toString();
		baseURL = baseURL.substring(0,baseURL.indexOf("/api/engine/")) + "/api/engine";
		Hashtable params = new Hashtable();
		params.put("dbName", engineApi);
		params.put("baseURL", baseURL);
		String result = Utility.retrieveResult(centralApi + "/api/engine/central/context/registerEngine", params);
		
		return getSO(result);
	}
	

	private StreamingOutput getSO(Object vec){
		Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
		output = gson.toJson(vec);
	    return new StreamingOutput() {
	        public void write(OutputStream outputStream) throws IOException, WebApplicationException {
	            PrintStream ps = new PrintStream(outputStream);
	            ps.println(output);
	        }
	    };		
	}
}
