package prerna.upload;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.commons.fileupload.FileItem;

import prerna.web.services.util.WebUtility;

public class FileUploader extends Uploader{

	/*
	 * Moving a file onto the BE cannot be performed through PKQL
	 * Thus, we still expose "drag and drop" of a file through a rest call
	 * However, this is only used to push the file to the BE server, the actual
	 * processing of the file to create/add to a data frame occurs through PKQL
	 */
	
	@POST
	@Path("determineDataTypesForFile")
	public Response determineDataTypesForFile(@Context HttpServletRequest request) {

		try {
			List<FileItem> fileItems = processRequest(request);
			
			// collect all of the data input on the form
			Hashtable<String, String> inputData = getInputData(fileItems);
			Map<String, Object> retObj = generateDataTypes(inputData);
//			return Response.status(200).entity(WebUtility.getSO(retObj)).build();
			return WebUtility.getResponse(retObj, 200);

		} catch(Exception e) {
			e.printStackTrace();
			HashMap<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("errorMessage", "Error processing new data");
//			return Response.status(400).entity(WebUtility.getSO(errorMap)).build();
			return WebUtility.getResponse(errorMap, 400);
		}
	}
	
	@Override
	/**
	 * Extract the data to generate the file being passed from the user to the BE server
	 */
	protected Hashtable<String, String> getInputData(List<FileItem> fileItems) 
	{
		// Process the uploaded file items
		Iterator<FileItem> iteratorFileItems = fileItems.iterator();

		// collect all of the data input on the form
		Hashtable<String, String> inputData = new Hashtable<String, String>();
		File file;

		while(iteratorFileItems.hasNext()) 
		{
			FileItem fi = (FileItem) iteratorFileItems.next();
			// Get the uploaded file parameters
			String fieldName = fi.getFieldName();
			String fileName = fi.getName();
			String value = fi.getString();
			if (!fi.isFormField()) {
				if(fileName.equals("")) {
					continue;
				}
				else {
					if(fieldName.equals("file")) {
						// need to clean the fileName to not have ";" since we split on that in upload data
						fileName = fileName.replace(";", "");
						Date date = new Date();
						String modifiedDate = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSSS").format(date);
						value = filePath + "\\\\" + fileName.substring(fileName.lastIndexOf("\\") + 1, fileName.lastIndexOf(".")).replace(" ", "") + modifiedDate + fileName.substring(fileName.lastIndexOf("."));
						file = new File(value);
						writeFile(fi, file);
						System.out.println( "Saved Filename: " + fileName + "  to "+ file);
					}
				}
			} else if(fieldName.equals("file")) { // its a file, but not in a form
				System.err.println("Writing input string into file...");
				Date date = new Date();
				String modifiedDate = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSSS").format(date);
				value = filePath + "\\\\FileString_" + modifiedDate;
				file = new File(value);
				writeFile(fi, file);
				System.out.println( "Created new file...");
			}
			//need to handle multiple files getting selected for upload
			if(inputData.get(fieldName) != null)
			{
				value = inputData.get(fieldName) + ";" + value;
			}
			inputData.put(fieldName, value);
			fi.delete();
		}

		return inputData;
	}
}
