package prerna.web.conf;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityUserAccessKeyUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;

public class OpenAIFilter implements Filter {

	private static final Logger classLogger = LogManager.getLogger(OpenAIFilter.class);

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		HttpServletRequest request = (HttpServletRequest) arg0;
		HttpSession session = request.getSession(false);
		User user = null;
		if(session != null) {
			user = (User) session.getAttribute(Constants.SESSION_USER);
		}

		if(user != null) {
			arg2.doFilter(arg0, arg1);
			return;
		} 

		// see if there is an auth value
		String authValue = request.getHeader("Authorization");
		if(authValue == null) {
			authValue = request.getHeader("authorization");
			if(authValue == null) {
				// no token? just go through and other filters will validate
				arg2.doFilter(arg0, arg1);
				return;
			}
		}

		
		// This is a unique format we use when calling SEMOSS directly from OpenAI
		// We pass our access key and secret key in an f string separated by a colon (ex. f"{access_key}:{secret_key}") in the api_key parameter
		// OpenAI sets this as the "Bearer " authorization
		if(authValue.startsWith("Bearer") || authValue.startsWith("bearer")) {
			String bearerToken = authValue.substring("Bearer".length()).trim();

			if(bearerToken != null && !bearerToken.isEmpty()) {
				String[] split = bearerToken.split(":");
				if(split != null && split.length == 2) {
					String accessKey = split[0];
					String secretKey = split[1];

					try {
						user = SecurityUserAccessKeyUtils.validateKeysAndReturnUser(accessKey, secretKey);
					} catch (IllegalAccessException e) {
						classLogger.error(Constants.STACKTRACE, e);
					}
					if(user == null) {
						classLogger.error(ResourceUtility.getLogMessage(request, request.getSession(false), null, "could not login using user access key '"+accessKey+"' with invalid secret key"));
					}

					AccessToken token = null;
					if(user != null) {
						token = user.getPrimaryLoginToken();
						// let us make sure this login type is still allowed to login via access/secret key
						{
							AuthProvider provider = token.getProvider();
							boolean accessKeysAllowed = SocialPropertiesUtil.getInstance().accessKeysAllowed(provider);
							if(!accessKeysAllowed) {
								classLogger.error(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to login using access/secret key but administrator has disabeled for provider "+provider.name()));
								user = null;
								token = null;
							}
						}
					}

					if(user != null && token != null) {
						SecurityUserAccessKeyUtils.updateAccessTokenLastUsed(accessKey);
						session = request.getSession(true);
						session.setAttribute(Constants.SESSION_USER, user);
						session.setAttribute(Constants.SESSION_USER_ID_LOG, token.getId());
						classLogger.info(ResourceUtility.getLogMessage(request, session, User.getSingleLogginName(user), "is logging in with provider " +  token.getProvider() + " with user access key"));
					}
				}
			}
		}

		// doesn't matter if we made a user or didn't
		// we will continue the filter chain because the {@link NoUserInSessionFilter} 
		// will catch unauthorized access

		// wrap the request to allow subsequent reading
		HttpServletRequestWrapper requestWrapper = new HttpServletRequestWrapper(request);
		arg2.doFilter(requestWrapper, arg1);
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
