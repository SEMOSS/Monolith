package prerna.semoss.web.services;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
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
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;

import prerna.rdf.engine.api.IEngine;
import prerna.ui.components.ImportDataProcessor;
import prerna.util.DIHelper;

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
			urls.put("Perspectives in a specific engine", "hostname:portname/Monolith/api/engine/e-<enginename>/perspectives");
			urls.put("Insights for specific perspective specific engine", "hostname:portname/Monolith/api/engine/e-<enginename>/insights?perspective=<perspective>");
			urls.put("All Insights in a engine", "hostname:portname/Monolith/api/engine/e-<enginename>/insights");
			urls.put("All Perspectives and Insights in a engine", "hostname:portname/Monolith/api/engine/e-<enginename>/pinsights");
			urls.put("Insight definition for a particular insight", "hostname:portname/Monolith/api/engine/e-<enginename>/insight?insight=<label of insight (NOT ID)>");
			urls.put("Execute insight without parameter", "hostname:portname/Monolith/api/engine/e-<enginename>/output?insight=<label of insight (NOT ID)>");
			urls.put("Execute insight with parameter", "hostname:portname/Monolith/api/engine/e-<enginename>/output?insight=<label of insight (NOT ID)>&params=key:value;key2:value2;key3:value3");
			urls.put("Execute Custom Query Select ", "hostname:portname/Monolith/api/engine/e-<enginename>/querys?query=<sparql query>");
			urls.put("Execute Custom Query Construct ", "hostname:portname/Monolith/api/engine/e-<enginename>/queryc?query=<sparql query>");
			urls.put("Insert query ", "hostname:portname/Monolith/api/engine/e-<enginename>/insert?query=<sparql query>");
			urls.put("Properties of a given node", "hostname:portname/Monolith/api/engine/e-<enginename>/properties?node=<URI>");
			urls.put("Fill Values for a given parameter (You already get this in insights)", "hostname:portname/Monolith/api/engine/e-<enginename>/fill?type=type");
			urls.put("Get Neighbors of a particular engine", "hostname:portname/Monolith/api/engine/e-<enginename>/neighbors?node=<URI>");
			urls.put("Tags for an insight (Specific Engine)", "hostname:portname/Monolith/api/engine/e-<enginename>/tags?insight=<insight label>");
			urls.put("Insights for a given tag (Tag is optional) (Specific Engine) ", "hostname:portname/Monolith/api/engine/e-<enginename>/insight?tag=<xyz>");		
			urls.put("Get All the engines", "hostname:portname/Monolith/api/engine/all");
			urls.put("Neighbors of across all engine", "hostname:portname/Monolith/api/engine/neighbors?node=<URI>");
			urls.put("Tags for an insight", "hostname:portname/Monolith/api/engine/tags?insight=<insight label>");
			urls.put("Insights for a given tag (Tag is optional)", "hostname:portname/Monolith/api/engine/insight?tag=<xyz>");		
			urls.put("Create a new engine - takes you to uploader", "hostname:portname/Monolith/api/engine/create");
			urls.put("Help - this menu", "hostname:portname/Monolith/api/engine/help");
			helpHash = urls;
		}
		return getSOHTML();
	}	
	
	// uploader functionality
	@POST
	@Path("/insight/upload")
	@Produces("text/html")
	public Response uploadFile(@Context HttpServletRequest request) {
		String htmlResponse = "";
		try {
			int maxFileSize = 50 * 1024;
			int maxMemSize = 4 * 1024;
			File file;
			String filePath = context.getInitParameter("file-upload");
			// Check that we have a file upload request
			boolean isMultipart = ServletFileUpload.isMultipartContent(request);

			// java.io.PrintWriter out = response.getWriter( );
			if (!isMultipart) {
				htmlResponse += "<html>";
				htmlResponse += "<head>";
				htmlResponse += "<title>Servlet upload</title>";
				htmlResponse += "</head>";
				htmlResponse += "<body>";
				htmlResponse += "<p>No file uploaded</p>";
				htmlResponse += "</body>";
				htmlResponse += "</html>";
				return Response.status(200).entity(htmlResponse).build();
			}
			DiskFileItemFactory factory = new DiskFileItemFactory();
			// maximum size that will be stored in memory
			factory.setSizeThreshold(maxMemSize);
			// Location to save data that is larger than maxMemSize.
			factory.setRepository(new File("c:\\temp"));

			// Create a new file upload handler
			ServletFileUpload upload = new ServletFileUpload(factory);
			// maximum file size to be uploaded.
			upload.setSizeMax(maxFileSize);

			// Parse the request to get file items.
			List fileItems = upload.parseRequest(request);

			// Process the uploaded file items
			Iterator i = fileItems.iterator();

			htmlResponse += "<html>";
			htmlResponse += "<head>";
			htmlResponse += "<title>Servlet upload</title>";
			htmlResponse += "</head>";
			htmlResponse += "<body>";
			// collect all of the data input on the form
			Hashtable inputData = new Hashtable();
			while (i.hasNext()) {
				FileItem fi = (FileItem) i.next();
				// Get the uploaded file parameters
				String fieldName = fi.getFieldName();
				String fileName = fi.getName();
				String contentType = fi.getContentType();
				String value = fi.getString();
				boolean isInMemory = fi.isInMemory();
				long sizeInBytes = fi.getSize();
				if (!fi.isFormField()) {
					// Write the file
					if (fileName.lastIndexOf("\\") >= 0) {
						value = filePath
								+ fileName
										.substring(fileName.lastIndexOf("\\"));
						file = new File(value);
					} 
					else if (fileName.equals("")){
						continue;
					}
					else {
						value = filePath
								+ fileName
										.substring(fileName.lastIndexOf("\\") + 1);
						file = new File(value);
					}
					fi.write(file);
					htmlResponse += "Uploaded Filename: " + fileName + "  to "
							+ file + " <br>";
				} else
					System.err.println("Type is " + fi.getFieldName()
							+ fi.getString());
				htmlResponse += "Importing data: " + fieldName + "   " + value
						+ " <br>";
				
				//need to handle multiple files getting selected for upload
				if(inputData.get(fieldName)!=null)
					value = inputData.get(fieldName) + ";" + value;
				
				inputData.put(fieldName, value);

			}
			htmlResponse += "</body>";
			htmlResponse += "</html>";

			System.out.println(inputData);
			// time to run the import
			ImportDataProcessor importer = new ImportDataProcessor();
			importer.setBaseDirectory(DIHelper.getInstance().getProperty(
					"BaseFolder"));

			// figure out what type of import we need to do based on parameters
			// selected
			String methodString = inputData.get("importMethod") + "";
			ImportDataProcessor.IMPORT_METHOD importMethod = 
					methodString.equals("Create new database engine") ? ImportDataProcessor.IMPORT_METHOD.CREATE_NEW
					: methodString.equals("Add To existing database engine") ? ImportDataProcessor.IMPORT_METHOD.ADD_TO_EXISTING
							: methodString.equals("Modify/Replace data in existing engine") ? ImportDataProcessor.IMPORT_METHOD.OVERRIDE
									: null;
			String typeString = inputData.get("importType") + "";
			ImportDataProcessor.IMPORT_TYPE importType = 
					typeString.equals("Comma Seperated Value (.csv)") ? ImportDataProcessor.IMPORT_TYPE.CSV
					: typeString.equals("Microsoft Excel") ? ImportDataProcessor.IMPORT_TYPE.EXCEL
							: typeString.equals("NLP") ? ImportDataProcessor.IMPORT_TYPE.NLP
									: null;
			
			//call the right process method with correct parameters
			importer.runProcessor(importMethod, importType, inputData.get("uploadFile")+"", 
					inputData.get("customBaseURI")+"", inputData.get("newDBname")+"", 
					"","","","");
					//inputData.get("mapFile")+"", inputData.get("dbPropFile")+"", inputData.get("questionFile")+"", inputData.get("existingDBname")+"");
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (FileUploadException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return Response.status(200).entity(htmlResponse).build();
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
