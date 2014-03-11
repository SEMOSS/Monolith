package prerna.semoss.web.services;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.StreamingOutput;

import prerna.rdf.engine.api.IEngine;
import prerna.upload.Uploader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ibm.icu.util.StringTokenizer;

@Path("/engine")
public class NameServer {
	 @Context ServletContext context;
	String output = "";
	Hashtable helpHash = null;
	// gets the specific database
	@Path("e-{engine}")
	public Object getDatabase(@PathParam("engine") String db, @Context HttpServletRequest request)
	{
		// this is the name server
		// this needs to return stuff
		System.out.println(" Getting DB... " + db);
		HttpSession session = request.getSession();
		IEngine engine = (IEngine)session.getAttribute(db);
		EngineResource res = new EngineResource();
		res.setEngine(engine);
		return res;
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
	public StreamingOutput printEngines(@Context HttpServletRequest request)
	{
		// would be cool to give this as an HTML
		Vector enginesV = new Vector<String>();
		HttpSession session = request.getSession();
		String engines = (String)session.getAttribute("ENGINES");
		StringTokenizer tokens = new StringTokenizer(engines, ":");
		while(tokens.hasMoreTokens())
			enginesV.add(tokens.nextToken());
		return getSO(enginesV);
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

	
	private StreamingOutput getSO(Object vec)
	{
		Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
		output = gson.toJson(vec);
		   return new StreamingOutput() {
		         public void write(OutputStream outputStream) throws IOException, WebApplicationException {
		            PrintStream ps = new PrintStream(outputStream);
		            ps.println(output);
		         }};		
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

}
