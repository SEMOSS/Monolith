package prerna.semoss.web.services;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.StreamingOutput;

public class OutputResource {

	// gets a particular insight
	// not sure if I should keep it as it is or turn this into a post because of the query
	@GET
	@Path("queryOutput")
	@Produces("application/json")
	public StreamingOutput queryOutput(@QueryParam("outputID") String id, @QueryParam("query") String query)
	{
		// returns the insight
		// based on the current ID get the data
		// typically is a JSON of the insight
		// this will also cache it
		return null;
	}	
	
	// gets a particular insight
	// not sure if I should keep it as it is or turn this into a post because of the query
	@GET
	@Path("list")
	@Produces("application/json")
	public StreamingOutput getAllOutputs()
	{
		// returns the insight
		// based on the current ID get the data
		// typically is a JSON of the insight
		// this will also cache it
		return null;
	}	

	// gets a particular output
	@GET
	@Path("{id}")
	@Produces("application/json")
	public StreamingOutput getOutput(@QueryParam("outputID") String id)
	{
		// returns the insight
		// based on the current ID get the data
		// typically is a JSON of the insight
		// this will also cache it
		return null;
	}	

}
