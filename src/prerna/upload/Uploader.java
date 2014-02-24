package prerna.upload;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;

import prerna.ui.components.CSVMetamodelBuilder;
import prerna.ui.components.CSVPropFileBuilder;
import prerna.ui.components.ImportDataProcessor;
import prerna.util.DIHelper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Servlet implementation class Uploader
 */
public class Uploader extends HttpServlet {

	int maxFileSize = 10000 * 1024;
	int maxMemSize = 4 * 1024;
	String output = "";

	String filePath;

	public void setFilePath(String filePath){
		this.filePath = filePath;
	}

	@POST
	@Path("/csv/meta")
	@Produces("application/json")
	public StreamingOutput uploadCSVFileToMeta(@Context HttpServletRequest request) {
		Hashtable<String, Hashtable<String, Set<String>>> dataTypes	= new Hashtable<String, Hashtable<String, Set<String>>>();
		try {
			File file = null;
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

			// collect all of the data input on the form
			Hashtable inputData = new Hashtable();
			ArrayList<File> allFiles = new ArrayList<File>();
			while (i.hasNext()) {
				FileItem fi = (FileItem) i.next();
				// Get the uploaded file parameters
				String fieldName = fi.getFieldName();
				String fileName = fi.getName();
				String value = fi.getString();
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
					allFiles.add(file);
					System.out.println( "CSV importer saved Filename: " + fileName + "  to "+ file);
				} else
					System.err.println("Type is " + fi.getFieldName()
							+ fi.getString());
				System.out.println( "Importing data: " + fieldName + "   " + value);

				//need to handle multiple files getting selected for upload
				if(inputData.get(fieldName)!=null)
					value = inputData.get(fieldName) + ";" + value;

				inputData.put(fieldName, value);
			}

			CSVMetamodelBuilder builder = new CSVMetamodelBuilder();
			builder.setFiles(allFiles);
			dataTypes = builder.returnDataTypes();

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

		return getSO(dataTypes);
	}

	@POST
	@Path("/csv/upload")
	@Produces("text/html")
	public Response uploadCSVFile(@FormParam ("dbImportOption") String dbImportOption, 
			@FormParam ("filename") String filename,
			@FormParam ("dbName") String dbName,
			@FormParam ("designateBaseUri") String baseURI,
			@FormParam ("relationship") List<String> rel,
			@FormParam ("property") List<String> prop)
	{

		Gson gson = new Gson();
		CSVPropFileBuilder propWriter = new CSVPropFileBuilder();

		for(String str : rel) {
			// subject and object keys link to array list for concatenations, while the predicate is always a string
			Hashtable<String, Object> mRow = gson.fromJson(str, Hashtable.class);
			System.out.println(mRow);
			propWriter.addRelationship((ArrayList<String>) mRow.get("selectedRelSubject"),mRow.get("relPredicate").toString(), (ArrayList<String>) mRow.get("selectedRelObject"));
		}
		
		for(String str: prop) {
			Hashtable<String, Object> mRow = gson.fromJson(str, Hashtable.class);
			System.out.println(mRow);
			propWriter.addProperty((ArrayList<String>) mRow.get("selectedPropSubject"), (ArrayList<String>) mRow.get("selectedPropObject"), (String) mRow.get("selectedPropDataType"));
		}
			
		propWriter.columnTypes(filePath + filename.toString());
		Hashtable<String, String> propFile = propWriter.getPropHash(); 
		
		ImportDataProcessor importer = new ImportDataProcessor();
		importer.setPropHash(propFile);
		importer.setBaseDirectory(DIHelper.getInstance().getProperty("BaseFolder"));

		// figure out what type of import we need to do based on parameters
		String methodString = dbImportOption.toString();
		ImportDataProcessor.IMPORT_METHOD importMethod = 
				methodString.equals("Create new database engine") ? ImportDataProcessor.IMPORT_METHOD.CREATE_NEW
						: methodString.equals("Add To existing database engine") ? ImportDataProcessor.IMPORT_METHOD.ADD_TO_EXISTING
								: methodString.equals("Modify/Replace data in existing engine") ? ImportDataProcessor.IMPORT_METHOD.OVERRIDE
										: null;

		//call the right process method with correct parameters
		importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.CSV, filePath + filename.toString()+"", 
				baseURI.toString(), dbName.toString(),"","","","");

		return Response.status(200).entity("Holla!").build();
	}


	@POST
	@Path("/excel/upload")
	@Produces("text/html")
	public Response uploadExcelFile(@Context HttpServletRequest request) {
		String htmlResponse = "";
		try {
			File file;

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
				String value = fi.getString();
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

			//call the right process method with correct parameters
			importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.EXCEL, inputData.get("uploadFile")+"", 
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
}
