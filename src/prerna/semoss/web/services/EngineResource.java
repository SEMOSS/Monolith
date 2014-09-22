package prerna.semoss.web.services;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Hashtable;
import java.util.List;
import java.util.Vector;

import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.log4j.Logger;

import prerna.om.Insight;
import prerna.om.SEMOSSParam;
import prerna.rdf.engine.api.IEngine;
import prerna.rdf.engine.impl.AbstractEngine;
import prerna.rdf.engine.impl.RDFFileSesameEngine;
import prerna.rdf.engine.impl.SesameJenaSelectStatement;
import prerna.rdf.engine.impl.SesameJenaSelectWrapper;
import prerna.rdf.engine.impl.SesameJenaUpdateWrapper;
import prerna.rdf.query.builder.AbstractCustomVizBuilder;
import prerna.rdf.query.builder.CustomVizHeatMapBuilder;
import prerna.rdf.query.builder.CustomVizTableBuilder;
import prerna.rdf.query.builder.ICustomVizBuilder;
import prerna.rdf.query.util.SEMOSSQuery;
import prerna.rdf.util.RDFJSONConverter;
import prerna.ui.components.ExecuteQueryProcessor;
import prerna.ui.components.api.IPlaySheet;
import prerna.ui.components.playsheets.GraphPlaySheet;
import prerna.ui.helpers.PlaysheetCreateRunner;
import prerna.ui.main.listener.impl.SPARQLExecuteFilterBaseFunction;
import prerna.ui.main.listener.impl.SPARQLExecuteFilterNoBaseFunction;
import prerna.util.PlaySheetEnum;
import prerna.util.Utility;
import prerna.web.streaming.IStreamable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.internal.StringMap;

public class EngineResource {
	
	// gets everything specific to an engine
	// essentially this is a wrapper over the engine
	IEngine coreEngine = null;
	String output = "";
	Logger logger = Logger.getLogger(getClass());
	Hashtable<String, SEMOSSQuery> vizHash = new Hashtable<String, SEMOSSQuery>();
	// to send class name if error occurs
	String className = this.getClass().getName();
	
	public void setEngine(IEngine coreEngine)
	{
		System.out.println("Setting core engine to " + coreEngine);
		this.coreEngine = coreEngine;
	}
	
	// All playsheet specific manipulations will go through this
	@Path("p-{playSheetID}")
	public Object uploadFile(@PathParam("playSheetID") String playSheetID, @Context HttpServletRequest request) {
		PlaySheetResource psr = new PlaySheetResource();
		// get the playsheet from session
		HttpSession session = ((HttpServletRequest)request).getSession(false);
		IPlaySheet playSheet = (IPlaySheet) session.getAttribute(playSheetID);
		psr.setPlaySheet(playSheet);
		psr.setEngine(coreEngine);
		return psr;
	}
	
