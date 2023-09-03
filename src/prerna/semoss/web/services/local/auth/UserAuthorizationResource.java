package prerna.semoss.web.services.local.auth;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
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
import prerna.auth.utils.SecurityUserAccessKeyUtils;
import prerna.auth.utils.UserRegistrationEmailService;
import prerna.date.SemossDate;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/auth/user")
public class UserAuthorizationResource extends AbstractAdminResource {
	
	private static final Logger classLogger = LogManager.getLogger(UserAuthorizationResource.class);
	
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
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		Gson gson = new Gson();
		Map<String, Object> userInfo = gson.fromJson(form.getFirst("user"), Map.class);
		return null;
	}

	/**
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("/deleteUser")
	public Response deleteUser(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		return null;
	}
	
	/**
	 * Get the user access keys
	 * @param request
	 * @return
	 */
	@GET
	@Path("/getUserAccessKeys")
	@Produces("application/json")
	public Response getUserAccessKeys(@Context HttpServletRequest request) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, Object>> results = SecurityUserAccessKeyUtils.getUserAccessKeyInfo(user.getPrimaryLoginToken());
		return WebUtility.getResponse(results, 200);
	}
	
	/**
	 * 
	 * @param context
	 * @param request
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("/setupResetPassword")
	public Response setupResetPassword(@Context ServletContext context, @Context HttpServletRequest request) {
		// do we allow users to change their password?
		try {
			if(!PasswordRequirements.getInstance().isAllowUserChangePassword()) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, "Only the administrator is allowed to change the user password");
				return WebUtility.getResponse(errorMap, 401);
			}
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String email = request.getParameter("email");
		String type = request.getParameter("type");
		String resetEmailUrl = request.getParameter("url");
		String sender = request.getParameter("sender");
		
		String uniqueToken = null;
		try {
			uniqueToken = SecurityPasswordResetUtils.allowUserResetPassword(email, type);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(resetEmailUrl == null || (resetEmailUrl=resetEmailUrl.trim()).isEmpty() ) {
			String fullUrl = Utility.cleanHttpResponse(((HttpServletRequest) request).getRequestURL().toString());
			String contextPath = ((HttpServletRequest) request).getContextPath();
			resetEmailUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length()) 
					+ RESET_PASSWORD + "index.html?token=" + uniqueToken;
		} else {
			resetEmailUrl += "?token=" + uniqueToken;
		}
		
		if(!UserRegistrationEmailService.getInstance().sendPasswordResetRequestEmail(email, resetEmailUrl, sender)) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error occurred sending email to " + email);
			SecurityPasswordResetUtils.deleteToken(uniqueToken);
			return WebUtility.getResponse(errorMap, 500);
		}
		
		// log the operation
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user),
					"has requested a password reset for email = " + email));
		} catch (IllegalAccessException e) {
			//ignore
			classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), "No user in session",
					"has requested a password reset for email = " + email));
		}
		
		Map<String, Object> retMap = new HashMap<>();
		retMap.put("success", true);
		retMap.put("message", "Email has been sent to: " + email);
		return WebUtility.getResponse(retMap, 200);
	}
	
	/**
	 * 
	 * @param context
	 * @param request
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("/resetPassword")
	public Response resetPassword(@Context ServletContext context, @Context HttpServletRequest request) {
		// do we allow users to change their password?
		try {
			if(!PasswordRequirements.getInstance().isAllowUserChangePassword()) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, "Only the administrator is allowed to change the user password");
				return WebUtility.getResponse(errorMap, 401);
			}
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String token = request.getParameter("token");
		String password = request.getParameter("password");
		String sender = request.getParameter("sender");

		Map<String, Object> resetDetails = null;
		try {
			resetDetails = SecurityPasswordResetUtils.userResetPassword(token, password);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String userId = (String) resetDetails.get("userId");
		String email = (String) resetDetails.get("email");
		SemossDate dateAdded = (SemossDate) resetDetails.get("dateAdded");
		
		// log the operation
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user),
					"has changed password for user id = " + userId + " for reset request on " + dateAdded + " with email " + email));
		} catch (IllegalAccessException e) {
			//ignore
			classLogger.info(ResourceUtility.getLogMessage(request, request.getSession(false), "No user in session",
					"has changed password for user id = " + userId + " for reset request on " + dateAdded + " with email " + email));
		}
		
		UserRegistrationEmailService.getInstance().sendPasswordResetSuccessEmail(email, sender);
		
		Map<String, Object> retMap = new HashMap<>();
		retMap.put("success", true);
		retMap.put("userId", userId);
		retMap.put("message", "Email has been sent to: " + email);
		return WebUtility.getResponse(retMap, 200);
	}

}
