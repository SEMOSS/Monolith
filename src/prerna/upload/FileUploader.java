package prerna.upload;

import java.io.File;
import java.io.IOException;
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
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import prerna.cache.FileStore;
import prerna.poi.main.helper.CSVFileHelper;
import prerna.poi.main.helper.FileHelperUtil;
import prerna.poi.main.helper.XLFileHelper;
import prerna.poi.main.helper.excel.ExcelBlock;
import prerna.poi.main.helper.excel.ExcelRange;
import prerna.poi.main.helper.excel.ExcelSheetPreProcessor;
import prerna.poi.main.helper.excel.ExcelWorkbookFilePreProcessor;
import prerna.web.services.util.WebUtility;

public class FileUploader extends Uploader {

	private static final Logger LOGGER = LogManager.getLogger(FileUploader.class);
	/*
	 * Moving a file onto the BE cannot be performed through PKQL
	 * Thus, we still expose "drag and drop" of a file through a rest call
	 * However, this is only used to push the file to the BE server, the actual
	 * processing of the file to create/add to a data frame occurs through PKQL
	 */
	
	@POST
	@Path("baseUpload")
	public Response uploadFile(@Context HttpServletRequest request) {
		try {
			List<FileItem> fileItems = processRequest(request);
			// collect all of the data input on the form
			Hashtable<String, String> inputData = getInputData(fileItems);
			return WebUtility.getResponse(inputData, 200);
		} catch(Exception e) {
			e.printStackTrace();
			HashMap<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("errorMessage", "Error moving file to server");
			return WebUtility.getResponse(errorMap, 400);
		}
	}

	@POST
	@Path("determineDataTypesForFile")
	public Response determineDataTypesForFile(@Context HttpServletRequest request) {
		try {
			List<FileItem> fileItems = processRequest(request);
			// collect all of the data input on the form
			Hashtable<String, String> inputData = getInputData(fileItems);
			Map<String, Object> retObj = generateDataTypes(inputData);
			return WebUtility.getResponse(retObj, 200);
		} catch(Exception e) {
			e.printStackTrace();
			HashMap<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("errorMessage", "Error processing new data");
			return WebUtility.getResponse(errorMap, 400);
		}
	}


	/**
	 * 
	 * @param inputData
	 * @return
	 * @throws IOException
	 */
	protected static Map<String, Object> generateDataTypes(Map<String, String> inputData) throws IOException {
		Map<String, Object> retObj = new HashMap<String, Object>();

		String fileLoc = inputData.get("file");
		String uniqueStorageId = FileStore.getInstance().put(fileLoc);
		retObj.put("uniqueFileKey", uniqueStorageId);
		if(fileLoc.endsWith(".xlsx") || fileLoc.endsWith(".xlsm")) {
			
			// new approach
			{
				Map<String, Map<String, Object>> newPayload = new HashMap<String, Map<String, Object>>();
				
				ExcelWorkbookFilePreProcessor preProcessor = new ExcelWorkbookFilePreProcessor();
				preProcessor.parse(fileLoc);
				Map<String, ExcelSheetPreProcessor> sProcessors = preProcessor.getSheetProcessors();
				for(String sheet : sProcessors.keySet()) {
					ExcelSheetPreProcessor processor = sProcessors.get(sheet);
					List<ExcelBlock> blocks = processor.getAllBlocks();
					
					Map<String, Object> rangeInfo = new HashMap<String, Object>();
					
					for(ExcelBlock block : blocks) {
						List<ExcelRange> ranges = block.getRanges();
						for(ExcelRange r : ranges) {
							String rSyntax = r.getRangeSyntax();
							String[] origHeaders = processor.getRangeHeaders(r);
							String[] cleanedHeaders = processor.getCleanedRangeHeaders(r);
							
							Object[][] rangeTypes = block.getRangeTypes(r);
							Map[] retMaps = FileHelperUtil.generateDataTypeMapsFromPrediction(cleanedHeaders, rangeTypes);
							Map<String, Map> typeMap = new HashMap<String, Map>();
							typeMap.put("dataTypes", retMaps[0]);
							typeMap.put("additionalDataTypes", retMaps[1]);
							
							Map<String, Object> rangeMap = new HashMap<String, Object>();
							rangeMap.put("headers", origHeaders);
							rangeMap.put("cleanHeaders", cleanedHeaders);
							rangeMap.put("types", typeMap);
							
							rangeInfo.put(rSyntax, rangeMap);
						}
					}
					
					// add all ranges in the sheet
					newPayload.put(sheet, rangeInfo);
				}
			
				// put in return object
				retObj.put("newPayload", newPayload);
				preProcessor.clear();
			}
			
			// old
			{
				XLFileHelper helper = new XLFileHelper();
				helper.parse(fileLoc);
	
				String[] sheets = helper.getTables();
				for(int i = 0; i < sheets.length; i++) {
					String sheetName = sheets[i];
					String[] headers = helper.getHeaders(sheetName);
					Map[] retMaps = FileHelperUtil.generateDataTypeMapsFromPrediction(headers, helper.predictTypes(sheetName));
	
					Map<String, Map> innerObj = new HashMap<String, Map>();
					innerObj.put("dataTypes", retMaps[0]);
					innerObj.put("additionalDataTypes", retMaps[1]);
					retObj.put(sheetName, innerObj);
				}
				
				helper.clear();
			}
		} else {
			String delimiter = inputData.get("delimiter");
			// generate tinker frame from 
			if(inputData.get("delimiter") == null || inputData.get("delimiter").isEmpty()) {
				delimiter = "\t";
			}

			CSVFileHelper helper = new CSVFileHelper();
			helper.setDelimiter(delimiter.charAt(0));
			helper.parse(fileLoc);

			String[] headers = helper.getHeaders();
			Map[] retMaps = FileHelperUtil.generateDataTypeMapsFromPrediction(headers, helper.predictTypes());

			Map<String, Map> innerObj = new HashMap<String, Map>();
			innerObj.put("dataTypes", retMaps[0]);
			innerObj.put("additionalDataTypes", retMaps[1]);
			retObj.put(CSV_FILE_KEY, innerObj);
			retObj.put("delimiter", delimiter);
			helper.clear();
		}

		return retObj;
	}


	/**
	 * Extract the data to generate the file being passed from the user to the BE server
	 */
	@Override
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
						value = filePath + "\\\\" + fileName.substring(fileName.lastIndexOf("\\") + 1, fileName.lastIndexOf(".")).replace(" ", "") + "_____UNIQUE" + modifiedDate + fileName.substring(fileName.lastIndexOf("."));
						file = new File(value);
						writeFile(fi, file);
						LOGGER.info("Saved Filename: " + fileName + "  to "+ file);
					}
				}
			} else if(fieldName.equals("file")) { // its a file, but not in a form
				System.err.println("Writing input string into file...");
				Date date = new Date();
				String modifiedDate = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSSS").format(date);
				value = filePath + "\\\\FileString_" + modifiedDate;
				file = new File(value);
				writeFile(fi, file);
				LOGGER.info( "Created new file...");
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
