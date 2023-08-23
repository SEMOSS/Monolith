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

import prerna.auth.utils.SecurityEngineUtils;
import prerna.engine.api.IEngine;
import prerna.web.services.util.WebUtility;

@Path("/e-{engineId}")
public class EngineRouteResource {

	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	
	@POST
	@Path("/updateSmssFile")
	@Produces("application/json;charset=utf-8")
	public Response updateSmssFile(@Context HttpServletRequest request, @PathParam("engineId") String engineId) {
		// the called resource class does the security checks

		IEngine.CATALOG_TYPE catalogType = null;
		Object[] typeAndSubtype = null;
		try {
			typeAndSubtype = SecurityEngineUtils.getEngineTypeAndSubtype(engineId);
			catalogType = (IEngine.CATALOG_TYPE) typeAndSubtype[0];
		} catch(Exception e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", "Unknown engine with id " + engineId);
			return WebUtility.getResponse(errorMap, 400);
		}
		
		if(IEngine.CATALOG_TYPE.DATABASE == catalogType) {
			return new DatabaseEngineResource().updateSmssFile(request, engineId);
		} else if(IEngine.CATALOG_TYPE.STORAGE == catalogType) {
			return new StorageEngineResource().updateSmssFile(request, engineId);
		} else if(IEngine.CATALOG_TYPE.MODEL == catalogType) {
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
		// the called resource class does the security checks

		IEngine.CATALOG_TYPE catalogType = null;
		Object[] typeAndSubtype = null;
		try {
			typeAndSubtype = SecurityEngineUtils.getEngineTypeAndSubtype(engineId);
			catalogType = (IEngine.CATALOG_TYPE) typeAndSubtype[0];
		} catch(Exception e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", "Unknown engine with id " + engineId);
			return WebUtility.getResponse(errorMap, 400);
		}

		if(IEngine.CATALOG_TYPE.DATABASE == catalogType) {
			return new DatabaseEngineResource().imageDownload(coreRequest, request, engineId);
		} else if(IEngine.CATALOG_TYPE.STORAGE == catalogType) {
			return new StorageEngineResource().imageDownload(coreRequest, request, engineId);
		} else if(IEngine.CATALOG_TYPE.MODEL == catalogType) {
			return new ModelEngineResource().imageDownload(coreRequest, request, engineId);
		}
		
		Map<String, String> errorMap = new HashMap<>();
		errorMap.put("error", "Unknown engine with id " + engineId);
		return WebUtility.getResponse(errorMap, 400);
	}
	
}
