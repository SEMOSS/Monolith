package prerna.semoss.web.services.local.auth;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;

import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.semoss.web.services.local.ResourceUtility;

public class AbstractAdminResource {

	SecurityAdminUtils performAdminCheck(@Context HttpServletRequest request) throws IllegalAccessException {
		User user = ResourceUtility.getUser(request);
		SecurityAdminUtils adminUtils = SecurityAdminUtils.getInstance(user);
		if(adminUtils == null) {
			throw new IllegalArgumentException("User is not an admin");
		}
		return adminUtils;
	}
	
}
