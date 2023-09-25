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
import prerna.engine.api.IEngine;
import prerna.engine.api.IModelEngine;
import prerna.engine.impl.SmssUtilities;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/model-{modelId}")
public class ModelEngineResource {

	private static final String DIR_SEPARATOR = java.nio.file.FileSystems.getDefault().getSeparator();
	
	private static final Logger logger = LogManager.getLogger(ModelEngineResource.class);
	
	private boolean canViewModel(User user, String modelId) throws IllegalAccessException {
		modelId = SecurityQueryUtils.testUserEngineIdForAlias(user, modelId);
		if(!SecurityEngineUtils.userCanViewEngine(user, modelId)
				&& !SecurityEngineUtils.engineIsDiscoverable(modelId)) {
			throw new IllegalAccessException("Model " + modelId + " does not exist or user does not have access");
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
	public Response updateSmssFile(@Context HttpServletRequest request, @PathParam("modelId") String modelId) {
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
				boolean isOwner = SecurityEngineUtils.userIsOwner(user, modelId);
				if(!isOwner) {
					throw new IllegalAccessException("Model " + modelId + " does not exist or user does not have permissions to update the smss. User must be the owner to perform this function.");
				}
			}
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		IModelEngine engine = Utility.getModel(modelId);
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
		} catch (Exception e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "An error occurred closing the connection to the model. Detailed message = " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		try {
			try (FileWriter fw = new FileWriter(currentSmssFile, false)){
				fw.write(unconcealedNewSmssContent);
			}
			engine.open(currentSmssFileLocation);
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
				engine.open(currentSmssFileLocation);
			} catch(Exception e2) {
				logger.error(Constants.STACKTRACE, e2);
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "A fatal error occurred and could not revert the model to an operational state. Detailed message = " + e2.getMessage());
				return WebUtility.getResponse(errorMap, 400);
			}
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "An error occurred initializing the new model details. Detailed message = " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// push to cloud
		ClusterUtil.pushEngineSmss(modelId, IEngine.CATALOG_TYPE.MODEL);
		
		Map<String, Object> success = new HashMap<>();
		success.put("success", true);
		return WebUtility.getResponse(success, 200);
	}
	
}
