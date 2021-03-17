package prerna.semoss.web.services.saml;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import prerna.web.services.util.WebUtility;

@Path("/config")
public class SamlVerifierResource {

	@GET
	@Path("/test")
	@Produces("application/json")
	public Response test() {
		return WebUtility.getResponse("success", 200);
	}
	
}
