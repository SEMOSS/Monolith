package prerna.web.conf;

import java.io.IOException;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;

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

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.util.Constants;

public class NoUserInSessionTrustedTokenFilter implements Filter {

	private static String TRUSTED_TOKEN_PREFIX = "trustedTokenPrefix";
	private static String TRUSTED_TOKEN_DOMAIN = "trustedTokenDomain";

	private static String tokenName = null;
	private static List<String> trustedDomains = null;

	// maps from the IP the user is coming in with the cookie
	private static Map<String, String> sessionMapper = new Hashtable<String, String>();

	private FilterConfig filterConfig;

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		setInitParams(arg0);

		if(AbstractSecurityUtils.securityEnabled()) {
			// this will be the full path of the request
			// like http://localhost:8080/Monolith_Dev/api/engine/runPixel
			String fullUrl = ((HttpServletRequest) arg0).getRequestURL().toString();
			String contextPath = ((HttpServletRequest) arg0).getContextPath();
			HttpSession session = ((HttpServletRequest) arg0).getSession(false);

			User user = null;
			if(session != null) {
				user = (User) session.getAttribute(Constants.SESSION_USER);
			}

			// if we have a user, there is nothing to do
			if(user == null) {
				
				// the front end comes with
				// fullUrl?prefix_token=userId
				// check if the ip address is allowed
				// check if the userId actually exists
				// if first time, add the user
				// if not, redirect the GET/POST call
				
				HttpServletRequest req = (HttpServletRequest) arg0;
				// grab the token id
				// if the token exists
				String userId = req.getParameter(tokenName);
				if(userId != null) {
					if(!sessionMapper.containsKey(userId)) {
						// grab the ip address
						String ipAddress = req.getHeader("X-FORWARDED-FOR");
						if (ipAddress == null) {  
							ipAddress = req.getRemoteAddr();  
						}
						// check if the ip address is allowed
						boolean allow = trustedDomains.contains("*");
						if(!allow) {
							for(String domain : trustedDomains) {
								if(ipAddress.matches(domain)) {
									allow = true;
									break;
								}
							}
						}
						if(allow && SecurityQueryUtils.checkUserExist(userId)) {
							// you are allowed
							// i just have to check if the token id exists
							// and id you do, i make the user object
							if(session == null) {
								session = req.getSession(true);
							}
							user = new User();
							AccessToken token = new AccessToken();
							token.setProvider(AuthProvider.WINDOWS_USER);
							token.setId(userId);
							user.setAccessToken(token);
							session.setAttribute(Constants.SESSION_USER, user);

							String sessionId = session.getId();
							sessionMapper.put(userId, sessionId);
						}
					} else {
						// this is the class where you redirect
						String redirectSessionId = (String) sessionMapper.get(userId);
						// add the session id cookie
						Cookie k = new Cookie(DBLoader.getSessionIdKey(), redirectSessionId);
						k.setPath(contextPath);
						((HttpServletResponse)arg1).addCookie(k);
						// replace any other session id cookies
						Cookie[] cookies = req.getCookies();
						if (cookies != null) {
							System.err.println("Forcing session value !");
							for (Cookie c : cookies) {
								if (c.getName().equals(DBLoader.getSessionIdKey())) {
									if(c.getName().equalsIgnoreCase(DBLoader.getSessionIdKey())) {
										c.setValue(redirectSessionId);
									}
								}
							}
						}
						
						String method = req.getMethod();
						if(method.equalsIgnoreCase("GET")) {
							((HttpServletResponse) arg1).setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY);
							((HttpServletResponse) arg1).sendRedirect(fullUrl + "?" + req.getQueryString());
							return;
						} else if(method.equalsIgnoreCase("POST")) {
							((HttpServletResponse) arg1).setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
							((HttpServletResponse) arg1).setHeader("Location", fullUrl);
							return;
						}
					}
				}
			}
//			else {
//				System.out.println("Have user = " + user.getAccessToken(user.getPrimaryLogin()).getId());
//			}
		}
		arg2.doFilter(arg0, arg1);
	}

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}
	
	/**
	 * Remove the session from the mapper
	 * @param sessionId
	 */
	public static void removeSession(String sessionId) {
		Iterator<String> iterator = NoUserInSessionTrustedTokenFilter.sessionMapper.keySet().iterator();
		while(iterator.hasNext()) {
			String key = iterator.next();
			if(NoUserInSessionTrustedTokenFilter.sessionMapper.get(key).equals(sessionId)) {
				// remove this
				iterator.remove();
			}
		}
	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		this.filterConfig = arg0;
	}

	private void setInitParams(ServletRequest arg0) {
		// the token name
		if(NoUserInSessionTrustedTokenFilter.tokenName == null) {
			NoUserInSessionTrustedTokenFilter.tokenName = this.filterConfig.getInitParameter(NoUserInSessionTrustedTokenFilter.TRUSTED_TOKEN_PREFIX);
		}

		// the token domains
		if(NoUserInSessionTrustedTokenFilter.trustedDomains == null) {
			String [] trustedIPs = this.filterConfig.getInitParameter(NoUserInSessionTrustedTokenFilter.TRUSTED_TOKEN_DOMAIN).split(";");
			NoUserInSessionTrustedTokenFilter.trustedDomains = new Vector<String>();
			for(String trustedIP : trustedIPs) {
				NoUserInSessionTrustedTokenFilter.trustedDomains.add(trustedIP.toLowerCase());
			}
		}
	}

}