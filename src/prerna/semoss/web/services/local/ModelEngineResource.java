package prerna.semoss.web.services.local;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
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
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.engine.api.IModelEngine;
import prerna.engine.impl.SmssUtilities;
import prerna.io.connector.couch.CouchException;
import prerna.io.connector.couch.CouchUtil;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;
import prerna.util.insight.TextToGraphic;
import prerna.web.services.util.WebUtility;

@Path("/model-{modelId}")
public class ModelEngineResource {

	private static final String DIR_SEPARATOR = java.nio.file.FileSystems.getDefault().getSeparator();
	
	private static final Logger logger = LogManager.getLogger(ModelEngineResource.class);
	
	private boolean canViewModel(User user, String modelId) throws IllegalAccessException {
		if(AbstractSecurityUtils.securityEnabled()) {
			modelId = SecurityQueryUtils.testUserEngineIdForAlias(user, modelId);
			if(!SecurityEngineUtils.userCanViewEngine(user, modelId)
					&& !SecurityEngineUtils.engineIsDiscoverable(modelId)) {
				throw new IllegalAccessException("Model " + modelId + " does not exist or user does not have access");
			}
		}
		
		return true;
	}
	
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	
	@POST
	@Path("/updateSmssFile")
	@Produces("application/json;charset=utf-8")
	public Response updateSmssFile(@Context HttpServletRequest request, @PathParam("storageId") String storageId) {
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
				boolean isAdmin = SecurityAdminUtils.userIsAdmin(user);
				if(!isAdmin) {
					boolean isOwner = SecurityEngineUtils.userIsOwner(user, storageId);
					if(!isOwner) {
						throw new IllegalAccessException("Model " + storageId + " does not exist or user does not have permissions to update the smss. User must be the owner to perform this function.");
					}
				}
			} catch (IllegalAccessException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put("error", e.getMessage());
				return WebUtility.getResponse(errorMap, 401);
			}
		}

		IModelEngine engine = Utility.getModel(storageId);
		String currentSmssFileLocation = engine.getSmssFilePath();
		File currentSmssFile = new File(currentSmssFileLocation);
		if(!currentSmssFile.exists() || !currentSmssFile.isFile()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Could not find current storage smss file");
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// using the current smss properties
		// and the new file contents
		// unconceal any hidden values that have not been altered
		Properties currentSmssProperties = engine.getSmssProp();
		String newSmssContent = request.getParameter("smss");
		String unconcealedNewSmssContent = SmssUtilities.unconcealSmssSensitiveInfo(newSmssContent, currentSmssProperties);
		
		// read the current smss as text in case of an error
		String currentSmssContent = null;
		try {
			currentSmssContent = new String(Files.readAllBytes(Paths.get(currentSmssFile.toURI())));
		} catch (IOException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "An error occurred reading the current storage smss details. Detailed message = " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		try {
			engine.close();
		} catch (IOException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "An error occurred closing the connection to the storage. Detailed message = " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		try {
			try (FileWriter fw = new FileWriter(currentSmssFile, false)){
				fw.write(unconcealedNewSmssContent);
			}
			engine.loadModel(currentSmssFileLocation);
		} catch(Exception e) {
			logger.error(Constants.STACKTRACE, e);
			// reset the values
			try {
				// close the model engine again
				engine.close();
			} catch (IOException e1) {
				logger.error(Constants.STACKTRACE, e1);
			}
			currentSmssFile.delete();
			try (FileWriter fw = new FileWriter(currentSmssFile, false)){
				fw.write(currentSmssContent);
				engine.loadModel(currentSmssFileLocation);
			} catch(Exception e2) {
				logger.error(Constants.STACKTRACE, e2);
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "A fatal error occurred and could not revert the storage to an operational state. Detailed message = " + e2.getMessage());
				return WebUtility.getResponse(errorMap, 400);
			}
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "An error occurred initializing the new storage details. Detailed message = " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// push to cloud
		ClusterUtil.pushModelSmss(storageId);
		
		Map<String, Object> success = new HashMap<>();
		success.put("success", true);
		return WebUtility.getResponse(success, 200);
	}
	
	
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////

	/*
	 * Code below is around database images
	 */
	
	@GET
	@Path("/modelImage/download")
	@Produces({MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_SVG_XML})
	public Response downloadStorageImage(@Context final Request coreRequest, @Context HttpServletRequest request, @PathParam("modelId") String modelId) {
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
				canViewModel(user, modelId);
			} catch (IllegalAccessException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put("error", e.getMessage());
				return WebUtility.getResponse(errorMap, 401);
			}
		}
		
		if(CouchUtil.COUCH_ENABLED) {
			try {
				Map<String, String> selectors = new HashMap<>();
				selectors.put(CouchUtil.MODEL, modelId);
				return CouchUtil.download(CouchUtil.DATABASE, selectors);
			} catch (CouchException e) {
				logger.error(Constants.STACKTRACE, e);
			}
		}
		
		File exportFile = getModelImageFile(modelId);
		if(exportFile != null && exportFile.exists()) {
			String exportName = modelId + "_Image." + FilenameUtils.getExtension(exportFile.getAbsolutePath());
			// want to cache this on browser if user has access
//			CacheControl cc = new CacheControl();
//			cc.setMaxAge(86400);
//			cc.setPrivate(true);
//			cc.setMustRevalidate(true);
		    EntityTag etag = new EntityTag(Long.toString(exportFile.lastModified()));
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
			errorMap.put(Constants.ERROR_MESSAGE, "Error sending image file");
			return WebUtility.getResponse(errorMap, 400);
		}
	}
	
	/**
	 * Use to find the file for the image
	 * @param modelId
	 * @return
	 */
	protected File getModelImageFile(String modelId) {
		if(ClusterUtil.IS_CLUSTER){
			return ClusterUtil.getModelImage(modelId);
		}
		String propFileLoc = (String) DIHelper.getInstance().getEngineProperty(modelId + "_" + Constants.STORE);
		if(propFileLoc == null && !modelId.equals("NEWSEMOSSAPP")) {
			String imageDir = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER) + "/images/stock/";
			return new File(imageDir + "color-logo.png");
		}
		Properties prop = Utility.loadProperties(propFileLoc);
		String modelName = prop.getProperty(Constants.ENGINE_ALIAS);
		
		String baseFolder = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER);
		String fileLocation = baseFolder 
				+ DIR_SEPARATOR + Constants.MODEL_FOLDER 
				+ DIR_SEPARATOR + SmssUtilities.getUniqueName(modelName, modelId) 
				+ DIR_SEPARATOR + "app_root" 
				+ DIR_SEPARATOR + "version";

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
			if(modelName != null) {
				TextToGraphic.makeImage(modelName, fileLocation);
			} else {
				TextToGraphic.makeImage(modelId, fileLocation);
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
