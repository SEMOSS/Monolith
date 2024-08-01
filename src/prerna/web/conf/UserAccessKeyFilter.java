package prerna.web.conf;

import java.io.IOException;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
import prerna.io.connector.ms.MSProfile;
import prerna.security.HttpHelperUtility;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.semoss.web.services.local.UserResource;
import prerna.util.BeanFiller;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;

public class UserAccessKeyFilter implements Filter {

	private static final Logger classLogger = LogManager.getLogger(UserAccessKeyFilter.class);

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
		
		/*
		 * Check if bearer token is passed
		 * Which will initiate a lookup against some SSO provider
		 * 
		 * Else if Basic is used
		 * This will initiate a platform access/secret key pair being used
		 */
		
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

		if(authValue.startsWith("Bearer") || authValue.startsWith("bearer")) {
			String bearerToken = authValue.substring("Bearer".length()).trim();

			// attempt to login using bearer token
			String provider = request.getHeader("Bearer-Provider");
			if(provider == null) {
				// try to guess
				Map<String, Boolean> loginsMap = SocialPropertiesUtil.getInstance().getLoginsAllowed();
				Set<String> allowedLogins = new HashSet<>();
				for(String login : loginsMap.keySet()) {
					if(loginsMap.get(login)) {
						allowedLogins.add(login);
					}
				}
				
				//TODO: add a property on AuthProvider to make this easier
				// now remove all the non-sso logins
				allowedLogins.remove(AuthProvider.GOOGLE_MAP.toString());
				allowedLogins.remove(AuthProvider.NATIVE.toString());
				allowedLogins.remove(AuthProvider.SAML.toString());
				allowedLogins.remove(AuthProvider.LINOTP.toString());
				allowedLogins.remove(AuthProvider.CAC.toString());
				allowedLogins.remove(AuthProvider.WINDOWS_USER.toString());
				allowedLogins.remove(AuthProvider.API_USER.toString());
				
				if(allowedLogins.size() == 1) {
					provider = allowedLogins.iterator().next();
				} else {
					classLogger.warn("Bearer token passed but unknown provider to use");
				}
			}
			
			if(provider != null) {
				SocialPropertiesUtil socialData = SocialPropertiesUtil.getInstance();
				
				// TODO: need to build out better objects instead of hard coding login urls/json parsing payloads
				// TODO: need to build out better objects instead of hard coding login urls/json parsing payloads
				// TODO: need to build out better objects instead of hard coding login urls/json parsing payloads

				if(provider.equalsIgnoreCase("okta")) {
					String prefix = "okta_";
					// sub is the unique id for a user in okta
					String jsonPattern = "[sub,name,email,phone_number]";
					String[] beanProps = {"id","name","email","phone"};
					String userinfo_url = socialData.getProperty(prefix + "userinfo_url");
					boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

					AccessToken accessToken = new AccessToken();
					accessToken.setProvider(AuthProvider.OKTA);

					String output = HttpHelperUtility.makeGetCall(userinfo_url, bearerToken, null, true);
					accessToken = (AccessToken)BeanFiller.fillFromJson(output, jsonPattern, beanProps, accessToken);
					
					UserResource.addAccessToken(accessToken, request, autoAdd);
				} else if(provider.equalsIgnoreCase("ms")) {
					String prefix = "ms_";
					boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

					AccessToken accessToken = new AccessToken();
					accessToken.setProvider(AuthProvider.OKTA);
					MSProfile.fillAccessToken(accessToken, null);
					UserResource.addAccessToken(accessToken, request, autoAdd);
				}
			}
		} else if (authValue.startsWith("Basic") || authValue.startsWith("basic")){
			String basicAuth = authValue.substring("basic".length()).trim();
			
			// this is a base64 encoded username:password
			byte[] decodedBytes = Base64.getDecoder().decode(basicAuth);
			String userpass = new String(decodedBytes);
			if(userpass != null && !userpass.isEmpty()) {
				String[] split = userpass.split(":");
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
							String prefix = provider.toString().toLowerCase();
							boolean accessKeysAllowed = Boolean.parseBoolean(SocialPropertiesUtil.getInstance().getProperty(prefix + "_access_keys_allowed")+"");
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
