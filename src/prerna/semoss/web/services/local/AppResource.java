package prerna.semoss.web.services.local;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Date;
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
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAppUtils;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.engine.api.IEngine;
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
	private static String defaultEmbedLogo = null;
	private static boolean noLogo = false;
	
	private static final Logger logger = Logger.getLogger(AppResource.class);
	private static final String STACKTRACE = "StackTrace: ";
	
	private boolean canAccessApp(User user, String appId) throws IllegalAccessException {
		if(AbstractSecurityUtils.securityEnabled()) {
			appId = SecurityQueryUtils.testUserEngineIdForAlias(user, appId);
			if(!SecurityAppUtils.userCanViewEngine(user, appId)) {
				throw new IllegalAccessException("App " + appId + " does not exist or user does not have access to database");
			}
		} else {
			appId = MasterDatabaseUtility.testEngineIdIfAlias(appId);
			if(!MasterDatabaseUtility.getAllEngineIds().contains(appId)) {
				throw new IllegalAccessException("App " + appId + " does not exist");
			}
		}
		
		return true;
	}
	
	private boolean canAccessInsight(User user, String appId, String insightId) throws IllegalAccessException {
		if(AbstractSecurityUtils.securityEnabled()) {
			appId = SecurityQueryUtils.testUserEngineIdForAlias(user, appId);
			if(!SecurityInsightUtils.userCanViewInsight(user, appId, insightId)) {
				throw new IllegalAccessException("Insight does not exist or user does not have access to view");
			}
		} else {
			appId = MasterDatabaseUtility.testEngineIdIfAlias(appId);
			if(!MasterDatabaseUtility.getAllEngineIds().contains(appId)) {
				throw new IllegalAccessException("App " + appId + " does not exist");
			}
		}
		
		return true;
	}
	
	/**
	 * Utility method to get the app only if person has access to it
	 * @param user
	 * @param appId
	 * @return
	 * @throws IllegalAccessException
	 */
	private IEngine getApp(User user, String appId) throws IllegalAccessException {
		canAccessApp(user, appId);
		return Utility.getEngine(appId);
	}
		
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	
	/*
	* Code below is around retrieving app assets
	*/

	@GET
	@Path("/landing")
	@Produces(MediaType.TEXT_HTML)
	public Response getAppLandingPage(@Context final Request coreRequest, @Context HttpServletRequest request, @PathParam("appId") String appId) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		try {
			canAccessApp(user, appId);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String propFileLoc = DIHelper.getInstance().getProperty(appId + "_" + Constants.STORE);
		Properties prop = Utility.loadProperties(propFileLoc);
		String appName = prop.getProperty(Constants.ENGINE_ALIAS);
		
		String baseFolder = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER);
		String fileLocation = baseFolder + DIR_SEPARATOR + "db" + DIR_SEPARATOR + SmssUtilities.getUniqueName(appName, appId) + DIR_SEPARATOR + "version/assets/landing.html";
		File file = new File(fileLocation);
		if(file != null && file.exists()) {
		    try {
		    	String html = new String(Files.readAllBytes(Paths.get(file.getAbsolutePath())));
				
				// want to cache this on browser if user has access
				CacheControl cc = new CacheControl();
				cc.setMaxAge(1);
				cc.setPrivate(true);
			    EntityTag etag = new EntityTag(Integer.toString(html.hashCode()));
			    ResponseBuilder builder = coreRequest.evaluatePreconditions(etag);

			    // cached resource did not change
			    if(builder != null) {
			        return builder.build();
			    }
				
				return Response.status(200).entity(html).cacheControl(cc).tag(etag).lastModified(new Date(file.lastModified())).build();
			} catch (IOException e) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("errorMessage", "Unable to load landing html file");
				return WebUtility.getResponse(errorMap, 404);
			}
		} else {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("errorMessage", "No custom landing page found");
			return WebUtility.getResponse(errorMap, 404);
		}
	} 
	
	@GET
	@Path("/downloadAppAsset/{relPath}")
	@Produces({MediaType.TEXT_HTML, MediaType.APPLICATION_OCTET_STREAM})
	public Response getAppFile(@Context final Request coreRequest, @Context HttpServletRequest request, @PathParam("appId") String appId, @PathParam("relPath") String relPath) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		try {
			canAccessApp(user, appId);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String propFileLoc = DIHelper.getInstance().getProperty(appId + "_" + Constants.STORE);
		Properties prop = Utility.loadProperties(propFileLoc);
		String appName = prop.getProperty(Constants.ENGINE_ALIAS);
		
		String baseFolder = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER);
		String fileLocation = baseFolder + DIR_SEPARATOR + "db" + DIR_SEPARATOR + SmssUtilities.getUniqueName(appName, appId) + DIR_SEPARATOR + "version/assets/" + relPath;
		File file = new File(fileLocation);
		if(file != null && file.exists()) {
		    try {
				String contents = new String(Files.readAllBytes(Paths.get(file.getAbsolutePath())));
				
				// want to cache this on browser if user has access
				CacheControl cc = new CacheControl();
				cc.setMaxAge(1);
				cc.setPrivate(true);
			    EntityTag etag = new EntityTag(Integer.toString(contents.hashCode()));
			    ResponseBuilder builder = coreRequest.evaluatePreconditions(etag);

			    // cached resource did not change
			    if(builder != null) {
			        return builder.build();
			    }
				
				return Response.status(200).entity(contents).cacheControl(cc).tag(etag).lastModified(new Date(file.lastModified())).build();
			} catch (IOException e) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("errorMessage", "Unable to load file");
				return WebUtility.getResponse(errorMap, 404);
			}
		} else {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("errorMessage", "No file found");
			return WebUtility.getResponse(errorMap, 404);
		}
	}
	
	@GET
	@Path("/embedLogo")
	@Produces({MediaType.TEXT_HTML, MediaType.APPLICATION_OCTET_STREAM})
	public Response getEmbedUrl(@Context final Request coreRequest, @Context HttpServletRequest request, @PathParam("appId") String appId, @QueryParam("insightId") String insightId) {
		User user = null;
		if(AbstractSecurityUtils.securityEnabled()) {
			try {
				user = ResourceUtility.getUser(request);
			} catch (IllegalAccessException e) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("error", "User session is invalid");
				return WebUtility.getResponse(errorMap, 401);
			}
			try {
				canAccessApp(user, appId);
			} catch (IllegalAccessException e) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("error", e.getMessage());
				return WebUtility.getResponse(errorMap, 401);
			}
		}
		
		//TODO: ALLOW APP SPECIFIC EMBED IMAGES 
		//TODO: ALLOW INSIGHT SPECIFIC EMBED IMAGES
		
		String embedLogo = getEmbedLogo();
		if(AppResource.noLogo) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "no defualt logo");
			return WebUtility.getResponse(errorMap, 404);
		}
			
		File file = new File(embedLogo);
		if(file != null && file.exists()) {
		    try {
				String contents = new String(Files.readAllBytes(Paths.get(file.getAbsolutePath())));
				
				// want to cache this on browser if user has access
				CacheControl cc = new CacheControl();
				cc.setMaxAge(1);
				cc.setPrivate(true);
			    EntityTag etag = new EntityTag(Integer.toString(contents.hashCode()));
			    ResponseBuilder builder = coreRequest.evaluatePreconditions(etag);

			    // cached resource did not change
			    if(builder != null) {
			        return builder.build();
			    }
				
			    String mimeType = Files.probeContentType(file.toPath());
				return Response.status(200).entity(contents).type(mimeType).cacheControl(cc).tag(etag).lastModified(new Date(file.lastModified())).build();
			} catch (IOException e) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("errorMessage", "Unable to load file");
				return WebUtility.getResponse(errorMap, 400);
			}
		} else {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("errorMessage", "Default logo file not found");
			return WebUtility.getResponse(errorMap, 404);
		}
	}
	
	private static String getEmbedLogo() {
		if(AppResource.defaultEmbedLogo == null) {
			String embedFileName = DIHelper.getInstance().getProperty(Constants.EMBED_URL_LOGO);
			if(embedFileName.equalsIgnoreCase("NONE")) {
				AppResource.noLogo = true;
				AppResource.defaultEmbedLogo = "NONE";
			} else {
				String baseFolder = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER);
				String embedFolder = baseFolder + DIR_SEPARATOR + "images" + DIR_SEPARATOR + "embed";
				AppResource.defaultEmbedLogo = embedFolder + DIR_SEPARATOR + embedFileName;
				AppResource.noLogo = false;
			}
		}
		return AppResource.defaultEmbedLogo;
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
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("error", "User session is invalid");
				return WebUtility.getResponse(errorMap, 401);
			}
			try {
				canAccessApp(user, appId);
			} catch (IllegalAccessException e) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("error", e.getMessage());
				return WebUtility.getResponse(errorMap, 401);
			}
		}
		
		File exportFile = getAppImageFile(appId);
		if(exportFile != null && exportFile.exists()) {
			String exportName = appId + "_Image." + FilenameUtils.getExtension(exportFile.getAbsolutePath());
			// want to cache this on browser if user has access
			CacheControl cc = new CacheControl();
			cc.setMaxAge(86400);
			cc.setPrivate(true);
			cc.setMustRevalidate(true);
		    EntityTag etag = new EntityTag(Integer.toString(exportFile.hashCode()));
		    ResponseBuilder builder = coreRequest.evaluatePreconditions(etag);

		    // cached resource did not change
		    if(builder != null) {
		        return builder.build();
		    }
		    
			return Response.status(200).entity(exportFile).header("Content-Disposition", "attachment; filename=" + exportName)
					.cacheControl(cc).tag(etag).lastModified(new Date(exportFile.lastModified())).build();
		} else {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("errorMessage", "error sending image file");
			return WebUtility.getResponse(errorMap, 400);
		}
	}
	
	/**
	 * Use to find the file for the image
	 * @param app
	 * @return
	 */
	protected File getAppImageFile(String app) {
		String appId = MasterDatabaseUtility.testEngineIdIfAlias(app);
		if(ClusterUtil.IS_CLUSTER){
			return 	ClusterUtil.getImage(app);
		}
		String propFileLoc = DIHelper.getInstance().getProperty(appId + "_" + Constants.STORE);
		if(propFileLoc == null && !app.equals("NEWSEMOSSAPP")) {
			String imageDir = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER) + "/images/stock/";
			return new File(imageDir + "color-logo.png");
		}
		Properties prop = Utility.loadProperties(propFileLoc);
		app = prop.getProperty(Constants.ENGINE_ALIAS);
		
		String baseFolder = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER);
		String fileLocation = baseFolder + DIR_SEPARATOR + "db" + DIR_SEPARATOR + SmssUtilities.getUniqueName(app, appId) + DIR_SEPARATOR + "version";
		File f = findImageFile(fileLocation);
		if(f != null) {
			return f;
		} else {
			// make the image
			f = new File(fileLocation);
			f.mkdirs();
			fileLocation = fileLocation + DIR_SEPARATOR + "image.png";
			if(app != null) {
				TextToGraphic.makeImage(app, fileLocation);
			} else {
				TextToGraphic.makeImage(appId, fileLocation);
			}
			f = new File(fileLocation);
			return f;
		}
	}
	
	@GET
	@Path("/insightImage/download")
	@Produces({MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_SVG_XML})
	public Response downloadInsightImage(@Context final Request coreRequest, @Context HttpServletRequest request, @PathParam("appId") String appId, @QueryParam("rdbmsId") String id, @QueryParam("params") String params) {
		String sessionId = null;
		if(AbstractSecurityUtils.securityEnabled()) {
			User user = null;
			try {
				user = ResourceUtility.getUser(request);
			} catch (IllegalAccessException e) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("error", "User session is invalid");
				return WebUtility.getResponse(errorMap, 401);
			}
			try {
				canAccessInsight(user, appId, id);
			} catch (IllegalAccessException e) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("error", e.getMessage());
				return WebUtility.getResponse(errorMap, 401);
			}
			
			sessionId = request.getSession(false).getId();
		}		

		File exportFile = getInsightImageFile(appId, id, request.getHeader("Referer"), params, sessionId);
		if(exportFile != null && exportFile.exists()) {
			String exportName = appId + "_Image." + FilenameUtils.getExtension(exportFile.getAbsolutePath());
			// want to cache this on browser if user has access
			CacheControl cc = new CacheControl();
			cc.setMaxAge(86400);
			cc.setPrivate(true);
			cc.setMustRevalidate(true);
		    EntityTag etag = new EntityTag(Integer.toString(exportFile.hashCode()));
		    ResponseBuilder builder = coreRequest.evaluatePreconditions(etag);

		    // cached resource did not change
		    if(builder != null) {
		        return builder.build();
		    }
		    
			return Response.status(200).entity(exportFile).header("Content-Disposition", "attachment; filename=" + exportName)
					.cacheControl(cc).tag(etag).lastModified(new Date(exportFile.lastModified())).build();
		} else {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("errorMessage", "Error sending image file");
			return WebUtility.getResponse(errorMap, 404);
		}
	}
	
	/**
	 * Use to find the file for the image
	 * @param app
	 * @return
	 */
	private File getInsightImageFile(String appId, String id, String feUrl, String params, String sessionId) {
		String propFileLoc = DIHelper.getInstance().getProperty(appId + "_" + Constants.STORE);
		Properties prop = Utility.loadProperties(propFileLoc);
		String appName = prop.getProperty(Constants.ENGINE_ALIAS);
		
		String baseFolder = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER);
		String fileLocation = baseFolder + DIR_SEPARATOR + "db" + DIR_SEPARATOR + SmssUtilities.getUniqueName(appName, appId) + DIR_SEPARATOR + "version";
		if(params != null && !params.isEmpty() && !params.equals("undefined")) {
			String encodedParams = Utility.encodeURIComponent(params);
			fileLocation = fileLocation + 
					DIR_SEPARATOR + id + 
					DIR_SEPARATOR + "params" + 
					DIR_SEPARATOR + encodedParams;
		} else {
			fileLocation = fileLocation + DIR_SEPARATOR + id;
		}
		File f = findImageFile(fileLocation);
		if(f != null && f.exists()) {
			return f;
		} else {
			// try making the image
			// JK! this is super annoying when running a bunch of 
			// insights at the same time which is what happens 
			// currently on the app home page
//			if (!ClusterUtil.IS_CLUSTER) {
//				if(feUrl != null) {
//					try {
//						ImageCaptureReactor.runImageCapture(feUrl, appId, id, params, sessionId);
//					}
//					catch(Exception | NoSuchMethodError er) {
//						//Image Capture will not run. No image exists nor will be made. The exception kills the rest.
//						// return stock image
//						er.printStackTrace();
//						f = AbstractSecurityUtils.getStockImage(appId, id);
//						return f;
//					}
//				}
//			}
			// the image capture ran
			// let us try to see if there is a file now...
			f = findImageFile(fileLocation);
			if(f != null && f.exists()) {
				return f;
			} else {
				// return stock image
				f = AbstractSecurityUtils.getStockImage(appId, id);
				return f;
			}
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
		List<String> extensions = new Vector<String>();
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
	    		logger.error(STACKTRACE, e);
			}
		}
	}
	
	///////////////////////////////////////////////////////
	///////////////////////////////////////////////////////
	///////////////////////////////////////////////////////
	///////////////////////////////////////////////////////
	///////////////////////////////////////////////////////

	/*
	 * Currently not used below...
	 */
	
	@GET
	@Path("/appWidget")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	public Response getAppWidget(@PathParam("appId") String app, @QueryParam("widget") String widgetName, @QueryParam("file") String fileName) {
		final String basePath = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER);
		String appWidgetDirLoc = basePath + DIR_SEPARATOR + "db" + 
				DIR_SEPARATOR + app + 
				DIR_SEPARATOR + "version" + 
				DIR_SEPARATOR + "widgets";

		// Get widget file
		String widgetFile = appWidgetDirLoc + DIR_SEPARATOR + widgetName + DIR_SEPARATOR + fileName;
		File f = new File(widgetFile);
		FileInputStream fis = null;
		if (f.exists()) {
			try {
				fis = new FileInputStream(f);
				byte[] byteArray = IOUtils.toByteArray(fis);
				// return file
				return Response.status(200).entity(byteArray).build();
			} catch (IOException e) {
	    		logger.error(STACKTRACE, e);
			} finally {
				closeStream(fis);
			}
		}
		// return error
		Map<String, String> errorMap = new HashMap<String, String>();
		errorMap.put("errorMessage", "error sending widget file " + widgetName + "\\" + fileName);
		return WebUtility.getResponse(errorMap, 400);
	}

}
