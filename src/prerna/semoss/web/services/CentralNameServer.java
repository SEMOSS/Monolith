package prerna.semoss.web.services;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.StreamingOutput;

import org.apache.log4j.Logger;

import prerna.algorithm.impl.CreateMasterDB;
import prerna.algorithm.impl.DeleteMasterDB;
import prerna.util.Utility;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class CentralNameServer {

	@Context ServletContext context;
	Logger logger = Logger.getLogger(CentralNameServer.class.getName());
	String output = "";
	String centralApi = "";
	List<String> localDb = Arrays.asList("LocalMasterDatabase");
	
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
			return getSO(Utility.retrieveResult(centralApi + "/api/engine/central/context/insights", params));
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
			
			return getSO(Utility.retrieveResult(centralApi + "/api/engine/central/context/databases", params));
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
			return getSO(Utility.retrieveResult(centralApi + "/api/engine/central/context/registerEngine", params));
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
			return getSO(result);
		}
		else{
			logger.info("LOCALLY removing engineAPI  ::: " + dbArray.toString());
			NameServer ns = new NameServer();
			form.put("localMasterDbName", localDb);
			return ns.unregisterEngine2MasterDatabase(form, request);
		}
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
