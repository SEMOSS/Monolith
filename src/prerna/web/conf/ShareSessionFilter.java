package prerna.web.conf;

import java.io.IOException;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.SecurityShareSessionUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;

public class ShareSessionFilter implements Filter {
	
	private static final Logger classLogger = LogManager.getLogger(ShareSessionFilter.class);
	private static final String SHARE_TOKEN_KEY = "sessionToken";
	
	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {

		// this will be the full path of the request
		// like http://localhost:8080/Monolith_Dev/api/engine/runPixel

		String fullUrl = Utility.cleanHttpResponse(((HttpServletRequest) arg0).getRequestURL().toString());
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
			// check if there is a session key 
			// for sharing in the url

			if (user == null || user.getLogins().isEmpty()) {
				// do a condition here if the share key is provided
				HttpServletRequest req = (HttpServletRequest) arg0;

				if (req.getParameter(SHARE_TOKEN_KEY) != null) {
					String shareToken = Utility.cleanHttpResponse(req.getParameter(SHARE_TOKEN_KEY));
					
					try {
						Object[] shareDetails = SecurityShareSessionUtils.getShareSessionDetails(shareToken);
						if(shareDetails == null) {
							classLogger.info(ResourceUtility.getLogMessage((HttpServletRequest)arg0, session, 
									User.getSingleLogginName(user), "is trying to login through a share token but the token '"+shareToken+"' doesn't exist"));
						}
						// this either returns true or throws an error
						SecurityShareSessionUtils.validateShareSessionDetails(shareDetails);
						classLogger.info(ResourceUtility.getLogMessage((HttpServletRequest)arg0, session, 
								User.getSingleLogginName(user), "successfully used a share token '" + shareToken + "' to attempt to redirect to the session and login"));

						String sessionId = (String) shareDetails[1];
						String routeId = (String) shareDetails[2];
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
						
						// add route if it exists
						String routeCookieName = DIHelper.getInstance().getProperty(Constants.MONOLITH_ROUTE);
						if (routeCookieName != null && !routeCookieName.isEmpty()
								&& routeId != null && !routeId.isEmpty()) {
							Cookie c = new Cookie(routeCookieName, routeId);
							c.setHttpOnly(true);
							c.setSecure(req.isSecure());
							c.setPath(contextPath);
							((HttpServletResponse) arg1).addCookie(c);
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
							return;
						} else if (method.equalsIgnoreCase("POST")) {
							// modify the prefix if necessary
							Map<String, String> envMap = System.getenv();
							if (envMap.containsKey(Constants.MONOLITH_PREFIX)) {
								fullUrl = fullUrl.replace(contextPath, envMap.get(Constants.MONOLITH_PREFIX));
							}
	
							((HttpServletResponse) arg1).setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
							((HttpServletResponse) arg1).setHeader("Location", fullUrl);
							return;
						} 
					} catch (Exception e) {
						classLogger.info(ResourceUtility.getLogMessage((HttpServletRequest)arg0, session, 
								User.getSingleLogginName(user), "is trying to login through a share token but the token '"+shareToken+"' resulted in the error: " + e.getMessage()));
						classLogger.error(Constants.STACKTRACE, e);
					}
					
					// don't know if you are not a POST or a GET
					// continue the chain
				}
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
		// initialize
	}

}
