package prerna.semoss.web.services;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class EngineResource {
	
	// gets everything specific to an engine
	// essentially this is a wrapper over the engine
	IEngine coreEngine = null;
	String output = "";
	Logger logger = Logger.getLogger(getClass());
	Hashtable<String, SEMOSSQuery> vizHash = new Hashtable<String, SEMOSSQuery>();
	
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
	public StreamingOutput getMetamodel(@Context HttpServletRequest request)
	{
		ExecuteQueryProcessor exQueryProcessor = new ExecuteQueryProcessor();
		//hard code playsheet attributes since no insight exists for this
		String sparql = "SELECT ?s ?p ?o WHERE {?s ?p ?o} LIMIT 1";
		String playSheetName = "prerna.ui.components.playsheets.GraphPlaySheet";
		String title = "Metamodel";
		String id = coreEngine.getEngineName() + "-Metamodel";
		AbstractEngine eng = ((AbstractEngine)coreEngine).getBaseDataEngine();
		eng.setEngineName(id);
		eng.setBaseData((RDFFileSesameEngine) eng);
		Hashtable filterHash = new Hashtable();
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
		}catch(Exception ex)
		{
			ex.printStackTrace();
		}
		
		return getSO(obj);
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

	//gets all node types connected to a given node type along with the verbs connecting the given types
	@GET
	@Path("neighbors/verbs")
	@Produces("application/json")
	public StreamingOutput getNeighborsWithVerbs(@QueryParam("nodeType") String type, @Context HttpServletRequest request)
	{
		Hashtable<String, Hashtable<String, Vector<String>>> finalTypes = new Hashtable<String, Hashtable<String, Vector<String>>>();
		if(coreEngine instanceof AbstractEngine){
			Hashtable<String, Vector<String>> downNodes = ((AbstractEngine) coreEngine).getToNeighborsWithVerbs(type, 0);
			finalTypes.put("downstream", downNodes);
			Hashtable<String, Vector<String>> upNodes = ((AbstractEngine) coreEngine).getFromNeighborsWithVerbs(type, 0);
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
	
	// gets all the insights for a given type and tag in all the engines
	// both tag and type are optional
	@GET
	@Path("insights")
	@Produces("application/json")
	public StreamingOutput getInsights(@QueryParam("nodeType") String type, @QueryParam("tag") String tag,@QueryParam("perspective") String perspective, @Context HttpServletRequest request)
	{
		// if the type is null then send all the insights else only that
		Vector result = null;
		if(perspective != null)
			result = coreEngine.getInsights(perspective);
		else if(type != null)
			result = coreEngine.getInsight4Type(type);
		else if(tag != null)
			result = coreEngine.getInsight4Tag(tag);
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
		Hashtable<String, Object> paramHash = Utility.getParamsFromString(params);
		
		ExecuteQueryProcessor exQueryProcessor = new ExecuteQueryProcessor();
		exQueryProcessor.processQuestionQuery(coreEngine.getEngineName(), insight, paramHash);
		Object obj = null;
		try
		{
			IPlaySheet playSheet= exQueryProcessor.getPlaySheet();
			PlaysheetCreateRunner playRunner = new PlaysheetCreateRunner(playSheet);
			playRunner.runWeb();
			/*playRunner.setCreateSwingView(false);
			Thread playThread = new Thread(playRunner);
			playThread.start();
			while(playThread.isAlive()){
				//wait for processing to finish before getting the data
			}*/
			
			obj = playSheet.getData();

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
	
	// gets all numeric properties associated with a specific node type
	@GET
	@Path("properties/node/type/numeric")
	@Produces("application/json")
	public StreamingOutput getNumericNodeProperties(
			@QueryParam("nodeType")  String nodeUri)
	{
		String nodePropQuery = "SELECT DISTINCT ?entity WHERE {{?source <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <@NODE_TYPE_URI@>} {?entity <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Relation/Contains>} {?source ?entity ?prop } FILTER(ISNUMERIC(?prop))}";

		//fill the query
		String query = nodePropQuery.replace("@NODE_TYPE_URI@", nodeUri);
		
		logger.info("Running node property query " + query);

		return getSO(coreEngine.getEntityOfType(query));
	}	
	
	// gets all numeric edge properties for a specific edge type
	@GET
	@Path("properties/edge/type/numeric")
	@Produces("application/json")
	public StreamingOutput getNumericEdgeProperties(
			@QueryParam("source")  String sourceTypeUri,
			@QueryParam("target")  String targetTypeUri,
			@QueryParam("verb")  String verbTypeUri)
	{
		String edgePropQuery = "SELECT DISTINCT ?entity WHERE {{?source <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <@SOURCE_TYPE@>} {?target <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <@TARGET_TYPE@>} {?verb <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> <@VERB_TYPE@>}{?source ?verb ?target;} {?entity <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Relation/Contains>} {?verb ?entity ?prop } FILTER(ISNUMERIC(?prop))}";

		//fill the query
		String query = edgePropQuery.replace("@SOURCE_TYPE@", sourceTypeUri).replace("@TARGET_TYPE@", targetTypeUri).replace("@VERB_TYPE@", verbTypeUri);
		
		logger.info("Running edge property query " + query);

		return getSO(coreEngine.getEntityOfType(query));
	}	
	
	@GET
	@Path("customViz")
	@Produces("application/json")
	public StreamingOutput getVizData(@QueryParam("QueryData") String pathObject, @Context HttpServletRequest request)
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
		}catch(Exception ex)
		{
			ex.printStackTrace();
		}
		
		return getSO(obj);
	}	
	
	@GET
	@Path("customVizTable")
	@Produces("application/json")
	public StreamingOutput getVizTable(@QueryParam("QueryData") String pathObject, @Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		Hashtable<String, Object> dataHash = gson.fromJson(pathObject, Hashtable.class);
		CustomVizTableBuilder tableViz = new CustomVizTableBuilder();
		tableViz.setJSONDataHash(dataHash);
		tableViz.setEngine(coreEngine);
		tableViz.buildQuery();
		String query = tableViz.getQuery() + " LIMIT 50";
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

				
			//store the playsheet in session, do i need to do this here?
			HttpSession session = ((HttpServletRequest)request).getSession(false);
			session.setAttribute("VizBuilder", playSheet);
		}catch(Exception ex)
		{
			ex.printStackTrace();
		}
		
		return getSO(obj);
	}	

  	@GET
	@Path("menu")
	@Produces("application/json")	
	public StreamingOutput getMenu(@QueryParam("user") String user, @QueryParam("start") String starter, @Context HttpServletRequest request)
	{
		String finalString = null;
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
			if(allMenu.containsKey(subMenuLabel))
				smenuHash = (Hashtable)allMenu.get(subMenuLabel);
			else
				subMenus.add(subMenuLabel);
			
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
				else
					childMenus.add(chMenuLabel);
				
				childMenuHash.put("Label", chMenuLabel);
				childMenuHash.put("URL", chURL);
				childMenuHash.put("Type", cType);
				childMenuHash.put("Filler", cFiller);
				
				System.out.println("Child menus is " + childMenus);

				if(childMenus.size() > 0)
					smenuHash.put("Submenus", childMenus);
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
		
		return getSO(allMenu);
	}


}