	//gets all edges and nodes from owl file to display as metamodel
	@GET
	@Path("metamodel")
	@Produces("application/json")
	public Response getMetamodel(@Context HttpServletRequest request)
	{
		if(coreEngine == null) {
			Hashtable<String, String> errorHash = new Hashtable<String, String>();
			errorHash.put("Message", "No engine defined.");
			errorHash.put("Class", className);
			return Response.status(400).entity(getSO(errorHash)).build();
		}
		
		ExecuteQueryProcessor exQueryProcessor = new ExecuteQueryProcessor();
		//hard code playsheet attributes since no insight exists for this
		String sparql = "SELECT ?s ?p ?o WHERE {?s ?p ?o} LIMIT 1";
		String playSheetName = "prerna.ui.components.playsheets.GraphPlaySheet";
		String title = "Metamodel";
		String id = coreEngine.getEngineName() + "-Metamodel";
		AbstractEngine eng = ((AbstractEngine)coreEngine).getBaseDataEngine();
		eng.setEngineName(id);
		eng.setBaseData((RDFFileSesameEngine) eng);
		Hashtable<String, String> filterHash = new Hashtable<String, String>();
		filterHash.put("http://semoss.org/ontologies/Relation", "http://semoss.org/ontologies/Relation");
		eng.setBaseHash(filterHash);
		
		exQueryProcessor.prepareQueryOutputPlaySheet(eng, sparql, playSheetName, title, id);
		Object obj = null;
		try
		{
			GraphPlaySheet playSheet= (GraphPlaySheet) exQueryProcessor.getPlaySheet();
			playSheet.getGraphData().setSubclassCreate(true);//this makes the base queries use subclass instead of type--necessary for the metamodel query
			playSheet.createData();
			playSheet.runAnalytics();

			obj = playSheet.getData();
			
			HttpSession session = ((HttpServletRequest)request).getSession(false);
			session.setAttribute(playSheet.getQuestionID(), playSheet);
		} catch (Exception ex) { 
			ex.printStackTrace();
			Hashtable<String, String> errorHash = new Hashtable<String, String>();
			errorHash.put("Message", "Error processing query.");
			errorHash.put("Class", className);
			return Response.status(500).entity(getSO(errorHash)).build();
		}
		
		return Response.status(200).entity(getSO(obj)).build();
	}

	
	//gets all node types connected to a given node type
	@GET
	@Path("neighbors")
	@Produces("application/json")
	public Response getNeighbors(@QueryParam("nodeType") String type, @Context HttpServletRequest request)
	{
		Hashtable<String, Vector<String>> finalTypes = new Hashtable<String, Vector<String>>();
		if(coreEngine instanceof AbstractEngine){
			Vector<String> downNodes = ((AbstractEngine) coreEngine).getToNeighbors(type, 0);
			finalTypes.put("downstream", downNodes);
			Vector<String> upNodes = ((AbstractEngine) coreEngine).getFromNeighbors(type, 0);
			finalTypes.put("upstream", upNodes);
		} 
		return Response.status(200).entity(getSO(finalTypes)).build();
	}

	//gets all node types connected to a given node type along with the verbs connecting the given types
	@GET
	@Path("neighbors/verbs")
	@Produces("application/json")
	public Response getNeighborsWithVerbs(@QueryParam("nodeType") String type, @Context HttpServletRequest request)
	{
		Hashtable<String, Hashtable<String, Vector<String>>> finalTypes = new Hashtable<String, Hashtable<String, Vector<String>>>();
		if(coreEngine instanceof AbstractEngine){
			Hashtable<String, Vector<String>> downNodes = ((AbstractEngine) coreEngine).getToNeighborsWithVerbs(type, 0);
			finalTypes.put("downstream", downNodes);
			Hashtable<String, Vector<String>> upNodes = ((AbstractEngine) coreEngine).getFromNeighborsWithVerbs(type, 0);
			finalTypes.put("upstream", upNodes);
		}
		return Response.status(200).entity(getSO(finalTypes)).build();
	}
	
	//gets all node types connected to a specific node instance
	@POST
	@Path("neighbors/instance")
	@Produces("application/json")
//	@Consumes(MediaType.APPLICATION_JSON)
	public Response getNeighborsInstance(MultivaluedMap<String, String> form, @Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		List<String> uriArray = gson.fromJson(form.getFirst("node"), List.class);
		
		Hashtable<String, Vector<String>> finalTypes = new Hashtable<String, Vector<String>>();
		if(coreEngine instanceof AbstractEngine){
			AbstractEngine engine = (AbstractEngine) coreEngine;
			
			//create bindings string
			String bindingsString = "";
			for(String uri : uriArray){
				bindingsString = bindingsString + "(<" + uri + ">)";
			}
			logger.info("bindings string = " + bindingsString);
			
			String uniqueTypesQuery = "SELECT DISTINCT ?entity WHERE { { ?subject <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ?entity}   FILTER NOT EXISTS { { ?subject <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ?subtype} {?subtype <http://www.w3.org/2000/01/rdf-schema#subClassOf> ?entity} }} BINDINGS ?subject {"+bindingsString+"}";
			
			//get node types
			Vector<String> types = coreEngine.getEntityOfType(uniqueTypesQuery);
			
			//DOWNSTREAM PROCESSING
			//get node types connected to this type
			Vector<String> downNodeTypes = new Vector<String>();
			for(String type : types){
				downNodeTypes.addAll(engine.getToNeighbors(type, 0));
			}
			
			//for each available type, ensure each type has at least one instance connected to original node
			String downAskQuery = "ASK { "
					+ "{?connectedNode a <@NODE_TYPE@>} "
					+ "{?node ?rel ?connectedNode}"
							+ "} BINDINGS ?node {"+bindingsString+"}" ;
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
			Vector<String> upNodeTypes = new Vector<String>();
			for(String type : types){
				upNodeTypes.addAll(engine.getFromNeighbors(type, 0));
			}
			
			//for each available type, ensure each type has at least one instance connected to original node
			String upAskQuery = "ASK { "
					+ "{?connectedNode a <@NODE_TYPE@>} "
					+ "{?connectedNode ?rel ?node}"
							+ "} BINDINGS ?node {"+bindingsString+"}" ;
			Vector<String> validUpTypes = new Vector<String>();
			for (String connectedType : upNodeTypes){
				String filledUpAskQuery = upAskQuery.replace("@NODE_TYPE@", connectedType);
				logger.info("Checking type " + connectedType + " with query " + filledUpAskQuery);
				if(engine.execAskQuery(filledUpAskQuery))
					validUpTypes.add(connectedType);
			}
			finalTypes.put("upstream", validUpTypes);
		}
		return Response.status(200).entity(getSO(finalTypes)).build();
	}
	
