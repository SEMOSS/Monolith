package prerna.upload;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.io.FilenameUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.mime.MimeTypes;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.io.connector.antivirus.VirusScannerUtils;
import prerna.io.connector.antivirus.VirusScanningException;
import prerna.om.HeadersException;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.ThreadStore;
import prerna.project.api.IProject;
import prerna.util.AssetUtility;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/uploadFile")
public class FileUploader extends Uploader {

	private static final Logger classLogger = LogManager.getLogger(FileUploader.class);
	/*
	 * Moving a file onto the BE cannot be performed through pixel
	 * Thus, we still expose "drag and drop" of a file through a rest call
	 * However, this is only used to push the file to the BE server, the actual
	 * processing of the file to create/add to a data frame occurs through pixel
	 */
	
	@POST
	@Path("/headerCheck")
	@Produces("application/json")
	public Response checkUserDefinedHeaders(MultivaluedMap<String, String> form) {
		Gson gson = new Gson();
		
		// this will let me know if I should expect the csv data type map or the excel
		// data type map
		// this is really annoying... 
		// TODO: really should consider consolidating the formats it will make csv format look dumb 
		// since its a useless additional key but then I wouldn't need to have the bifurcation in 
		// formats here and bifurcation in formats in the ImportOptions object as well
		String type = form.getFirst("uploadType").toUpperCase();
		String headersToCheckString = form.getFirst("userHeaders");
		// grab the checker
		HeadersException headerChecker = HeadersException.getInstance();
		if(type.equalsIgnoreCase("EXCEL")) {
			List<Map<String, Map<String, String>>> invalidHeadersList = new ArrayList<>();
			
			// each entry (outer map object) in the list if a workbook
			// each key in that map object is the sheetName for that given workbook
			// the list are the headers inside that sheet
			List<Map<String, String[]>> userDefinedHeadersMap = null;
			try {
				userDefinedHeadersMap = gson.fromJson(headersToCheckString, new TypeToken<List<Map<String, String[]>>>() {}.getType());
			} catch(Exception e) {
				classLogger.error(Constants.STACKTRACE, e);
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Invalid format passed for user defined headers: " + headersToCheckString);
				return WebUtility.getResponse(errorMap, 400);
			}
			
			// iterate through each workbook
			for(Map<String, String[]> excelWorkbook : userDefinedHeadersMap) {
				Map<String, Map<String, String>> invalidHeadersMap = new Hashtable<>();
				
				for(String sheetName : excelWorkbook.keySet()) {
					// grab all the headers for the given sheet
					String[] userHeaders = excelWorkbook.get(sheetName);

					// now we need to check all of these headers
					for(int colIdx = 0; colIdx < userHeaders.length; colIdx++) {
						String userHeader = userHeaders[colIdx];
						Map<String, String> badHeaderMap = new Hashtable<>();
						if(headerChecker.isIllegalHeader(userHeader)) {
							badHeaderMap.put(userHeader, "This header name is a reserved word");
						} else if(headerChecker.containsIllegalCharacter(userHeader)) {
							badHeaderMap.put(userHeader, "Header names cannot contain +%@;");
						} else if(headerChecker.isDuplicated(userHeader, userHeaders, colIdx)) {
							badHeaderMap.put(userHeader, "Cannot have duplicate header names");
						}
						
						// map is filled in only if the header is bad
						if(!badHeaderMap.isEmpty()) {
							// need to make sure we do not override existing bad headers stored
							// within the map
							Map<String, String> invalidHeadersForSheet = null;
							if(invalidHeadersMap.containsKey(sheetName)) {
								invalidHeadersForSheet = invalidHeadersMap.get(sheetName);
							} else {
								invalidHeadersForSheet = new Hashtable<>();
							}
							
							// now add in the bad header for the file map
							invalidHeadersForSheet.putAll(badHeaderMap);
							// now store it in the overall object
							invalidHeadersMap.put(sheetName, invalidHeadersForSheet);
						}
					}
				}
				
				// now store the invalid headers map inside the list
				// even if it is empty, we need to store it since the FE does this based on indices
				invalidHeadersList.add(invalidHeadersMap);
			}
			return WebUtility.getResponse(invalidHeadersList, 200);
		} 
		else 
		{
			Map<String, Map<String, String>> invalidHeadersMap = new Hashtable<>();
			
			// the key is for each file name
			// the list are the headers inside that file
			Map<String, String[]> userDefinedHeadersMap = null;
			try {
				userDefinedHeadersMap = gson.fromJson(headersToCheckString, new TypeToken<Map<String, String[]>>() {}.getType());
			} catch(Exception e) {
				classLogger.error(Constants.STACKTRACE, e);
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Invalid format passed for user defined headers: " + headersToCheckString);
				return WebUtility.getResponse(errorMap, 400);
			}
			
			for(String fileName : userDefinedHeadersMap.keySet()) {
				String[] userHeaders = userDefinedHeadersMap.get(fileName);
				
				// now we need to check all of these headers
				// now we need to check all of these headers
				for(int colIdx = 0; colIdx < userHeaders.length; colIdx++) {
					String userHeader = userHeaders[colIdx];
					Map<String, String> badHeaderMap = new Hashtable<>();
					if(headerChecker.isIllegalHeader(userHeader)) {
						badHeaderMap.put(userHeader, "This header name is a reserved word");
					} else if(headerChecker.containsIllegalCharacter(userHeader)) {
						badHeaderMap.put(userHeader, "Header names cannot contain +%@;");
					} else if(headerChecker.isDuplicated(userHeader, userHeaders, colIdx)) {
						badHeaderMap.put(userHeader, "Cannot have duplicate header names");
					}
					
					// map is filled in only if the header is bad
					if(!badHeaderMap.isEmpty()) {
						// need to make sure we do not override existing bad headers stored
						// within the map
						Map<String, String> invalidHeadersForFile = null;
						if(invalidHeadersMap.containsKey(fileName)) {
							invalidHeadersForFile = invalidHeadersMap.get(fileName);
						} else {
							invalidHeadersForFile = new Hashtable<>();
						}
						
						// now add in the bad header for the file map
						invalidHeadersForFile.putAll(badHeaderMap);
						// now store it in the overall object
						invalidHeadersMap.put(fileName, invalidHeadersForFile);
					}
				}
			}
			return WebUtility.getResponse(invalidHeadersMap, 200);
		}
	}
	
	
	@POST
	@Path("baseUpload")
	public Response baseUpload(@Context ServletContext context, @Context HttpServletRequest request, 
			@QueryParam("insightId") String insightId,
			@QueryParam("path") String relativePath, 
			@QueryParam("projectId") String projectId,
			@QueryParam("engineId") String engineId) {
		Insight in = InsightStore.getInstance().get(insightId);
		if(in == null) {
			HashMap<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Session could not be validated in order to upload files");
			return WebUtility.getResponse(errorMap, 400);
		}
			
		User user = in.getUser();
		if(user == null) {
			HashMap<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Session could not be validated in order to upload files");
			return WebUtility.getResponse(errorMap, 400);
		}
		
		if(user.isAnonymous() && !AbstractSecurityUtils.anonymousUserUploadData()) {
			HashMap<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must be logged in to upload files");
			return WebUtility.getResponse(errorMap, 400);
		}
		
		if(user.isAnonymous() && in.isSavedInsight()) {
			HashMap<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must be logged in to upload files to a saved insight");
			return WebUtility.getResponse(errorMap, 400);
		}
		
		if(in.isSavedInsight() && !SecurityInsightUtils.userCanEditInsight(user, in.getProjectId(), in.getRdbmsId())) {
			HashMap<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User does not edit access for this insight");
			return WebUtility.getResponse(errorMap, 400);
		}
		
		if(AbstractSecurityUtils.adminSetPublisher() && !SecurityQueryUtils.userIsPublisher(user)) {
			HashMap<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "User does not have permission to publish data. Please reach out to the admin to get proper access");
			return WebUtility.getResponse(errorMap, 400);
		}
		
