package prerna.upload;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.io.FilenameUtils;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.ThreadStore;
import prerna.web.services.util.WebUtility;

public class FileUploader extends Uploader {

	private static final Logger LOGGER = LogManager.getLogger(FileUploader.class);
	/*
	 * Moving a file onto the BE cannot be performed through pixel
	 * Thus, we still expose "drag and drop" of a file through a rest call
	 * However, this is only used to push the file to the BE server, the actual
	 * processing of the file to create/add to a data frame occurs through pixel
	 */
	
	@POST
	@Path("baseUpload")
	public Response uploadFile(@Context HttpServletRequest request, @QueryParam("insightId") String insightId) {
		Insight in = InsightStore.getInstance().get(insightId);
		if(in == null) {
			HashMap<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("errorMessage", "Session could not be validated in order to upload files");
			return WebUtility.getResponse(errorMap, 400);
		}
			
		if(AbstractSecurityUtils.securityEnabled()) {
			User user = in.getUser();
			if(user == null) {
				HashMap<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("errorMessage", "Session could not be validated in order to upload files");
				return WebUtility.getResponse(errorMap, 400);
			}
			
			if(user.isAnonymous() && !AbstractSecurityUtils.anonymousUserUploadData()) {
				HashMap<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("errorMessage", "Must be logged in to upload files");
				return WebUtility.getResponse(errorMap, 400);
			}
			
			if(user.isAnonymous() && in.isSavedInsight()) {
				HashMap<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("errorMessage", "Must be logged in to upload files to a saved insight");
				return WebUtility.getResponse(errorMap, 400);
			}
			
			if(in.isSavedInsight() && !SecurityInsightUtils.userCanEditInsight(user, in.getEngineId(), in.getRdbmsId())) {
				HashMap<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("errorMessage", "User does not edit access for this insight");
				return WebUtility.getResponse(errorMap, 400);
			}
			
			if(AbstractSecurityUtils.adminSetPublisher() && !SecurityQueryUtils.userIsPublisher(user)) {
				HashMap<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("errorMessage", "User does not have permission to publish data. Please reach out to the admin to get proper access");
				return WebUtility.getResponse(errorMap, 400);
			}
		}
		
		ThreadStore.setSessionId(request.getSession().getId());
		try {
			List<FileItem> fileItems = processRequest(request, insightId);
			// collect all of the data input on the form
			List<Map<String, String>> inputData = getBaseUploadData(fileItems, in);
			// clear the thread store
			return WebUtility.getResponse(inputData, 200);
		} catch(Exception e) {
			e.printStackTrace();
			HashMap<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("errorMessage", "Error moving file to server");
			return WebUtility.getResponse(errorMap, 400);
		} finally {
			ThreadStore.remove();
		}
	}
	
	/**
	 * Method to parse just files and move to the server
	 * @param fileItems		a list of maps containing the file name and file location
	 * @return
	 */
	private List<Map<String, String>> getBaseUploadData(List<FileItem> fileItems, Insight in) {
		String filePath = in.getInsightFolder();
		File fileDir = new File(filePath);
		if(!fileDir.exists()) {
			fileDir.mkdirs();
		}
		
		Iterator<FileItem> iteratorFileItems = fileItems.iterator();

		// collect all of the data input on the form
		List<Map<String, String>> retData = new Vector<Map<String, String>>();

		while(iteratorFileItems.hasNext()) 
		{
			FileItem fi = (FileItem) iteratorFileItems.next();
			if (!fi.isFormField()) {
				// Get the uploaded file parameters
				String fieldName = fi.getFieldName();
				String name = fi.getName();
				
				// we need the key to be file
				if(!fieldName.equals("file")) {
					// delete the field
					fi.delete();
					continue;
				}
				
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
				
				String fileLocation = getUniquePath(filePath, name);
				File file = new File(fileLocation);
				
				// instead of adding unique
				// we will do what a normal OS system does
				
				writeFile(fi, file);
				LOGGER.info("Saved Filename: " + name + "  to "+ file);
				
				String savedName = FilenameUtils.getName(fileLocation);
				Map<String, String> fileMap = new HashMap<String, String>();
				fileMap.put("fileName", savedName);
				fileMap.put("fileLocation", Insight.getInsightRelativeFolderKey(in) + DIR_SEPARATOR + savedName);
				retData.add(fileMap);
			} else if(fi.getFieldName().equals("file")) { 
				// its a file, but not in a form
				// i.e. this is a person copy/pasting 
				// the values directly 
				LOGGER.info("Writing Input To File");
//				Date date = new Date();
//				String modifiedDate = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSSS").format(date);
				
//				String fileSuffix = "FileString_" + modifiedDate;;
//				String fileLocation = filePath + DIR_SEPARATOR + fileSuffix;
				
				String fileLocation = getUniquePath(filePath, "AutoGeneratedFile.txt");
				File file = new File(fileLocation);
				writeFile(fi, file);
				LOGGER.info("Saved Pasted Data To "+ file);
				
				String savedName = FilenameUtils.getName(fileLocation);
				Map<String, String> fileMap = new HashMap<String, String>();
				fileMap.put("fileName", savedName);
				fileMap.put("fileLocation", Insight.getInsightRelativeFolderKey(in) + DIR_SEPARATOR + savedName);
				retData.add(fileMap);
			}
			// delete the field
			fi.delete();
		}

		return retData;
	}
	
	/**
	 * Makes sure that the file we are creating is in fact unique
	 * @param directory
	 * @param fileLocation
	 * @return
	 */
	private String getUniquePath(String directory, String fileLocation) {
		String fileName = FilenameUtils.getBaseName(fileLocation).trim();
		String fileExtension = FilenameUtils.getExtension(fileLocation).trim();
		
		// h2 is weird and will not work if it doesn't end in .mv.db
		boolean isH2 = fileLocation.endsWith(".mv.db");
		File f = new File(directory + DIR_SEPARATOR + fileName + "." + fileExtension);
		int counter = 2;
		while(f.exists()) {
			if(isH2) {
				f = new File(directory + DIR_SEPARATOR + fileName.replace(".mv", "") + " (" + counter + ")" + ".mv.db");
			} else {
				f = new File(directory + DIR_SEPARATOR + fileName + " (" + counter + ")" + "." + fileExtension);
			}
			counter++;
		}
		
		return f.getAbsolutePath();
	}
	
}
