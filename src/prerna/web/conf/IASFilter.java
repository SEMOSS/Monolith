package prerna.web.conf;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.util.Constants;
import prerna.util.Utility;

public class IASFilter implements Filter {

	private static final String DOD_ID = "DOD_EDI_PN_ID";
	private static final String EMAIL = "PRI_EMAIL";
	private static final String ID = "UNIQUE_ID";

	private static final Logger logger = LogManager.getLogger(IASFilter.class.getName()); 

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		HttpSession session = ((HttpServletRequest)arg0).getSession(true);
		
		User user = (User) session.getAttribute(Constants.SESSION_USER);
		if(user == null) {
			user = new User();
			
			String dodId = ((HttpServletRequest)arg0).getHeader(DOD_ID);
			String email = ((HttpServletRequest)arg0).getHeader(EMAIL);
			String id = ((HttpServletRequest)arg0).getHeader(ID);
			
			boolean valid = true;
			if(dodId == null || email == null || id == null 
					|| dodId.isEmpty() || email.isEmpty() || id.isEmpty() ) {
				
				logger.info("REQUEST COMING WITH NO USER");
				logger.info("REQUEST COMING WITH NO USER");
				logger.info("REQUEST COMING WITH NO USER");
				logger.info("REQUEST COMING WITH NO USER");
				logger.info("REQUEST COMING WITH NO USER");
				valid = false;
			}
			
			if(valid) {
				AccessToken	token = new AccessToken();
				token.setProvider(AuthProvider.CAC);
				token.setId(id);
				token.setEmail(email);
				token.setName(email);
				
				SecurityUpdateUtils.addOAuthUser(token);
				
				user.setAccessToken(token);
				session.setAttribute(Constants.SESSION_USER, user);
	
				logger.info("NEW SESSION - USER ADDED WITH ID = " + Utility.cleanLogString(id));
				logger.info("NEW SESSION - USER ADDED WITH ID = " + Utility.cleanLogString(id));
				logger.info("NEW SESSION - USER ADDED WITH ID = " + Utility.cleanLogString(id));
				logger.info("NEW SESSION - USER ADDED WITH ID = " + Utility.cleanLogString(id));
			}
		} else {
			logger.info("EXISTING SESSION WITH USER ALREADY PRESENT");
			logger.info("EXISTING SESSION WITH USER ALREADY PRESENT");
			logger.info("EXISTING SESSION WITH USER ALREADY PRESENT");
			logger.info("EXISTING SESSION WITH USER ALREADY PRESENT");
		}
		
		arg2.doFilter(arg0, arg1);
	}

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		// TODO Auto-generated method stub
		
	}

}
