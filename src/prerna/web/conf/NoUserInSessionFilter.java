package prerna.web.conf;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.owasp.encoder.Encode;

import prerna.auth.InsightToken;
import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.reactor.mgmt.MgmtUtil;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.Settings;
import prerna.util.SocialPropertiesUtil;
import prerna.util.Utility;
import prerna.web.requests.MultiReadHttpServletRequest;
import prerna.web.services.util.WebUtility;

public class NoUserInSessionFilter implements Filter {
	
	private static final Logger logger = LogManager.getLogger(NoUserInSessionFilter.class);

	// this is so if you are making a direct BE request
	// after you sign in
	// we can redirect you back to the original call
	// instead of taking you to the base SemossWeb URL
	private static final String LOGIN = "login";
	
	private static final String NO_USER_HTML = "/noUserFail/";

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
			throws IOException, ServletException {
		ServletContext context = arg0.getServletContext();

		// this will be the full path of the request
		// like http://localhost:8080/Monolith_Dev/api/engine/runPixel

		String fullUrl = WebUtility.cleanHttpResponse(((HttpServletRequest) arg0).getRequestURL().toString());
		String contextPath = ((HttpServletRequest) arg0).getContextPath();

		if (!ResourceUtility.allowAccessWithoutLogin(fullUrl)) {
			// due to FE being annoying
			// we need to push a response for this one end point
			// since security is embedded w/ normal semoss and not standalone

			HttpSession session = ((HttpServletRequest) arg0).getSession(false);
			User user = null;
			if (session != null) {
				// System.out.println("Session ID >> " + session.getId());
				user = (User) session.getAttribute(Constants.SESSION_USER);
			}

			// if no user
			// need to make a check in terms of am I a part of trusted host scheme
			// basically the RDF_map will have a list of IPs for trusted host who can make a
			// call
			// when that IP makes a call, we will transfer the session and give the user
			// over
			// RDF_MAP
			// Trusted_token true // says this instance will work with trusted token
			// Trusted_host <list of ips to accept request from>
			// Token_Prefix this is an optional piece - I am not going to implement right
			// now

			if (user == null || (!AbstractSecurityUtils.anonymousUsersEnabled() && user.getLogins().isEmpty())) {
				// do a condition here if the session id request parameter is available
				// eventually this will be that and the tableau
				HttpServletRequest req = (HttpServletRequest) arg0;

				if (req.getParameter(DBLoader.getSessionIdKey()) != null) {
					String sessionId = WebUtility.cleanHttpResponse(req.getParameter(DBLoader.getSessionIdKey()));
					// create the cookie add it and sent it back
					Cookie k = new Cookie(DBLoader.getSessionIdKey(), sessionId);
					k.setHttpOnly(true);
					k.setSecure(req.isSecure());
					k.setPath(contextPath);
					((HttpServletResponse) arg1).addCookie(k);
					// in case there are other JSESSIONID
					// cookies, reset the value to the correct sessionId
					Cookie[] cookies = req.getCookies();
					if (cookies != null) {
						for (Cookie c : cookies) {
							if (c.getName().equals(DBLoader.getSessionIdKey())) {
								c.setValue(sessionId);
								((HttpServletResponse) arg1).addCookie(c);
							}
						}
					}

					Set<String> routes = Collections.list(req.getParameterNames()).stream()
							.filter(s -> s.startsWith("route")).collect(Collectors.toSet());
					if (routes != null && !routes.isEmpty()) {
						for (String r : routes) {
							Cookie c = new Cookie(r, WebUtility.cleanHttpResponse(req.getParameter(r)));
							c.setHttpOnly(true);
							c.setSecure(req.isSecure());
							c.setPath(contextPath);
							((HttpServletResponse) arg1).addCookie(c);
						}
					}

					// add the hash cookie
					String hash = req.getParameter("hash");
					if(hash != null) {
						Cookie h = new Cookie("HASH", WebUtility.cleanHttpResponse(hash));
						h.setHttpOnly(true);
						h.setSecure(req.isSecure());
						h.setPath(contextPath);
						((HttpServletResponse) arg1).addCookie(h);
					}

					// and now redirect back to the URL
					// if get, we can do it
					String method = req.getMethod();
					if (method.equalsIgnoreCase("GET")) {
						// modify the prefix if necessary
						Map<String, String> envMap = System.getenv();
						if (envMap.containsKey(Constants.MONOLITH_PREFIX)) {
							fullUrl = fullUrl.replace(contextPath, envMap.get(Constants.MONOLITH_PREFIX));
						}

						((HttpServletResponse) arg1).setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY);
						((HttpServletResponse) arg1).sendRedirect(fullUrl + "?" + req.getQueryString());
					} else if (method.equalsIgnoreCase("POST")) {
						// modify the prefix if necessary
						Map<String, String> envMap = System.getenv();
						if (envMap.containsKey(Constants.MONOLITH_PREFIX)) {
							fullUrl = fullUrl.replace(contextPath, envMap.get(Constants.MONOLITH_PREFIX));
						}

						((HttpServletResponse) arg1).setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
						((HttpServletResponse) arg1).setHeader("Location", fullUrl);
					} else {
						// don't know what i am redirecting
						// send back an error and have the client remake the request
						setInvalidEntryRedirect(context, arg0, arg1, LOGIN);
					}

					return;
				}
				// no jsession id as a param
				// just a normal redirect
				else {
					setInvalidEntryRedirect(context, arg0, arg1, LOGIN);
					return;
				}
			}

			// is the user logging in, but was previously at a different page?
			// this is because even if i set the redirect URL in the
			// {@link UserResource#setMainPageRedirect(@Context HttpServletRequest request,
			// @Context HttpServletResponse response)}
			// is sent to the pop-up for OAuth login
			if(session != null) {
				String endpointRedirectUrl = WebUtility.cleanHttpResponse((String) session.getAttribute(Constants.ENDPOINT_REDIRECT_KEY));
				if (endpointRedirectUrl != null && !endpointRedirectUrl.isEmpty()) {
					((HttpServletResponse) arg1).setHeader("redirect", endpointRedirectUrl);
					((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + endpointRedirectUrl);
					session.removeAttribute(Constants.ENDPOINT_REDIRECT_KEY);
					return;
				}
			}

			// so we have a user
			// let us look at the cookies
			// are we redirecting based on the above
			// or it is the main session
			String hashId = null;
			Cookie[] cookies = ((HttpServletRequest) arg0).getCookies();
			if (cookies != null) {
				for (Cookie c : cookies) {
					if (c.getName().equals("HASH")) {
						hashId = c.getValue();
						break;
					}
				}
			}

			// well, we are the shared session
			if (hashId != null) {
				// is this the first time we are hooking up the shared session?
				if (!user.isShareSession(session.getId())) {
					// tricky tricky
					// if you have a hash id but not shared
					// you are trying to get in when you shouldn't
					setInvalidEntryRedirect(context, arg0, arg1, LOGIN);
					return;
				}

				// use a wrapper otherwise
				// POST data consumed will be destroyed
				// when we get to the actual request method
				MultiReadHttpServletRequest wrapper = new MultiReadHttpServletRequest(((HttpServletRequest) arg0));
				String insightId = wrapper.getParameter("i");
				String secret = wrapper.getParameter("s");

				// not enough input
				if (insightId == null || secret == null) {
					setInvalidEntryRedirect(context, arg0, arg1, LOGIN);
					return;
				}

				// we have the required input, but is it valid
				InsightToken token = user.getInsight(insightId);
				try {
					MessageDigest md = MessageDigest.getInstance("SHA-256");
					String finalData = token.getSalt() + secret;
					byte[] digest = md.digest(finalData.getBytes());
					StringBuffer sb = new StringBuffer();
					for (int i = 0; i < digest.length; i++) {
						sb.append(Integer.toString((digest[i] & 0xff) + 0x100, 16).substring(1));
					}
					if (hashId == null || !hashId.equals(sb + "")) {
						// bad input
						setInvalidEntryRedirect(context, arg0, arg1, LOGIN);
						return;
					}

					// this session has been ratified so remove the session and move the user
					// forward
					//user.removeShare(session.getId());
					// remove the hash cookie since the user has validated this session
					for (Cookie c : cookies) {
						if (c.getName().equals("HASH")) {
							c.setMaxAge(0);
							c.setPath(contextPath);
							((HttpServletResponse) arg1).addCookie(c);
							break;
						}
					}

					arg2.doFilter(wrapper, arg1);
					return;
				} catch (NoSuchAlgorithmException e) {
					logger.error(Constants.STACKTRACE, e);
				}
			}
		}

		arg2.doFilter(arg0, arg1);
	}

