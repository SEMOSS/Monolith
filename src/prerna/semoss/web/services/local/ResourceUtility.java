package prerna.semoss.web.services.local;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.core.Context;

import prerna.auth.User;
import prerna.util.Constants;

public class ResourceUtility {

	public static String ERROR_KEY = "errorMessage";
	
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
	
}
