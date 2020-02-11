package prerna.web.conf;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
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
import prerna.web.requests.MultiReadHttpServletRequest;

public class NoUserInSessionFilter implements Filter {

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

	static {
		// allow these for successful dropping of
		// sessions when browser is closed/refreshed
		// these do their own session checks
		ignoreDueToFE.add("session/cleanSession");
		ignoreDueToFE.add("session/cancelCleanSession");
		ignoreDueToFE.add("session/invalidateSession");

		ignoreDueToFE.add("config");
		ignoreDueToFE.add("auth/logins");
		ignoreDueToFE.add("auth/loginsAllowed");
		ignoreDueToFE.add("auth/login");
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

			// REALLY DISLIKE THIS CHECK!!!
			if(!isIgnored(fullUrl)) {
				// due to FE being annoying
				// we need to push a response for this one end point
				// since security is embedded w/ normal semoss and not standalone

				HttpSession session = ((HttpServletRequest) arg0).getSession(false);
				User user = null;
				if(session != null) {
					//System.out.println("Session ID >> " + session.getId());
					user = (User) session.getAttribute(Constants.SESSION_USER);
				}

				// if no user
				if(user == null || (!AbstractSecurityUtils.anonymousUsersEnabled() && user.getLogins().isEmpty()) ) {
					// do a condition here if the session id request parameter is available
					// eventually this will be that and the tableau
					HttpServletRequest req = (HttpServletRequest) arg0; 
					if(req.getParameter(DBLoader.getSessionIdKey()) != null) {
						String sessionId = req.getParameter(DBLoader.getSessionIdKey());
						// create the cookie add it and sent it back
						Cookie k = new Cookie(DBLoader.getSessionIdKey(), sessionId);
						k.setPath(contextPath);
						((HttpServletResponse)arg1).addCookie(k);
						// in case there are other JSESSIONID
						// cookies, reset the value to the correct sessionId
						Cookie[] cookies = req.getCookies();
						if (cookies != null) {
							for (Cookie c : cookies) {
								if (c.getName().equals(DBLoader.getSessionIdKey())) {
									c.setValue(sessionId);
									((HttpServletResponse)arg1).addCookie(c);
								}
							}
						}

						Set<String> routes = Collections.list(req.getParameterNames())
								.stream().filter(s -> s.startsWith("route"))
								.collect(Collectors.toSet());
						if(routes != null && !routes.isEmpty()) {
							for(String r : routes) {
								Cookie c = new Cookie(r, req.getParameter(r));
								c.setPath(contextPath);
								((HttpServletResponse)arg1).addCookie(c);
							}
						}
						
						// add the hash cookie
						String hash = req.getParameter("hash");
						Cookie h = new Cookie("HASH", hash);
						h.setPath(contextPath);
						((HttpServletResponse)arg1).addCookie(h);

						// and now redirect back to the URL
						// if get, we can do it
						if(req.getMethod().equalsIgnoreCase("GET")){
							((HttpServletResponse) arg1).setStatus(302);
							
							// modify the prefix if necessary
							Map<String, String> envMap = System.getenv();
							if(envMap.containsKey(MONOLITH_PREFIX)) {
								fullUrl = fullUrl.replace(contextPath, envMap.get(MONOLITH_PREFIX));
							}
							
							((HttpServletResponse) arg1).sendRedirect(fullUrl + "?" + req.getQueryString());
						} else {
							// BE cannot redirect a POST
							// send back an error and have the client remake the post
							setInvalidEntryRedirect(context, arg0, arg1, LOGIN);
						}
						return;
					}
					// no jsession id as a param
					// just a normal redirect
					else 
					{
						setInvalidEntryRedirect(context, arg0, arg1, LOGIN);
						// invalidate the session if necessary
						if(session != null) {
							session.invalidate();
						}
						return;
					}
				}

				// is the user logging in, but was previously at a different page?
				// this is because even if i set the redirect URL in the 
				// {@link UserResource#setMainPageRedirect(@Context HttpServletRequest request, @Context HttpServletResponse response)}
				// is sent to the pop-up for OAuth login
				String endpointRedirectUrl = (String) session.getAttribute(NoUserInSessionFilter.ENDPOINT_REDIRECT_KEY);
				if(endpointRedirectUrl != null && !endpointRedirectUrl.isEmpty()) {
					((HttpServletResponse) arg1).setHeader("redirect", endpointRedirectUrl);
					((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + endpointRedirectUrl);
					session.removeAttribute(NoUserInSessionFilter.ENDPOINT_REDIRECT_KEY);
					return;
				}
				
				// so we have a user
				// let us look at the cookies
				// are we redirecting based on the above
				// or it is the main session
				String hashId = null;
				Cookie[] cookies = ((HttpServletRequest)arg0).getCookies();
				if (cookies != null) {
					for (Cookie c : cookies) {
						if (c.getName().equals("HASH")) {
							hashId = c.getValue();
							break;
						}
					}
				}

				// well, we are the shared session
				if(hashId != null) {
					// is this the first time we are hooking up the shared session?
					if(!user.isShareSession(session.getId())) {
						// tricky tricky
						// if you have a hash id but not shared
						// you are trying to get in when you shouldn't
						setInvalidEntryRedirect(context, arg0, arg1, LOGIN);
						return;
					}

					// use a wrapper otherwise
					// POST data consumed will be destroyed
					// when we get to the actual request method
					MultiReadHttpServletRequest wrapper = new MultiReadHttpServletRequest(((HttpServletRequest)arg0));
					String insightId = wrapper.getParameter("i");
					String secret = wrapper.getParameter("s");

					// not enough input
					if(insightId == null || secret == null) {
						setInvalidEntryRedirect(context, arg0, arg1, LOGIN);
						return;
					}

					// we have the required input, but is it valid
					InsightToken token = user.getInsight(insightId);
					try {
						MessageDigest md = MessageDigest.getInstance("MD5");
						String finalData = token.getSalt()+secret;
						byte [] digest = md.digest(finalData.getBytes());
						StringBuffer sb = new StringBuffer();
						for (int i = 0; i < digest.length; i++) {
							sb.append(Integer.toString((digest[i] & 0xff) + 0x100, 16).substring(1));
						}
						if(hashId == null || !hashId.equals(sb+"")) {
							// bad input 
							setInvalidEntryRedirect(context, arg0, arg1, LOGIN);
							return;
						} 

						// this session has been ratified so remove the session and move the user forward
						user.removeShare(session.getId());
						// remove the hash cookie since the user has validated this session
						for (Cookie c : cookies) {
							if (c.getName().equals("HASH")) {
								c.setMaxAge(0);
								c.setPath(contextPath);
								((HttpServletResponse)arg1).addCookie(c);
								break;
							}
						}
						
						arg2.doFilter(wrapper, arg1);
						return;
					} catch (NoSuchAlgorithmException e) {
						e.printStackTrace();
					}
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
