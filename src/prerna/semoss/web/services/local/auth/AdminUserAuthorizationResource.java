package prerna.semoss.web.services.local.auth;

import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("/auth/user")
public class AdminUserAuthorizationResource extends AbstractAdminResource {
	
	private static final Logger logger = LogManager.getLogger(AdminUserAuthorizationResource.class);
	
	@POST
	@Produces("application/json")
	@Path("/registerUser")
	public Response registerUser(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Hashtable<String, String> errorRet = new Hashtable<>();
		boolean success = false;
		try {
			User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);

			String newUserId = form.getFirst("userId");
			String name = form.getFirst("name");
			String email = form.getFirst("email");
			String type = form.getFirst("type");
			Boolean newUserAdmin = Boolean.parseBoolean(form.getFirst("admin"));
			String password = form.getFirst("password");
			// validate email & password
			if (email != null && !email.isEmpty()) {
				String emailError = AbstractSecurityUtils.validEmail(email);
				if (!emailError.isEmpty()) {
					throw new IllegalArgumentException(emailError);
				}
			}
			// password is only defined if native type
			if (password != null && !password.isEmpty()) {
				String passwordError = AbstractSecurityUtils.validPassword(password);
				if (!passwordError.isEmpty()) {
					throw new IllegalArgumentException(passwordError);
				}
			}
			if(SecurityAdminUtils.userIsAdmin(user)){
				success = SecurityUpdateUtils.registerUser(newUserId, name, email, password, type, newUserAdmin, !AbstractSecurityUtils.adminSetPublisher());
			} else {
				errorRet.put("error", "The user doesn't have the permissions to perform this action.");
				return WebUtility.getResponse(errorRet, 400);
			}
		} catch (IllegalArgumentException e){
    		logger.error(Constants.STACKTRACE, e);
			errorRet.put("error", e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
    		logger.error(Constants.STACKTRACE, e);
			errorRet.put("error", "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}
		return WebUtility.getResponse(success, 200);
	}
	
	/**
	 * Set user as publisher
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Produces("application/json")
	@Path("setUserPublisher")
	public Response setUserPublisher(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String userId = form.getFirst("userId");
		boolean isPublisher = Boolean.parseBoolean(form.getFirst("isPublisher"));
		
		try {
			adminUtils.setUserPublisher(userId, isPublisher);
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}
}
