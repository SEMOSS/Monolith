package prerna.semoss.web.services;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;

import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.StreamingOutput;
import javax.xml.bind.DatatypeConverter;

import prerna.rdf.engine.api.IEngine;
import prerna.rdf.engine.api.IRemoteQueryable;
import prerna.rdf.engine.impl.SesameJenaConstructStatement;
import prerna.rdf.engine.impl.SesameJenaConstructWrapper;
import prerna.rdf.engine.impl.SesameJenaSelectCheater;
import prerna.rdf.engine.impl.SesameJenaSelectStatement;
import prerna.rdf.engine.impl.SesameJenaSelectWrapper;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.web.services.util.GraphStreamingOutput;
import prerna.web.services.util.QueryResultHash;
import prerna.web.services.util.TupleStreamingOutput;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

// the primary class that will expose any engine through a remote interface

public class EngineRemoteResource {
	
	public IEngine coreEngine = null;
	String output = null;
	String uriBase = null;
	
	public void setEngine(IEngine coreEngine)
	{
		this.coreEngine = coreEngine;
		if(uriBase == null && DIHelper.getInstance().getCoreProp().containsKey(Constants.URI_BASE))
			uriBase = (String)DIHelper.getInstance().getCoreProp().get(Constants.URI_BASE);
		else
			uriBase = uriBase;
		
	}

	@POST
	@Path("getFromNeighbors")
	@Produces("application/json")
	public StreamingOutput getFromNeighbors(@FormParam("nodeType") String nodeType, @FormParam("neighborHood") int neighborHood) {
		// TODO Auto-generated method stub
		return getSO(coreEngine.getFromNeighbors(nodeType, neighborHood));
	}

	@POST
	@Path("getToNeighbors")
	@Produces("application/json")
	public StreamingOutput getToNeighbors(@FormParam("nodeType") String nodeType, @FormParam("neighborHood") int neighborHood) {
		// TODO Auto-generated method stub
		return getSO(coreEngine.getToNeighbors(nodeType, neighborHood));
	}

	@POST
	@Path("getNeighbors")
	@Produces("application/json")
	public StreamingOutput getNeighbors(@FormParam("nodeType") String nodeType, @FormParam("neighborHood") int neighborHood) {
		// TODO Auto-generated method stub
		return getSO(coreEngine.getNeighbors(nodeType, neighborHood));
	}

