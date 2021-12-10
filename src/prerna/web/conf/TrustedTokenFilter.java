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

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.semoss.web.services.config.TrustedTokenService;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;

public class TrustedTokenFilter implements Filter {

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		HttpServletRequest request = (HttpServletRequest) arg0;
		String authValue = request.getHeader("Authorization");
		if(authValue == null) {
			// no token? just go through and other filters will validate
			arg2.doFilter(arg0, arg1);
			return;
		}
		
		HttpSession session = ((HttpServletRequest)arg0).getSession(false);
		User user = null;
		if(session != null) {
			user = (User) session.getAttribute(Constants.SESSION_USER);
		}
		
		if(user == null) {
			// okay, we have a token
			// and no current user
			// we have to validate this stuff
			String ip = ResourceUtility.getClientIp(request);
			String userId = request.getHeader("UserId");
			
			String token = TrustedTokenService.getTokenForIp(ip);
			authValue = authValue.replace("Bearer ", "");
			
			// error handling
			if(token == null) {
				throw new IllegalArgumentException("This application does not have a valid trusted token or token has expired");
			}
			if(!token.equals(authValue)) {
				throw new IllegalArgumentException("The token value is invalid");
			}
			if(userId == null || (userId = userId.trim()).isEmpty()) {
				throw new IllegalArgumentException("The user id must be defined");
			}
			
			AccessToken accessToken = new AccessToken();
			accessToken.setId(userId);
			accessToken.setProvider(AuthProvider.WINDOWS_USER);
			user = new User();
			user.setAccessToken(accessToken);
			request.getSession(true).setAttribute(Constants.SESSION_USER, user);
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
