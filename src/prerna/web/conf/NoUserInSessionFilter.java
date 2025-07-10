package prerna.web.conf;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.owasp.encoder.Encode;

import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;
import prerna.web.services.util.WebUtility;

public class NoUserInSessionFilter implements Filter {
	
	private static final String NO_USER_HTML = "/noUserFail/";

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
			throws IOException, ServletException {
		ServletContext context = arg0.getServletContext();

		// this will be the full path of the request
		// like http://localhost:8080/Monolith_Dev/api/engine/runPixel
		String fullUrl = WebUtility.cleanHttpResponse(((HttpServletRequest) arg0).getRequestURL().toString());
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

			if (user == null || (!AbstractSecurityUtils.anonymousUsersEnabled() && user.getLogins().isEmpty())) {
				// don't know what i am redirecting
				// send back an error and have the client remake the request
				setInvalidEntryRedirect(context, arg0, arg1);
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
	private void setInvalidEntryRedirect(ServletContext context, ServletRequest arg0, ServletResponse arg1) throws IOException {
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
			redirectUrl = redirectUrl + WebUtility.determineLoginExtension((HttpServletRequest) arg0);
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

}
