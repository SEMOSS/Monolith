package prerna.semoss.web.services.local;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.core.Context;

import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

public class ResourceUtility {

	protected static List<String> allowAccessWithoutUsers = new ArrayList<>();
	static {
		allowAccessWithoutUsers.add("config");
		allowAccessWithoutUsers.add("config/fetchCsrf");
	}
	
	public static List<String> allowAccessWithoutLogin = new ArrayList<>();
	static {
		// allow these for successful dropping of
		// sessions when browser is closed/refreshed
		// these do their own session checks
		allowAccessWithoutLogin.add("session/active");
		allowAccessWithoutLogin.add("session/cleanSession");
		allowAccessWithoutLogin.add("session/cancelCleanSession");
		allowAccessWithoutLogin.add("session/invalidateSession");

		allowAccessWithoutLogin.add("config");
		allowAccessWithoutLogin.add("config/fetchCsrf");
		allowAccessWithoutLogin.add("auth/logins");
		allowAccessWithoutLogin.add("auth/loginsAllowed");
		allowAccessWithoutLogin.add("auth/login");
		allowAccessWithoutLogin.add("auth/loginLDAP");
		allowAccessWithoutLogin.add("auth/changeADPassword");
		allowAccessWithoutLogin.add("auth/loginLinOTP");
		allowAccessWithoutLogin.add("auth/createUser");
		allowAccessWithoutLogin.add("auth/whoami");
		allowAccessWithoutLogin.add("auth/user/setupResetPassword");
		allowAccessWithoutLogin.add("auth/user/resetPassword");
		for (AuthProvider v : AuthProvider.values()) {
			allowAccessWithoutLogin.add("auth/userinfo/" + v.toString().toLowerCase());
			allowAccessWithoutLogin.add("auth/login/" + v.toString().toLowerCase());
		}
	}
	
	
	
	/**
	 * Get the user
	 * @param request
	 * @return
	 * @throws IOException
	 */
	public static User getUser(@Context HttpServletRequest request) throws IllegalAccessException {
		HttpSession session = request.getSession(false);
		if(session == null){
			throw new IllegalAccessException("User session is invalid");
		}
		
		User user = (User) session.getAttribute(Constants.SESSION_USER);
		if(user == null) {
			throw new IllegalAccessException("User session is invalid");
		}
		
		return user;
	}
	
	public static String getClientIp(@Context HttpServletRequest request) {
        String remoteAddr = "";
        if (request != null) {
            remoteAddr = WebUtility.inputSanitizer(request.getHeader("X-FORWARDED-FOR"));
            if (remoteAddr == null || "".equals(remoteAddr)) {
                remoteAddr =  request.getRemoteAddr();
            }
        }

        return WebUtility.inputSanitizer(remoteAddr);
    }
	
	/**
	 * Need to ignore some URLs
	 * @param fullUrl
	 * @return
	 */
	public static boolean allowAccessWithoutUsers(String fullUrl) {
		for (String ignore : allowAccessWithoutUsers) {
			if (fullUrl.endsWith(ignore)) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Need to ignore some URLs
	 * @param fullUrl
	 * @return
	 */
	public static boolean allowAccessWithoutLogin(String fullUrl) {
		for (String ignore : allowAccessWithoutLogin) {
			if (fullUrl.endsWith(ignore)) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Need to ignore some URLs
	 * @param fullUrl
	 * @return
	 */
	public static boolean endsWithMatch(Collection<String> ignoreForFE, String fullUrl) {
		for (String ignore : ignoreForFE) {
			if (fullUrl.endsWith(ignore)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Standardized format for the message
	 * @param request
	 * @param session
	 * @param userId
	 * @param message
	 * @return
	 */
	public static String getLogMessage(HttpServletRequest request, HttpSession session, String userId, String message) {
		String sessionId = "NO SESSION";
		if(session != null) {
			sessionId = session.getId();
		}
		if(userId == null || userId.isEmpty()) {
			return "IP = " + Utility.cleanLogString(ResourceUtility.getClientIp(request) + " : Session = " + sessionId + " : USERID = INVALID " + message);
		}
		return "IP = " + Utility.cleanLogString(ResourceUtility.getClientIp(request) + " : Session = " + sessionId + " : USERID = " + userId + " " + message);
	}
	
}
