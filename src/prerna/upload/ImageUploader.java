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
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.cluster.util.clients.AbstractCloudClient;
import prerna.engine.impl.SmssUtilities;
import prerna.io.connector.couch.CouchException;
import prerna.io.connector.couch.CouchUtil;
import prerna.nameserver.utility.MasterDatabaseUtility;
import prerna.util.AssetUtility;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;
import prerna.util.insight.InsightUtility;
import prerna.web.services.util.WebUtility;

@Path("/images")
public class ImageUploader extends Uploader {
	
	private static final Logger logger = LogManager.getLogger(ImageUploader.class);

	@POST
	@Path("/databaseImage/upload")
	@Produces("application/json")
	public Response uploadDatabaseImage(@Context ServletContext context, @Context HttpServletRequest request) throws SQLException {
		Map<String, String> returnMap = new HashMap<>();

		// base path is the db folder
		String filePath = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER) + DIR_SEPARATOR + Constants.DATABASE_FOLDER;
		filePath = Utility.normalizePath(filePath);
		
		List<FileItem> fileItems = processRequest(context, request, null);
		// collect all of the data input on the form
		FileItem imageFile = null;
		String databaseId = null;
		String databaseName = null;

		for (FileItem fi : fileItems) {
			String fieldName = fi.getFieldName();
			String value = fi.getString();
			if (fieldName.equals("file")) {
				imageFile = fi;
			}
			if (fieldName.equals("databaseId")) {
				databaseName = value;
			}
		}

		if (imageFile == null) {
			returnMap.put(Constants.ERROR_MESSAGE, "Could not find the file to upload for the insight in the request");
			return WebUtility.getResponse(returnMap, 400);
		} else if (databaseName == null) {
			returnMap.put(Constants.ERROR_MESSAGE, "Need to pass the proper database id to upload the image");
			return WebUtility.getResponse(returnMap, 400);
		}

		if (AbstractSecurityUtils.securityEnabled()) {
			HttpSession session = request.getSession(false);
			if (session != null) {
				User user = ((User) session.getAttribute(Constants.SESSION_USER));
				if (user == null) {
					HashMap<String, String> errorMap = new HashMap<>();
					errorMap.put(Constants.ERROR_MESSAGE, "Session could not be validated in order to upload the database image");
					return WebUtility.getResponse(errorMap, 400);
				}

				if (user.isAnonymous()) {
					HashMap<String, String> errorMap = new HashMap<>();
					errorMap.put(Constants.ERROR_MESSAGE, "Must be logged in to upload an database image");
					return WebUtility.getResponse(errorMap, 400);
				}

				try {
					databaseId = SecurityQueryUtils.testUserEngineIdForAlias(user, databaseName);
				} catch (Exception e) {
					returnMap.put(Constants.ERROR_MESSAGE, e.getMessage());
					return WebUtility.getResponse(returnMap, 400);
				}
				if (!SecurityEngineUtils.userCanEditEngine(user, databaseId)) {
					returnMap.put(Constants.ERROR_MESSAGE, "User does not have access to this database or the database id does not exist");
					return WebUtility.getResponse(returnMap, 400);
				}
				databaseName = SecurityEngineUtils.getEngineAliasForId(databaseId);
			} else {
				returnMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
				return WebUtility.getResponse(returnMap, 400);
			}
		} else {
			databaseId = MasterDatabaseUtility.testDatabaseIdIfAlias(databaseName);
			databaseName = MasterDatabaseUtility.getDatabaseAliasForId(databaseId);
			String appDir = filePath + DIR_SEPARATOR + SmssUtilities.getUniqueName(databaseName, databaseId);
			if (!(new File(appDir).exists())) {
				returnMap.put(Constants.ERROR_MESSAGE, "Could not find app directory");
				return WebUtility.getResponse(returnMap, 400);
			}
		}
		