	// gets all the insights for a given type and tag in all the engines
	// both tag and type are optional
	@GET
	@Path("insights")
	@Produces("application/json")
	public Response getInsights(@QueryParam("nodeType") String type, @QueryParam("nodeInstance") String instance, @QueryParam("tag") String tag,@QueryParam("perspective") String perspective, @Context HttpServletRequest request)
	{
		// if the type is null then send all the insights else only that
		Vector<String> resultInsights = null;
		if(perspective != null)
			resultInsights = coreEngine.getInsights(perspective);
		else if(type != null || instance != null) 
		{
			if(instance != null) type = Utility.getConceptType(coreEngine, instance);
			resultInsights = coreEngine.getInsight4Type(type);
		}
		else if(tag != null)
			resultInsights = coreEngine.getInsight4Tag(tag);
		else 
			resultInsights = coreEngine.getInsights();

		//Vector<Hashtable<String,String>> resultInsightObjects = coreEngine.getOutputs4Insights(resultInsights);
		Vector<Insight> resultInsightObjects = null;
		if(resultInsights!=null)
			resultInsightObjects = ((AbstractEngine)coreEngine).getInsight2(resultInsights.toArray(new String[resultInsights.size()]));

		return Response.status(200).entity(getSO(resultInsightObjects)).build();
	}
	
