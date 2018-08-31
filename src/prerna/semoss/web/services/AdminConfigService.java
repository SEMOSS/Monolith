package prerna.semoss.web.services;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

import prerna.web.services.util.WebUtility;

@Path("/adminConfig")
public class AdminConfigService {

	@GET
	@Path("/alive")
	public Response isAlive() {
		return WebUtility.getResponse("Server is Up", 200);
	}
	
}
