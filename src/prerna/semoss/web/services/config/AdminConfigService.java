package prerna.semoss.web.services.config;


import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import com.google.gson.Gson;

import prerna.auth.utils.SecurityUpdateUtils;
import prerna.web.conf.AdminStartupFilter;
import prerna.web.services.util.WebUtility;

@Path("/adminconfig")
public class AdminConfigService {

	private static final Gson GSON = new Gson();
	public static final String ADMIN_REDIRECT_KEY = "ADMIN_REDIRECT_KEY";
	
	@POST
	@Path("/setInitialAdmins")
	public Response setInitialAdmins(@Context HttpServletRequest request, @Context HttpServletResponse response, MultivaluedMap<String, String> form) throws IOException {
		HttpSession session = request.getSession(false);
		
		String idString = form.getFirst("ids");
		if(idString == null || idString.isEmpty()) {
			Map<String, String> errorMessage = new HashMap<String, String>();
			errorMessage.put("errorMessage", "Need to send valid ids");
			return WebUtility.getResponse(errorMessage, 200);
		}
		List<String> ids = GSON.fromJson(idString, List.class);
		
		for(String id : ids) {
			SecurityUpdateUtils.registerUser(id, true);
		}

		if(session.getAttribute(ADMIN_REDIRECT_KEY) != null) {
			String originalRedirect = session.getAttribute(ADMIN_REDIRECT_KEY) + "";
			response.setHeader("redirect", originalRedirect);
			response.sendError(302, "Need to redirect to " + originalRedirect);
			AdminStartupFilter.setSuccessfulRedirectUrl(originalRedirect);
		}

		return WebUtility.getResponse("success", 200);
	}
	
}
