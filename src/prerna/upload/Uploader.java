package prerna.upload;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

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
import org.apache.commons.io.FileUtils;

import prerna.ui.components.CSVMetamodelBuilder;
import prerna.ui.components.CSVPropFileBuilder;
import prerna.ui.components.ImportDataProcessor;
import prerna.util.DIHelper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

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

	public void writeFile(FileItem fi, File file){
		try {
			fi.write(file);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public List<FileItem> processRequest(@Context HttpServletRequest request)
	{
		List<FileItem> fileItems = null;
		try {
			DiskFileItemFactory factory = new DiskFileItemFactory();
			// maximum size that will be stored in memory
			factory.setSizeThreshold(maxMemSize);
			// Location to save data that is larger than maxMemSize.
			factory.setRepository(new File("c:\\temp"));
			// Create a new file upload handler
			ServletFileUpload upload = new ServletFileUpload(factory);
			// maximum file size to be uploaded.
			upload.setSizeMax(maxFileSize);

			// Parse the request to get file items
			fileItems = upload.parseRequest(request);
		} catch (FileUploadException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return fileItems;
	}

	public Hashtable<String, String> getInputData(List<FileItem> fileItems) 
	{
		// Process the uploaded file items
		Iterator<FileItem> iteratorFileItems = fileItems.iterator();

		// collect all of the data input on the form
		Hashtable<String, String> inputData = new Hashtable<String, String>();
		ArrayList<File> allLoadingFiles = new ArrayList<File>();
		File file;

		while(iteratorFileItems.hasNext()) 
		{
			FileItem fi = (FileItem) iteratorFileItems.next();
			// Get the uploaded file parameters
			String fieldName = fi.getFieldName();
			String fileName = fi.getName();
			String value = fi.getString();
			if (!fi.isFormField()) 
			{
				if(fileName.equals("")) {
					continue;
				}
				else {
					if(fieldName.equals("file"))
					{
						value = filePath + fileName.substring(fileName.lastIndexOf("\\") + 1);
						file = new File(value);
						writeFile(fi, file);
						allLoadingFiles.add(file);
						System.out.println( "Saved Filename: " + fileName + "  to "+ file);
					} 
				}
			}
			else 
			{
				System.err.println("Type is " + fi.getFieldName() + fi.getString());
			}
			//need to handle multiple files getting selected for upload
			if(inputData.get(fieldName) != null)
			{
				value = inputData.get(fieldName) + ";" + value;
			}
			inputData.put(fieldName, value);
		}

		return inputData;
	}

	@POST
	@Path("/csv/meta")
	@Produces("application/json")
	public Response uploadCSVFileToMeta(@Context HttpServletRequest request) 
	{
		File file;
		List<FileItem> fileItems = processRequest(request);
		// Process the uploaded file items
		Iterator<FileItem> iteratorFileItems = fileItems.iterator();

		// collect all of the data input on the form
		Hashtable<String, String> inputData = new Hashtable<String, String>();
		ArrayList<File> allLoadingFiles = new ArrayList<File>();
		ArrayList<File> propFiles = new ArrayList<File>();
		while(iteratorFileItems.hasNext()) 
		{
			FileItem fi = (FileItem) iteratorFileItems.next();
			// Get the uploaded file parameters
			String fieldName = fi.getFieldName();
			String fileName = fi.getName();
			String value = fi.getString();
			if (!fi.isFormField()) 
			{
				if(fileName.equals("")) {
					continue;
				}
				else {
					if(fieldName.equals("file"))
					{
						value = filePath + fileName.substring(fileName.lastIndexOf("\\") + 1);
						file = new File(value);
						writeFile(fi, file);
						allLoadingFiles.add(file);
						System.out.println( "Saved Filename: " + fileName + "  to "+ file);
					} else if(fieldName.equals("propFile")) {
						value = filePath + fileName.substring(fileName.lastIndexOf("\\") + 1);
						file = new File(value);
						writeFile(fi, file);
						propFiles.add(file);
						System.out.println( "Saved propfile: " + fileName + "  to "+ file);
					}
				}
			}
			else 
			{
				System.err.println("Type is " + fi.getFieldName() + fi.getString());
			}
			//need to handle multiple files getting selected for upload
			if(inputData.get(fieldName) != null)
			{
				value = inputData.get(fieldName) + ";" + value;
			}
			inputData.put(fieldName, value);
		}

		Hashtable<String, Object> returnHash = new Hashtable<String, Object>();

		CSVMetamodelBuilder builder = new CSVMetamodelBuilder();
		builder.setFiles(allLoadingFiles);
		Hashtable<String, Hashtable<String, LinkedHashSet<String>>> dataTypes = builder.returnDataTypes();
		returnHash.put("dataTypes", dataTypes);
		if(propFiles.size() > 0) { 
			builder.setPropFiles(propFiles);
			Hashtable<String, ArrayList<Hashtable<String, String[]>>> propData = builder.returnPropFileDataResults();
			returnHash.put("propData", propData);
		}

		if(!dataTypes.isEmpty()) {
			return Response.status(200).entity(getSO(returnHash)).build();
		} else {
			String outputText = "CSV Loading has failed.";
			return Response.status(400).entity(outputText).build();
		}
	}

	@POST
	@Path("/csv/upload")
	@Produces("text/html")
	public Response uploadCSVFile(@FormParam ("dbImportOption") String dbImportOption, 
			@FormParam ("filename") String filename,
			@FormParam ("filenameheaders") String headersList,
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

			if(!((String) mRow.get("selectedRelSubject").toString()).isEmpty() && !((String) mRow.get("relPredicate").toString()).isEmpty() && !((String) mRow.get("selectedRelObject").toString()).isEmpty())
			{
				propWriter.addRelationship((ArrayList<String>) mRow.get("selectedRelSubject"), mRow.get("relPredicate").toString(), (ArrayList<String>) mRow.get("selectedRelObject"));
			}
		}

		for(String str : prop) {
			Hashtable<String, Object> mRow = gson.fromJson(str, Hashtable.class);
			if(!((String) mRow.get("selectedPropSubject").toString()).isEmpty() && !((String) mRow.get("selectedPropObject").toString()).isEmpty() && !((String) mRow.get("selectedPropDataType").toString()).isEmpty())
			{
				propWriter.addProperty((ArrayList<String>) mRow.get("selectedPropSubject"), (ArrayList<String>) mRow.get("selectedPropObject"), (String) mRow.get("selectedPropDataType").toString());
			}
		}

		Hashtable<String, Object> headerHash = gson.fromJson(headersList, Hashtable.class);
		ArrayList<String> headers = (ArrayList<String>) headerHash.get("AllHeaders");

		propWriter.columnTypes(headers);
		Hashtable<String, String> propFile = propWriter.getPropHash(); 

		ImportDataProcessor importer = new ImportDataProcessor();
		importer.setPropHash(propFile);
		importer.setBaseDirectory(DIHelper.getInstance().getProperty("BaseFolder"));

		// figure out what type of import we need to do based on parameters
		String methodString = dbImportOption.toString();
		ImportDataProcessor.IMPORT_METHOD importMethod = 
				methodString.equals("Create new database engine") ? ImportDataProcessor.IMPORT_METHOD.CREATE_NEW
						: methodString.equals("addEngine") ? ImportDataProcessor.IMPORT_METHOD.ADD_TO_EXISTING
								: methodString.equals("modifyEngine") ? ImportDataProcessor.IMPORT_METHOD.OVERRIDE
										: null;

		//call the right process method with correct parameters
		boolean isSuccessful = false;
		if(methodString.equals("Create new database engine")) {
			isSuccessful = importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.CSV, filePath + "\\" + filename.toString(), 
					baseURI.toString(), dbName.toString(),"","","","");
		} else {
			isSuccessful = importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.CSV, filePath + "\\" + filename.toString(), 
					baseURI.toString(), "","","","", dbName.toString());
		}

		try {
			FileUtils.writeStringToFile(new File(DIHelper.getInstance().getProperty("BaseFolder") + "\\db\\" + dbName.toString() + "\\" + dbName.toString() + "_PROP.prop"), propWriter.getPropFile());
		} catch (IOException e) {
			e.printStackTrace();
		}

		String outputText = "";
		if(isSuccessful) {
			outputText = "CSV Loading was a success.";
			return Response.status(200).entity(outputText).build();
		} else {
			outputText = "CSV Loading has failed.";
			return Response.status(400).entity(outputText).build();
		}
	}


	@POST
	@Path("/excel/upload")
	@Produces("text/html")
	public Response uploadExcelFile(@Context HttpServletRequest request) 
	{
		List<FileItem> fileItems = processRequest(request);
		// collect all of the data input on the form
		Hashtable<String, String> inputData = getInputData(fileItems);
		
		System.out.println(inputData);
		// time to run the import
		ImportDataProcessor importer = new ImportDataProcessor();
		importer.setBaseDirectory(DIHelper.getInstance().getProperty("BaseFolder"));

		// figure out what type of import we need to do based on parameters
		// selected
		String methodString = inputData.get("importMethod") + "";
		ImportDataProcessor.IMPORT_METHOD importMethod = 
				methodString.equals("Create new database engine") ? ImportDataProcessor.IMPORT_METHOD.CREATE_NEW
						: methodString.equals("addEngine") ? ImportDataProcessor.IMPORT_METHOD.ADD_TO_EXISTING
								: methodString.equals("modifyEngine") ? ImportDataProcessor.IMPORT_METHOD.OVERRIDE
										: null;

		//call the right process method with correct parameters
		boolean isSuccessful = false;
		String dbName = "";
		if(methodString.equals("Create new database engine")) {
			dbName = inputData.get("newDBname");
			isSuccessful = importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.EXCEL, inputData.get("file")+"", 
					inputData.get("customBaseURI")+"", dbName,"","","","");
		} else {
			dbName = inputData.get("addDBname");
			isSuccessful = importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.EXCEL, inputData.get("file")+"", 
					inputData.get("customBaseURI")+"", "","","","", dbName);
		}

		String outputText = "";
		if(isSuccessful) {
			outputText = "Excel Loading was a success.";
			return Response.status(200).entity(outputText).build();
		} else {
			outputText = "Excel Loading has failed.";
			return Response.status(400).entity(outputText).build();
		}
	}

	@POST
	@Path("/nlp/upload")
	@Produces("text/html")
	public Response uploadNLPFile(@Context HttpServletRequest request) 
	{
		List<FileItem> fileItems = processRequest(request);
		// collect all of the data input on the form
		Hashtable<String, String> inputData = getInputData(fileItems);

		System.out.println(inputData);
		// time to run the import
		ImportDataProcessor importer = new ImportDataProcessor();
		importer.setBaseDirectory(DIHelper.getInstance().getProperty("BaseFolder"));

		// figure out what type of import we need to do based on parameters
		// selected
		String methodString = inputData.get("importMethod") + "";
		ImportDataProcessor.IMPORT_METHOD importMethod = 
				methodString.equals("Create new database engine") ? ImportDataProcessor.IMPORT_METHOD.CREATE_NEW
						: methodString.equals("addEngine") ? ImportDataProcessor.IMPORT_METHOD.ADD_TO_EXISTING
								: methodString.equals("modifyEngine") ? ImportDataProcessor.IMPORT_METHOD.OVERRIDE
										: null;

		//call the right process method with correct parameters
		boolean isSuccessful = false;
		String dbName = "";
		if(methodString.equals("Create new database engine")) {
			dbName = inputData.get("newDBname");
			isSuccessful = importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.EXCEL, inputData.get("file")+"", 
					inputData.get("customBaseURI")+"", dbName,"","","","");
		} else {
			dbName = inputData.get("addDBname");
			isSuccessful = importer.runProcessor(importMethod, ImportDataProcessor.IMPORT_TYPE.EXCEL, inputData.get("file")+"", 
					inputData.get("customBaseURI")+"", "","","","", dbName);
		}

		String outputText = "";
		if(isSuccessful) {
			outputText = "NLP Loading was a success.";
			return Response.status(200).entity(outputText).build();
		} else {
			outputText = "NLP Loading has failed.";
			return Response.status(400).entity(outputText).build();
		}

	}

	@POST
	@Path("/d2rq/upload")
	@Produces("text/html")
	public Response uploadD2RQFile(@Context HttpServletRequest request) 
	{
		List<FileItem> fileItems = processRequest(request);
		// collect all of the data input on the form
		Hashtable<String, String> inputData = getInputData(fileItems);
		
		System.out.println(inputData);
		// time to run the import
		ImportDataProcessor importer = new ImportDataProcessor();
		importer.setBaseDirectory(DIHelper.getInstance().getProperty("BaseFolder"));

		// figure out what type of import we need to do based on parameters
		// selected
		String methodString = inputData.get("importMethod") + "";
		ImportDataProcessor.IMPORT_METHOD importMethod = 
				methodString.equals("Create new database engine") ? ImportDataProcessor.IMPORT_METHOD.CREATE_NEW
						: methodString.equals("addEngine") ? ImportDataProcessor.IMPORT_METHOD.ADD_TO_EXISTING
								: methodString.equals("modifyEngine") ? ImportDataProcessor.IMPORT_METHOD.OVERRIDE
										: null;

		//call the right process method with correct parameters
		boolean isSuccessful = importer.processNewRDBMS((String) inputData.get("customBaseURI"), (String) inputData.get("file"), 
				(String) inputData.get("newDBname"), (String) inputData.get("dbType"), (String) inputData.get("dbUrl"), 
				(String) inputData.get("accountName"), (char[]) inputData.get("accountPassword").toCharArray());

		String outputText = "";
		if(isSuccessful) {
			outputText = "R2RQ Loading was a success.";
			return Response.status(200).entity(outputText).build();
		} else {
			outputText = "R2RQ Loading has failed.";
			return Response.status(400).entity(outputText).build();
		}
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
