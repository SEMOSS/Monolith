package prerna.semoss.web.services.local;

import java.io.IOException;
import java.util.Collection;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.core.Context;

import prerna.auth.User;
import prerna.util.Constants;
import prerna.util.Utility;

public class ResourceUtility {

	public static final String ERROR_KEY = "errorMessage";
	
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
            remoteAddr = request.getHeader("X-FORWARDED-FOR");
            if (remoteAddr == null || "".equals(remoteAddr)) {
                remoteAddr = request.getRemoteAddr();
            }
        }

        return remoteAddr;
    }
	
	/**
	 * Need to ignore some URLs
	 * @param fullUrl
	 * @return
	 */
	public static boolean isIgnored(Collection<String> ignoreForFE, String fullUrl) {
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
			return "IP = " + Utility.cleanLogString(ResourceUtility.getClientIp(request)) + " : Session = " + sessionId + " : USERID = INVALID " + message;
		}
		return "IP = " + Utility.cleanLogString(ResourceUtility.getClientIp(request)) + " : Session = " + sessionId + " : USERID = " + userId + " " + message;
	}
	
}
