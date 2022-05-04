package prerna.semoss.web.services.local;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

import net.snowflake.client.jdbc.internal.com.nimbusds.jose.util.IOUtils;
import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityDatabaseUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.engine.api.IEngine;
import prerna.engine.impl.SmssUtilities;
import prerna.io.connector.couch.CouchException;
import prerna.io.connector.couch.CouchUtil;
import prerna.nameserver.utility.MasterDatabaseUtility;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;
import prerna.util.insight.TextToGraphic;
import prerna.web.services.util.WebUtility;

@Path("/app-{appId}")
public class DatabaseResource {

	private static final String DIR_SEPARATOR = java.nio.file.FileSystems.getDefault().getSeparator();
	
	private static final Logger logger = LogManager.getLogger(DatabaseResource.class);
	
	private boolean canAccessDatabase(User user, String databaseId) throws IllegalAccessException {
		if(AbstractSecurityUtils.securityEnabled()) {
			databaseId = SecurityQueryUtils.testUserDatabaseIdForAlias(user, databaseId);
			if(!SecurityDatabaseUtils.userCanViewDatabase(user, databaseId)) {
				throw new IllegalAccessException("Database " + databaseId + " does not exist or user does not have access to database");
			}
		} else {
			databaseId = MasterDatabaseUtility.testDatabaseIdIfAlias(databaseId);
			if(!MasterDatabaseUtility.getAllDatabaseIds().contains(databaseId)) {
				throw new IllegalAccessException("Database " + databaseId + " does not exist");
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
	public Response updateSmssFile(@Context HttpServletRequest request) {
		String databaseId = request.getParameter("databaseId");
		String newSmssContent = request.getParameter("smss");
		
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
				canAccessDatabase(user, databaseId);
			} catch (IllegalAccessException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put("error", e.getMessage());
				return WebUtility.getResponse(errorMap, 401);
			}
		}
		
		IEngine engine = Utility.getEngine(databaseId);
		String currentSmssFileLocation = engine.getPropFile();
		File currentSmssFile = new File(currentSmssFileLocation);
		if(!currentSmssFile.exists() || currentSmssFile.isFile()) {
			Map<String, String> errorMessage = new HashMap<>();
			errorMessage.put(Constants.ERROR_MESSAGE, "Could not find current database smss file");
			return Response.status(400).entity(errorMessage)
					.header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0, post-check=0, pre-check=0")
					.header("Pragma", "no-cache")
					.build();
		}
		
		String currentSmssContent = null;
		try {
			currentSmssContent = IOUtils.readFileToString(currentSmssFile, StandardCharsets.UTF_8);
		} catch (IOException e) {
			Map<String, String> errorMessage = new HashMap<>();
			errorMessage.put(Constants.ERROR_MESSAGE, "An error occured reading the current database smss details. Detailed message = " + e.getMessage());
			return Response.status(400).entity(errorMessage)
					.header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0, post-check=0, pre-check=0")
					.header("Pragma", "no-cache")
					.build();
		}
		engine.closeDB();
		try (FileWriter fw = new FileWriter(currentSmssFile, false)){
			fw.write(newSmssContent);
			engine.openDB(currentSmssFileLocation);
		} catch(Exception e) {
			logger.error(Constants.STACKTRACE, e);
			// reset the values
			engine.closeDB();
			currentSmssFile.delete();
			try (FileWriter fw = new FileWriter(currentSmssFile, false)){
				fw.write(currentSmssContent);
				engine.openDB(currentSmssFileLocation);
			} catch(Exception e2) {
				logger.error(Constants.STACKTRACE, e2);
				Map<String, String> errorMessage = new HashMap<>();
				errorMessage.put(Constants.ERROR_MESSAGE, "A fatal error occured and could not revert the database to an operational state. Detailed message = " + e2.getMessage());
				return Response.status(400).entity(errorMessage)
						.header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0, post-check=0, pre-check=0")
						.header("Pragma", "no-cache")
						.build();
			}
			Map<String, String> errorMessage = new HashMap<>();
			errorMessage.put(Constants.ERROR_MESSAGE, "An error occured initializing the new database details. Detailed message = " + e.getMessage());
			return Response.status(400).entity(errorMessage)
					.header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0, post-check=0, pre-check=0")
					.header("Pragma", "no-cache")
					.build();
		}
		
		//TODO: need to push smss file to minio
		
		Map<String, Object> success = new HashMap<>();
		success.put("success", true);
		return Response.status(200).entity(success)
				.header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0, post-check=0, pre-check=0")
				.header("Pragma", "no-cache")
				.build();
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
	@Path("/appImage/download")
	@Produces({MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_SVG_XML})
	public Response downloadDatabaseImage(@Context final Request coreRequest, @Context HttpServletRequest request, @PathParam("appId") String databaseId) {
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
				canAccessDatabase(user, databaseId);
			} catch (IllegalAccessException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put("error", e.getMessage());
				return WebUtility.getResponse(errorMap, 401);
			}
		}
		
		if(CouchUtil.COUCH_ENABLED) {
			try {
				String actualAppId = MasterDatabaseUtility.testDatabaseIdIfAlias(databaseId);
				Map<String, String> selectors = new HashMap<>();
				selectors.put(CouchUtil.DATABASE, actualAppId);
				return CouchUtil.download(CouchUtil.DATABASE, selectors);
			} catch (CouchException e) {
				logger.error(Constants.STACKTRACE, e);
			}
		}
		
		File exportFile = getDatabaseImageFile(databaseId);
		if(exportFile != null && exportFile.exists()) {
			String exportName = databaseId + "_Image." + FilenameUtils.getExtension(exportFile.getAbsolutePath());
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
	 * @param databaseId
	 * @return
	 */
	protected File getDatabaseImageFile(String databaseId) {
		databaseId = MasterDatabaseUtility.testDatabaseIdIfAlias(databaseId);
		if(ClusterUtil.IS_CLUSTER){
			return ClusterUtil.getDatabaseImage(databaseId);
		}
		String propFileLoc = (String) DIHelper.getInstance().getDbProperty(databaseId + "_" + Constants.STORE);
		if(propFileLoc == null && !databaseId.equals("NEWSEMOSSAPP")) {
			String imageDir = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER) + "/images/stock/";
			return new File(imageDir + "color-logo.png");
		}
		Properties prop = Utility.loadProperties(propFileLoc);
		String databaseName = prop.getProperty(Constants.ENGINE_ALIAS);
		
		String baseFolder = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER);
		String fileLocation = baseFolder 
				+ DIR_SEPARATOR + Constants.DB_FOLDER 
				+ DIR_SEPARATOR + SmssUtilities.getUniqueName(databaseName, databaseId) 
				+ DIR_SEPARATOR + "app_root" 
				+ DIR_SEPARATOR + "version";
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
			if(databaseName != null) {
				TextToGraphic.makeImage(databaseName, fileLocation);
			} else {
				TextToGraphic.makeImage(databaseId, fileLocation);
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
