package prerna.upload;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.commons.fileupload.FileItem;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import prerna.cache.FileStore;
import prerna.ds.TableDataFrameFactory;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.ui.components.playsheets.datamakers.DataMakerComponent;
import prerna.ui.components.playsheets.datamakers.IDataMaker;
import prerna.util.Utility;
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
			return Response.status(200).entity(WebUtility.getSO(retObj)).build();

		} catch(Exception e) {
			e.printStackTrace();
			HashMap<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("errorMessage", "Error processing new data");
			return Response.status(400).entity(WebUtility.getSO(errorMap)).build();
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
	

	
	////////////////////////// THE BELOW TEXT IS NO LONGER USED /////////////////////////////////////
	/*
	 * Used to be used back when "drag and drop" file was done through a rest call
	 * This functionality has been moved to PKQL so this is never no longer invoked
	 */
	
	
	/**
	 * Create a data frame based on a file
	 * @param request
	 * @return
	 */
	//TODO: change to generateTableFromText
	@POST
	@Path("generateTableDataframe")
	public Response generateTableFromFile(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		try {
			String uniqueFileKey = form.getFirst("uniqueFileKey");
			String delimiter = form.getFirst("delimiter");
			String dataFrameType = form.getFirst("dataFrameType");
			String dataTypeMapStr = form.getFirst("headerData");
			String mainColumnStr = form.getFirst("mainColumn");
			
			Gson gson = new Gson();
			Map<String, Map<String, String>> dataTypeMap = gson.fromJson(dataTypeMapStr, new TypeToken<Map<String, Map<String, String>>>() {}.getType());
			
			// process the data types to be accurate
			if(dataTypeMap != null && !dataTypeMap.isEmpty()) {
				for(String sheet : dataTypeMap.keySet()) {
					Map<String, String> headerTypes = dataTypeMap.get(sheet);
					Map<String, String> newHeaderTypes = new Hashtable<String, String>();
					for(String header : headerTypes.keySet()) {
						newHeaderTypes.put(header, Utility.getRawDataType(headerTypes.get(header)));
					}
					// override existing with new
					dataTypeMap.put(sheet, newHeaderTypes);
				}
			}
			
			String fileName = FileStore.getInstance().get(uniqueFileKey);
			// generate tinker frame from 
			if(delimiter == null || delimiter.isEmpty()) {
				delimiter = "\t";
			}
			if(dataFrameType == null || dataFrameType.isEmpty()) {
				dataFrameType = "H2";
			}
			
			Map<String, String> mainCol = new HashMap<String, String>();
			if(mainColumnStr != null) {
				mainCol = gson.fromJson(mainColumnStr, new TypeToken<Map<String, String>>() {}.getType());
			}
		
			IDataMaker table = TableDataFrameFactory.generateDataFrameFromFile(fileName, delimiter, dataFrameType, dataTypeMap, mainCol);
			String dataFrame;
			if(dataFrameType.equalsIgnoreCase("H2")) {
				dataFrame = "H2Frame";
			} else {
				dataFrame = "TinkerFrame";
			}
		
			String insightId = generateInsight(table, dataFrame);
			Map<String, String> retObj = new HashMap<String, String>();
			retObj.put("insightID", insightId);
			
			deleteFilesFromServer(new String[]{fileName});
			
			return Response.status(200).entity(WebUtility.getSO(retObj)).build();
		} catch(Exception e) {
			e.printStackTrace();
			HashMap<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("errorMessage", "Error processing new data");
			return Response.status(400).entity(WebUtility.getSO(errorMap)).build();
		}
	}
	
	private String generateInsight(IDataMaker dm, String dataMakerName) {
		Insight in = new Insight(null, dataMakerName, "Grid");
		DataMakerComponent dmc = new DataMakerComponent(""); //dmc currently doesn't have a location since it is not saved yet
		Vector<DataMakerComponent> dmcList = new Vector<DataMakerComponent>();
		dmcList.add(dmc);
		in.setDataMakerComponents(dmcList);
		in.setDataMaker(dm);
		in.setIsDbInsight(false);
		String insightId = InsightStore.getInstance().put(in);
		return insightId;
	}
}
