package prerna.semoss.web.services;


import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import prerna.web.services.util.WebUtility;

@Path("/adminconfig")
public class AdminConfigService {

	public static final String ADMIN_REDIRECT_KEY = "ADMIN_REDIRECT_KEY";
	
	@POST
	@Path("/setInitialAdmins")
	public Response setInitialAdmins(@Context HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		System.out.println(session.getAttribute(ADMIN_REDIRECT_KEY));
		System.out.println(session.getAttribute(ADMIN_REDIRECT_KEY));
		System.out.println(session.getAttribute(ADMIN_REDIRECT_KEY));
		System.out.println(session.getAttribute(ADMIN_REDIRECT_KEY));
		System.out.println(session.getAttribute(ADMIN_REDIRECT_KEY));

		
		return WebUtility.getResponse("success", 200);
	}
	
}
