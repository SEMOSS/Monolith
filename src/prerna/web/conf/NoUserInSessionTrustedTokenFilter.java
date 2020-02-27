package prerna.web.conf;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.stream.Collectors;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import prerna.auth.AuthProvider;
import prerna.auth.InsightToken;
import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.semoss.web.services.local.UserResource;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.web.requests.MultiReadHttpServletRequest;

public class NoUserInSessionTrustedTokenFilter implements Filter {

	// this is so if you are making a direct BE request
	// after you sign in
	// we can redirect you back to the original call
	// instead of taking you to the base SemossWeb URL
	public static final String ENDPOINT_REDIRECT_KEY = "ENDPOINT_REDIRECT_KEY";
	
	public static final String MONOLITH_ROUTE = "MONOLITH_ROUTE";
	public static final String MONOLITH_PREFIX = "MONOLITH_PREFIX";
	
	private static final String LOGIN = "login";

	private static final String NO_USER_HTML = "/noUserFail/";
	protected static List<String> ignoreDueToFE = new Vector<String>();
	
	// maps from the IP the user is coming in with the cookie
	private static Map ipMapper = new Hashtable();

	static {
		// allow these for successful dropping of
		// sessions when browser is closed/refreshed
		// these do their own session checks
		ignoreDueToFE.add("engine/cleanSession");
		ignoreDueToFE.add("engine/cancelCleanSession");

		ignoreDueToFE.add("config");
		ignoreDueToFE.add("auth/logins");
		ignoreDueToFE.add("auth/loginsAllowed");
		//ignoreDueToFE.add("auth/login");
		ignoreDueToFE.add("auth/createUser");
		for(AuthProvider v : AuthProvider.values()) {
			ignoreDueToFE.add("auth/userinfo/" +  v.toString().toLowerCase());
			ignoreDueToFE.add("auth/login/" +  v.toString().toLowerCase());
		}
	}

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {


		ServletContext context = arg0.getServletContext();

		if(AbstractSecurityUtils.securityEnabled()) {
			// this will be the full path of the request
			// like http://localhost:8080/Monolith_Dev/api/engine/runPixel
			String fullUrl = ((HttpServletRequest) arg0).getRequestURL().toString();
			String contextPath = ((HttpServletRequest) arg0).getContextPath();
			HttpSession session = ((HttpServletRequest) arg0).getSession(false);
			//if(session != null)
			//	System.err.println(" Session id is " + session.getId());

			// additional requirement
			// the front end comes with
			// api/engine/redirect?insight=something something&prefix_token = something
			// need to see if there is a user if not, we redirect to the new url
			// user is not available ipmapper has it, all set good to go
			// user is there
			// first time when this url comes - I dont have a session
			// so do the filter get back and then redirect to this url
			
			// REALLY DISLIKE THIS CHECK!!!
			// eventually I need to take this off
			if(!isIgnored(fullUrl)) {
				// due to FE being annoying
				// we need to push a response for this one end point
				// since security is embedded w/ normal semoss and not standalone

				User user = null;
				if(session != null) {
					//System.out.println("Session ID >> " + session.getId());
					user = (User) session.getAttribute(Constants.SESSION_USER);
				}
				

				// if no user
				// need to make a check in terms of am I a part of trusted host scheme
				// basically the RDF_map will have a list of IPs for trusted host who can make a call
				// when that IP makes a call, we will transfer the session and give the user over
				// RDF_MAP
				// Trusted_token	true // says this instance will work with trusted token
				// Trusted_host <list of ips to accept request from>
				// Token_Prefix this is an optional piece - I am not going to implement right now
				boolean isTrustedDomain = DIHelper.getInstance().getProperty("TRUSTED_TOKEN") != null;
				
				if(isTrustedDomain)
				{				
					HttpServletRequest req = (HttpServletRequest) arg0; 
					String ipAddress = req.getHeader("X-FORWARDED-FOR");  
					if (ipAddress == null) {  
					    ipAddress = req.getRemoteAddr();  
					}
	
					String tokenName = DIHelper.getInstance().getProperty("TRUSTED_TOKEN_PREFIX");
	
					// if cookie is there - and session id is sent
					// associate with the session id
					String sessionId = null;
					if(req.getMethod().equalsIgnoreCase("GET"))
						sessionId = req.getParameter(tokenName);
					
					
					if(sessionId != null && !ipMapper.containsKey(sessionId)) // && user != null
					{
						String [] trustedIPs = DIHelper.getInstance().getProperty("TRUSTED_TOKEN_DOMAIN").split(";");
						boolean allow = false;
						for(int ipIndex = 0;ipIndex < trustedIPs.length;ipIndex++)
						{
							// let us do allow all as well
							if(ipAddress.equalsIgnoreCase(trustedIPs[ipIndex]) || trustedIPs[ipIndex].equalsIgnoreCase("*"))
							{
								allow = true;
								break;
							}
						}
						
						if(allow)
						{
							// this is the case where you associate
							String cookieId = null;
								
							// try to see if I can get the session id directly
							// if the user is coming in the first time
							// create the session
							/*if(session.isNew())
							{
								session.invalidate();
								session = null;
							}*/
							if(session == null)
							{
								session = req.getSession(true);
								//session.setAttribute(Constants.SESSION_USER, user);	
							}
							cookieId = session.getId();
							if(sessionId != null)
								ipMapper.put(sessionId, cookieId);
							
							/*Cookie[] cookies = req.getCookies();
							if (cookies != null) {
								for (Cookie c : cookies) {
									if (c.getName().equals(DBLoader.getSessionIdKey())) {
										cookieId = c.getValue();
										System.err.println("Cookie is set to.. " + cookieId);
										ipMapper.put(sessionId, cookieId);
										//break;
									}
								}
							}*/
						} 
					}
					else if(sessionId != null && ipMapper.containsKey(sessionId))
					{
						
						// this is the class where you redirect
						// I dont think 
						
						String cookieId = (String)ipMapper.get(sessionId);
						Cookie k = new Cookie(DBLoader.getSessionIdKey(), cookieId);
						k.setPath(contextPath);
						((HttpServletResponse)arg1).addCookie(k);
						
						Cookie[] cookies = req.getCookies();
						if (cookies != null) {
							System.err.println("Forcing session value !");
							for (Cookie c : cookies) {
								if (c.getName().equals(DBLoader.getSessionIdKey())) {
									if(c.getName().equalsIgnoreCase(DBLoader.getSessionIdKey()))
										c.setValue(cookieId);
									//System.err.println("Cookie is set to.. " + cookieId);
									ipMapper.put(sessionId, cookieId);
									//break;
								}
							}
						}
						
						if(req.getMethod().equalsIgnoreCase("GET")){
							((HttpServletResponse) arg1).setStatus(302);
							
							// modify the prefix if necessary
							Map<String, String> envMap = System.getenv();
							if(envMap.containsKey(MONOLITH_PREFIX)) {
								fullUrl = fullUrl.replace(contextPath, envMap.get(MONOLITH_PREFIX));
							}
						    String queryString = req.getQueryString();
						    queryString = queryString.replace(tokenName, "dummy");
						    ((HttpServletResponse) arg1).sendRedirect(fullUrl + "?" + queryString);
						    
						    return;
						} 					
					}
					// else this is the regular flow - let us the user go through it
				}
			}
		}
		arg2.doFilter(arg0, arg1);

	}

