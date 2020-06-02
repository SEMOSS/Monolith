package prerna.semoss.web.services.local;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.core.Context;

import prerna.auth.User;
import prerna.util.Constants;

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
	 * Standardized format for the message
	 * @param request
	 * @param session
	 * @param userId
	 * @param message
	 * @return
	 */
	public static String getLogMessage(HttpServletRequest request, HttpSession session, String userId, String message) {
		if(userId.isEmpty()) {
			return "IP = " + ResourceUtility.getClientIp(request) + " : Session = " + session.getId() + " : ID = INVALID " + message;
		}
		return "IP = " + ResourceUtility.getClientIp(request) + " : Session = " + session.getId() + " : ID = " + userId + " " + message;
	}
	
}
