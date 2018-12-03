package prerna.web.conf;

import java.io.IOException;
import java.util.Enumeration;
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
import prerna.auth.User;
import prerna.util.Constants;

public class NoUserInSessionFilter implements Filter {

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
				System.out.println("Session ID >> " + session.getId());
				User user = null;
				if(session != null) {
					user = (User) session.getAttribute(Constants.SESSION_USER);
				}
				if(user == null || user.getLogins().isEmpty()) {
					System.out.println("Url set to" + fullUrl);
					printHeaders((HttpServletRequest) arg0);

					// do a condition here if the session id request parameter is available
					// eventually this will be that and the tableau
					if(((HttpServletRequest)arg0).getParameter("JSESSIONID") != null) {
						HttpServletRequest req = (HttpServletRequest)arg0; 
						
						String sessionId = req.getParameter("JSESSIONID");
						// create the cookie add it and sent it back
						Cookie k = new Cookie("JSESSIONID", sessionId);
						// cool blink show if you enable the lower one
						//k.setPath(request.getContextPath());
						//k.setPath("/appui");
						//response.addCookie(k);

						k.setPath(req.getContextPath());
						((HttpServletResponse)arg1).addCookie(k);

						// I need some routine to remove this.. this is just a hack
						fullUrl = fullUrl.replace("JSESSIONID", "random");

						Cookie[] cookies = req.getCookies();
						if (cookies != null) {
							for (Cookie c : cookies) {
								if (c.getName().equals("JSESSIONID")) {
									c.setValue(sessionId);
									System.out.println("Session id " + sessionId);
									((HttpServletResponse)arg1).addCookie(c);
								}
							}
						}
						((HttpServletResponse)arg1).sendRedirect(fullUrl);
						
						// invalidate the current session to get rid of the session id
						session.invalidate();
						return;
					}
					else
					{
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
							redirectUrl = redirectUrl + "#!/login";
							((HttpServletResponse) arg1).setHeader("redirect", redirectUrl);
							((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + redirectUrl);
						}
						
						// invalidate the current session to get rid of the session id
						session.invalidate();
						return;
					}
				}
			}
		}

		arg2.doFilter(arg0, arg1);
	}

	private void printHeaders(HttpServletRequest req) {
		Enumeration <String> headerNames = req.getHeaderNames();
		while(headerNames.hasMoreElements()) {
			String thisHeader = headerNames.nextElement();
			String headerValue = req.getHeader(thisHeader);
			System.out.println(thisHeader + "<<>> " + headerValue );
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