	@POST
	@Path("execGraphQuery")
	@Produces("application/json")
	public Object execGraphQuery(@FormParam("query") String query) {
		// TODO Auto-generated method stub
		// Steps I need to do
		// Create a wrapper object
		// The wrapper consists of a unique number, the actual output object
		// sets this wrapper in the memory
		System.out.println("Executing GRAPH Query " + query);
		SesameJenaConstructWrapper sjw = new SesameJenaConstructWrapper();
		sjw.setQuery(query);
		sjw.setEngine(coreEngine);
		sjw.execute();
		// need someway to get an indirection for now hardcoded
		((IRemoteQueryable)sjw).setRemoteAPI(uriBase + coreEngine.getEngineName());
		QueryResultHash.getInstance().addObject(sjw);		
		
		return getSO(sjw);
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


	@POST
	@Path("execSelectQuery")
	@Produces("application/json")
	public Object execSelectQuery(@FormParam("query") String query) {
		// TODO Auto-generated method stub
		System.out.println("Executing Select Query  " + query);
		SesameJenaSelectWrapper sjsw = new SesameJenaSelectWrapper();
		sjsw.setQuery(query);
		sjsw.setEngine(coreEngine);
		sjsw.executeQuery();
		sjsw.getVariables();
		sjsw.setRemote(true);
		// need someway to get an indirection for now hardcoded
		((IRemoteQueryable)sjsw).setRemoteAPI(uriBase + coreEngine.getEngineName());
		QueryResultHash.getInstance().addObject(sjsw);		
	
		return getSO(sjsw);
	}


	@POST
	@Path("execCheaterQuery")
	@Produces("application/json")
	public Object execCheaterQuery(@FormParam("query") String query) {
		// TODO Auto-generated method stub
		System.out.println("Executing Select Query  " + query);
		SesameJenaSelectCheater sjsw = new SesameJenaSelectCheater();
		sjsw.setQuery(query);
		sjsw.setEngine(coreEngine);
		sjsw.execute();
		sjsw.getVariables();
		sjsw.setRemote(true);
		// need someway to get an indirection for now hardcoded
		((IRemoteQueryable)sjsw).setRemoteAPI(uriBase + coreEngine.getEngineName());
		QueryResultHash.getInstance().addObject(sjsw);		
	
		return getSO(sjsw);
	}

	@POST
	@Path("getEntityOfType")
	@Produces("application/json")
	public StreamingOutput getEntityOfType(@FormParam("sparqlQuery") String sparqlQuery) {
		// TODO Auto-generated method stub
		return getSO(coreEngine.getEntityOfType(sparqlQuery));
	}

	@POST
	@Path("execAskQuery")
	@Produces("application/json")
	public StreamingOutput execAskQuery(@FormParam("query") String query) {
		// TODO Auto-generated method stub
		return getSO(coreEngine.execAskQuery(query));
	}


	@POST
	@Path("getParamValues")
	@Produces("application/json")
	public StreamingOutput getParamValues(@FormParam("label") String label, @FormParam("type") String type,
			@FormParam("insightId") String insightId, @FormParam("query") String query) {
		// TODO Auto-generated method stub
		return getSO(coreEngine.getParamValues(label, type, insightId, query));
	}
	
	@POST
	@Path("getInsightDefinition")
	@Produces("application/xml")
	public String getInsightDefinition() {
		// TODO Auto-generated method stub
		System.out.println("ENgine is " + coreEngine);
		return coreEngine.getInsightDefinition();
	}

	@POST
	@Path("getOWLDefinition")
	@Produces("application/xml")
	public String getOWLDefinition() {
		// TODO Auto-generated method stub
		return coreEngine.getOWLDefinition();
	}
	
	// do the has Next
	@POST
	@Path("hasNext")
	@Produces("application/json")
	public StreamingOutput hasNext(@FormParam("id") String id)
	{
		boolean retValue = false;
		System.out.println("Got the id " + id);
		if(id != null)
		{
			Object wrapper = QueryResultHash.getInstance().getObject(id);

			System.out.println("Got the object as well" + wrapper);
			if(wrapper instanceof SesameJenaConstructWrapper)
				retValue = ((SesameJenaConstructWrapper)wrapper).hasNext();
			else if(wrapper instanceof SesameJenaSelectWrapper)
				retValue = ((SesameJenaSelectWrapper)wrapper).hasNext();
			if(wrapper instanceof SesameJenaSelectCheater)
				retValue = ((SesameJenaSelectCheater)wrapper).hasNext();
			
			if(!retValue) // cleanup
				QueryResultHash.getInstance().cleanObject(id);
		}
		
		return getSO(retValue);
	}

	@POST
	@Path("next")
	@Produces("application/json")
	public StreamingOutput next(@FormParam("id") String id)
	{
		Object retValue = null;
		if(id != null)
		{
			// I can avoid the wrapper BS below by just putting through an interface
			// good things come to people who wait
			Object wrapper = QueryResultHash.getInstance().getObject(id);
			QueryResultHash.getInstance().cleanObject(id);
			if(wrapper instanceof SesameJenaSelectCheater)
			{
				System.out.println(" Cheater.... ");
				return new TupleStreamingOutput(((SesameJenaSelectCheater)wrapper).tqr);
			}
			else if(wrapper instanceof SesameJenaConstructWrapper)
			{
				System.out.println(" Construct.... ");
				return new GraphStreamingOutput(((SesameJenaConstructWrapper)(wrapper)).gqr);				
			}
			else if(wrapper instanceof SesameJenaSelectWrapper)
			{
				System.out.println(" Select.... ");
				return new TupleStreamingOutput(((SesameJenaSelectWrapper)(wrapper)).tqr);
			}
		}
		// set the data into the statement
		
		
		// not sure if I should flesh the entire select query object or just the data hash yet
		return null;
	}

	@POST
	@Path("bvnext")
	@Produces("application/json")
	public StreamingOutput bvnext(@FormParam("id") String id)
	{
		// this is only applicable to select query
		Object retValue = null;
		if(id != null)
		{
			// I can avoid the wrapper BS below by just putting through an interface
			// good things come to people who wait
			Object wrapper = QueryResultHash.getInstance().getObject(id);
			if(wrapper instanceof SesameJenaSelectWrapper)
				retValue = ((SesameJenaSelectWrapper)wrapper).BVnext();
		}
		// not sure if I should flesh the entire select query object or just the data hash yet
		return getSO(retValue);
	}	
	
	@POST
	@Path("getProperty")
	@Produces("application/text")
	public String getProperty(@FormParam("key") String key)
	{
		return coreEngine.getProperty(key);
	}


	@POST
	@Path("streamTester")
	@Produces("application/text")
	public StreamingOutput getStreamTester()
	{
		   return new StreamingOutput() {
		         public void write(OutputStream outputStream) throws IOException, WebApplicationException {
		            PrintStream ps = new PrintStream(outputStream);
		            ObjectOutputStream os = new ObjectOutputStream(outputStream);
		            Integer myInt = null;
		            for(int i = 0;i< 1000000;i++)
		            {
		            	myInt = new Integer(i);
			            //ps.println("Sending " + i);
		            	os.writeObject(myInt);
			            if(i %1000 == 0)
							try {
								Thread.sleep(200);
							} catch (InterruptedException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
		            }
		         }};		
	}

}
