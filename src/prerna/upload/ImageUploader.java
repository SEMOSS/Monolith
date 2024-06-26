package prerna.upload;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.io.Files;

import prerna.auth.User;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.engine.api.IEngine;
import prerna.engine.impl.SmssUtilities;
import prerna.io.connector.couch.CouchException;
import prerna.io.connector.couch.CouchUtil;
import prerna.util.AssetUtility;
import prerna.util.Constants;
import prerna.util.EngineUtility;
import prerna.util.Utility;
import prerna.util.insight.InsightUtility;
import prerna.web.services.util.WebUtility;

@Path("/images")
public class ImageUploader extends Uploader {
	
	private static final Logger classLogger = LogManager.getLogger(ImageUploader.class);

	/*
	 * ENGINE
	 */
	
	@POST
	@Path("/engine/upload")
	@Produces("application/json")
	public Response uploadEngineImage(@Context ServletContext context, @Context HttpServletRequest request) throws SQLException {
		Map<String, String> returnMap = new HashMap<>();

		HttpSession session = request.getSession(false);
		User user = null;
		if (session != null) {
			user = ((User) session.getAttribute(Constants.SESSION_USER));
			if (user == null) {
				HashMap<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Session could not be validated in order to upload the engine image");
				return WebUtility.getResponse(errorMap, 400);
			}

			if (user.isAnonymous()) {
				HashMap<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Must be logged in to upload an engine image");
				return WebUtility.getResponse(errorMap, 400);
			}
		} else {
			returnMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(returnMap, 400);
		}
		
		List<FileItem> fileItems = null;
		try {
			fileItems = processRequest(context, request, null);
		} catch (FileUploadException e) {
			HashMap<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error uploading file. Error = " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		// collect all of the data input on the form
		FileItem imageFile = null;
		String engineId = null;
		
		for (FileItem fi : fileItems) {
			String fieldName = fi.getFieldName();
			String value = fi.getString();
			if (fieldName.equals("file")) {
				imageFile = fi;
			}
			if (fieldName.equals("engineId")) {
				engineId = value;
			}
		}

		if (imageFile == null) {
			returnMap.put(Constants.ERROR_MESSAGE, "Could not find the file to upload for the insight in the request");
			return WebUtility.getResponse(returnMap, 400);
		} else if (engineId == null) {
			returnMap.put(Constants.ERROR_MESSAGE, "Need to pass the proper engine id to upload the image");
			return WebUtility.getResponse(returnMap, 400);
		}
		
		if (!SecurityEngineUtils.userCanEditEngine(user, engineId)) {
			returnMap.put(Constants.ERROR_MESSAGE, "User does not have access to this engine or the engine id does not exist");
			return WebUtility.getResponse(returnMap, 400);
		}
		
		String engineName = SecurityEngineUtils.getEngineAliasForId(engineId);
		String engineNameAndId = SmssUtilities.getUniqueName(engineName, engineId);
		
		Object[] engineTypeAndSubtype = SecurityEngineUtils.getEngineTypeAndSubtype(engineId);
		IEngine.CATALOG_TYPE engineType = (IEngine.CATALOG_TYPE) engineTypeAndSubtype[0];
		
		// will define these here up front
		String couchSelector = null;
		String localEngineImageFolderPath = null;
		String engineVersionPath = null;
		try {
			couchSelector = EngineUtility.getCouchSelector(engineType);
			localEngineImageFolderPath = EngineUtility.getLocalEngineImageDirectory(engineType);
			engineVersionPath = EngineUtility.getSpecificEngineVersionFolder(engineType, engineNameAndId);
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			returnMap.put(Constants.ERROR_MESSAGE, "Unknown engine type '"+engineType+"' for engine " + engineNameAndId);
			return WebUtility.getResponse(returnMap, 400);
		}

		// i always want to fix it in the engine version folder
		// so that way export works as expected
		// if it is on cloud - also push it to the images/<eType> folder and sync that
		// if it is on couchdb - push to that	
		
		// regardless, have to pull the engine
		Utility.getEngine(engineId, engineType, true);
		
		// local changes
		File newImageFileInVersionFolder = null;
		String newImageFileType = null;
		{
			// remove the old image files in the version folder
			// and push new one to the version folder
			File engineVersionF = new File(Utility.normalizePath(engineVersionPath));
			if (!engineVersionF.exists() || !engineVersionF.isDirectory()) {
				Boolean success = engineVersionF.mkdirs();
				if(!success) {
					classLogger.warn("Unable to make engine version folder at path " + engineVersionPath);
					returnMap.put(Constants.ERROR_MESSAGE, "Error occured attempting to make the engine version folder");
					return WebUtility.getResponse(returnMap, 400);
				}
			}
			
			// find all the image files and delete them
			File[] oldImageFiles = engineVersionF.listFiles(new FilenameFilter() {
				@Override
				public boolean accept(File dir, String name) {
					return name.startsWith("image.");
				}
			});
			
			for(File f : oldImageFiles) {
				f.delete();
				if(ClusterUtil.IS_CLUSTER) {
					ClusterUtil.deleteEngineCloudFile(engineId, engineType, f.getAbsolutePath());
				}
			}
			
			newImageFileType = imageFile.getContentType().split("/")[1];
			String imageFileName = "image." + newImageFileType;
			String imageLoc = engineVersionF.getAbsolutePath() + DIR_SEPARATOR + imageFileName;
			newImageFileInVersionFolder = new File(Utility.normalizePath(imageLoc));
			writeFile(imageFile, newImageFileInVersionFolder);
			ClusterUtil.copyLocalFileToEngineCloudFolder(engineId, engineType, imageLoc);
		}
		
		// now we figure out the cloud and where we push this
		if(CouchUtil.COUCH_ENABLED) {
			try {
				Map<String, String> selectors = new HashMap<>();
				selectors.put(couchSelector, engineId);
				CouchUtil.upload(couchSelector, selectors, imageFile);
			} catch (CouchException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Upload of engine image failed");
				return WebUtility.getResponse(errorMap, HttpStatus.SC_INTERNAL_SERVER_ERROR);
			}
		} else if(ClusterUtil.IS_CLUSTER) {
			// we need to push to the engine specific image folder
			File localCloudImageF = new File(Utility.normalizePath( localEngineImageFolderPath));
			File newImageFileInCloudFolder = new File(Utility.normalizePath(localEngineImageFolderPath + "/" + engineId + "." + newImageFileType));
			
			// find all the image files and delete them
			File[] oldImageFiles = localCloudImageF.listFiles(new FilenameFilter() {
				
				String engineId = null;
				
				private FilenameFilter init(String engineId) {
					this.engineId = engineId;
					return this;
				}
				
				@Override
				public boolean accept(File dir, String name) {
					return name.startsWith(engineId);
				}
			}.init(engineId));
			
			for(File f : oldImageFiles) {
				// delete on local
				f.delete();
				// delete from cloud as well
				ClusterUtil.deleteEngineAndProjectImage(engineType, f.getName());
			}
			
			// move the current image file to the cloud folder
			try {
				Files.copy(newImageFileInVersionFolder, newImageFileInCloudFolder);
			} catch (IOException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Upload of engine image failed");
				return WebUtility.getResponse(errorMap, HttpStatus.SC_INTERNAL_SERVER_ERROR);
			}
			ClusterUtil.pushEngineAndProjectImage(engineType, newImageFileInCloudFolder.getName());
		}
			
		returnMap.put("message", "Successfully updated engine image");
		returnMap.put("engine_id", engineId);
		returnMap.put("engine_name", engineName);
		return WebUtility.getResponse(returnMap, 200);
	}
	
	@POST
	@Path("/engine/delete")
	public Response deleteEngineImage(@Context HttpServletRequest request) throws SQLException {
		Map<String, String> returnMap = new HashMap<>();
		
		String engineId = request.getParameter("engineId");
		if(engineId == null) {
			returnMap.put(Constants.ERROR_MESSAGE, "Need to pass the proper engine id to remove the image");
			return WebUtility.getResponse(returnMap, 400);
		}
		
		HttpSession session = request.getSession(false);
		if (session != null) {
			User user = ((User) session.getAttribute(Constants.SESSION_USER));
			if (user == null) {
				HashMap<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Session could not be validated in order to delete the engine image");
				return WebUtility.getResponse(errorMap, 400);
			}

			if (user.isAnonymous()) {
				HashMap<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Must be logged in to upload an engine image");
				return WebUtility.getResponse(errorMap, 400);
			}

			if (!SecurityEngineUtils.userCanEditEngine(user, engineId)) {
				returnMap.put(Constants.ERROR_MESSAGE, "User does not have access to this engine or the engine id does not exist");
				return WebUtility.getResponse(returnMap, 400);
			}
		} else {
			returnMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(returnMap, 400);
		}
		
		String engineName = SecurityEngineUtils.getEngineAliasForId(engineId);
		String engineNameAndId = SmssUtilities.getUniqueName(engineName, engineId);
		
		Object[] engineTypeAndSubtype = SecurityEngineUtils.getEngineTypeAndSubtype(engineId);
		IEngine.CATALOG_TYPE engineType = (IEngine.CATALOG_TYPE) engineTypeAndSubtype[0];
		
		// will define these here up front
		String couchSelector = null;
		String localEngineImageFolderPath = null;
		String engineVersionPath = null;
		try {
			couchSelector = EngineUtility.getCouchSelector(engineType);
			localEngineImageFolderPath = EngineUtility.getLocalEngineImageDirectory(engineType);
			engineVersionPath = EngineUtility.getSpecificEngineVersionFolder(engineType, engineNameAndId);
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			returnMap.put(Constants.ERROR_MESSAGE, "Unknown engine type '"+engineType+"' for engine " + engineNameAndId);
			return WebUtility.getResponse(returnMap, 400);
		}

		// i always want to delete it in the engine version folder
		// so that way export works as expected
		// if it is on cloud - also delete it to the images/<eType> folder and sync that
		// if it is on couchdb - delete from that	
		
		// regardless, have to pull the engine
		Utility.getEngine(engineId, engineType, true);
		
		// local changes
		{
			// remove the old image files in the version folder
			// and push new one to the version folder
			File engineVersionF = new File(Utility.normalizePath(engineVersionPath));
			if (!engineVersionF.exists() || !engineVersionF.isDirectory()) {
				Boolean success = engineVersionF.mkdirs();
				if(!success) {
					classLogger.warn("Unable to make engine version folder at path " + engineVersionPath);
					returnMap.put(Constants.ERROR_MESSAGE, "Error occured attempting to make the engine version folder");
					return WebUtility.getResponse(returnMap, 400);
				}
			}
			
			// find all the image files and delete them
			File[] oldImageFiles = engineVersionF.listFiles(new FilenameFilter() {
				@Override
				public boolean accept(File dir, String name) {
					return name.startsWith("image.");
				}
			});
			
			for(File f : oldImageFiles) {
				f.delete();
				if(ClusterUtil.IS_CLUSTER) {
					ClusterUtil.deleteEngineCloudFile(engineId, engineType, f.getAbsolutePath());
				}
			}
		}
		
		// now we figure out the cloud and where we push this
		if(CouchUtil.COUCH_ENABLED) {
			try {
				Map<String, String> selectors = new HashMap<>();
				selectors.put(couchSelector, engineId);
				CouchUtil.delete(couchSelector, selectors);
			} catch (CouchException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Delete of engine image failed");
				return WebUtility.getResponse(errorMap, HttpStatus.SC_INTERNAL_SERVER_ERROR);
			}
		} else if(ClusterUtil.IS_CLUSTER) {
			// we need to push to the engine specific image folder
			File localCloudImageF = new File(Utility.normalizePath(localEngineImageFolderPath));
			// find all the image files and delete them
			File[] oldImageFiles = localCloudImageF.listFiles(new FilenameFilter() {
				
				String engineId = null;
				
				private FilenameFilter init(String engineId) {
					this.engineId = engineId;
					return this;
				}
				
				@Override
				public boolean accept(File dir, String name) {
					return name.startsWith(engineId);
				}
			}.init(engineId));
			
			for(File f : oldImageFiles) {
				// delete on local
				f.delete();
				// delete from cloud as well
				ClusterUtil.deleteEngineAndProjectImage(engineType, f.getName());
			}
		}
			
		returnMap.put("message", "Successfully deleted engine image");
		returnMap.put("engine_id", engineId);
		returnMap.put("engine_name", engineName);
		return WebUtility.getResponse(returnMap, 200);
	}
	
	////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////

	/*
	 * PROJECT
	 */
	
	@POST
	@Path("/projectImage/upload")
	@Produces("application/json")
	public Response uploadProjectImage(@Context ServletContext context, @Context HttpServletRequest request) throws SQLException {
		Map<String, String> returnMap = new HashMap<>();

		// base path is the project folder
		String filePath = Utility.normalizePath(EngineUtility.getLocalEngineBaseDirectory(IEngine.CATALOG_TYPE.PROJECT));
		
		HttpSession session = request.getSession(false);
		User user = null;
		if (session != null) {
			user = ((User) session.getAttribute(Constants.SESSION_USER));
			if (user == null) {
				HashMap<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Session could not be validated in order to upload the project image");
				return WebUtility.getResponse(errorMap, 400);
			}

			if (user.isAnonymous()) {
				HashMap<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Must be logged in to upload an project image");
				return WebUtility.getResponse(errorMap, 400);
			}
		} else {
			returnMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(returnMap, 400);
		}
		
		List<FileItem> fileItems = null;
		try {
			fileItems = processRequest(context, request, null);
		} catch (FileUploadException e) {
			HashMap<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error uploading file. Error = " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		// collect all of the data input on the form
		FileItem imageFile = null;
		String projectId = null;
		String projectName = null;

		for (FileItem fi : fileItems) {
			String fieldName = fi.getFieldName();
			String value = fi.getString();
			if (fieldName.equals("file")) {
				imageFile = fi;
			}
			if (fieldName.equals("projectId")) {
				projectId = value;
			}
		}

		if (imageFile == null) {
			returnMap.put(Constants.ERROR_MESSAGE, "Could not find the file to upload for the insight in the request");
			return WebUtility.getResponse(returnMap, 400);
		} else if (projectId == null) {
			returnMap.put(Constants.ERROR_MESSAGE, "Need to pass the proper project id to upload the image");
			return WebUtility.getResponse(returnMap, 400);
		}
		
		try {
			projectId = SecurityProjectUtils.testUserProjectIdForAlias(user, projectId);
		} catch (Exception e) {
			returnMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(returnMap, 400);
		}
		if (!SecurityProjectUtils.userCanEditProject(user, projectId)) {
			returnMap.put(Constants.ERROR_MESSAGE, "User does not have access to edit this project");
			return WebUtility.getResponse(returnMap, 400);
		}
		projectName = SecurityProjectUtils.getProjectAliasForId(projectId);

		if(CouchUtil.COUCH_ENABLED) {
			try {
				Map<String, String> selectors = new HashMap<>();
				selectors.put(CouchUtil.PROJECT, projectId);
				CouchUtil.upload(CouchUtil.PROJECT, selectors, imageFile);
			} catch (CouchException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Upload of project image failed");
				return WebUtility.getResponse(errorMap, HttpStatus.SC_INTERNAL_SERVER_ERROR);
			}
		} else {
			String imageDir = getProjectImageDir(filePath, projectId, projectName);
			String imageLoc = getProjectImageLoc(filePath, projectId, projectName, imageFile);
			
			File f = new File(Utility.normalizePath(imageDir));
			if (!f.exists()) {
				Boolean success = f.mkdirs();
				if(!success) {
					classLogger.info("Unable to make direction at location: " + Utility.cleanLogString(filePath));
				}
			}
			f = new File(Utility.normalizePath(imageLoc));
			// find all the existing image files
			// and delete them
			File[] oldImages = null;
			if (ClusterUtil.IS_CLUSTER) {
				FilenameFilter appIdFilter = new WildcardFileFilter(projectId + "*");
				oldImages = f.getParentFile().listFiles(appIdFilter);
			} else {
				oldImages = InsightUtility.findImageFile(f.getParentFile());
			}
			// delete if any exist
			if (oldImages != null) {
				for (File oldI : oldImages) {
					Boolean success = oldI.delete();
					if (!success) {
						classLogger.info("Unable to delete file at location: " + Utility.cleanLogString(oldI.getAbsolutePath()));
					}
				}
			}
			writeFile(imageFile, f);

			try {
				if (ClusterUtil.IS_CLUSTER) {
					ClusterUtil.pushEngineAndProjectImage(IEngine.CATALOG_TYPE.PROJECT, f.getName());
				}
			} catch(Exception e) {
				Thread.currentThread().interrupt();
				classLogger.error(Constants.STACKTRACE, e);
			}
		}
		
		returnMap.put("message", "Successfully updated project image");
		returnMap.put("app_id", projectId);
		returnMap.put("app_name", projectName);
		return WebUtility.getResponse(returnMap, 200);
	}
	
	@POST
	@Path("/projectImage/delete")
	public Response deleteProjectImage(@Context HttpServletRequest request) throws SQLException {
		Map<String, String> returnMap = new HashMap<>();

		// base path is the project folder
		String filePath = Utility.normalizePath(EngineUtility.getLocalEngineBaseDirectory(IEngine.CATALOG_TYPE.PROJECT));

		String projectId = request.getParameter("projectId");
		String projectName = null;
		if(projectId == null) {
			returnMap.put(Constants.ERROR_MESSAGE, "Need to pass the proper project id to remove the image");
			return WebUtility.getResponse(returnMap, 400);
		}

		HttpSession session = request.getSession(false);
		if (session != null) {
			User user = ((User) session.getAttribute(Constants.SESSION_USER));
			if (user == null) {
				HashMap<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Session could not be validated in order to delete the project image");
				return WebUtility.getResponse(errorMap, 400);
			}

			if (user.isAnonymous()) {
				HashMap<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Must be logged in to upload an project image");
				return WebUtility.getResponse(errorMap, 400);
			}

			try {
				projectId = SecurityProjectUtils.testUserProjectIdForAlias(user, projectId);
			} catch (Exception e) {
				returnMap.put(Constants.ERROR_MESSAGE, e.getMessage());
				return WebUtility.getResponse(returnMap, 400);
			}
			if (!SecurityProjectUtils.userCanEditProject(user, projectId)) {
				returnMap.put(Constants.ERROR_MESSAGE, "User does not have access to this project or the project id does not exist");
				return WebUtility.getResponse(returnMap, 400);
			}
			projectName = SecurityProjectUtils.getProjectAliasForId(projectId);
		} else {
			returnMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(returnMap, 400);
		}

		if(CouchUtil.COUCH_ENABLED) {
			try {
				Map<String, String> selectors = new HashMap<>();
				selectors.put(CouchUtil.PROJECT, projectId);
				CouchUtil.delete(CouchUtil.PROJECT, selectors);
			} catch (CouchException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Project image deletion failed");
				return WebUtility.getResponse(errorMap, HttpStatus.SC_INTERNAL_SERVER_ERROR);
			}
		}
		
		String imageDir = getProjectImageDir(filePath, projectId, projectName);
		File f = new File(Utility.normalizePath(imageDir));
		File[] oldImages = null;
		if (ClusterUtil.IS_CLUSTER) {
			FilenameFilter appIdFilter = new WildcardFileFilter(projectId + "*");
			oldImages = f.listFiles(appIdFilter);
		} else {
			oldImages = InsightUtility.findImageFile(f);
		}
		// delete if any exist
		if (oldImages != null) {
			for (File oldI : oldImages) {
				Boolean success = oldI.delete();
				if (!success) {
					classLogger.info("Unable to delete file at location: " + Utility.cleanLogString(oldI.getAbsolutePath()));
				}
			}
		}
		
		try {
			if (ClusterUtil.IS_CLUSTER) {
				ClusterUtil.deleteEngineAndProjectImageById(IEngine.CATALOG_TYPE.PROJECT, projectId);
			}
		} catch(Exception e) {
			Thread.currentThread().interrupt();
			classLogger.error(Constants.STACKTRACE, e);
		}
		
		returnMap.put("project_id", projectId);
		returnMap.put("project_name", projectName);
		returnMap.put("message", "Successfully deleted project image");
		return WebUtility.getResponse(returnMap, 200);
	}
	
	private String getProjectImageDir(String filePath, String projectId, String projectName) {
		if(ClusterUtil.IS_CLUSTER){
			return ClusterUtil.IMAGES_FOLDER_PATH + DIR_SEPARATOR + "projects";
		}
		return AssetUtility.getProjectVersionFolder(projectName, projectId);
	}

	private String getProjectImageLoc(String filePath, String id, String name, FileItem imageFile){
		String imageDir = getProjectImageDir(filePath, id, name);
		if(ClusterUtil.IS_CLUSTER){
			return imageDir + DIR_SEPARATOR + id + "." + imageFile.getContentType().split("/")[1];
		}
		return imageDir + DIR_SEPARATOR + "image." + imageFile.getContentType().split("/")[1];
	}
	
	/*
	 * INSIGHT
	 */
	
	@POST
	@Path("/insightImage/upload")
	@Produces("application/json")
	public Response uploadInsightImage(@Context ServletContext context, @Context HttpServletRequest request) {
		Map<String, String> returnMap = new HashMap<>();
		
		// base path is the project folder
		String filePath = Utility.normalizePath(EngineUtility.getLocalEngineBaseDirectory(IEngine.CATALOG_TYPE.PROJECT));

		HttpSession session = request.getSession(false);
		User user = null;
		if (session != null) {
			user = ((User) session.getAttribute(Constants.SESSION_USER));
			if (user == null) {
				HashMap<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Session could not be validated in order to upload the insight image");
				return WebUtility.getResponse(errorMap, 400);
			}

			if (user.isAnonymous()) {
				HashMap<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Must be logged in to upload an insight image");
				return WebUtility.getResponse(errorMap, 400);
			}
		} else {
			returnMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(returnMap, 400);
		}
		
		List<FileItem> fileItems = null;
		try {
			fileItems = processRequest(context, request, null);
		} catch (FileUploadException e) {
			HashMap<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error uploading file. Error = " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		// collect all of the data input on the form
		FileItem imageFile = null;
		String projectId = null;
		String projectName = null;
		String insightId = null;

		for (FileItem fi : fileItems) {
			String fieldName = fi.getFieldName();
			String value = fi.getString();
			if (fieldName.equals("file")) {
				imageFile = fi;
			}
			if(fieldName.equals("projectId")) {
				projectId = value;
			}
			if (fieldName.equals("insightId")) {
				insightId = value;
			}
		}

		if (imageFile == null) {
			returnMap.put(Constants.ERROR_MESSAGE, "Could not find the file to upload for the insight in the request");
			return WebUtility.getResponse(returnMap, 400);
		} else if (projectId == null || insightId == null) {
			returnMap.put(Constants.ERROR_MESSAGE, "Need to pass the proper project and insight ids to upload the image");
			return WebUtility.getResponse(returnMap, 400);
		}
		
		try {
			projectId = SecurityProjectUtils.testUserProjectIdForAlias(user, projectId);
		} catch (Exception e) {
			returnMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(returnMap, 400);
		}
		if (!SecurityInsightUtils.userCanEditInsight(user, projectId, insightId)) {
			returnMap.put(Constants.ERROR_MESSAGE, "User does not have access to edit this insight within the project");
			return WebUtility.getResponse(returnMap, 400);
		}
		projectName = SecurityProjectUtils.getProjectAliasForId(projectId);
		
		if(CouchUtil.COUCH_ENABLED) {
			try {
				Map<String, String> selectors = new HashMap<>();
				selectors.put(CouchUtil.INSIGHT, insightId);
				selectors.put(CouchUtil.PROJECT, projectId);
				CouchUtil.upload(CouchUtil.INSIGHT, selectors, imageFile);
			} catch (CouchException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Upload of insight image failed");
				return WebUtility.getResponse(errorMap, HttpStatus.SC_INTERNAL_SERVER_ERROR);
			}
		} else {
			// now that we have the app name
			// and the image file
			// we want to write it into the project location
			String imageDir = AssetUtility.getProjectVersionFolder(projectName, projectId) + DIR_SEPARATOR + insightId;
			File f = new File(Utility.normalizePath(imageDir));
			if (!f.exists()) {
				Boolean success = f.mkdirs();
				if(!success) {
					classLogger.info("Unable to make direction at location: " + Utility.cleanLogString(imageDir));
				}
			}
			// find all the existing image files
			// and delete them
			String oldImageName = null;
			File[] oldImages = InsightUtility.findImageFile(f);
			// delete if any exist
			if (oldImages != null) {
				for (File oldI : oldImages) {
					oldImageName = oldI.getName();
					Boolean success = oldI.delete();
					if(!success) {
						classLogger.info("Unable to delete file at location: " + Utility.cleanLogString(oldI.getAbsolutePath()));
					}
	
				}
			}
			
			String imageFileName = "image." + imageFile.getContentType().split("/")[1];
			String imageLoc = imageDir + DIR_SEPARATOR + imageFileName;
			f = new File(Utility.normalizePath(imageLoc));
			writeFile(imageFile, f);
			
			try {
				if (ClusterUtil.IS_CLUSTER) {
					ClusterUtil.pushInsightImage(projectId, insightId, oldImageName, imageFileName);
				}
			} catch(Exception e) {
				Thread.currentThread().interrupt();
				classLogger.error(Constants.STACKTRACE, e);
			}
		}
		returnMap.put("project_id", projectId);
		returnMap.put("project_name", projectName);
		returnMap.put("insight_id", insightId);
		returnMap.put("message", "Successfully updated insight image");
		return WebUtility.getResponse(returnMap, 200);
	}
	
	@POST
	@Path("/insightImage/delete")
	public Response deleteInsightImage(@Context HttpServletRequest request) throws SQLException {
		Map<String, String> returnMap = new HashMap<>();

		String projectId = request.getParameter("projectId");
		String projectName = null;
		String insightId = request.getParameter("insightId");
		if(projectId == null) {
			returnMap.put(Constants.ERROR_MESSAGE, "Need to pass the proper project id to remove the image");
			return WebUtility.getResponse(returnMap, 400);
		}
		if(insightId == null) {
			returnMap.put(Constants.ERROR_MESSAGE, "Need to pass the proper insight id to remove the image");
			return WebUtility.getResponse(returnMap, 400);
		}

		HttpSession session = request.getSession(false);
		if (session != null) {
			User user = ((User) session.getAttribute(Constants.SESSION_USER));
			if (user == null) {
				HashMap<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Session could not be validated in order to delete the insight image");
				return WebUtility.getResponse(errorMap, 400);
			}

			if (user.isAnonymous()) {
				HashMap<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Must be logged in to upload an app image");
				return WebUtility.getResponse(errorMap, 400);
			}

			try {
				projectId = SecurityProjectUtils.testUserProjectIdForAlias(user, projectId);
			} catch (Exception e) {
				returnMap.put(Constants.ERROR_MESSAGE, e.getMessage());
				return WebUtility.getResponse(returnMap, 400);
			}
			if (!SecurityInsightUtils.userCanEditInsight(user, projectId, insightId)) {
				returnMap.put(Constants.ERROR_MESSAGE, "User does not have access to this insight or the insight id does not exist");
				return WebUtility.getResponse(returnMap, 400);
			}
			projectName = SecurityProjectUtils.getProjectAliasForId(projectId);
		} else {
			returnMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(returnMap, 400);
		}

		if(CouchUtil.COUCH_ENABLED) {
			try {
				Map<String, String> selectors = new HashMap<>();
				selectors.put(CouchUtil.INSIGHT, insightId);
				selectors.put(CouchUtil.PROJECT, projectId);
				CouchUtil.delete(CouchUtil.INSIGHT, selectors);
			} catch (CouchException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Insight image deletion failed");
				return WebUtility.getResponse(errorMap, HttpStatus.SC_INTERNAL_SERVER_ERROR);
			}
		}
		
		// now that we have the app name
		// and the image file
		// we want to write it into the app location
		String imageDir = AssetUtility.getProjectVersionFolder(projectName, projectId) + DIR_SEPARATOR + insightId;
		File f = new File(Utility.normalizePath(imageDir));
		if (f.exists()) {
			// find all the existing image files
			// and delete them
			String oldImageName = null;
			File[] oldImages = InsightUtility.findImageFile(f);
			// delete if any exist
			if (oldImages != null) {
				for (File oldI : oldImages) {
					oldImageName = oldI.getName();
					Boolean success = oldI.delete();
					if(!success) {
						classLogger.info("Unable to delete file at location: " + Utility.cleanLogString(oldI.getAbsolutePath()));
					}
				}
			}
			
			try {
				if (ClusterUtil.IS_CLUSTER) {
					ClusterUtil.pushInsightImage(projectId, insightId, oldImageName, null);
				}
			} catch(Exception e) {
				Thread.currentThread().interrupt();
				classLogger.error(Constants.STACKTRACE, e);
			}
		} else {
			returnMap.put(Constants.ERROR_MESSAGE, "You do not have a custom insight image to delete");
			return WebUtility.getResponse(returnMap, 400);
		}
		
		returnMap.put("project_id", projectId);
		returnMap.put("project_name", projectName);
		returnMap.put("insight_id", insightId);
		returnMap.put("message", "Successfully deleted insight image");
		return WebUtility.getResponse(returnMap, 200);
	}

	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	///////////////////////////////////////////////////////
	
	/*
	 * Deprecated Methods To Delete
	 */
	
	@POST
	@Path("/databaseImage/upload")
	@Produces("application/json")
	@Deprecated
	public Response uploadDatabaseImage(@Context ServletContext context, @Context HttpServletRequest request) throws SQLException {
		Map<String, String> returnMap = new HashMap<>();

		HttpSession session = request.getSession(false);
		User user = null;
		if (session != null) {
			user = ((User) session.getAttribute(Constants.SESSION_USER));
			if (user == null) {
				HashMap<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Session could not be validated in order to upload the engine image");
				return WebUtility.getResponse(errorMap, 400);
			}

			if (user.isAnonymous()) {
				HashMap<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Must be logged in to upload an engine image");
				return WebUtility.getResponse(errorMap, 400);
			}
		} else {
			returnMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(returnMap, 400);
		}
		
		List<FileItem> fileItems = null;
		try {
			fileItems = processRequest(context, request, null);
		} catch (FileUploadException e) {
			HashMap<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error uploading file. Error = " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		// collect all of the data input on the form
		FileItem imageFile = null;
		String engineId = null;
		
		for (FileItem fi : fileItems) {
			String fieldName = fi.getFieldName();
			String value = fi.getString();
			if (fieldName.equals("file")) {
				imageFile = fi;
			}
			if (fieldName.equals("engineId")) {
				engineId = value;
			}
			if (fieldName.equals("databaseId")) {
				engineId = value;
			}
		}

		if (imageFile == null) {
			returnMap.put(Constants.ERROR_MESSAGE, "Could not find the file to upload for the insight in the request");
			return WebUtility.getResponse(returnMap, 400);
		} else if (engineId == null) {
			returnMap.put(Constants.ERROR_MESSAGE, "Need to pass the proper engine id to upload the image");
			return WebUtility.getResponse(returnMap, 400);
		}
		
		if (!SecurityEngineUtils.userCanEditEngine(user, engineId)) {
			returnMap.put(Constants.ERROR_MESSAGE, "User does not have access to this engine or the engine id does not exist");
			return WebUtility.getResponse(returnMap, 400);
		}
		
		String engineName = SecurityEngineUtils.getEngineAliasForId(engineId);
		String engineNameAndId = SmssUtilities.getUniqueName(engineName, engineId);
		
		Object[] engineTypeAndSubtype = SecurityEngineUtils.getEngineTypeAndSubtype(engineId);
		IEngine.CATALOG_TYPE engineType = (IEngine.CATALOG_TYPE) engineTypeAndSubtype[0];
		
		// will define these here up front
		String couchSelector = null;
		String localEngineImageFolderPath = null;
		String engineVersionPath = null;
		try {
			couchSelector = EngineUtility.getCouchSelector(engineType);
			localEngineImageFolderPath = EngineUtility.getLocalEngineImageDirectory(engineType);
			engineVersionPath = EngineUtility.getSpecificEngineVersionFolder(engineType, engineNameAndId);
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			returnMap.put(Constants.ERROR_MESSAGE, "Unknown engine type '"+engineType+"' for engine " + engineNameAndId);
			return WebUtility.getResponse(returnMap, 400);
		}

		// i always want to fix it in the engine version folder
		// so that way export works as expected
		// if it is on cloud - also push it to the images/<eType> folder and sync that
		// if it is on couchdb - push to that	
		
		// regardless, have to pull the engine
		Utility.getEngine(engineId, engineType, true);
		
		// local changes
		File newImageFileInVersionFolder = null;
		String newImageFileType = null;
		{
			// remove the old image files in the version folder
			// and push new one to the version folder
			File engineVersionF = new File( Utility.normalizePath(engineVersionPath));
			if (!engineVersionF.exists() || !engineVersionF.isDirectory()) {
				Boolean success = engineVersionF.mkdirs();
				if(!success) {
					classLogger.warn("Unable to make engine version folder at path " + engineVersionPath);
					returnMap.put(Constants.ERROR_MESSAGE, "Error occured attempting to make the engine version folder");
					return WebUtility.getResponse(returnMap, 400);
				}
			}
			
			// find all the image files and delete them
			File[] oldImageFiles = engineVersionF.listFiles(new FilenameFilter() {
				@Override
				public boolean accept(File dir, String name) {
					return name.startsWith("image.");
				}
			});
			
			for(File f : oldImageFiles) {
				f.delete();
				if(ClusterUtil.IS_CLUSTER) {
					ClusterUtil.deleteEngineCloudFile(engineId, engineType, f.getAbsolutePath());
				}
			}
			
			newImageFileType = imageFile.getContentType().split("/")[1];
			String imageFileName = "image." + newImageFileType;
			String imageLoc = engineVersionF.getAbsolutePath() + DIR_SEPARATOR + imageFileName;
			newImageFileInVersionFolder = new File(Utility.normalizePath(imageLoc));
			writeFile(imageFile, newImageFileInVersionFolder);
			ClusterUtil.copyLocalFileToEngineCloudFolder(engineId, engineType, imageLoc);
		}
		
		// now we figure out the cloud and where we push this
		if(CouchUtil.COUCH_ENABLED) {
			try {
				Map<String, String> selectors = new HashMap<>();
				selectors.put(couchSelector, engineId);
				CouchUtil.upload(couchSelector, selectors, imageFile);
			} catch (CouchException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Upload of engine image failed");
				return WebUtility.getResponse(errorMap, HttpStatus.SC_INTERNAL_SERVER_ERROR);
			}
		} else if(ClusterUtil.IS_CLUSTER) {
			// we need to push to the engine specific image folder
			File localCloudImageF = new File( Utility.normalizePath(localEngineImageFolderPath));
			File newImageFileInCloudFolder = new File(Utility.normalizePath(localEngineImageFolderPath + "/" + engineId + "." + newImageFileType));
			
			// find all the image files and delete them
			File[] oldImageFiles = localCloudImageF.listFiles(new FilenameFilter() {
				
				String engineId = null;
				
				private FilenameFilter init(String engineId) {
					this.engineId = engineId;
					return this;
				}
				
				@Override
				public boolean accept(File dir, String name) {
					return name.startsWith(engineId);
				}
			}.init(engineId));
			
			for(File f : oldImageFiles) {
				// delete on local
				f.delete();
				// delete from cloud as well
				ClusterUtil.deleteEngineAndProjectImage(engineType, f.getName());
			}
			
			// move the current image file to the cloud folder
			try {
				Files.copy(newImageFileInVersionFolder, newImageFileInCloudFolder);
			} catch (IOException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Upload of engine image failed");
				return WebUtility.getResponse(errorMap, HttpStatus.SC_INTERNAL_SERVER_ERROR);
			}
			ClusterUtil.pushEngineAndProjectImage(engineType, newImageFileInCloudFolder.getName());
		}
		// new keys
		returnMap.put("message", "Successfully updated engine image");
		returnMap.put("engine_id", engineId);
		returnMap.put("engine_name", engineName);
		// old keys
		returnMap.put("message", "Successfully updated app image");
		returnMap.put("database_id", engineId);
		returnMap.put("database_name", engineName);
		return WebUtility.getResponse(returnMap, 200);
	}
	
	@POST
	@Path("/databaseImage/delete")
	@Deprecated
	public Response deleteDatabaseImage(@Context HttpServletRequest request) throws SQLException {
		Map<String, String> returnMap = new HashMap<>();
		
		String engineId = request.getParameter("engineId");
		if(engineId == null) {
			engineId = request.getParameter("databaseId");
			if(engineId == null) {
				returnMap.put(Constants.ERROR_MESSAGE, "Need to pass the proper engine id to remove the image");
				return WebUtility.getResponse(returnMap, 400);
			}
		}
		
		HttpSession session = request.getSession(false);
		if (session != null) {
			User user = ((User) session.getAttribute(Constants.SESSION_USER));
			if (user == null) {
				HashMap<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Session could not be validated in order to upload the engine image");
				return WebUtility.getResponse(errorMap, 400);
			}

			if (user.isAnonymous()) {
				HashMap<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Must be logged in to upload an engine image");
				return WebUtility.getResponse(errorMap, 400);
			}

			if (!SecurityEngineUtils.userCanEditEngine(user, engineId)) {
				returnMap.put(Constants.ERROR_MESSAGE, "User does not have access to this engine or the engine id does not exist");
				return WebUtility.getResponse(returnMap, 400);
			}
		} else {
			returnMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(returnMap, 400);
		}
		
		String engineName = SecurityEngineUtils.getEngineAliasForId(engineId);
		String engineNameAndId = SmssUtilities.getUniqueName(engineName, engineId);
		
		Object[] engineTypeAndSubtype = SecurityEngineUtils.getEngineTypeAndSubtype(engineId);
		IEngine.CATALOG_TYPE engineType = (IEngine.CATALOG_TYPE) engineTypeAndSubtype[0];
		
		// will define these here up front
		String couchSelector = null;
		String localEngineImageFolderPath = null;
		String engineVersionPath = null;
		try {
			couchSelector = EngineUtility.getCouchSelector(engineType);
			localEngineImageFolderPath = EngineUtility.getLocalEngineImageDirectory(engineType);
			engineVersionPath = EngineUtility.getSpecificEngineVersionFolder(engineType, engineNameAndId);
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			returnMap.put(Constants.ERROR_MESSAGE, "Unknown engine type '"+engineType+"' for engine " + engineNameAndId);
			return WebUtility.getResponse(returnMap, 400);
		}
		
		// i always want to delete it in the engine version folder
		// so that way export works as expected
		// if it is on cloud - also delete it to the images/<eType> folder and sync that
		// if it is on couchdb - delete from that	
		
		// regardless, have to pull the engine
		Utility.getEngine(engineId, engineType, true);
		
		// local changes
		{
			// remove the old image files in the version folder
			// and push new one to the version folder
			File engineVersionF = new File(Utility.normalizePath(engineVersionPath));
			if (!engineVersionF.exists() || !engineVersionF.isDirectory()) {
				Boolean success = engineVersionF.mkdirs();
				if(!success) {
					classLogger.warn("Unable to make engine version folder at path " + engineVersionPath);
					returnMap.put(Constants.ERROR_MESSAGE, "Error occured attempting to make the engine version folder");
					return WebUtility.getResponse(returnMap, 400);
				}
			}
			
			// find all the image files and delete them
			File[] oldImageFiles = engineVersionF.listFiles(new FilenameFilter() {
				@Override
				public boolean accept(File dir, String name) {
					return name.startsWith("image.");
				}
			});
			
			for(File f : oldImageFiles) {
				f.delete();
				if(ClusterUtil.IS_CLUSTER) {
					ClusterUtil.deleteEngineCloudFile(engineId, engineType, f.getAbsolutePath());
				}
			}
		}
		
		// now we figure out the cloud and where we push this
		if(CouchUtil.COUCH_ENABLED) {
			try {
				Map<String, String> selectors = new HashMap<>();
				selectors.put(couchSelector, engineId);
				CouchUtil.delete(couchSelector, selectors);
			} catch (CouchException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Delete of engine image failed");
				return WebUtility.getResponse(errorMap, HttpStatus.SC_INTERNAL_SERVER_ERROR);
			}
		} else if(ClusterUtil.IS_CLUSTER) {
			// we need to push to the engine specific image folder
			File localCloudImageF = new File(Utility.normalizePath(localEngineImageFolderPath));
			// find all the image files and delete them
			File[] oldImageFiles = localCloudImageF.listFiles(new FilenameFilter() {
				
				String engineId = null;
				
				private FilenameFilter init(String engineId) {
					this.engineId = engineId;
					return this;
				}
				
				@Override
				public boolean accept(File dir, String name) {
					return name.startsWith(engineId);
				}
			}.init(engineId));
			
			for(File f : oldImageFiles) {
				// delete on local
				f.delete();
				// delete from cloud as well
				ClusterUtil.deleteEngineAndProjectImage(engineType, f.getName());
			}
		}
		
		// new keys
		returnMap.put("message", "Successfully deleted engine image");
		returnMap.put("engine_id", engineId);
		returnMap.put("engine_name", engineName);
		// old keys
		returnMap.put("database_id", engineId);
		returnMap.put("database_name", engineName);
		returnMap.put("message", "Successfully deleted database image");
		return WebUtility.getResponse(returnMap, 200);
	}
	
}
