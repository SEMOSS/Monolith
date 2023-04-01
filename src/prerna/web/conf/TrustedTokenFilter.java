package prerna.web.conf;

import java.io.IOException;
import java.util.Base64;

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
import prerna.auth.utils.SecurityAPIUserUtils;
import prerna.engine.impl.rdbms.RDBMSNativeEngine;
import prerna.semoss.web.services.config.TrustedTokenService;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;
import prerna.util.Utility;

public class TrustedTokenFilter implements Filter {

	private static final Logger logger = LogManager.getLogger(RDBMSNativeEngine.class);

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		HttpServletRequest request = (HttpServletRequest) arg0;
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
			
			
			boolean dynamicToken = Utility.getApplicationRequireDynamicToken();
			String authValue = request.getHeader("authorization");
			if(authValue == null) {
				// no token? just go through and other filters will validate
				arg2.doFilter(arg0, arg1);
				return;
			}
			if(dynamicToken && !authValue.contains("Bearer")) {
				// no bearer token
				arg2.doFilter(arg0, arg1);
				return;
			} else if(!dynamicToken && !authValue.contains("Basic")) {
				// no basic token
				arg2.doFilter(arg0, arg1);
				return;
			}
			
			if(!dynamicToken) {
				authValue = authValue.replace("Basic ", "");
				// this is a base64 encoded username:password
				byte[] decodedBytes = Base64.getDecoder().decode(authValue);
				String userpass = new String(decodedBytes);
				String[] split = userpass.split(":");
				String clientId = split[0];
				String secretKey = split[1];
				
				// can you login?
				if(SecurityAPIUserUtils.validCredentials(clientId, secretKey)) {
					
					AccessToken token = new AccessToken();
					token.setId(clientId);
					token.setProvider(AuthProvider.API_USER);
					user = new User();
					user.setAccessToken(token);
					session = request.getSession(true);
					session.setAttribute(Constants.SESSION_USER, user);
					session.setAttribute(Constants.SESSION_USER_ID_LOG, token.getId());
					
					logger.info(ResourceUtility.getLogMessage(request, session, User.getSingleLogginName(user), "is logging in with provider " +  token.getProvider()));
				} else {
					logger.error(ResourceUtility.getLogMessage(request, request.getSession(false), null, "is trying to login as API_USER with invalid credentails using client id = '" + clientId + "'"));
				}
				
			} else {
				authValue = authValue.replace("Bearer ", "");

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
	
				String userId = (String) tokenDetails[2];
	
				// error handling
				if(ipToken == null) {
					logger.error(ResourceUtility.getLogMessage(request, request.getSession(false), null, "is trying to login as API_USER but does not have a valid trust token or token has expired"));
					arg2.doFilter(arg0, arg1);
					return;
				}
				if(!ipToken.equals(authValue)) {
					logger.error(ResourceUtility.getLogMessage(request, request.getSession(false), null, "is trying to login as API_USER but token value is invalid"));
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
				logger.info(ResourceUtility.getLogMessage(request, session, User.getSingleLogginName(user), "is logging in with provider " +  token.getProvider()));
			}
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
