package prerna.semoss.web.services.local.auth;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;

import prerna.auth.PasswordRequirements;
import prerna.auth.User;
import prerna.auth.utils.SecurityPasswordResetUtils;
import prerna.auth.utils.UserRegistrationEmailService;
import prerna.date.SemossDate;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/auth/user")
public class UserAuthorizationResource extends AbstractAdminResource {
	
	private static final Logger logger = LogManager.getLogger(UserAuthorizationResource.class);
	private static final String RESET_PASSWORD = "/resetPassword/";

	/**
	 * Edit user properties 
	 * @param request
	 * @param form
	 * @return true if the edition was performed
	 */
	@POST
	@Path("/editUser")
	@Produces("application/json")
	public Response editUser(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		Gson gson = new Gson();
		Map<String, Object> userInfo = gson.fromJson(form.getFirst("user"), Map.class);
		return null;
	}

	@POST
	@Produces("application/json")
	@Path("/deleteUser")
	public Response deleteUser(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		return null;
	}
	
	@POST
	@Produces("application/json")
	@Path("/setupResetPassword")
	public Response setupResetPassword(@Context ServletContext context, @Context HttpServletRequest request) {
		// do we allow users to change their password?
		try {
			if(!PasswordRequirements.getInstance().isAllowUserChangePassword()) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(ResourceUtility.ERROR_KEY, "Only the administrator is allowed to change the user password");
				return WebUtility.getResponse(errorMap, 401);
			}
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String email = request.getParameter("email");
		String type = request.getParameter("type");
		String url = request.getParameter("url");
		
		String uniqueToken = null;
		try {
			uniqueToken = SecurityPasswordResetUtils.allowUserResetPassword(email, type);
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String resetEmailUrl = null;
		if(url == null || (url=url.trim()).isEmpty() ) {
			String fullUrl = Utility.cleanHttpResponse(((HttpServletRequest) request).getRequestURL().toString());
			String contextPath = ((HttpServletRequest) request).getContextPath();
			resetEmailUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length()) 
					+ RESET_PASSWORD + "index.html?token=" + uniqueToken;
		} else {
			url += "?token=" + uniqueToken;
		}
		
		UserRegistrationEmailService.getInstance().sendPasswordResetRequestEmail(email, resetEmailUrl);
		
		// log the operation
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			logger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user),
					"has requested a password reset for email = " + email));
		} catch (IllegalAccessException e) {
			//ignore
			logger.info(ResourceUtility.getLogMessage(request, request.getSession(false), "No user in session",
					"has requested a password reset for email = " + email));
		}
		
		Map<String, Object> retMap = new HashMap<>();
		retMap.put("success", true);
		retMap.put("message", "Email has been sent to: " + email);
		return WebUtility.getResponse(retMap, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("/resetPassword")
	public Response resetPassword(@Context ServletContext context, @Context HttpServletRequest request) {
		// do we allow users to change their password?
		try {
			if(!PasswordRequirements.getInstance().isAllowUserChangePassword()) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(ResourceUtility.ERROR_KEY, "Only the administrator is allowed to change the user password");
				return WebUtility.getResponse(errorMap, 401);
			}
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String token = request.getParameter("token");
		String password = request.getParameter("password");
		Map<String, Object> resetDetails = null;
		try {
			resetDetails = SecurityPasswordResetUtils.userResetPassword(token, password);
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String userId = (String) resetDetails.get("userId");
		String email = (String) resetDetails.get("email");
		SemossDate dateAdded = (SemossDate) resetDetails.get("dateAdded");
		
		// log the operation
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			logger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user),
					"has changed password for user id = " + userId + " for reset request on " + dateAdded + " with email " + email));
		} catch (IllegalAccessException e) {
			//ignore
			logger.info(ResourceUtility.getLogMessage(request, request.getSession(false), "No user in session",
					"has changed password for user id = " + userId + " for reset request on " + dateAdded + " with email " + email));
		}
		
		UserRegistrationEmailService.getInstance().sendPasswordResetSuccessEmail(email);
		
		Map<String, Object> retMap = new HashMap<>();
		retMap.put("success", true);
		return WebUtility.getResponse(retMap, 200);
	}

}
