package prerna.web.services.util;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.StreamingOutput;

import org.openrdf.query.GraphQueryResult;
import org.openrdf.query.QueryEvaluationException;

public class GraphStreamingOutput implements StreamingOutput {

	GraphQueryResult gqr = null;
	
	public GraphStreamingOutput(GraphQueryResult gqr)
	{
		this.gqr = gqr;
	}
	
	@Override
	public void write(OutputStream outputStream) throws IOException,
			WebApplicationException {
	            ObjectOutputStream os = new ObjectOutputStream(outputStream);
	            Integer myInt = null;
	            try {
					while(gqr.hasNext())
						os.writeObject(gqr.next());
					os.writeObject("null");
				} catch (QueryEvaluationException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
	}
		// TODO Auto-generated method stub
		
}
