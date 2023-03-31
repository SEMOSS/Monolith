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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.engine.impl.rdbms.RDBMSNativeEngine;
import prerna.semoss.web.services.config.TrustedTokenService;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;

public class TrustedTokenFilter implements Filter {

	private static final Logger logger = LogManager.getLogger(RDBMSNativeEngine.class);
	
	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		HttpServletRequest request = (HttpServletRequest) arg0;
		String authValue = request.getHeader("Authorization");
		if(authValue == null || !authValue.contains("Bearer")) {
			// no token? just go through and other filters will validate
			arg2.doFilter(arg0, arg1);
			return;
		}
		
		HttpSession session = request.getSession(false);
		User user = null;
		if(session != null) {
			user = (User) session.getAttribute(Constants.SESSION_USER);
		}
		
		if(user == null) {
			SocialPropertiesUtil socialData = SocialPropertiesUtil.getInstance();
			if(socialData.getLoginsAllowed().get("api_user") == null || !socialData.getLoginsAllowed().get("api_user")) {
				// token is not enabled
				arg2.doFilter(arg0, arg1);
				return;
			}
			
			// okay, we have a token
			// and no current user
			// we have to validate this stuff
			String ip = ResourceUtility.getClientIp(request);
			Object[] tokenDetails = TrustedTokenService.getTokenForIp(ip);
			if(tokenDetails == null) {
				// token not found for this ip
				arg2.doFilter(arg0, arg1);
				return;
			}
			
			String ipToken = (String) tokenDetails[0];
			authValue = authValue.replace("Bearer ", "");
			
			String userId = (String) tokenDetails[2];
			
			// error handling
			if(ipToken == null) {
				logger.error(Constants.STACKTRACE, "This application does not have a valid trusted token or token has expired");
				arg2.doFilter(arg0, arg1);
				return;
			}
			if(!ipToken.equals(authValue)) {
				logger.error(Constants.STACKTRACE, "The token value is invalid");
				arg2.doFilter(arg0, arg1);
				return;
			}
			if(userId == null || (userId = userId.trim()).isEmpty()) {
				logger.error(Constants.STACKTRACE, "The client id must be defined");
				arg2.doFilter(arg0, arg1);
				return;
			}
			
			AccessToken token = new AccessToken();
			token.setId(userId);
			token.setProvider(AuthProvider.API_USER);
			user = new User();
			user.setAccessToken(token);
			session = request.getSession(true);
			session.setAttribute(Constants.SESSION_USER, user);
			session.setAttribute(Constants.SESSION_USER_ID_LOG, token.getId());
		}
		
		arg2.doFilter(arg0, arg1);
	}
	
	@Override
	public void destroy() {
		// destroy

	}
	
	@Override
	public void init(FilterConfig arg0) throws ServletException {
		// init
	}

}
