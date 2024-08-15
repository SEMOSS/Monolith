package prerna.semoss.web.services.config;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.owasp.encoder.Encode;

import com.google.gson.Gson;

import prerna.auth.utils.SecurityUpdateUtils;
import prerna.engine.api.IDatabaseEngine;
import prerna.engine.api.IRawSelectWrapper;
import prerna.query.querystruct.SelectQueryStruct;
import prerna.query.querystruct.selectors.QueryColumnSelector;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.conf.AdminStartupFilter;
import prerna.web.services.util.WebUtility;

@Path("/")
@PermitAll
public class AdminConfigService {
	
	private static final Logger logger = LogManager.getLogger(AdminConfigService.class);
	
	private static final Gson GSON = new Gson();
	public static final String ADMIN_REDIRECT_KEY = "ADMIN_REDIRECT_KEY";
	
	
	@POST
	@Path("/setInitialAdmins")
	public Response setInitialAdmins(@Context HttpServletRequest request, @Context HttpServletResponse response) 
			throws IOException {
		HttpSession session = request.getSession(false);

		IDatabaseEngine engine = Utility.getDatabase(Constants.SECURITY_DB);
		SelectQueryStruct qs = new SelectQueryStruct();
		qs.addSelector(new QueryColumnSelector("SMSS_USER__ID"));
		qs.setLimit(1);
		IRawSelectWrapper wrapper = null;
		try {
			wrapper = WrapperManager.getInstance().getRawWrapper(engine, qs);
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
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error occurred attempting to determine if the initial admin is set. Please check the system logs for assistance");
			return WebUtility.getResponse(errorMap, 400);
		} finally {
			if (wrapper != null) {
				try {
					wrapper.close();
				} catch(IOException e) {
					logger.error(Constants.STACKTRACE, e);
				}
			}
		}
		
		String idString = request.getParameter("ids");
		if (idString == null || idString.isEmpty()) {
			Map<String, String> errorMessage = new HashMap<>();
			errorMessage.put(Constants.ERROR_MESSAGE, "Need to send valid ids");
			return WebUtility.getResponse(errorMessage, 200);
		}

		List<String> ids = GSON.fromJson(idString, List.class);
		
		for (String id : ids) {
			SecurityUpdateUtils.registerUser(id, null, null,null, null, null, null, null, true, true, true); 
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
	
	//NEW method for env var userid which registers id to db
	public static synchronized void setInitialAdminViaENV(String id) {
			SecurityUpdateUtils.registerUser(id, null, null,null, null, null, null, null, true, true, true);
	}

}
