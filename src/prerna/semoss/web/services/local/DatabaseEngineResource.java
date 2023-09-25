package prerna.semoss.web.services.local;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.engine.api.IDatabaseEngine;
import prerna.engine.api.IEngine;
import prerna.engine.impl.SmssUtilities;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/database-{databaseId}")
public class DatabaseEngineResource {

	private static final String DIR_SEPARATOR = java.nio.file.FileSystems.getDefault().getSeparator();
	
	private static final Logger classLogger = LogManager.getLogger(DatabaseEngineResource.class);
	
	private boolean canViewDatabase(User user, String databaseId) throws IllegalAccessException {
		databaseId = SecurityQueryUtils.testUserEngineIdForAlias(user, databaseId);
		if(!SecurityEngineUtils.userCanViewEngine(user, databaseId)
				&& !SecurityEngineUtils.engineIsDiscoverable(databaseId)) {
			throw new IllegalAccessException("Database " + databaseId + " does not exist or user does not have access to the database");
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
	public Response updateSmssFile(@Context HttpServletRequest request, @PathParam("databaseId") String databaseId) {
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
				boolean isOwner = SecurityEngineUtils.userIsOwner(user, databaseId);
				if(!isOwner) {
					throw new IllegalAccessException("Database " + databaseId + " does not exist or user does not have permissions to update the smss. User must be the owner to perform this function.");
				}
			}
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		IDatabaseEngine engine = Utility.getDatabase(databaseId);
		String currentSmssFileLocation = engine.getSmssFilePath();
		File currentSmssFile = new File(currentSmssFileLocation);
		if(!currentSmssFile.exists() || !currentSmssFile.isFile()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Could not find current database smss file");
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
			errorMap.put(Constants.ERROR_MESSAGE, "An error occurred reading the current database smss details. Detailed message = " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		try {
			engine.close();
		} catch (Exception e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "An error occurred closing the connection to the database. Detailed message = " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		try {
			try (FileWriter fw = new FileWriter(currentSmssFile, false)){
				fw.write(unconcealedNewSmssContent);
			}
			engine.open(currentSmssFileLocation);
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			// reset the values
			try {
				// close the database again
				engine.close();
			} catch (IOException e1) {
				classLogger.error(Constants.STACKTRACE, e1);
			}
			currentSmssFile.delete();
			try (FileWriter fw = new FileWriter(currentSmssFile, false)){
				fw.write(currentSmssContent);
				engine.open(currentSmssFileLocation);
			} catch(Exception e2) {
				classLogger.error(Constants.STACKTRACE, e2);
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "A fatal error occurred and could not revert the database to an operational state. Detailed message = " + e2.getMessage());
				return WebUtility.getResponse(errorMap, 400);
			}
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "An error occurred initializing the new database details. Detailed message = " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// push to cloud
		ClusterUtil.pushEngineSmss(databaseId, IEngine.CATALOG_TYPE.DATABASE);
		
		Map<String, Object> success = new HashMap<>();
		success.put("success", true);
		return WebUtility.getResponse(success, 200);
	}
	
}
