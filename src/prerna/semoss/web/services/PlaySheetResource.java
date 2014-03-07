package prerna.semoss.web.services;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Hashtable;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.StreamingOutput;

import org.apache.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import prerna.om.GraphDataModel;
import prerna.om.SEMOSSVertex;
import prerna.rdf.engine.api.IEngine;
import prerna.ui.components.api.IPlaySheet;
import prerna.ui.components.playsheets.AbstractRDFPlaySheet;
import prerna.ui.components.playsheets.GraphPlaySheet;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;

public class PlaySheetResource {

	IPlaySheet playSheet;
	IEngine coreEngine;
	String output = "";
	Logger logger = Logger.getLogger(getClass());
	
	public void setPlaySheet(IPlaySheet playSheet){
		this.playSheet = playSheet;
	}

	public void setEngine(IEngine engine){
		this.coreEngine = engine;
	}

	// Binds the upNode currently on the graph and runs traversal query
	@GET
	@Path("extend/downstream/instance")
	@Produces("application/json")
	public Object createDownstreamInstanceTraversal( 
			@QueryParam("upNode") String upNodeUri, 
			@QueryParam("downNodeType") String downNodeType,
			@Context HttpServletRequest request)
	{
		logger.info("Processing downstream traversal for node instance " + upNodeUri);
		
		//get the query
		String prefix = "";
		String sparql = DIHelper.getInstance().getProperty(Constants.TRAVERSE_FREELY_QUERY + prefix);
		
		//get necessary info about params passed in
		String filterValues = "(<" + upNodeUri + ">)";
		String upNodeType = Utility.getConceptType(coreEngine, upNodeUri);
		
		//process traversal
		Object obj = runPlaySheetTraversal(sparql, upNodeType, downNodeType, filterValues);

		// put the playsheet back in session
		storePlaySheet(request);
		
		return getSO(obj);
	}	

	// Binds the downNode currently on the graph and runs traversal query
	@GET
	@Path("extend/upstream/instance")
	@Produces("application/json")
	public Object createUpstreamInstanceTraversal( 
			@QueryParam("downNode") String downNodeUri, 
			@QueryParam("upNodeType") String upNodeType,
			@Context HttpServletRequest request)
	{
		logger.info("Processing upstream traversal for node instance " + downNodeUri);
		
		//get the query
		String prefix = "_2";
		String sparql = DIHelper.getInstance().getProperty(Constants.TRAVERSE_FREELY_QUERY + prefix);

		//fill the query
		String filterValues = "(<" + downNodeUri + ">)";
		String downNodeType = Utility.getConceptType(coreEngine, downNodeUri);

		//process traversal
		Object obj = runPlaySheetTraversal(sparql, upNodeType, downNodeType, filterValues);

		// put the playsheet back in session
		storePlaySheet(request);
		
		return getSO(obj);
	}	

	// Binds all nodes of upNodeType currently on the graph and runs traversal query
	@GET
	@Path("extend/downstream/type")
	@Produces("application/json")
	public Object createDownstreamTypeTraversal( 
			@QueryParam("upNodeType") String upNodeType, 
			@QueryParam("downNodeType") String downNodeType,
			@Context HttpServletRequest request)
	{
		logger.info("Processing downstream traversal for node type " + upNodeType);
		
		//get the query
		String prefix = "";
		String sparql = DIHelper.getInstance().getProperty(Constants.TRAVERSE_FREELY_QUERY + prefix);

		//get necessary info about params passed in
		String targetType = Utility.getInstanceName(upNodeType);
		String filterValues = getNodesOfType(targetType);
		
		//process traversal
		Object obj = runPlaySheetTraversal(sparql, upNodeType, downNodeType, filterValues);

		// put the playsheet back in session
		storePlaySheet(request);
		
		return getSO(obj);
	}	

	// Binds all nodes of downNodeType currently on the graph and runs traversal query
	@GET
	@Path("extend/upstream/type")
	@Produces("application/json")
	public Object createUpstreamTypeTraversal( 
			@QueryParam("upNodeType") String upNodeType, 
			@QueryParam("downNodeType") String downNodeType,
			@Context HttpServletRequest request)
	{
		logger.info("Processing upstream traversal for node type " + downNodeType);
		
		//get the query
		String prefix = "_2";
		String sparql = DIHelper.getInstance().getProperty(Constants.TRAVERSE_FREELY_QUERY + prefix);

		//get necessary info about params passed in
		String targetType = Utility.getInstanceName(downNodeType);
		String filterValues = getNodesOfType(targetType);
		
		//process traversal
		Object obj = runPlaySheetTraversal(sparql, upNodeType, downNodeType, filterValues);

		// put the playsheet back in session
		storePlaySheet(request);
		
		return getSO(obj);
	}	
	
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
		return getSO(retHash);
	}
	
	private StreamingOutput getSO(Object vec)
	{
		if(vec != null)
		{
			Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
			output = gson.toJson(vec);
			   return new StreamingOutput() {
			         public void write(OutputStream outputStream) throws IOException, WebApplicationException {
			            PrintStream ps = new PrintStream(outputStream);
			            ps.println(output);
			         }};		
		}
		return null;
	}
	
	// fills the traversal query and calls overlay
	private Object runPlaySheetTraversal(String sparql, String upNodeType, String downNodeType, String filterValues){
		//fill the sparql
		sparql = sparql.replace("@FILTER_VALUES@", filterValues).replace("@SUBJECT_TYPE@", upNodeType).replace("@OBJECT_TYPE@", downNodeType);
		Object obj = runPlaySheetOverlay(sparql);
		return obj;
	}

	//basic overlay processing with a given sparql
	private Object runPlaySheetOverlay(String sparql){
		Object obj = null;

		try
		{
			// this check probably isn't needed... for the time being, though, if the ps is not in session, create a new graph play sheet
			if(playSheet == null)
				playSheet = (IPlaySheet)Class.forName("prerna.ui.components.playsheets.GraphPlaySheet").newInstance();
			
			System.err.println("SPARQL is " + sparql);
			
			if(playSheet instanceof AbstractRDFPlaySheet)
				((AbstractRDFPlaySheet)playSheet).setAppend(true);
			
			playSheet.setRDFEngine(coreEngine);
			playSheet.setQuery(sparql);
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
	private String getNodesOfType(String targetType){
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

}