	/**
	 * Method to determine where to redirect the user
	 * @param context
	 * @param arg0
	 * @param arg1
	 * @throws IOException
	 */
	private void setInvalidEntryRedirect(ServletContext context, ServletRequest arg0, ServletResponse arg1, String endpoint) throws IOException {
		String fullUrl = ((HttpServletRequest) arg0).getRequestURL().toString();
		((HttpServletResponse) arg1).setStatus(302);
		String redirectUrl = ((HttpServletRequest) arg0).getHeader("referer");
		// if no referrer
		// then a person hit the endpoint directly
		if(redirectUrl == null) {
			((HttpServletRequest) arg0).getSession(true).setAttribute(ENDPOINT_REDIRECT_KEY, fullUrl);
			// this will be the deployment name of the app
			String loginRedirect = UserResource.getLoginRedirect();
			((HttpServletResponse) arg1).setStatus(302);
			if(loginRedirect != null) {
				((HttpServletResponse) arg1).sendRedirect(loginRedirect);
			} else {
				String contextPath = context.getContextPath();
				redirectUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length()) + NO_USER_HTML;
				((HttpServletResponse) arg1).sendRedirect(redirectUrl);
			}
		} else {
			redirectUrl = redirectUrl + "#!/" + endpoint;
			((HttpServletResponse) arg1).setHeader("redirect", redirectUrl);
			((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + redirectUrl);
		}
	}

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		// TODO Auto-generated method stub

	}

	/**
	 * Due to how the FE security is set up
	 * Need to ignore some URLs :(
	 * I REALLY DISLIKE THIS!!!
	 * @param fullUrl
	 * @return
	 */
	protected static boolean isIgnored(String fullUrl) {
		for(String ignore : ignoreDueToFE) {
			if(fullUrl.endsWith(ignore)) {
				return true;
			}
		}
		return false;
	}

}
