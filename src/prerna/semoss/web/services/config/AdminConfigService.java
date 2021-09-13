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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.owasp.encoder.Encode;

import com.google.gson.Gson;

import prerna.auth.utils.SecurityUpdateUtils;
import prerna.engine.api.IEngine;
import prerna.engine.api.IRawSelectWrapper;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.conf.AdminStartupFilter;
import prerna.web.services.util.WebUtility;

@Path("/")
public class AdminConfigService {
	
	private static final Logger logger = LogManager.getLogger(AdminConfigService.class);
	
	private static final Gson GSON = new Gson();
	public static final String ADMIN_REDIRECT_KEY = "ADMIN_REDIRECT_KEY";

	@POST
	@Path("/setInitialAdmins")
	public Response setInitialAdmins(@Context HttpServletRequest request, @Context HttpServletResponse response,
			MultivaluedMap<String, String> form) throws IOException {
		HttpSession session = request.getSession(false);

		IEngine engine = Utility.getEngine(Constants.SECURITY_DB);
		String q = "SELECT * FROM SMSS_USER LIMIT 1";
		IRawSelectWrapper wrapper = null;
		try {
			wrapper = WrapperManager.getInstance().getRawWrapper(engine, q);
			boolean hasUser = wrapper.hasNext();
			// if there are users, redirect to the main semoss page
			// we do not want to allow the person to make any admin requests
			if (hasUser) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Initial admin has already been set");
				return WebUtility.getResponse(errorMap, 400);
			}
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
		} finally {
			if (wrapper != null) {
				wrapper.cleanUp();
			}
		}
		
		String idString = form.getFirst("ids");
		if (idString == null || idString.isEmpty()) {
			Map<String, String> errorMessage = new HashMap<>();
			errorMessage.put("errorMessage", "Need to send valid ids");
			return WebUtility.getResponse(errorMessage, 200);
		}
		List<String> ids = GSON.fromJson(idString, List.class);

		for (String id : ids) {
			SecurityUpdateUtils.registerUser(id, null, null, null, null , true, true);
		}

		if (session != null && session.getAttribute(ADMIN_REDIRECT_KEY) != null) {
			String originalRedirect = session.getAttribute(ADMIN_REDIRECT_KEY) + "";
			String encodedRedirectUrl = Encode.forHtml(originalRedirect);
			response.setHeader("redirect", encodedRedirectUrl);
			response.sendError(302, "Need to redirect to " + encodedRedirectUrl);
			AdminStartupFilter.setSuccessfulRedirectUrl(encodedRedirectUrl);
		}

		return WebUtility.getResponse("success", 200);
	}

}
