package prerna.semoss.web.services.local;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAppUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.engine.impl.SmssUtilities;
import prerna.nameserver.utility.MasterDatabaseUtility;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;
import prerna.util.insight.TextToGraphic;
import prerna.web.services.util.WebUtility;

@Path("/app-{appId}")
public class AppResource {

	private static final String DIR_SEPARATOR = java.nio.file.FileSystems.getDefault().getSeparator();
	
	private static final Logger logger = LogManager.getLogger(AppResource.class);
	
	private boolean canAccessApp(User user, String appId) throws IllegalAccessException {
		if(AbstractSecurityUtils.securityEnabled()) {
			appId = SecurityQueryUtils.testUserDatabaseIdForAlias(user, appId);
			if(!SecurityAppUtils.userCanViewDatabase(user, appId)) {
				throw new IllegalAccessException("App " + appId + " does not exist or user does not have access to database");
			}
		} else {
			appId = MasterDatabaseUtility.testDatabaseIdIfAlias(appId);
			if(!MasterDatabaseUtility.getAllDatabaseIds().contains(appId)) {
				throw new IllegalAccessException("App " + appId + " does not exist");
			}
		}
		
		return true;
	}
	
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////

	/*
	 * Code below is around app images and insight images
	 */
	
	@GET
	@Path("/appImage/download")
	@Produces({MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_SVG_XML})
	public Response downloadAppImage(@Context final Request coreRequest, @Context HttpServletRequest request, @PathParam("appId") String appId) {
		if(AbstractSecurityUtils.securityEnabled()) {
			User user = null;
			try {
				user = ResourceUtility.getUser(request);
			} catch (IllegalAccessException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put("error", "User session is invalid");
				return WebUtility.getResponse(errorMap, 401);
			}
			try {
				canAccessApp(user, appId);
			} catch (IllegalAccessException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put("error", e.getMessage());
				return WebUtility.getResponse(errorMap, 401);
			}
		}
		
		File exportFile = getAppImageFile(appId);
		if(exportFile != null && exportFile.exists()) {
			String exportName = appId + "_Image." + FilenameUtils.getExtension(exportFile.getAbsolutePath());
			// want to cache this on browser if user has access
//			CacheControl cc = new CacheControl();
//			cc.setMaxAge(86400);
//			cc.setPrivate(true);
//			cc.setMustRevalidate(true);
		    EntityTag etag = new EntityTag(Integer.toString(exportFile.hashCode()));
		    ResponseBuilder builder = coreRequest.evaluatePreconditions(etag);

		    // cached resource did not change
		    if(builder != null) {
		        return builder.build();
		    }
		    
			return Response.status(200).entity(exportFile).header("Content-Disposition", "attachment; filename=" + exportName)
//					.cacheControl(cc)
					.tag(etag)
//					.lastModified(new Date(exportFile.lastModified()))
					.build();
		} else {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("errorMessage", "error sending image file");
			return WebUtility.getResponse(errorMap, 400);
		}
	}
	
	/**
	 * Use to find the file for the image
	 * @param appId
	 * @return
	 */
	protected File getAppImageFile(String appId) {
		appId = MasterDatabaseUtility.testDatabaseIdIfAlias(appId);
		if(ClusterUtil.IS_CLUSTER){
			return ClusterUtil.getDatabaseImage(appId);
		}
		String propFileLoc = (String) DIHelper.getInstance().getDbProperty(appId + "_" + Constants.STORE);
		if(propFileLoc == null && !appId.equals("NEWSEMOSSAPP")) {
			String imageDir = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER) + "/images/stock/";
			return new File(imageDir + "color-logo.png");
		}
		Properties prop = Utility.loadProperties(propFileLoc);
		String appName = prop.getProperty(Constants.ENGINE_ALIAS);
		
		String baseFolder = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER);
		String fileLocation = baseFolder + DIR_SEPARATOR + Constants.DB_FOLDER + DIR_SEPARATOR 
				+ SmssUtilities.getUniqueName(appName, appId) 
				+ DIR_SEPARATOR + "app_root" + DIR_SEPARATOR + "version";
		//String fileLocation = AssetUtility.getAppAssetVersionFolder(app, appId);

		File f = findImageFile(fileLocation);
		if(f != null) {
			return f;
		} else {
			// make the image
			f = new File(fileLocation);
			if(!f.exists()) {
			Boolean success = f.mkdirs();
			if(!success) {
				logger.info("Unable to make direction at location: " + Utility.cleanLogString(fileLocation));
			}
			}
			fileLocation = fileLocation + DIR_SEPARATOR + "image.png";
			if(appId != null) {
				TextToGraphic.makeImage(appId, fileLocation);
			} else {
				TextToGraphic.makeImage(appId, fileLocation);
			}
			f = new File(fileLocation);
			return f;
		}
	}
	
	/////////////////////////////////////////////////////////////////
	
	/*
	 * Image utility methods
	 */
	
	/**
	 * Find an image in the directory
	 * @param baseDir
	 * @return
	 */
	private File findImageFile(String baseDir) {
		List<String> extensions = new Vector<>();
		extensions.add("image.png");
		extensions.add("image.jpeg");
		extensions.add("image.jpg");
		extensions.add("image.gif");
		extensions.add("image.svg");
		FileFilter imageExtensionFilter = new WildcardFileFilter(extensions);
		File baseFolder = new File(baseDir);
		File[] imageFiles = baseFolder.listFiles(imageExtensionFilter);
		if(imageFiles != null && imageFiles.length > 0) {
			return imageFiles[0];
		}
		return null;
	}
	
	/**
	 * Close a file stream
	 * @param fis
	 */
	protected void closeStream(FileInputStream fis) {
		if(fis != null) {
			try {
				fis.close();
			} catch (IOException e) {
	    		logger.error(Constants.STACKTRACE, e);
			}
		}
	}
	
}
