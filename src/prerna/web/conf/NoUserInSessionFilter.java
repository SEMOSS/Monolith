package prerna.web.conf;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Vector;

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
import prerna.util.Constants;

public class NoUserInSessionFilter implements Filter {

	private static final String LOGIN = "login";
	private static final String SHARE = "share";	
	
	private static final String NO_USER_HTML = "/noUserFail.html";
	protected static List<String> ignoreDueToFE = new Vector<String>();

	static {
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

		boolean security = Boolean.parseBoolean(context.getInitParameter(Constants.SECURITY_ENABLED));
		if(security) {
			// this will be the full path of the request
			// like http://localhost:8080/Monolith_Dev/api/engine/runPixel
			String fullUrl = ((HttpServletRequest) arg0).getRequestURL().toString();

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
				if(user == null || user.getLogins().isEmpty()) {
					// do a condition here if the session id request parameter is available
					// eventually this will be that and the tableau
					HttpServletRequest req = (HttpServletRequest) arg0; 
					if(req.getParameter("JSESSIONID") != null) {
						String cookiePath = req.getContextPath();
						
						String sessionId = req.getParameter("JSESSIONID");
						// create the cookie add it and sent it back
						Cookie k = new Cookie("JSESSIONID", sessionId);
						k.setPath(cookiePath);
						((HttpServletResponse)arg1).addCookie(k);
						
						// in case there are other JSESSIONID
						// cookies, reset the value to the correct sessionId
						Cookie[] cookies = req.getCookies();
						if (cookies != null) {
							for (Cookie c : cookies) {
								if (c.getName().equals("JSESSIONID")) {
									c.setValue(sessionId);
									((HttpServletResponse)arg1).addCookie(c);
								}
							}
						}
						
						// add the hash cookie
						String hash = req.getParameter("hash");
						Cookie h = new Cookie("HASH", hash);
						h.setHttpOnly(true);
						h.setPath(cookiePath);
						((HttpServletResponse)arg1).addCookie(h);
						
						// lastly, store a cookie that says we are the ones who are doing a redirect
						// we will check for this cookie later on
						Cookie r = new Cookie("R", true + "");
						r.setHttpOnly(true);
						r.setPath(cookiePath);
						((HttpServletResponse)arg1).addCookie(r);

						// and now redirect back to the URL
						((HttpServletResponse)arg1).sendRedirect(fullUrl+"?"+req.getQueryString());
						return;
					}
					// no jsession id as a param
					// just a normal redirect
					else 
					{
						setInvalidEntryRedirect(context, arg0, arg1, LOGIN);
					}
					
					// invalidate the session if necessary
					if(session != null) {
						session.invalidate();
					}
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
					if(user.isShareSession(session.getId())) {
						String insightId = ((HttpServletRequest)arg0).getParameter("i");
						String secret = ((HttpServletRequest)arg0).getParameter("s");

						// not enough input
						if(insightId == null || secret == null) {
							setInvalidEntryRedirect(context, arg0, arg1, SHARE);
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
								setInvalidEntryRedirect(context, arg0, arg1, SHARE);
								return;
							} 
							
							// this session has been ratified so remove the session and move the user forward
							user.removeShare(session.getId());
							// remove the R cookie since the user has validated this session
							if (cookies != null) {
								for (Cookie c : cookies) {
									if (c.getName().equals("R")) {
										c.setMaxAge(0);
										break;
									}
								}
							}
						} catch (NoSuchAlgorithmException e) {
							e.printStackTrace();
						}
					} else {
						// this is a shared session
						// but did someone first share
						// and someone else is trying to steal it
						if (cookies != null) {
							for (Cookie c : cookies) {
								if (c.getName().equals("R")) {
									// sneaky sneaky...
									setInvalidEntryRedirect(context, arg0, arg1, LOGIN);
									return;
								}
							}
						}
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
			// this will be the deployment name of the app
			String contextPath = context.getContextPath();
			redirectUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length()) + NO_USER_HTML;
			((HttpServletResponse) arg1).setStatus(302);
			((HttpServletResponse) arg1).sendRedirect(redirectUrl);
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
