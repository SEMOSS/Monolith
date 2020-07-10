package prerna.upload;

import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

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
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAppUtils;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.cluster.util.CloudClient;
import prerna.cluster.util.ClusterUtil;
import prerna.engine.impl.SmssUtilities;
import prerna.nameserver.utility.MasterDatabaseUtility;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/images")
public class ImageUploader extends Uploader {
	
	private static final Logger logger = LogManager.getLogger(ImageUploader.class);

	@POST
	@Path("/appImage/upload")
	@Produces("application/json")
	public Response uploadAppImage(@Context ServletContext context, @Context HttpServletRequest request) throws SQLException {
		Map<String, String> returnMap = new HashMap<>();

		// base path is the db folder
		String filePath = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER) + DIR_SEPARATOR + "db";
		filePath = Utility.normalizePath(filePath);
		
		List<FileItem> fileItems = processRequest(context, request, null);
		// collect all of the data input on the form
		FileItem imageFile = null;
		String appId = null;
		String appName = null;

		for (FileItem fi : fileItems) {
			String fieldName = fi.getFieldName();
			String value = fi.getString();
			if (fieldName.equals("file")) {
				imageFile = fi;
			}
			if (fieldName.equals("app")) {
				appName = value;
			}
		}

		if (imageFile == null) {
			returnMap.put(Constants.ERROR_MESSAGE, "Could not find the file to upload for the insight in the request");
			return WebUtility.getResponse(returnMap, 400);
		} else if (appName == null) {
			returnMap.put(Constants.ERROR_MESSAGE, "Need to pass the proper app id to upload the image");
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
					appId = SecurityQueryUtils.testUserEngineIdForAlias(user, appName);
				} catch (Exception e) {
					returnMap.put(Constants.ERROR_MESSAGE, e.getMessage());
					return WebUtility.getResponse(returnMap, 400);
				}
				if (!SecurityAppUtils.userCanEditEngine(user, appId)) {
					returnMap.put(Constants.ERROR_MESSAGE, "User does not have access to this app or the app id does not exist");
					return WebUtility.getResponse(returnMap, 400);
				}
				appName = SecurityQueryUtils.getEngineAliasForId(appId);
			} else {
				returnMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
				return WebUtility.getResponse(returnMap, 400);
			}
		} else {
			appId = MasterDatabaseUtility.testEngineIdIfAlias(appName);
			appName = MasterDatabaseUtility.getEngineAliasForId(appId);
			String appDir = filePath + DIR_SEPARATOR + SmssUtilities.getUniqueName(appName, appId);
			if (!(new File(appDir).exists())) {
				returnMap.put(Constants.ERROR_MESSAGE, "Could not find app directory");
				return WebUtility.getResponse(returnMap, 400);
			}
		}

		String imageDir = getImageDir(filePath, appId, appName);
		String imageLoc = getImageLoc(filePath, appId, appName, imageFile);

		File f = new File(imageDir);
		if (!f.exists()) {
			Boolean success = f.mkdirs();
			if(!success) {
				logger.info("Unable to make direction at location: " + Utility.cleanLogString(filePath));
			}
		}
		f = new File(imageLoc);
		// find all the existing image files
		// and delete them
		File[] oldImages = null;
		if (ClusterUtil.IS_CLUSTER) {
			FilenameFilter appIdFilter = new WildcardFileFilter(appId + "*");
			oldImages = f.getParentFile().listFiles(appIdFilter);
		} else {
			oldImages = findImageFile(f.getParentFile());
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
				CloudClient.getClient().pushImageFolder();
			}
		} catch (IOException ioe) {
			logger.error(Constants.STACKTRACE, ioe);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			logger.error(Constants.STACKTRACE, ie);
		}
		
		returnMap.put("message", "successfully updated app image");
		returnMap.put("app_id", appId);
		returnMap.put("app_name", appName);
		return WebUtility.getResponse(returnMap, 200);
	}
	
	@POST
	@Path("/appImage/delete")
	public Response deleteAppImage(@Context HttpServletRequest request) throws SQLException {
		Map<String, String> returnMap = new HashMap<>();

		// base path is the db folder
		String filePath = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER) + DIR_SEPARATOR + "db";
		filePath = Utility.normalizePath(filePath);

		String appId = request.getParameter("appId");
		String appName = null;
		if(appId == null) {
			returnMap.put(Constants.ERROR_MESSAGE, "Need to pass the proper app id to remove the image");
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
					appId = SecurityQueryUtils.testUserEngineIdForAlias(user, appId);
				} catch (Exception e) {
					returnMap.put(Constants.ERROR_MESSAGE, e.getMessage());
					return WebUtility.getResponse(returnMap, 400);
				}
				if (!SecurityAppUtils.userCanEditEngine(user, appId)) {
					returnMap.put(Constants.ERROR_MESSAGE, "User does not have access to this app or the app id does not exist");
					return WebUtility.getResponse(returnMap, 400);
				}
				appName = SecurityQueryUtils.getEngineAliasForId(appId);
			} else {
				returnMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
				return WebUtility.getResponse(returnMap, 400);
			}
		} else {
			appId = MasterDatabaseUtility.testEngineIdIfAlias(appId);
			appName = MasterDatabaseUtility.getEngineAliasForId(appId);
			String appDir = filePath + DIR_SEPARATOR + SmssUtilities.getUniqueName(appName, appId);
			if (!(new File(appDir).exists())) {
				returnMap.put(Constants.ERROR_MESSAGE, "Could not find app directory");
				return WebUtility.getResponse(returnMap, 400);
			}
		}

		String imageDir = getImageDir(filePath, appId, appName);
		File f = new File(imageDir);
		File[] oldImages = null;
		if (ClusterUtil.IS_CLUSTER) {
			FilenameFilter appIdFilter = new WildcardFileFilter(appId + "*");
			oldImages = f.listFiles(appIdFilter);
		} else {
			oldImages = findImageFile(f);
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
				CloudClient.getClient().pushImageFolder();
			}
		} catch (IOException ioe) {
			logger.error(Constants.STACKTRACE, ioe);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			logger.error(Constants.STACKTRACE, ie);
		}

		returnMap.put("app_id", appId);
		returnMap.put("app_name", appName);
		returnMap.put("message", "successfully deleted app image");
		return WebUtility.getResponse(returnMap, 200);
	}

	private String getImageDir(String filePath, String appId, String appName) {
		if(ClusterUtil.IS_CLUSTER){
			return ClusterUtil.IMAGES_FOLDER_PATH + DIR_SEPARATOR + "apps";
		}
		return filePath + DIR_SEPARATOR + SmssUtilities.getUniqueName(appName, appId) + DIR_SEPARATOR + "version";
	}

	private String getImageLoc(String filePath, String appId, String appName, FileItem imageFile){
		String imageDir = getImageDir(filePath, appId, appName);
		if(ClusterUtil.IS_CLUSTER){
			return imageDir + DIR_SEPARATOR + appId + "." + imageFile.getContentType().split("/")[1];
		}
		return imageDir + DIR_SEPARATOR + "image." + imageFile.getContentType().split("/")[1];
	}

	@POST
	@Path("/insightImage/upload")
	@Produces("application/json")
	public Response uploadInsightImage(@Context ServletContext context, @Context HttpServletRequest request) {
		Map<String, String> returnMap = new HashMap<>();
		
		// base path is the db folder
		String filePath = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER) + DIR_SEPARATOR + "db";
		filePath = Utility.normalizePath(filePath);

		List<FileItem> fileItems = processRequest(context, request, null);
		// collect all of the data input on the form
		FileItem imageFile = null;
		String appId = null;
		String appName = null;
		String insightId = null;

		for (FileItem fi : fileItems) {
			String fieldName = fi.getFieldName();
			String value = fi.getString();
			if (fieldName.equals("file")) {
				imageFile = fi;
			}
			if (fieldName.equals("app")) {
				appName = value;
			}
			if (fieldName.equals("insightId")) {
				insightId = value;
			}
		}

		if (imageFile == null) {
			returnMap.put(Constants.ERROR_MESSAGE, "Could not find the file to upload for the insight in the request");
			return WebUtility.getResponse(returnMap, 400);
		} else if (appName == null || insightId == null) {
			returnMap.put(Constants.ERROR_MESSAGE, "Need to pass the proper app and insight ids to upload the image");
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
					appId = SecurityQueryUtils.testUserEngineIdForAlias(user, appName);
				} catch (Exception e) {
					returnMap.put(Constants.ERROR_MESSAGE, e.getMessage());
					return WebUtility.getResponse(returnMap, 400);
				}
				if (!SecurityInsightUtils.userCanEditInsight(user, appId, insightId)) {
					returnMap.put(Constants.ERROR_MESSAGE, "User does not have access to edit this insight within the app");
					return WebUtility.getResponse(returnMap, 400);
				}
				appName = SecurityQueryUtils.getEngineAliasForId(appId);
			} else {
				returnMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
				return WebUtility.getResponse(returnMap, 400);
			}
		} else {
			appId = MasterDatabaseUtility.testEngineIdIfAlias(appName);
			appName = MasterDatabaseUtility.getEngineAliasForId(appId);
			String appDir = filePath + DIR_SEPARATOR + SmssUtilities.getUniqueName(appName, appId);
			if (!(new File(appDir).exists())) {
				returnMap.put(Constants.ERROR_MESSAGE, "Could not find app directory");
				return WebUtility.getResponse(returnMap, 400);
			}
		}

		// now that we have the app name
		// and the image file
		// we want to write it into the app location
		String imageDir = filePath + DIR_SEPARATOR + SmssUtilities.getUniqueName(appName, appId) + DIR_SEPARATOR + "version" + DIR_SEPARATOR + insightId;
		File f = new File(imageDir);
		if (!f.exists()) {
			Boolean success = f.mkdirs();
			if(!success) {
				logger.info("Unable to make direction at location: " + Utility.cleanLogString(imageDir));
			}
		}
		// find all the existing image files
		// and delete them
		File[] oldImages = findImageFile(f);
		// delete if any exist
		if (oldImages != null) {
			for (File oldI : oldImages) {
				Boolean success = oldI.delete();
				if(!success) {
					logger.info("Unable to delete file at location: " + Utility.cleanLogString(oldI.getAbsolutePath()));
				}

			}
		}
				
		String imageLoc = imageDir + DIR_SEPARATOR + "image." + imageFile.getContentType().split("/")[1];
		f = new File(imageLoc);
		writeFile(imageFile, f);

		returnMap.put("app_id", appId);
		returnMap.put("app_name", appName);
		returnMap.put("insight_id", insightId);
		returnMap.put("message", "successfully updated insight image");
		return WebUtility.getResponse(returnMap, 200);
	}
	
	@POST
	@Path("/insightImage/delete")
	public Response deleteInsightImage(@Context HttpServletRequest request) throws SQLException {
		Map<String, String> returnMap = new HashMap<>();

		// base path is the db folder
		String filePath = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER) + DIR_SEPARATOR + "db";
		filePath = Utility.normalizePath(filePath);

		String appId = request.getParameter("appId");
		String appName = null;
		String insightId = request.getParameter("insightId");
		if(appId == null) {
			returnMap.put(Constants.ERROR_MESSAGE, "Need to pass the proper app id to remove the image");
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
					appId = SecurityQueryUtils.testUserEngineIdForAlias(user, appId);
				} catch (Exception e) {
					returnMap.put(Constants.ERROR_MESSAGE, e.getMessage());
					return WebUtility.getResponse(returnMap, 400);
				}
				if (!SecurityAppUtils.userCanEditEngine(user, appId)) {
					returnMap.put(Constants.ERROR_MESSAGE, "User does not have access to this app or the app id does not exist");
					return WebUtility.getResponse(returnMap, 400);
				}
				appName = SecurityQueryUtils.getEngineAliasForId(appId);
			} else {
				returnMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
				return WebUtility.getResponse(returnMap, 400);
			}
		} else {
			appId = MasterDatabaseUtility.testEngineIdIfAlias(appId);
			appName = MasterDatabaseUtility.getEngineAliasForId(appId);
			String appDir = filePath + DIR_SEPARATOR + SmssUtilities.getUniqueName(appName, appId);
			if (!(new File(appDir).exists())) {
				returnMap.put(Constants.ERROR_MESSAGE, "Could not find app directory");
				return WebUtility.getResponse(returnMap, 400);
			}
		}

		// now that we have the app name
		// and the image file
		// we want to write it into the app location
		String imageDir = filePath + DIR_SEPARATOR + SmssUtilities.getUniqueName(appName, appId) + DIR_SEPARATOR + "version" + DIR_SEPARATOR + insightId;
		File f = new File(imageDir);
		if (f.exists()) {
			// find all the existing image files
			// and delete them
			File[] oldImages = findImageFile(f);
			// delete if any exist
			if (oldImages != null) {
				for (File oldI : oldImages) {
					Boolean success = oldI.delete();
					if(!success) {
						logger.info("Unable to delete file at location: " + Utility.cleanLogString(oldI.getAbsolutePath()));
					}
				}
			}
		} else {
			returnMap.put(Constants.ERROR_MESSAGE, "You do not have a custom insight image to delete");
			return WebUtility.getResponse(returnMap, 400);
		}

		returnMap.put("app_id", appId);
		returnMap.put("app_name", appName);
		returnMap.put("insight_id", insightId);
		returnMap.put("message", "successfully deleted insight image");
		return WebUtility.getResponse(returnMap, 200);
	}
	
	/**
	 * Find an image in the directory
	 * 
	 * @param baseDir
	 * @return
	 */
	public static File[] findImageFile(String baseDir) {
		List<String> extensions = new Vector<>();
		extensions.add("image.png");
		extensions.add("image.jpeg");
		extensions.add("image.jpg");
		extensions.add("image.gif");
		extensions.add("image.svg");
		FileFilter imageExtensionFilter = new WildcardFileFilter(extensions);
		File baseFolder = new File(baseDir);

		return baseFolder.listFiles(imageExtensionFilter);
	}

	/**
	 * Find an image in the directory
	 * 
	 * @param baseDir
	 * @return
	 */
	public static File[] findImageFile(File baseFolder) {
		List<String> extensions = new Vector<>();
		extensions.add("image.png");
		extensions.add("image.jpeg");
		extensions.add("image.jpg");
		extensions.add("image.gif");
		extensions.add("image.svg");
		FileFilter imageExtensionFilter = new WildcardFileFilter(extensions);

		return baseFolder.listFiles(imageExtensionFilter);
	}
}
