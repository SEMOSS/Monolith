package prerna.semoss.web.services.local;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.engine.api.IDatabase;
import prerna.engine.api.IEngine;
import prerna.engine.api.IModelEngine;
import prerna.engine.api.IStorage;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/e-{engineId}")
public class EngineRouteResource {

	private static final String DIR_SEPARATOR = java.nio.file.FileSystems.getDefault().getSeparator();
	private static final Logger logger = LogManager.getLogger(EngineRouteResource.class);
	
	private boolean canViewEngine(User user, String engineId) throws IllegalAccessException {
		if(AbstractSecurityUtils.securityEnabled()) {
			engineId = SecurityQueryUtils.testUserEngineIdForAlias(user, engineId);
			if(!SecurityEngineUtils.userCanViewEngine(user, engineId)
					&& !SecurityEngineUtils.engineIsDiscoverable(engineId)) {
				throw new IllegalAccessException("Engine " + engineId + " does not exist or user does not have access to the engine");
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
	public Response updateSmssFile(@Context HttpServletRequest request, @PathParam("engineId") String engineId) {
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
					boolean isOwner = SecurityEngineUtils.userIsOwner(user, engineId);
					if(!isOwner) {
						throw new IllegalAccessException("Engine " + engineId + " does not exist or user does not have permissions to update the smss. User must be the owner to perform this function.");
					}
				}
			} catch (IllegalAccessException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put("error", e.getMessage());
				return WebUtility.getResponse(errorMap, 401);
			}
		}

		IEngine engine = Utility.getEngine(engineId);
		if(engine.getCatalogType().equals(IDatabase.CATALOG_TYPE)) {
			return new DatabaseEngineResource().updateSmssFile(request, engineId);
		} else if(engine.getCatalogType().equals(IStorage.CATALOG_TYPE)) {
			return new StorageEngineResource().updateSmssFile(request, engineId);
		} else if(engine.getCatalogType().equals(IModelEngine.CATALOG_TYPE)) {
			return new ModelEngineResource().updateSmssFile(request, engineId);
		}
		
		Map<String, String> errorMap = new HashMap<>();
		errorMap.put("error", "Unknown engine with id " + engineId);
		return WebUtility.getResponse(errorMap, 400);
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
	@Path("/image/download")
	@Produces({MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_SVG_XML})
	public Response imageDownload(@Context final Request coreRequest, @Context HttpServletRequest request, @PathParam("engineId") String engineId) {
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
				canViewEngine(user, engineId);
			} catch (IllegalAccessException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put("error", e.getMessage());
				return WebUtility.getResponse(errorMap, 401);
			}
		}
		
		IEngine engine = Utility.getEngine(engineId);
		if(engine.getCatalogType().equals(IDatabase.CATALOG_TYPE)) {
			return new DatabaseEngineResource().imageDownload(coreRequest, request, engineId);
		} else if(engine.getCatalogType().equals(IStorage.CATALOG_TYPE)) {
			return new StorageEngineResource().imageDownload(coreRequest, request, engineId);
		} else if(engine.getCatalogType().equals(IModelEngine.CATALOG_TYPE)) {
			return new ModelEngineResource().imageDownload(coreRequest, request, engineId);
		}
		
		Map<String, String> errorMap = new HashMap<>();
		errorMap.put("error", "Unknown engine with id " + engineId);
		return WebUtility.getResponse(errorMap, 400);
	}
	
}