	/**
	 * Method to determine where to redirect the user
	 * 
	 * @param context
	 * @param arg0
	 * @param arg1
	 * @throws IOException
	 */
	private void setInvalidEntryRedirect(ServletContext context, ServletRequest arg0, ServletResponse arg1, String endpoint) throws IOException {
		String fullUrl = WebUtility.cleanHttpResponse(((HttpServletRequest) arg0).getRequestURL().toString());
		((HttpServletResponse) arg1).setStatus(302);
		String redirectUrl = ((HttpServletRequest) arg0).getHeader("referer");
		// if no referrer
		// then a person hit the endpoint directly
		if (redirectUrl == null) {
			((HttpServletRequest) arg0).getSession(true).setAttribute(Constants.ENDPOINT_REDIRECT_KEY, fullUrl);
			// this will be the deployment name of the app
			String loginRedirect = SocialPropertiesUtil.getInstance().getLoginRedirect();
			((HttpServletResponse) arg1).setStatus(302);
			if (loginRedirect != null) {
				((HttpServletResponse) arg1).sendRedirect(loginRedirect);
			} else {
				String contextPath = context.getContextPath();
				redirectUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length()) + NO_USER_HTML;
				((HttpServletResponse) arg1).sendRedirect(redirectUrl);
			}
		} else {
			// are we in public home - if no, we dont include ! in the redirect
			if(redirectUrl.contains("/public_home/")) {
				redirectUrl = redirectUrl + "#/" + endpoint;
			} else {
				redirectUrl = redirectUrl + "#!/" + endpoint;
			}
			String encodedRedirectUrl = Encode.forHtml(redirectUrl);
			((HttpServletResponse) arg1).setHeader("redirect", encodedRedirectUrl);
			((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + encodedRedirectUrl);
			
			// invalidate the session if necessary
			HttpSession session = ((HttpServletRequest) arg0).getSession(false);
			if (session != null && (session.isNew() || ((HttpServletRequest) arg0).isRequestedSessionIdValid())) {
				session.invalidate();
			}
		}
	}

	@Override
	public void destroy() {
		// destroy
	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		// initialize
	}

	public boolean canLoadUser() {
		boolean canLoad = true;

		String checkMemSettings = Utility.getDIHelperProperty(Settings.CHECK_MEM);
		boolean checkMem = checkMemSettings != null && checkMemSettings.equalsIgnoreCase("true"); 
		if(checkMem)
		{
			long freeMem = MgmtUtil.getFreeMemory();
			String memProfileSettings = Utility.getDIHelperProperty(Settings.MEM_PROFILE_SETTINGS);
			
			if(memProfileSettings.equalsIgnoreCase(Settings.CONSTANT_MEM))
			{
				String memLimitSettings = Utility.getDIHelperProperty(Settings.USER_MEM_LIMIT);
				int limit = Integer.parseInt(memLimitSettings);
				canLoad = limit < freeMem;
			}
		}
		
		return canLoad;
	}

}