	// gets all the insights for a given type and tag in all the engines
	// both tag and type are optional
	@GET
	@Path("pinsights")
	@Produces("application/json")
	public Response getPInsights(@Context HttpServletRequest request)
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
		return Response.status(200).entity(getSO(retP)).build();
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
	public Response getPerspectives(@Context HttpServletRequest request)
	{
		// if the type is null then send all the insights else only that
		Hashtable<String, Vector<String>> hashtable = new Hashtable<String, Vector<String>>(); 
		Vector<String> perspectivesVector = coreEngine.getPerspectives();
		hashtable.put("perspectives", perspectivesVector);
		return Response.status(200).entity(getSO(hashtable)).build();
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
	public Response getInsightDefinition(@QueryParam("insight") String insight)
	{
		// returns the insight
		// typically is a JSON of the insight
		System.out.println("Insight is " + insight);
		Insight in = ((AbstractEngine)coreEngine).getInsight2(insight).get(0);
		System.out.println("Insight is " + in);
		System.out.println(in.getOutput());
		Hashtable outputHash = new Hashtable<String, Hashtable>();
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
		
		return Response.status(200).entity(getSO(outputHash)).build();
	}	

	// executes a particular insight
	@GET
	@Path("output")
	@Produces("application/json")
	public Response createOutput(@QueryParam("insight") String insight, @QueryParam("params") String params, @Context HttpServletRequest request, @Context HttpServletResponse response)
	{
		// executes the output and gives the data
		// executes the create runner
		// once complete, it would plug the output into the session
		// need to find a way where I can specify if I want to keep the result or not
		// params are typically passed on as
		// pairs like this
		// key$value~key2:value2 etc
		// need to find a way to handle other types than strings
		
		// if insight is null throw bad data exception
		if(insight == null) {
			Hashtable<String, String> errorHash = new Hashtable<String, String>();
			errorHash.put("Message", "No question defined.");
			errorHash.put("Class", className);
//			return getSO(errorHash);
			return Response.status(400).entity(getSO(errorHash)).build();
		}
		
		System.out.println("Params is " + params);
		Hashtable<String, Object> paramHash = Utility.getParamsFromString(params);
		
		ExecuteQueryProcessor exQueryProcessor = new ExecuteQueryProcessor();
		exQueryProcessor.processQuestionQuery(coreEngine.getEngineName(), insight, paramHash);
		Object obj = null;
		try {
			IPlaySheet playSheet= exQueryProcessor.getPlaySheet();
			
			if(playSheet instanceof IStreamable){
				ServletOutputStream stream = response.getOutputStream();
				((IStreamable) playSheet).setOutputStream(stream);
			}
			
			PlaysheetCreateRunner playRunner = new PlaysheetCreateRunner(playSheet);
			//playRunner.setCreateSwingView(false);
			playRunner.runWeb();
			/*Thread playThread = new Thread(playRunner);
			playThread.start();
			while(playThread.isAlive()) {
				//wait for processing to finish before getting the data
			}
			*/
			obj = playSheet.getData();

			// store the playsheet in session
			HttpSession session = ((HttpServletRequest)request).getSession(false);
			session.setAttribute(playSheet.getQuestionID(), playSheet);
		} catch (Exception ex) { //need to specify the different exceptions 
			ex.printStackTrace();
			Hashtable<String, String> errorHash = new Hashtable<String, String>();
			errorHash.put("Message", "Error occured processing question.");
			errorHash.put("Class", className);
//			return getSO(errorHash);
			return Response.status(500).entity(getSO(errorHash)).build();
		}

//		return getSO("");
		return Response.status(200).entity(getSO(obj)).build();
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
	// not sure if I should keep it as it is or turn this into a post because of the query
	@POST
	@Path("querys")
	@Produces("application/json")
	public Response queryDataSelect(MultivaluedMap<String, String> form)
	{
		// returns the insight
		// based on the current ID get the data
		// typically is a JSON of the insight
		// this will also cache it
		System.out.println(form.getFirst("query"));
		return Response.status(200).entity(getSO(RDFJSONConverter.getSelectAsJSON(form.getFirst("query")+"", coreEngine))).build();
	}	

	// runs a query against the engine while filtering out everything included in baseHash
	@POST
	@Path("querys/filter/noBase")
	@Produces("application/json")
	public Response queryDataSelectWithoutBase(MultivaluedMap<String, String> form)
	{
		// create and set the filter class
		// send the query
		SPARQLExecuteFilterNoBaseFunction filterFunction = new SPARQLExecuteFilterNoBaseFunction();
		filterFunction.setEngine(coreEngine);
		if(coreEngine instanceof AbstractEngine)
			filterFunction.setFilterHash(((AbstractEngine)coreEngine).getBaseHash());
		System.out.println(form.getFirst("query"));
		return Response.status(200).entity(getSO(filterFunction.process(form.getFirst("query")+""))).build();
	}	

	// runs a query against the engine while filtering out everything included in baseHash
	@POST
	@Path("querys/filter/onlyBase")
	@Produces("application/json")
	public Response queryDataSelectOnlyBase(MultivaluedMap<String, String> form)
	{
		// create and set the filter class
		// send the query
		SPARQLExecuteFilterBaseFunction filterFunction = new SPARQLExecuteFilterBaseFunction();
		filterFunction.setEngine(coreEngine);
		if(coreEngine instanceof AbstractEngine)
			filterFunction.setFilterHash(((AbstractEngine)coreEngine).getBaseHash());
		System.out.println(form.getFirst("query"));
		
		return Response.status(200).entity(getSO(filterFunction.process(form.getFirst("query")+""))).build();
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
	public Response insertData2DB(MultivaluedMap<String, String> form)
	{
		// returns the insight
		// based on the current ID get the data
		// typically is a JSON of the insight
		// this will also cache it
		SesameJenaUpdateWrapper wrapper = new SesameJenaUpdateWrapper();
		wrapper.setEngine(coreEngine);
		wrapper.setQuery(form.getFirst("query")+"");
		boolean success = wrapper.execute();
		if(success) {
			return Response.status(200).entity(getSO("success")).build();
		} else {
			Hashtable<String, String> errorHash = new Hashtable<String, String>();
			errorHash.put("Message", "Error processing query.");
			errorHash.put("Class", className);
			return Response.status(500).entity(getSO(errorHash)).build();
		}
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
	public Response getFillEntity(@QueryParam("type") String typeToFill, @QueryParam("query") String query)
	{
		// returns the insight
		// based on the current ID get the data
		// typically is a JSON of the insight
		// this will also cache it
		if(typeToFill != null) {
			return Response.status(200).entity(getSO(coreEngine.getParamValues("", typeToFill, ""))).build();
		} else if(query != null) {
			return Response.status(200).entity(getSO(coreEngine.getParamValues("", "", "", query))).build();
		}
		return Response.status(200).entity(getSO(null)).build();
	}		
	
	// gets all numeric properties associated with a specific node type
	@GET
	@Path("properties/node/type/numeric")
	@Produces("application/json")
	public Response getNumericNodeProperties(
			@QueryParam("nodeType")  String nodeUri)
	{
		String nodePropQuery = "SELECT DISTINCT ?entity WHERE {{?source <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <@NODE_TYPE_URI@>} {?entity <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Relation/Contains>} {?source ?entity ?prop } FILTER(ISNUMERIC(?prop))}";

		//fill the query
		String query = nodePropQuery.replace("@NODE_TYPE_URI@", nodeUri);
		logger.info("Running node property query " + query);
		return Response.status(200).entity(getSO(coreEngine.getEntityOfType(query))).build();
	}	
	
	// gets all numeric edge properties for a specific edge type
	@GET
	@Path("properties/edge/type/numeric")
	@Produces("application/json")
	public Response getNumericEdgeProperties(
			@QueryParam("source")  String sourceTypeUri,
			@QueryParam("target")  String targetTypeUri,
			@QueryParam("verb")  String verbTypeUri)
	{
		String edgePropQuery = "SELECT DISTINCT ?entity WHERE {{?source <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <@SOURCE_TYPE@>} {?target <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <@TARGET_TYPE@>} {?verb <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> <@VERB_TYPE@>}{?source ?verb ?target;} {?entity <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Relation/Contains>} {?verb ?entity ?prop } FILTER(ISNUMERIC(?prop))}";

		//fill the query
		String query = edgePropQuery.replace("@SOURCE_TYPE@", sourceTypeUri).replace("@TARGET_TYPE@", targetTypeUri).replace("@VERB_TYPE@", verbTypeUri);
		logger.info("Running edge property query " + query);
		return Response.status(200).entity(getSO(coreEngine.getEntityOfType(query))).build();
	}	
	
	@GET
	@Path("customViz")
	@Produces("application/json")
	public Response getVizData(@QueryParam("QueryData") String pathObject, @Context HttpServletRequest request)
	{
		String heatMapName = "testName";
		Gson gson = new Gson();
		Hashtable<String, Object> dataHash = gson.fromJson(pathObject, Hashtable.class);
		String vizType = (String) dataHash.get(AbstractCustomVizBuilder.vizTypeKey);
		ICustomVizBuilder viz = null;
		if(vizType.equals(PlaySheetEnum.Heat_Map.getSheetName()))
		{
			viz = new CustomVizHeatMapBuilder();
		}
		viz.setJSONDataHash(dataHash);
		viz.setVisualType(PlaySheetEnum.Heat_Map.getSheetName());
		viz.buildQuery();
		String query = viz.getQuery();
		
		Object obj = null;
		try
		{
			String playSheetClassName = PlaySheetEnum.getClassFromName(PlaySheetEnum.Heat_Map.getSheetName());
			IPlaySheet playSheet = (IPlaySheet) Class.forName(playSheetClassName).getConstructor(null).newInstance(null);
			playSheet.setQuery(query);
			playSheet.setRDFEngine(coreEngine);
			playSheet.setQuestionID(heatMapName);
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
			session.setAttribute(heatMapName, playSheet);
		} catch(Exception ex) {
			ex.printStackTrace();
			Hashtable<String, String> errorHash = new Hashtable<String, String>();
			errorHash.put("Message", "Error occured processing query.");
			errorHash.put("Class", className);
			return Response.status(500).entity(getSO(errorHash)).build();
		}
		return Response.status(200).entity(getSO(obj)).build();
	}	
	
	@POST
	@Path("customVizTable")
	@Produces("application/json")
	public Response getVizTable(MultivaluedMap<String, String> form, @Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		Hashtable<String, Object> dataHash = gson.fromJson(form.getFirst("QueryData"), Hashtable.class);
		Integer items = 100;
		if (form.containsKey("ItemCount"))
			items = gson.fromJson(form.getFirst("ItemCount"), Integer.class);
		Integer pageNumber = null;
		if (form.containsKey("PageNumber"))
			pageNumber = gson.fromJson(form.getFirst("PageNumber"), Integer.class);
		CustomVizTableBuilder tableViz = new CustomVizTableBuilder();
		
		ArrayList<Hashtable<String,String>> nodePropArray = getHashArrayFromString(form.getFirst("SelectedNodeProps") + "");
		ArrayList<Hashtable<String,String>> edgePropArray = getHashArrayFromString(form.getFirst("SelectedEdgeProps") + "");
		tableViz.setPropV(nodePropArray, edgePropArray);
		
		tableViz.setJSONDataHash(dataHash);
		tableViz.setEngine(coreEngine);
		tableViz.buildQuery();
		SEMOSSQuery semossQuery = tableViz.getSEMOSSQuery();
		
		//get header array before adding pagination stuff
		ArrayList<Hashtable<String, String>> varObjV = tableViz.getHeaderArray();
		Collection<Hashtable<String, String>> varObjVector = varObjV;
		
		//add pagination information
		Hashtable limitHash = new Hashtable();
		int fullTableRowNum = tableViz.runCountQuery();
		
		semossQuery.addAllVarToOrderBy();// necessary for pagination
		limitHash.put("fullSize", fullTableRowNum);
		
		if(items!= null){
			int limitSize = items;
			semossQuery.setLimit(limitSize);
		}
		if(pageNumber != null) {
			int offset = items * (pageNumber - 1);
			semossQuery.setOffset(offset);
		}
			
		semossQuery.createQuery();
		String query = semossQuery.getQuery();
		
		System.out.println(query);
		Object obj = null;
		try
		{
			String playSheetClassName = PlaySheetEnum.getClassFromName(PlaySheetEnum.Grid.getSheetName());
			IPlaySheet playSheet = (IPlaySheet) Class.forName(playSheetClassName).getConstructor(null).newInstance(null);
			playSheet.setQuery(query);
			playSheet.setRDFEngine(coreEngine);
			//should through what questionID this should be
			playSheet.setQuestionID("VizBuilder");
			playSheet.createData();
			playSheet.runAnalytics();
			obj = playSheet.getData();
			
		} catch(Exception ex) {
			ex.printStackTrace();
			Hashtable<String, String> errorHash = new Hashtable<String, String>();
			errorHash.put("Message", "Error occured processing query.");
			errorHash.put("Class", className);
			return Response.status(500).entity(getSO(errorHash)).build();
		}
		
		//add variable info to return data
		((Hashtable)obj).put("variableHeaders", varObjVector);
		((Hashtable)obj).put("limit", limitHash);
		
		return Response.status(200).entity(getSO(obj)).build();
	}	
	
	@GET
	@Path("customVizPathProperties")
	@Produces("application/json")
	public Response getPathProperties(@QueryParam("QueryData") String pathObject, @Context HttpServletRequest request)
	{
		logger.info("Getting properties for path");
		Gson gson = new Gson();
		Hashtable<String, Object> dataHash = gson.fromJson(pathObject, Hashtable.class);
		CustomVizTableBuilder tableViz = new CustomVizTableBuilder();
		tableViz.setJSONDataHash(dataHash);
		tableViz.setEngine(coreEngine);
		Object obj = tableViz.getPropsFromPath();
		return Response.status(200).entity(getSO(obj)).build();
	}	

  	@GET
	@Path("menu")
	@Produces("application/json")	
	public Response getMenu(@QueryParam("user") String user, @QueryParam("start") String starter, @Context HttpServletRequest request)
	{
		if(user == null)
			user = "All";
		if(starter == null)
			starter = "ContextMenu";
		
		 String menuSubMenuQuery = "SELECT ?Menu ?MType ?MURL ?Submenu ?SMenuLabel ?SURL ?SType ?ChildMenu ?ChURL ?ChLabel ?CType ?MenuFiller ?SFiller ?CFiller WHERE " +
	   		"{BIND( <http://semoss.org/ontologies/Concept/Owner/" + user + "> AS ?User) " +
	   		"{?Menu <http://semoss.org/ontologies/Relation/Owner> ?User} " +
	   		"{?Menu <http://semoss.org/ontologies/Relation/submenu> ?Submenu} " +
	   		"{?Menu <http://semoss.org/ontologies/Relation/Contains/Label> ?MenuLabel} " +
	   		"{?Menu <http://semoss.org/ontologies/Relation/Contains/FillerName> ?MenuFiller} " +
	   		"{?Menu <http://semoss.org/ontologies/Relation/Contains/Type> ?MType} " +
	   		"{?Submenu <http://semoss.org/ontologies/Relation/Contains/Label> ?SMenuLabel} " +
	   		"{?Submenu <http://semoss.org/ontologies/Relation/Contains/Type> ?SType} " +
	   		"OPTIONAL " +
	   			"{" +
	   			"{?Submenu <http://semoss.org/ontologies/Relation/Contains/URL> ?SURL}  " +
	   			"{?Submenu <http://semoss.org/ontologies/Relation/Contains/FillerName> ?SFiller}  " +
	   			"{?Submenu <http://semoss.org/ontologies/Relation/submenu> ?ChildMenu} " +
	   			"{?ChildMenu <http://semoss.org/ontologies/Relation/Contains/Label> ?ChLabel}" +
		   		"{?ChildMenu <http://semoss.org/ontologies/Relation/Contains/Type> ?CType} " +
	   			"}" +
	   		"OPTIONAL " +
	   			"{" +
	   			"{?ChildMenu <http://semoss.org/ontologies/Relation/Contains/URL> ?ChURL;} " +
	   			"{?ChildMenu <http://semoss.org/ontologies/Relation/Contains/FillerName> ?CFiller;} " +
	   			"} " +
	   		  "OPTIONAL " +
	   		  "{" +
	   		  		"{?Menu <http://semoss.org/ontologies/Relation/Contains/URL> ?MURL}" +
	   		  "}" +
	   		  "FILTER regex(str(?MenuLabel),'" + starter + "', 'i')}";
		 
		// menuSubMenuQuery = "SELECT ?Menu ?Submenu ?SMenuLabel ?SURL ?SType ?ChildMenu ?ChURL ?ChLabel WHERE {BIND( <http://semoss.org/ontologies/Concept/Owner/All> AS ?User) {?Menu <http://semoss.org/ontologies/Relation/Owner> ?User} }";
		 //{?Menu <http://semoss.org/ontologies/Relation/submenu> ?Submenu} {?Menu <http://semoss.org/ontologies/Relation/Contains/Label> ?MenuLabel} {?Submenu <http://semoss.org/ontologies/Relation/Contains/Label> ?SMenuLabel} {?Submenu <http://semoss.org/ontologies/Relation/Contains/Type> ?SType} OPTIONAL {{?Submenu <http://semoss.org/ontologies/Relation/Contains/URL> ?SURL}  {?Submenu <http://semoss.org/ontologies/Relation/submenu> ?ChildMenu} {?ChildMenu <http://semoss.org/ontologies/Relation/Contains/Type> ?CType} {?ChildMenu <http://semoss.org/ontologies/Relation/Contains/URL> ?ChURL;} {?ChildMenu <http://semoss.org/ontologies/Relation/Contains/Label> ?ChLabel}} FILTER regex(str(?MenuLabel), 'Data', 'i')}";
		 
		SesameJenaSelectWrapper wrapper = new SesameJenaSelectWrapper();
		wrapper.setEngine(coreEngine);
		wrapper.setQuery(menuSubMenuQuery);
		wrapper.setEngineType(IEngine.ENGINE_TYPE.SESAME);
		wrapper.executeQuery();
		
		System.out.println("Query.... " + menuSubMenuQuery);
		System.out.println("Variables " + wrapper.getVariables());
		
		Hashtable allMenu = new Hashtable();
		ArrayList<String> subMenus = new ArrayList();
		
		while(wrapper.hasNext())
		{
			System.out.println("New record");
			SesameJenaSelectStatement stmt = wrapper.next();
			String menu = (String)stmt.getVar("Menu");

			String menuType = ((String)stmt.getVar("MType")).replace("\"", "");
			String menuURL = ((String)stmt.getVar("MURL")).replace("\"", "").replace("*","/");
			String menuFiller = ((String)stmt.getVar("MenuFiller")).replace("\"", "");
			
			String subMenu = ((String)stmt.getVar("Submenu")).replace("\"", "");
			String subMenuLabel = ((String)stmt.getVar("SMenuLabel")).replace("\"", "");
			String sType = ((String)stmt.getVar("SType")).replace("\"", "");
			String surl = ((String)stmt.getVar("SURL")).replace("\"", "").replace("*","/"); // eventually it will be a * instead of a -
			String sFiller = ((String)stmt.getVar("SFiller")).replace("\"", "");

			allMenu.put("Menu", starter);
			allMenu.put("Type", menuType);
			allMenu.put("URL", menuURL);
			allMenu.put("Filler", menuFiller);
			

			// submenu
			Hashtable smenuHash = new Hashtable();
			if(allMenu.containsKey(subMenuLabel)) {
				smenuHash = (Hashtable)allMenu.get(subMenuLabel);
			}
			else {
				subMenus.add(subMenuLabel);
			}
			
			smenuHash.put("Label", subMenuLabel);
			smenuHash.put("Type", sType);
			smenuHash.put("URL", surl);
			smenuHash.put("Filler", sFiller);

			// finally the sub sub menu
			String childMenu = ((String)stmt.getVar("ChildMenu")).replace("\"", "");
			String chMenuLabel = ((String)stmt.getVar("ChLabel")).replace("\"","");
			String chURL = ((String)stmt.getVar("ChURL")).replace("\"", "").replace("*","/"); // eventually I will put this as a * so it can be replaced
			String cType = ((String)stmt.getVar("CType")).replace("\"", "");
			String cFiller = ((String)stmt.getVar("CFiller")).replace("\"", "");

			ArrayList <String> childMenus = new ArrayList();
			if(chMenuLabel != null && chMenuLabel.length() != 0)
			{
				System.out.println(" Child Menu for " + subMenuLabel + "  Child is " + childMenu);
				Hashtable childMenuHash = new Hashtable();
				if(smenuHash.containsKey(chMenuLabel))
				{
					System.out.println("Has the child menu label [" + chMenuLabel + "]");
					childMenuHash = (Hashtable)smenuHash.get(chMenuLabel);
				}
				else {
					childMenus.add(chMenuLabel);
				}
				childMenuHash.put("Label", chMenuLabel);
				childMenuHash.put("URL", chURL);
				childMenuHash.put("Type", cType);
				childMenuHash.put("Filler", cFiller);
				
				System.out.println("Child menus is " + childMenus);

				if(childMenus.size() > 0) {
					smenuHash.put("Submenus", childMenus);
				}
				smenuHash.put(chMenuLabel, childMenuHash);
			}
			
			System.out.println("Submenu " + smenuHash);
			allMenu.put("Submenus", subMenus);
			allMenu.put(subMenuLabel, smenuHash);
		}

		// master the hashtable for empty
		for(int subMenuIndex = 0;subMenuIndex < subMenus.size();subMenuIndex++)
		{
			Hashtable thisMenu = (Hashtable)allMenu.get(subMenus.get(subMenuIndex));
			if(!thisMenu.containsKey("Submenus") || ((ArrayList)thisMenu.get("Submenus")).size() == 0)
			{
				ArrayList tempList = new ArrayList();
				tempList.add("EMPTY");
				thisMenu.put("Submenus", tempList);
			}
		}
		if(subMenus.size() == 0)
		{
			subMenus.add("EMPTY");
			allMenu.put("Submenus", subMenus);
		}	
		
		System.out.println(">>>.... " + new GsonBuilder().setPrettyPrinting().create().toJson(allMenu));
		
		return Response.status(200).entity(getSO(allMenu)).build();
	}
  	
  	private ArrayList<Hashtable<String,String>> getHashArrayFromString(String arrayString){
  		System.err.println("MY STRING " + arrayString);
  		ArrayList<Hashtable<String,String>> retArray = new ArrayList<Hashtable<String,String>>();
		if(arrayString != null) {
			Gson gson = new Gson();
			ArrayList<Object> varsObjArray = gson.fromJson(arrayString, ArrayList.class);
			for(Object varsObj : varsObjArray){
				Hashtable newHash = new Hashtable();
				newHash.putAll((StringMap)varsObj);
				retArray.add(newHash);
			}
		}
		return retArray;
  	}
}