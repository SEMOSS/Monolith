package prerna.semoss.web.services;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Hashtable;
import java.util.StringTokenizer;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.StreamingOutput;

import org.apache.log4j.Logger;
import org.openrdf.repository.RepositoryConnection;

import prerna.om.Insight;
import prerna.om.SEMOSSParam;
import prerna.om.SEMOSSVertex;
import prerna.rdf.engine.api.IEngine;
import prerna.rdf.engine.impl.AbstractEngine;
import prerna.rdf.engine.impl.InMemorySesameEngine;
import prerna.rdf.engine.impl.SesameJenaUpdateWrapper;
import prerna.rdf.util.RDFJSONConverter;
import prerna.ui.components.ExecuteQueryProcessor;
import prerna.ui.components.api.IPlaySheet;
import prerna.ui.components.playsheets.GraphPlaySheet;
import prerna.ui.main.listener.impl.SPARQLExecuteFilterBaseFunction;
import prerna.ui.main.listener.impl.SPARQLExecuteFilterNoBaseFunction;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.QuestionPlaySheetStore;
import prerna.util.Utility;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class EngineResource {
	
	// gets everything specific to an engine
	// essentially this is a wrapper over the engine
	IEngine coreEngine = null;
	String output = "";
	Logger logger = Logger.getLogger(getClass());
	public void setEngine(IEngine coreEngine)
	{
		System.out.println("Setting core engine to " + coreEngine);
		this.coreEngine = coreEngine;
	}
	
	//gets all node types connected to a given node type
	@GET
	@Path("neighbors")
	@Produces("application/json")
	public StreamingOutput getNeighbors(@QueryParam("nodeType") String type, @Context HttpServletRequest request)
	{
		Hashtable<String, Vector<String>> finalTypes = new Hashtable<String, Vector<String>>();
		if(coreEngine instanceof AbstractEngine){
			Vector<String> downNodes = ((AbstractEngine) coreEngine).getToNeighbors(type, 0);
			finalTypes.put("downstream", downNodes);
			Vector<String> upNodes = ((AbstractEngine) coreEngine).getFromNeighbors(type, 0);
			finalTypes.put("upstream", upNodes);
		}
		return getSO(finalTypes);
	}

	//gets all node types connected to a specific node instance
	@GET
	@Path("neighbors/instance")
	@Produces("application/json")
	public StreamingOutput getNeighborsInstance(@QueryParam("node") String uri, @Context HttpServletRequest request)
	{
		Hashtable<String, Vector<String>> finalTypes = new Hashtable<String, Vector<String>>();
		if(coreEngine instanceof AbstractEngine){
			AbstractEngine engine = (AbstractEngine) coreEngine;
			//get node type
			String type = Utility.getConceptType(coreEngine, uri);
			
			//DOWNSTREAM PROCESSING
			//get node types connected to this type
			Vector<String> downNodeTypes = engine.getToNeighbors(type, 0);
			
			//for each available type, ensure each type has at least one instance connected to original node
			String downAskQuery = "ASK { "
					+ "{?connectedNode a <@NODE_TYPE@>} "
					+ "{<" + uri + "> ?rel ?connectedNode}"
							+ "}" ;
			Vector<String> validDownTypes = new Vector<String>();
			for (String connectedType : downNodeTypes){
				String filledDownAskQuery = downAskQuery.replace("@NODE_TYPE@", connectedType);
				logger.info("Checking type " + connectedType + " with query " + filledDownAskQuery);
				if(engine.execAskQuery(filledDownAskQuery))
					validDownTypes.add(connectedType);
			}
			finalTypes.put("downstream", validDownTypes);
			
			//UPSTREAM PROCESSING
			//get node types connected to this type
			Vector<String> upNodeTypes = engine.getFromNeighbors(type, 0);
			
			//for each available type, ensure each type has at least one instance connected to original node
			String upAskQuery = "ASK { "
					+ "{?connectedNode a <@NODE_TYPE@>} "
					+ "{?connectedNode ?rel <" + uri + ">}"
							+ "}" ;
			Vector<String> validUpTypes = new Vector<String>();
			for (String connectedType : upNodeTypes){
				String filledUpAskQuery = upAskQuery.replace("@NODE_TYPE@", connectedType);
				logger.info("Checking type " + connectedType + " with query " + filledUpAskQuery);
				if(engine.execAskQuery(filledUpAskQuery))
					validUpTypes.add(connectedType);
			}
			finalTypes.put("upstream", validUpTypes);
		}
		return getSO(finalTypes);
	}
	
	// performs extend functionality (currently only for a graph play sheet)
	// upnode or downnode or both can be null
	// must pass in (upNode and downNodeType) or (downNode and upNodeType) or (upNodeType and downNodeType) -- depending on where / how the user wants to traverse
	// need to figure out how javascript is going to tell which playsheet is getting extended so that for Traverse From All only adds to those on the graph
	@GET
	@Path("output/extend")
	@Produces("application/json")
	public StreamingOutput createOverlayOutput(@QueryParam("upNode") String upNodeUri, 
			@QueryParam("upNodeType") String upNodeType, 
			@QueryParam("downNode") String downNodeUri, 
			@QueryParam("downNodeType") String downNodeType,
			@QueryParam("playSheetID") String playSheetID,
			@Context HttpServletRequest request)
	{
		Object obj = null;
		String prefix = "";
		String nodeUri = "";
		if(upNodeUri != null){
			logger.info("Processing downstream traversal for node instance " + upNodeUri);
			prefix = "";
			nodeUri = "(<" + upNodeUri + ">)";
			upNodeType = Utility.getConceptType(coreEngine, upNodeUri);
		}
		else if(downNodeUri != null){
			logger.info("Processing upstream traversal for node instance " + downNodeUri);
			prefix = "_2";
			nodeUri = "(<" + downNodeUri + ">)";
			downNodeType = Utility.getConceptType(coreEngine, downNodeUri);
		}
		
		try
		{
			// get the playsheet from session
			HttpSession session = ((HttpServletRequest)request).getSession(false);
			IPlaySheet playSheet = (IPlaySheet) session.getAttribute(playSheetID);
			// this check probably isn't needed... for the time being, though, if the ps is not in session, create a new graph play sheet
			if(playSheet == null)
				playSheet = (IPlaySheet)Class.forName("prerna.ui.components.playsheets.GraphPlaySheet").newInstance();
			
			String sparql = DIHelper.getInstance().getProperty(Constants.TRAVERSE_FREELY_QUERY + prefix);
			sparql = sparql.replace("@FILTER_VALUES@", nodeUri).replace("@SUBJECT_TYPE@", upNodeType).replace("@OBJECT_TYPE@", downNodeType);
			System.err.println("SPARQL is " + sparql);
			playSheet.setRDFEngine(coreEngine);
			playSheet.setQuery(sparql);
			playSheet.createData();
			playSheet.runAnalytics();
			
//			if(!(ps instanceof GraphPlaySheet))
			obj = playSheet.getData();
//			else
//			{
//				GraphPlaySheet gps = (GraphPlaySheet)ps;
//				gps.createData();
//				gps.runAnalytics();
//				RepositoryConnection rc = (RepositoryConnection)((GraphPlaySheet)ps).getData();
//				InMemorySesameEngine imse = new InMemorySesameEngine();
//				imse.setRepositoryConnection(rc);
//				imse.openDB(null);
//				obj = RDFJSONConverter.getGraphAsJSON(imse, gps.semossGraph.baseFilterHash);
//			}
				
			// put the playsheet back in session
			if(playSheet.getQuestionID() != null)
				session.setAttribute(playSheet.getQuestionID(), playSheet);
		}catch(Exception ex)
		{
			ex.printStackTrace();
		}
		return getSO(obj);
	}	
	
	// temporary function for getting chart it data
	// will be replaced with query builder
	@GET
	@Path("output/chartData")
	@Produces("application/json")
	public StreamingOutput getPlaySheetChartData(
			@QueryParam("playSheetID") String playSheetID,
			@Context HttpServletRequest request)
	{
		// get the playsheet from session
		HttpSession session = ((HttpServletRequest)request).getSession(false);
		IPlaySheet playSheet = (IPlaySheet) session.getAttribute(playSheetID);
		
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
	
	// gets all the insights for a given type and tag in all the engines
	// both tag and type are optional
	@GET
	@Path("insights")
	@Produces("application/json")
	public StreamingOutput getInsights(@QueryParam("node") String type, @QueryParam("tag") String tag,@QueryParam("perspective") String perspective, @Context HttpServletRequest request)
	{
		// if the type is null then send all the insights else only that
		Vector result = null;
		if(perspective != null)
			result = coreEngine.getInsights(perspective);
		else if(type != null)
			result = coreEngine.getInsight4Type(tag);
		else 
			result = coreEngine.getInsights();

		return getSO(result);
	}
	// gets all the insights for a given type and tag in all the engines
	// both tag and type are optional
	@GET
	@Path("pinsights")
	@Produces("application/json")
	public StreamingOutput getPInsights(@Context HttpServletRequest request)
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
		return getSO(retP);
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

	@GET
	@Path("perspectives")
	@Produces("application/json")
	public StreamingOutput getPerspectives(@Context HttpServletRequest request)
	{
		// if the type is null then send all the insights else only that
		Vector vec = coreEngine.getPerspectives();
		return getSO(vec);
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
	@Path("insight")
	@Produces("application/json")
	public StreamingOutput getInsightDefinition(@QueryParam("insight") String insight)
	{
		// returns the insight
		// typically is a JSON of the insight
		System.out.println("Insight is " + insight);
		Insight in = coreEngine.getInsight(insight);
		System.out.println("Insight is " + in);
		System.out.println(in.getOutput());
		Hashtable outputHash = new Hashtable();
		outputHash.put("result", in);
		
		
		Vector <SEMOSSParam> paramVector = coreEngine.getParams(insight);
		System.err.println("Params are " + paramVector);
		Hashtable optionsHash = new Hashtable();
		Hashtable paramsHash = new Hashtable();

		for(int paramIndex = 0;paramIndex < paramVector.size();paramIndex++)
		{
			SEMOSSParam param = paramVector.elementAt(paramIndex);
			if(param.isDepends().equalsIgnoreCase("false"))
			{
				// do the logic to get the stuff
				String query = param.getQuery();
				optionsHash.put(param.getName(), coreEngine.getParamValues(param.getName(), param.getType(), in.getId(), query));
			}
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
		return getSO(outputHash);
	}	

	// executes a particular insight
	@GET
	@Path("output")
	@Produces("application/json")
	public StreamingOutput createOutput(@QueryParam("insight") String insight, @QueryParam("params") String params, @Context HttpServletRequest request)
	{
		// executes the output and gives the data
		// executes the create runner
		// once complete, it would plug the output into the session
		// need to find a way where I can specify if I want to keep the result or not
		// params are typically passed on as
		// pairs like this
		// key$value~key2:value2 etc
		// need to find a way to handle other types than strings
		System.out.println("Params is " + params);
		Hashtable <String, Object> paramHash = new Hashtable<String, Object>();
		if(params != null)
		{
			StringTokenizer tokenz = new StringTokenizer(params,"~");
			while(tokenz.hasMoreTokens())
			{
				String thisToken = tokenz.nextToken();
				int index = thisToken.indexOf("$");
				String key = thisToken.substring(0, index);
				String value = thisToken.substring(index+1);
				// attempt to see if 
				boolean found = false;
				try{
					double dub = Double.parseDouble(value);
					paramHash.put(key, dub);
					found = true;
				}catch (Exception ignored)
				{
				}
				if(!found){
					try{
						int dub = Integer.parseInt(value);
						paramHash.put(key, dub);
						found = true;
					}catch (Exception ignored)
					{
					}
				}
				//if(!found)
					paramHash.put(key, value);
			}
		}
		
		ExecuteQueryProcessor exQueryProcessor = new ExecuteQueryProcessor();
		exQueryProcessor.processQuestionQuery(coreEngine.getEngineName(), insight, paramHash);
		Object obj = null;
		try
		{
			IPlaySheet playSheet= exQueryProcessor.getPlaySheet();
			playSheet.createData();
			playSheet.runAnalytics();
//			if(!(playSheet instanceof GraphPlaySheet))
				obj = playSheet.getData();
//			else
//			{
//				GraphPlaySheet gps = (GraphPlaySheet)playSheet;
//				RepositoryConnection rc = (RepositoryConnection)((GraphPlaySheet)playSheet).getData();
//				InMemorySesameEngine imse = new InMemorySesameEngine();
//				imse.setRepositoryConnection(rc);
//				imse.openDB(null);
//				obj = RDFJSONConverter.getGraphAsJSON(imse, gps.semossGraph.baseFilterHash);
//			}
				
			//store the playsheet in session
			HttpSession session = ((HttpServletRequest)request).getSession(false);
			session.setAttribute(playSheet.getQuestionID(), playSheet);
		}catch(Exception ex)
		{
			ex.printStackTrace();
		}
		
		return getSO(obj);
//		Hashtable <String, Object> paramHash = new Hashtable<String, Object>();
//		if(params != null)
//		{
//			StringTokenizer tokenz = new StringTokenizer(params,"~");
//			while(tokenz.hasMoreTokens())
//			{
//				String thisToken = tokenz.nextToken();
//				int index = thisToken.indexOf("$");
//				String key = thisToken.substring(0, index);
//				String value = thisToken.substring(index+1);
//				// attempt to see if 
//				boolean found = false;
//				try{
//					double dub = Double.parseDouble(value);
//					paramHash.put(key, dub);
//					found = true;
//				}catch (Exception ignored)
//				{
//				}
//				if(!found){
//					try{
//						int dub = Integer.parseInt(value);
//						paramHash.put(key, dub);
//						found = true;
//					}catch (Exception ignored)
//					{
//					}
//				}
//				//if(!found)
//					paramHash.put(key, value);
//			}
//		}
//		
//		System.out.println("Insight is " + insight);
//		Insight in = coreEngine.getInsight(insight);
//		String output = in.getOutput();
//		Object obj = null;
//		
//		try
//		{
//			IPlaySheet ps = (IPlaySheet)Class.forName(output).newInstance();
//			String sparql = in.getSparql();
//			System.out.println("Param Hash is " + paramHash);
//			// need to replace the whole params with the base params first
//			sparql = Utility.normalizeParam(sparql);
//			System.out.println("SPARQL " + sparql);
//			sparql = Utility.fillParam(sparql, paramHash);
//			System.err.println("SPARQL is " + sparql);
//			ps.setRDFEngine(coreEngine);
//			ps.setQuery(sparql);
//			ps.setQuestionID(in.getId());
//			ps.setTitle("Sample ");
//			ps.createData();
//			ps.runAnalytics();
//			if(!(ps instanceof GraphPlaySheet))
//				obj = ps.getData();
//			else
//			{
//				GraphPlaySheet gps = (GraphPlaySheet)ps;
//				RepositoryConnection rc = (RepositoryConnection)((GraphPlaySheet)ps).getData();
//				InMemorySesameEngine imse = new InMemorySesameEngine();
//				imse.setRepositoryConnection(rc);
//				imse.openDB(null);
//				obj = RDFJSONConverter.getGraphAsJSON(imse, gps.baseFilterHash);
//			}
//		}catch(Exception ex)
//		{
//			ex.printStackTrace();
//		}
//		return getSO(obj);
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
	@GET
	@Path("overlay")
	@Produces("application/json")
	public StreamingOutput overlayOutput(@QueryParam("outputID") String id, @QueryParam("params") String params)
	{
		// returns the insight
		// based on the current ID get the data
		// typically is a JSON of the insight
		return null;
	}	
	
	// gets a particular insight
	// not sure if I should keep it as it is or turn this into a post because of the query
	@POST
	@Path("querys")
	@Produces("application/json")
	public StreamingOutput queryDataSelect(MultivaluedMap<String, String> form)
	{
		// returns the insight
		// based on the current ID get the data
		// typically is a JSON of the insight
		// this will also cache it
		System.out.println(form.getFirst("query"));
		return getSO(RDFJSONConverter.getSelectAsJSON(form.getFirst("query")+"", coreEngine));
	}	

	// runs a query against the engine while filtering out everything included in baseHash
	@POST
	@Path("querys/filter/noBase")
	@Produces("application/json")
	public StreamingOutput queryDataSelectWithoutBase(MultivaluedMap<String, String> form)
	{
		// create and set the filter class
		// send the query
		SPARQLExecuteFilterNoBaseFunction filterFunction = new SPARQLExecuteFilterNoBaseFunction();
		filterFunction.setEngine(coreEngine);
		if(coreEngine instanceof AbstractEngine)
			filterFunction.setFilterHash(((AbstractEngine)coreEngine).getBaseHash());
		System.out.println(form.getFirst("query"));
		return getSO(filterFunction.process(form.getFirst("query")+""));
	}	

	// runs a query against the engine while filtering out everything included in baseHash
	@POST
	@Path("querys/filter/onlyBase")
	@Produces("application/json")
	public StreamingOutput queryDataSelectOnlyBase(MultivaluedMap<String, String> form)
	{
		// create and set the filter class
		// send the query
		SPARQLExecuteFilterBaseFunction filterFunction = new SPARQLExecuteFilterBaseFunction();
		filterFunction.setEngine(coreEngine);
		if(coreEngine instanceof AbstractEngine)
			filterFunction.setFilterHash(((AbstractEngine)coreEngine).getBaseHash());
		System.out.println(form.getFirst("query"));
		return getSO(filterFunction.process(form.getFirst("query")+""));
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
	public StreamingOutput insertData2DB(MultivaluedMap<String, String> form)
	{
		// returns the insight
		// based on the current ID get the data
		// typically is a JSON of the insight
		// this will also cache it
		SesameJenaUpdateWrapper wrapper = new SesameJenaUpdateWrapper();
		wrapper.setEngine(coreEngine);
		wrapper.setQuery(form.getFirst("query")+"");
		boolean success = wrapper.execute();
		return getSO("success");
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
	
	// gets a particular insight
	// not sure if I should keep it as it is or turn this into a post because of the query
	// Can give a set of nodeids
	@GET
	@Path("fill")
	@Produces("application/json")
	public StreamingOutput getFillEntity(@QueryParam("type") String typeToFill, @QueryParam("query") String query)
	{
		// returns the insight
		// based on the current ID get the data
		// typically is a JSON of the insight
		// this will also cache it
		if(typeToFill != null)
			return getSO(coreEngine.getParamValues("", typeToFill, ""));
		else if(query != null)
			return getSO(coreEngine.getParamValues("", "", "", query));
		return null;
	}	

	
	// gets all types from a given db
	
	@GET
	@Path("conceptType")
	@Produces("application/json")
	public StreamingOutput getConceptType()
	{
		
		return getSO(coreEngine.getParamValues("", "", "", "SELECT ?entity WHERE { {?entity <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://semoss.org/ontologies/Concept> ;} }"));
	}	
	
}
