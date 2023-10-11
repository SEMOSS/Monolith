package prerna.semoss.web.services.local;

import javax.ws.rs.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityQueryUtils;

@Path("/model-{modelId}")
public class ModelEngineResource {

	private static final String DIR_SEPARATOR = java.nio.file.FileSystems.getDefault().getSeparator();
	
	private static final Logger classLogger = LogManager.getLogger(ModelEngineResource.class);
	
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
	
	
}
