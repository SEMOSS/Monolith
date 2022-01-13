package prerna.semoss.web.services.local.auth;

import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

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

import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("/auth/admin/user")
public class AdminUserAuthorizationResource extends AbstractAdminResource {
	
	private static final Logger logger = LogManager.getLogger(AdminUserAuthorizationResource.class);
	
	@GET
	@Path("/isAdminUser")
	public Response isAdminUser(@Context HttpServletRequest request) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(ResourceUtility.ERROR_KEY, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		boolean isAdmin = SecurityAdminUtils.userIsAdmin(user);
		return WebUtility.getResponse(isAdmin, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("/registerUser")
	public Response registerUser(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Hashtable<String, String> errorRet = new Hashtable<>();
		boolean success = false;
		try {
			String newUserId = form.getFirst("userId");
			if(newUserId == null || newUserId.isEmpty()) {
				throw new IllegalArgumentException("The user id cannot be null or empty");
			}
			String name = form.getFirst("name");
			String email = form.getFirst("email");
			String type = form.getFirst("type");
			Boolean newUserAdmin = Boolean.parseBoolean(form.getFirst("admin"));
			Boolean publisher = Boolean.parseBoolean(form.getFirst("publisher"));
			String exporterInput = form.getFirst("exporter");
			Boolean exporter = Boolean.TRUE;
			if (exporterInput != null && !exporterInput.isEmpty()) {
				exporter = Boolean.parseBoolean(exporterInput);
			}


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
			
			User user = null;
			try {
				user = ResourceUtility.getUser(request);
			} catch (IllegalAccessException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(ResourceUtility.ERROR_KEY, "User session is invalid");
				return WebUtility.getResponse(errorMap, 401);
			}
			
			if(SecurityAdminUtils.userIsAdmin(user)){
				success = SecurityUpdateUtils.registerUser(newUserId, name, email, password, type, newUserAdmin, publisher, exporter);
			} else {
				errorRet.put(ResourceUtility.ERROR_KEY, "The user doesn't have the permissions to perform this action.");
				return WebUtility.getResponse(errorRet, 400);
			}
		} catch (IllegalArgumentException e){
    		logger.error(Constants.STACKTRACE, e);
			errorRet.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
    		logger.error(Constants.STACKTRACE, e);
			errorRet.put(ResourceUtility.ERROR_KEY, "An unexpected error happened. Please try again.");
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
	@Path("/setUserPublisher")
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

		Gson gson = new Gson();
		Map<String, Object> userInfo = gson.fromJson(form.getFirst("user"), Map.class);
		boolean ret = false;
		try {
			ret = adminUtils.editUser(userInfo);
		} catch(IllegalArgumentException e) {
			Map<String, String> retMap = new Hashtable<>();
			retMap.put(ResourceUtility.ERROR_KEY, e.getMessage());
			return WebUtility.getResponse(retMap, 400);
		}
		if(!ret) {
			Map<String, String> retMap = new Hashtable<>();
			retMap.put(ResourceUtility.ERROR_KEY, "Unknown error occured with updating user. Please try again.");
			return WebUtility.getResponse(retMap, 400);
		}
		return WebUtility.getResponse(ret, 200);
	}

	@POST
	@Produces("application/json")
	@Path("/deleteUser")
	public Response deleteUser(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
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

		String userToDelete = form.getFirst("userId");
		boolean success = adminUtils.deleteUser(userToDelete);
		return WebUtility.getResponse(success, 200);
	}

	@GET
	@Path("/getAllDbUsers")
	@Produces("application/json")
	@Deprecated
	/**
	 * PLEASE USE {@link AdminUserAuthorizationResource#getAllUsers(HttpServletRequest)}
	 * @param request
	 * @return
	 */
	public Response getAllDbUsers(@Context HttpServletRequest request) {
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

		List<Map<String, Object>> ret = adminUtils.getAllUsers();
		return WebUtility.getResponse(ret, 200);
	}
	
	@GET
	@Path("/getAllUsers")
	@Produces("application/json")
	public Response getAllUsers(@Context HttpServletRequest request) {
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

		List<Map<String, Object>> ret = adminUtils.getAllUsers();
		return WebUtility.getResponse(ret, 200);
	}
}
