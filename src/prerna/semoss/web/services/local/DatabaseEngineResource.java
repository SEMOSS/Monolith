package prerna.semoss.web.services.local;

import javax.ws.rs.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityQueryUtils;

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
	
}