		if(projectId != null && !projectId.equalsIgnoreCase("user")) {
			if (!SecurityProjectUtils.userCanEditProject(in.getUser(), projectId)) {
				HashMap<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, "User does not have permission for this project.");
				return WebUtility.getResponse(errorMap, 400);
			}
		}
		
		if(engineId != null) {
			if (!SecurityEngineUtils.userCanEditEngine(in.getUser(), engineId)) {
				HashMap<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, "User does not have permission for this engine.");
				return WebUtility.getResponse(errorMap, 400);
			}
		}
		
		ThreadStore.setSessionId(request.getSession().getId());
		try {
			List<FileItem> fileItems = processRequest(context, request, insightId);
			// collect all of the data input on the form
			List<Map<String, String>> inputData = getBaseUploadData(fileItems, in, relativePath, projectId, engineId, user);
			// clear the thread store
			return WebUtility.getResponse(inputData, 200);
		} catch(VirusScanningException e) {
			classLogger.error(Constants.STACKTRACE, e);
			HashMap<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			HashMap<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error uploading file. Error = " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} finally {
			ThreadStore.remove();
		}
	}
	
	/**
	 * Method to parse just files and move to the server
	 * @param fileItems		a list of maps containing the file name and file location
	 * @param in
	 * @param relativePath
	 * @param projectId
	 * @param engineId
	 * @param user
	 * @return
	 * @throws VirusScanningException
	 * @throws IOException
	 */
	private List<Map<String, String>> getBaseUploadData(List<FileItem> fileItems, Insight in, String relativePath, String projectId, String engineId, User user) throws VirusScanningException, IOException {
		// get base asset folder
		String assetFolder = null;
		String fePath = DIR_SEPARATOR;
		if (projectId != null) {
			if(projectId.equals("user")) {
				AuthProvider provider = user.getPrimaryLogin();
				projectId = user.getAssetProjectId(provider);
				String projectName = "Asset";
				assetFolder = AssetUtility.getUserAssetAndWorkspaceBaseFolder(projectName, projectId);
			} else {
				IProject project = Utility.getProject(projectId);
				assetFolder = AssetUtility.getProjectBaseFolder(project.getProjectName(), projectId);
			}
		} else if (engineId != null) {
			//TODO: build out
			//TODO: build out
			//TODO: build out
			//TODO: build out

		} else {
			assetFolder = in.getInsightFolder();
		}
		String filePath = assetFolder;
		// add relative path
		if (relativePath != null) {
			filePath = assetFolder + DIR_SEPARATOR + Utility.normalizePath(relativePath);
			fePath += relativePath;
		}
		File fileDir = new File( Utility.normalizePath(filePath));
		if (!fileDir.exists()) {
			Boolean success =fileDir.mkdirs();
			if(!success) {
				classLogger.info("Unable to make direction at location: " + Utility.cleanLogString(filePath));
			}
		}
		
		Iterator<FileItem> iteratorFileItems = fileItems.iterator();
		// collect all of the data input on the form
		List<Map<String, String>> retData = new ArrayList<Map<String, String>>();

		while(iteratorFileItems.hasNext()) 
		{
			FileItem fi = (FileItem) iteratorFileItems.next();
			if (!fi.isFormField()) {
				// Get the uploaded file parameters
				String fieldName = fi.getFieldName();
				String name = Utility.inputSanitizer(fi.getName());
				String fileExtension = FilenameUtils.getExtension(name);
				String contentType = fi.getContentType();
				MimeType type = null;
				if(fileExtension == null || fileExtension.isEmpty()) {
					try {
						type = MimeTypes.getDefaultMimeTypes().forName(contentType);
						name += type.getExtension();
					} catch (MimeTypeException e) {
						classLogger.error(Constants.STACKTRACE, e);
					}
				}
				
				// we need the key to be file
				if(!fieldName.equals("file")) {
					// delete the field
					fi.delete();
					continue;
				}
				
				// Check for viruses on upload
				checkForViruses(fi);
			
//				String fileLocation = null;
//				String fileSuffix = null;
//				Date date = new Date();
//				String modifiedDate = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSSS").format(date);
				// account for upload of an h2
				// the connection url requires it to end with .mv.db
				// otherwise it errors
//				if(name.endsWith(".mv.db")) {
//					fileSuffix = FilenameUtils.getBaseName(name).trim().replace(".mv",  "").replace(" ", "_") 
//							+ "_____UNIQUE" + modifiedDate + ".mv.db";
//				} else {
//					fileSuffix = FilenameUtils.getBaseName(name).trim().replace(" ", "_") 
//							+ "_____UNIQUE" + modifiedDate + "." + FilenameUtils.getExtension(name);
//				}
//				fileLocation = filePath + DIR_SEPARATOR + fileSuffix;
				
				String fileLocation = Utility.getUniqueFilePath(filePath, name);
				File file = new File( Utility.normalizePath(fileLocation));
				
				// instead of adding unique
				// we will do what a normal OS system does
				writeFile(fi, file);

				String savedName = FilenameUtils.getName(fileLocation);
				Map<String, String> fileMap = new HashMap<String, String>();
				fileMap.put("fileName", savedName);
				if(fePath.endsWith(DIR_SEPARATOR)) {
					fileMap.put("fileLocation", fePath + savedName);
				} else {
					fileMap.put("fileLocation", fePath + DIR_SEPARATOR + savedName);
				}
				retData.add(fileMap);
			} else if(fi.getFieldName().equals("file")) { 
				// its a file, but not in a form
				// i.e. this is a person copy/pasting 
				// the values directly 
				classLogger.info("Writing Input To File");
//				Date date = new Date();
//				String modifiedDate = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSSS").format(date);
//				String fileSuffix = "FileString_" + modifiedDate;;
//				String fileLocation = filePath + DIR_SEPARATOR + fileSuffix;
				
				// Check for viruses on upload
				checkForViruses(fi);
				
				String fileLocation = Utility.getUniqueFilePath(filePath, "AutoGeneratedFile.txt");
					
				File file = new File(Utility.normalizePath( fileLocation));
				writeFile(fi, file);
				classLogger.info(Utility.cleanLogString("Saved Pasted Data To "+ file));
				
				String savedName = FilenameUtils.getName(fileLocation);
				Map<String, String> fileMap = new HashMap<String, String>();
				fileMap.put("fileName", savedName);
				if(fePath.endsWith(DIR_SEPARATOR)) {
					fileMap.put("fileLocation", fePath + savedName);
				} else {
					fileMap.put("fileLocation", fePath + DIR_SEPARATOR + savedName);
				}
				retData.add(fileMap);
			}
			// delete the field
			fi.delete();
		}

		return retData;
	}
	
	/**
	 * 
	 * @param fi
	 */
	private void checkForViruses(FileItem fi) throws VirusScanningException, IOException {
		if (Utility.isVirusScanningEnabled()) {
			try {
				Map<String, Collection<String>> viruses = VirusScannerUtils.getViruses(fi.getName(), fi.getInputStream());
				
				if (!viruses.isEmpty()) {
					classLogger.warn(Utility.cleanLogString("Virus scanner errors map for " + fi.getName() + " : " + viruses));
					String error = "Detected " + viruses.size() + " virus";
					
					if (viruses.size() > 1) {
						error = error + "es";
					}
					
					error += ". If you believe this is an error, please contact an administrator.";
					
					throw new VirusScanningException(error);
				}
			} catch(VirusScanningException e) {
				throw e;
			} catch (IOException e) {
				classLogger.error(Constants.STACKTRACE, e);
				throw new IllegalArgumentException("Could not read file item.");
			}
		}
	}
}
