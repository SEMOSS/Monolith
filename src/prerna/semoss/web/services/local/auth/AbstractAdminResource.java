package prerna.semoss.web.services.local.auth;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;

import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;

public class AbstractAdminResource {
	
	SecurityAdminUtils performAdminCheck(@Context HttpServletRequest request, User user) throws IllegalAccessException {
		SecurityAdminUtils adminUtils = SecurityAdminUtils.getInstance(user);
		if(adminUtils == null) {
			throw new IllegalArgumentException("User is not an admin");
		}
		return adminUtils;
	}
	
}