		if(CouchUtil.COUCH_ENABLED) {
			try {
				Map<String, String> selectors = new HashMap<>();
				selectors.put(CouchUtil.DATABASE, databaseId);
				CouchUtil.upload(CouchUtil.DATABASE, selectors, imageFile);
			} catch (CouchException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Upload of database image failed");
				return WebUtility.getResponse(errorMap, HttpStatus.SC_INTERNAL_SERVER_ERROR);
			}
		} else {
			String imageDir = getDbImageDir(filePath, databaseId, databaseName);
			String imageLoc = getDbImageLoc(filePath, databaseId, databaseName, imageFile);
			
			File f = new File(imageDir);
			if (!f.exists()) {
				Boolean success = f.mkdirs();
				if(!success) {
					logger.info("Unable to make direction at location: " + Utility.cleanLogString(filePath));
				}
			}
			f = new File(Utility.normalizePath(imageLoc));
			// find all the existing image files
			// and delete them
			File[] oldImages = null;
			if (ClusterUtil.IS_CLUSTER) {
				FilenameFilter appIdFilter = new WildcardFileFilter(databaseId + "*");
				oldImages = f.getParentFile().listFiles(appIdFilter);
			} else {
				oldImages = InsightUtility.findImageFile(f.getParentFile());
			}
			// delete if any exist
			if (oldImages != null) {
				for (File oldI : oldImages) {
					Boolean success = oldI.delete();
					if (!success) {
						logger.info("Unable to delete file at location: " + Utility.cleanLogString(oldI.getAbsolutePath()));
					}
				}
			}
			writeFile(imageFile, f);
			try {
				if (ClusterUtil.IS_CLUSTER) {
					AbstractCloudClient.getClient().pushDatabaseImageFolder();
				}
			} catch (IOException ioe) {
				logger.error(Constants.STACKTRACE, ioe);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				logger.error(Constants.STACKTRACE, ie);
			}
		}
		
