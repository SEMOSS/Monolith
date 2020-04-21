package prerna.upload;

import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.io.filefilter.WildcardFileFilter;

import prerna.auth.User;
import prerna.auth.utils.SecurityAppUtils;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.cluster.util.CloudClient;
import prerna.cluster.util.ClusterUtil;
import prerna.engine.impl.SmssUtilities;
import prerna.nameserver.utility.MasterDatabaseUtility;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

public class ImageUploader extends Uploader {
	
	@POST
	@Path("/appImage")
	@Produces("application/json")
	public Response uploadAppImage(@Context HttpServletRequest request) {
		Map<String, String> returnMap = new HashMap<String, String>();
		
		List<FileItem> fileItems = processRequest(request, null);
		// collect all of the data input on the form
		FileItem imageFile = null;
		String appId = null;
		String appName = null;
		
		for(FileItem fi : fileItems) {
			String fieldName = fi.getFieldName();
			String value = fi.getString();
			if(fieldName.equals("file")) {
				imageFile = fi;
			}
			if(fieldName.equals("app")) {
				appName = value;
			}
		}

		if(this.securityEnabled) {
			HttpSession session = request.getSession(false);
			if(session != null){
				User user = ((User) session.getAttribute(Constants.SESSION_USER));
				if(user == null) {
					HashMap<String, String> errorMap = new HashMap<String, String>();
					errorMap.put("errorMessage", "Session could not be validated in order to upload the app image");
					return WebUtility.getResponse(errorMap, 400);
				}
				
				if(user.isAnonymous()) {
					HashMap<String, String> errorMap = new HashMap<String, String>();
					errorMap.put("errorMessage", "Must be logged in to upload an app image");
					return WebUtility.getResponse(errorMap, 400);
				}
				
				try {
					appId = SecurityQueryUtils.testUserEngineIdForAlias(user, appName);
				} catch(Exception e) {
					returnMap.put("errorMessage", e.getMessage());
					return WebUtility.getResponse(returnMap, 400);
				}
				if(!SecurityAppUtils.userCanEditEngine(user, appId)) {
					returnMap.put("errorMessage", "User does not have access to this app or the app id does not exist");
					return WebUtility.getResponse(returnMap, 400);
				}
				appName = SecurityQueryUtils.getEngineAliasForId(appId);
			} else {
				returnMap.put("errorMessage", "User session is invalid");
				return WebUtility.getResponse(returnMap, 400);
			}
		} else {
			appId = MasterDatabaseUtility.testEngineIdIfAlias(appName);
			appName = MasterDatabaseUtility.getEngineAliasForId(appId);
			String appDir = filePath + DIR_SEPARATOR + SmssUtilities.getUniqueName(appName, appId);
			if(!(new File(appDir).exists())) {
				returnMap.put("errorMessage", "Could not find app directory");
				return WebUtility.getResponse(returnMap, 400);
			}
		}
		
		// now that we have the app name
		// and the image file
		// we want to write it into the app location
		String imageDir = filePath + DIR_SEPARATOR + SmssUtilities.getUniqueName(appName, appId) + DIR_SEPARATOR + "version";
		String imageLoc = imageDir + DIR_SEPARATOR + "image." + imageFile.getContentType().split("/")[1];

		if(ClusterUtil.IS_CLUSTER){
			imageDir=ClusterUtil.IMAGES_FOLDER_PATH+ DIR_SEPARATOR + "apps";	
			imageLoc = imageDir + DIR_SEPARATOR + appId + "." + imageFile.getContentType().split("/")[1];
		}
		File f = new File(imageDir);
		if(!f.exists()) {
			f.mkdirs();
		}
		f = new File(imageLoc);
		// find all the existing image files
		// and delete them
		File[] oldImages = null;
		if(ClusterUtil.IS_CLUSTER){
			FilenameFilter appIdFilter = new WildcardFileFilter(appId+"*");
			oldImages = f.getParentFile().listFiles(appIdFilter);
		} else{
			 oldImages = findImageFile(f.getParentFile());
		}
		// delete if any exist
		if(oldImages != null) {
			for(File oldI : oldImages) {
				oldI.delete();
			}
		}
		writeFile(imageFile, f);
		try {
			if(ClusterUtil.IS_CLUSTER){
			CloudClient.getClient().pushImageFolder();
			}
		} catch (IOException | InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		returnMap.put("message", "successfully updated app image");
		return WebUtility.getResponse(returnMap, 200);
	}
	
	@POST
	@Path("/insightImage")
	@Produces("application/json")
	public Response uploadInsightImage(@Context HttpServletRequest request) {
		Map<String, String> returnMap = new HashMap<String, String>();
		
		List<FileItem> fileItems = processRequest(request, null);
		// collect all of the data input on the form
		FileItem imageFile = null;
		String appId = null;
		String appName = null;
		String insightId = null;
		
		for(FileItem fi : fileItems) {
			String fieldName = fi.getFieldName();
			String value = fi.getString();
			if(fieldName.equals("file")) {
				imageFile = fi;
			}
			if(fieldName.equals("app")) {
				appName = value;
			}
			if(fieldName.equals("insightId")) {
				insightId = value;
			}
		}

		if(imageFile == null) {
			returnMap.put("errorMessage", "Could not find the file to upload for the insight in the request");
			return WebUtility.getResponse(returnMap, 400);
		} else if(appName == null || insightId == null) {
			returnMap.put("errorMessage", "Need to pass the proper app and insight ids to upload the image");
			return WebUtility.getResponse(returnMap, 400);
		}
		
		if(this.securityEnabled) {
			HttpSession session = request.getSession(false);
			if(session != null){
				User user = ((User) session.getAttribute(Constants.SESSION_USER));
				if(user == null) {
					HashMap<String, String> errorMap = new HashMap<String, String>();
					errorMap.put("errorMessage", "Session could not be validated in order to upload the insight image");
					return WebUtility.getResponse(errorMap, 400);
				}
				
				if(user.isAnonymous()) {
					HashMap<String, String> errorMap = new HashMap<String, String>();
					errorMap.put("errorMessage", "Must be logged in to upload an insight image");
					return WebUtility.getResponse(errorMap, 400);
				}
				
				try {
					appId = SecurityQueryUtils.testUserEngineIdForAlias(user, appName);
				} catch(Exception e) {
					returnMap.put("errorMessage", e.getMessage());
					return WebUtility.getResponse(returnMap, 400);
				}
				if(!SecurityInsightUtils.userCanEditInsight(user, appId, insightId)) {
					returnMap.put("errorMessage", "User does not have access to edit this insight within the app");
					return WebUtility.getResponse(returnMap, 400);
				}
				appName = SecurityQueryUtils.getEngineAliasForId(appId);
			} else {
				returnMap.put("errorMessage", "User session is invalid");
				return WebUtility.getResponse(returnMap, 400);
			}
		} else {
			appId = MasterDatabaseUtility.testEngineIdIfAlias(appName);
			appName = MasterDatabaseUtility.getEngineAliasForId(appId);
			String appDir = filePath + DIR_SEPARATOR + SmssUtilities.getUniqueName(appName, appId);
			if(!(new File(appDir).exists())) {
				returnMap.put("errorMessage", "Could not find app directory");
				return WebUtility.getResponse(returnMap, 400);
			}
		}
		
		// now that we have the app name
		// and the image file
		// we want to write it into the app location
		String imageDir = filePath + DIR_SEPARATOR + SmssUtilities.getUniqueName(appName, appId) + DIR_SEPARATOR + "version" + DIR_SEPARATOR + insightId;
		File f = new File(imageDir);
		if(!f.exists()) {
			f.mkdirs();
		}
		String imageLoc = imageDir + DIR_SEPARATOR + "image." + imageFile.getContentType().split("/")[1];
		f = new File(imageLoc);
		// find all the existing image files
		// and delete them
		File[] oldImages = findImageFile(f.getParentFile());
		// delete if any exist
		if(oldImages != null) {
			for(File oldI : oldImages) {
				oldI.delete();
			}
		}
		writeFile(imageFile, f);
		
		returnMap.put("message", "successfully updated insight image");
		return WebUtility.getResponse(returnMap, 200);
	}
	
	/**
	 * Find an image in the directory
	 * @param baseDir
	 * @return
	 */
	public static File[] findImageFile(String baseDir) {
		List<String> extensions = new Vector<String>();
		extensions.add("image.png");
		extensions.add("image.jpeg");
		extensions.add("image.jpg");
		extensions.add("image.gif");
		extensions.add("image.svg");
		FileFilter imageExtensionFilter = new WildcardFileFilter(extensions);
		File baseFolder = new File(baseDir);
		File[] imageFiles = baseFolder.listFiles(imageExtensionFilter);
		return imageFiles;
	}
	
	/**
	 * Find an image in the directory
	 * @param baseDir
	 * @return
	 */
	public static File[] findImageFile(File baseFolder) {
		List<String> extensions = new Vector<String>();
		extensions.add("image.png");
		extensions.add("image.jpeg");
		extensions.add("image.jpg");
		extensions.add("image.gif");
		extensions.add("image.svg");
		FileFilter imageExtensionFilter = new WildcardFileFilter(extensions);
		File[] imageFiles = baseFolder.listFiles(imageExtensionFilter);
		return imageFiles;
	}
}
