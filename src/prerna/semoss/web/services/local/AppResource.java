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
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;

import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.engine.api.IEngine;
import prerna.engine.impl.SmssUtilities;
import prerna.nameserver.utility.MasterDatabaseUtility;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;
import prerna.util.insight.TextToGraphic;
import prerna.web.services.util.WebUtility;

@Path("/app-{appName}")
public class AppResource {

	private static final String DIR_SEPARATOR = java.nio.file.FileSystems.getDefault().getSeparator();
	
	/**
	 * Utility method to get the app only if person has access to it
	 * @param user
	 * @param appId
	 * @return
	 * @throws IllegalAccessException
	 */
	private IEngine getApp(User user, String appId) throws IllegalAccessException {
		if(AbstractSecurityUtils.securityEnabled()) {
			appId = SecurityQueryUtils.testUserEngineIdForAlias(user, appId);
			if(!SecurityQueryUtils.userCanViewEngine(user, appId)) {
				throw new IllegalAccessException("App " + appId + " does not exist or user does not have access to database");
			}
		} else {
			appId = MasterDatabaseUtility.testEngineIdIfAlias(appId);
			if(!MasterDatabaseUtility.getAllEngineIds().contains(appId)) {
				throw new IllegalAccessException("App " + appId + " does not exist");
			}
		}
		
		return Utility.getEngine(appId);
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
	public Response downloadAppImage(@Context HttpServletRequest request, @PathParam("appName") String app) {
		File exportFile = getAppImageFile(app);
		if(exportFile != null && exportFile.exists()) {
			String exportName = app + "_Image." + FilenameUtils.getExtension(exportFile.getAbsolutePath());
			return Response.status(200).entity(exportFile).header("Content-Disposition", "attachment; filename=" + exportName).build();
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
	public Response downloadInsightImage(@Context HttpServletRequest request, @PathParam("appName") String app, @QueryParam("rdbmsId") String id, @QueryParam("params") String params) {
		boolean securityEnabled = AbstractSecurityUtils.securityEnabled();
		String sessionId = null;
		if(securityEnabled){
			sessionId = request.getSession(false).getId();
		}
		
		File exportFile = getInsightImageFile(app, id, request.getHeader("Referer"), params, sessionId);
		if(exportFile != null && exportFile.exists()) {
			String exportName = app + "_Image." + FilenameUtils.getExtension(exportFile.getAbsolutePath());
			return Response.status(200).entity(exportFile).header("Content-Disposition", "attachment; filename=" + exportName).build();
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
	private File getInsightImageFile(String app, String id, String feUrl, String params, String sessionId) {
		String appId = MasterDatabaseUtility.testEngineIdIfAlias(app);
		String propFileLoc = DIHelper.getInstance().getProperty(appId + "_" + Constants.STORE);
		Properties prop = Utility.loadProperties(propFileLoc);
		app = prop.getProperty(Constants.ENGINE_ALIAS);
		
		String baseFolder = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER);
		String fileLocation = baseFolder + DIR_SEPARATOR + "db" + DIR_SEPARATOR + SmssUtilities.getUniqueName(app, appId) + DIR_SEPARATOR + "version";
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
				e.printStackTrace();
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
	public Response getAppWidget(@PathParam("appName") String app, @QueryParam("widget") String widgetName, @QueryParam("file") String fileName) {
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
				e.printStackTrace();
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