		returnMap.put("message", "Successfully updated app image");
		returnMap.put("database_id", databaseId);
		returnMap.put("database_name", databaseName);
		return WebUtility.getResponse(returnMap, 200);
	}
	
	@POST
	@Path("/databaseImage/delete")
	public Response deleteDatabaseImage(@Context HttpServletRequest request) throws SQLException {
		Map<String, String> returnMap = new HashMap<>();

		// base path is the db folder
		String filePath = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER) + DIR_SEPARATOR + "db";
		filePath = Utility.normalizePath(filePath);

		String appId = request.getParameter("databaseId");
		String appName = null;
		if(appId == null) {
			returnMap.put(Constants.ERROR_MESSAGE, "Need to pass the proper database id to remove the image");
			return WebUtility.getResponse(returnMap, 400);
		}

		if (AbstractSecurityUtils.securityEnabled()) {
			HttpSession session = request.getSession(false);
			if (session != null) {
				User user = ((User) session.getAttribute(Constants.SESSION_USER));
				if (user == null) {
					HashMap<String, String> errorMap = new HashMap<>();
					errorMap.put(Constants.ERROR_MESSAGE, "Session could not be validated in order to upload the database image");
					return WebUtility.getResponse(errorMap, 400);
				}

				if (user.isAnonymous()) {
					HashMap<String, String> errorMap = new HashMap<>();
					errorMap.put(Constants.ERROR_MESSAGE, "Must be logged in to upload an database image");
					return WebUtility.getResponse(errorMap, 400);
				}

				try {
					appId = SecurityQueryUtils.testUserEngineIdForAlias(user, appId);
				} catch (Exception e) {
					returnMap.put(Constants.ERROR_MESSAGE, e.getMessage());
					return WebUtility.getResponse(returnMap, 400);
				}
				if (!SecurityEngineUtils.userCanEditEngine(user, appId)) {
					returnMap.put(Constants.ERROR_MESSAGE, "User does not have access to this app or the database id does not exist");
					return WebUtility.getResponse(returnMap, 400);
				}
				appName = SecurityEngineUtils.getEngineAliasForId(appId);
			} else {
				returnMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
				return WebUtility.getResponse(returnMap, 400);
			}
		} else {
			appId = MasterDatabaseUtility.testDatabaseIdIfAlias(appId);
			appName = MasterDatabaseUtility.getDatabaseAliasForId(appId);
			String appDir = filePath + DIR_SEPARATOR + SmssUtilities.getUniqueName(appName, appId);
			if (!(new File(appDir).exists())) {
				returnMap.put(Constants.ERROR_MESSAGE, "Could not find app directory");
				return WebUtility.getResponse(returnMap, 400);
			}
		}
		
		if(CouchUtil.COUCH_ENABLED) {
			try {
				Map<String, String> selectors = new HashMap<>();
				selectors.put(CouchUtil.DATABASE, appId);
				CouchUtil.delete(CouchUtil.DATABASE, selectors);
			} catch (CouchException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Database image deletion failed");
				return WebUtility.getResponse(errorMap, HttpStatus.SC_INTERNAL_SERVER_ERROR);
			}
		}
		
		String imageDir = getDbImageDir(filePath, appId, appName);
		File f = new File(imageDir);
		File[] oldImages = null;
		if (ClusterUtil.IS_CLUSTER) {
			FilenameFilter appIdFilter = new WildcardFileFilter(appId + "*");
			oldImages = f.listFiles(appIdFilter);
		} else {
			oldImages = InsightUtility.findImageFile(f);
		}
		// delete if any exist
		if (oldImages != null) {
			for (File oldI : oldImages) {
				Boolean success = oldI.delete();
				if (!success) {
					logger.info("Unable to delete file at location: " + Utility.cleanLogString(oldI.getAbsolutePath()));
				}
			}
		}
		
		try {
			if (ClusterUtil.IS_CLUSTER) {
				AbstractCloudClient.getClient().pushDatabaseImageFolder();
			}
		} catch (IOException ioe) {
			logger.error(Constants.STACKTRACE, ioe);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			logger.error(Constants.STACKTRACE, ie);
		}
		
		returnMap.put("database_id", appId);
		returnMap.put("database_name", appName);
		returnMap.put("message", "Successfully deleted database image");
		return WebUtility.getResponse(returnMap, 200);
	}
	
	private String getDbImageDir(String filePath, String appId, String appName) {
		if(ClusterUtil.IS_CLUSTER){
			return ClusterUtil.IMAGES_FOLDER_PATH + DIR_SEPARATOR + "databases";
		}
		String baseFolder = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER);
		String versionFolder = baseFolder 
				+ DIR_SEPARATOR + Constants.DB_FOLDER
				+ DIR_SEPARATOR + SmssUtilities.getUniqueName(appName, appId)
				+ DIR_SEPARATOR + "app_root"
				+ DIR_SEPARATOR + "version";
		return versionFolder;
	}

	private String getDbImageLoc(String filePath, String id, String name, FileItem imageFile){
		String imageDir = getDbImageDir(filePath, id, name);
		if(ClusterUtil.IS_CLUSTER){
			return imageDir + DIR_SEPARATOR + id + "." + imageFile.getContentType().split("/")[1];
		}
		return imageDir + DIR_SEPARATOR + "image." + imageFile.getContentType().split("/")[1];
	}

	@POST
	@Path("/projectImage/upload")
	@Produces("application/json")
	public Response uploadProjectImage(@Context ServletContext context, @Context HttpServletRequest request) throws SQLException {
		Map<String, String> returnMap = new HashMap<>();

		// base path is the db folder
		String filePath = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER) + DIR_SEPARATOR + Constants.PROJECT_FOLDER;
		filePath = Utility.normalizePath(filePath);
		
		List<FileItem> fileItems = processRequest(context, request, null);
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

		if (AbstractSecurityUtils.securityEnabled()) {
			HttpSession session = request.getSession(false);
			if (session != null) {
				User user = ((User) session.getAttribute(Constants.SESSION_USER));
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
			} else {
				returnMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
				return WebUtility.getResponse(returnMap, 400);
			}
		} else {
			projectName = SecurityProjectUtils.getProjectAliasForId(projectId);
		}

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
			
			File f = new File(imageDir);
			if (!f.exists()) {
				Boolean success = f.mkdirs();
				if(!success) {
					logger.info("Unable to make direction at location: " + Utility.cleanLogString(filePath));
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
						logger.info("Unable to delete file at location: " + Utility.cleanLogString(oldI.getAbsolutePath()));
					}
				}
			}
			writeFile(imageFile, f);
			try {
				if (ClusterUtil.IS_CLUSTER) {
					AbstractCloudClient.getClient().pushProjectImageFolder();
				}
			} catch (IOException ioe) {
				logger.error(Constants.STACKTRACE, ioe);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				logger.error(Constants.STACKTRACE, ie);
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

		// base path is the db folder
		String filePath = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER) + DIR_SEPARATOR + Constants.PROJECT;
		filePath = Utility.normalizePath(filePath);

		String projectId = request.getParameter("projectId");
		String projectName = null;
		if(projectId == null) {
			returnMap.put(Constants.ERROR_MESSAGE, "Need to pass the proper project id to remove the image");
			return WebUtility.getResponse(returnMap, 400);
		}

		if (AbstractSecurityUtils.securityEnabled()) {
			HttpSession session = request.getSession(false);
			if (session != null) {
				User user = ((User) session.getAttribute(Constants.SESSION_USER));
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
		} else {
			projectName = SecurityProjectUtils.getProjectAliasForId(projectId);
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
		File f = new File(imageDir);
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
					logger.info("Unable to delete file at location: " + Utility.cleanLogString(oldI.getAbsolutePath()));
				}
			}
		}
		try {
			if (ClusterUtil.IS_CLUSTER) {
				AbstractCloudClient.getClient().pushProjectImageFolder();
			}
		} catch (IOException ioe) {
			logger.error(Constants.STACKTRACE, ioe);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			logger.error(Constants.STACKTRACE, ie);
		}
		
		returnMap.put("project_id", projectId);
		returnMap.put("project_name", projectName);
		returnMap.put("message", "Successfully deleted project image");
		return WebUtility.getResponse(returnMap, 200);
	}
	

	@POST
	@Path("/insightImage/upload")
	@Produces("application/json")
	public Response uploadInsightImage(@Context ServletContext context, @Context HttpServletRequest request) {
		Map<String, String> returnMap = new HashMap<>();
		
		// base path is the db folder
		String filePath = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER) + DIR_SEPARATOR + Constants.PROJECT_FOLDER;
		filePath = Utility.normalizePath(filePath);

		List<FileItem> fileItems = processRequest(context, request, null);
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

		if (AbstractSecurityUtils.securityEnabled()) {
			HttpSession session = request.getSession(false);
			if (session != null) {
				User user = ((User) session.getAttribute(Constants.SESSION_USER));
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
			} else {
				returnMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
				return WebUtility.getResponse(returnMap, 400);
			}
		} else {
			projectName = SecurityProjectUtils.getProjectAliasForId(projectId);
		}
		
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
					logger.info("Unable to make direction at location: " + Utility.cleanLogString(imageDir));
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
						logger.info("Unable to delete file at location: " + Utility.cleanLogString(oldI.getAbsolutePath()));
					}
	
				}
			}
			
			String imageFileName = "image." + imageFile.getContentType().split("/")[1];
			String imageLoc = imageDir + DIR_SEPARATOR + imageFileName;
			f = new File(Utility.normalizePath(imageLoc));
			writeFile(imageFile, f);
			try {
				if (ClusterUtil.IS_CLUSTER) {
					AbstractCloudClient.getClient().pushInsightImage(projectId, insightId, oldImageName, imageFileName);
				}
			} catch (IOException ioe) {
				logger.error(Constants.STACKTRACE, ioe);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				logger.error(Constants.STACKTRACE, ie);
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

		// base path is the db folder
		String filePath = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER) + DIR_SEPARATOR + Constants.PROJECT_FOLDER;
		filePath = Utility.normalizePath(filePath);

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

		if (AbstractSecurityUtils.securityEnabled()) {
			HttpSession session = request.getSession(false);
			if (session != null) {
				User user = ((User) session.getAttribute(Constants.SESSION_USER));
				if (user == null) {
					HashMap<String, String> errorMap = new HashMap<>();
					errorMap.put(Constants.ERROR_MESSAGE, "Session could not be validated in order to upload the app image");
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
				if (!SecurityProjectUtils.userCanEditProject(user, projectId)) {
					returnMap.put(Constants.ERROR_MESSAGE, "User does not have access to this project or the project id does not exist");
					return WebUtility.getResponse(returnMap, 400);
				}
				projectName = SecurityProjectUtils.getProjectAliasForId(projectId);
			} else {
				returnMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
				return WebUtility.getResponse(returnMap, 400);
			}
		} else {
			projectName = SecurityProjectUtils.getProjectAliasForId(projectId);
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
						logger.info("Unable to delete file at location: " + Utility.cleanLogString(oldI.getAbsolutePath()));
					}
				}
			}
			
			try {
				if (ClusterUtil.IS_CLUSTER) {
					AbstractCloudClient.getClient().pushInsightImage(projectId, insightId, oldImageName, null);
				}
			} catch (IOException ioe) {
				logger.error(Constants.STACKTRACE, ioe);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				logger.error(Constants.STACKTRACE, ie);
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
	
}
