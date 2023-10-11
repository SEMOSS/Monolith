package prerna.semoss.web.services.local;

import javax.ws.rs.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityQueryUtils;

@Path("/storage-{storageId}")
public class StorageEngineResource {

	private static final String DIR_SEPARATOR = java.nio.file.FileSystems.getDefault().getSeparator();
	
	private static final Logger classLogger = LogManager.getLogger(StorageEngineResource.class);
	
	private boolean canViewStorage(User user, String storageId) throws IllegalAccessException {
		storageId = SecurityQueryUtils.testUserEngineIdForAlias(user, storageId);
		if(!SecurityEngineUtils.userCanViewEngine(user, storageId)
				&& !SecurityEngineUtils.engineIsDiscoverable(storageId)) {
			throw new IllegalAccessException("Storage " + storageId + " does not exist or user does not have access");
		}
	
		return true;
	}
	
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	
	
}
