package prerna.web.conf;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.owasp.encoder.Encode;

import prerna.auth.AccessToken;
import prerna.auth.User;
import prerna.auth.utils.AdminSecurityGroupUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;

public class UserExistsFilter extends NoUserInSessionFilter {
	
	private static final Logger classLogger = LogManager.getLogger(UserExistsFilter.class);

	private static FilterConfig filterConfig;
	
	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		HttpSession session = ((HttpServletRequest) arg0).getSession(false);
		User user = null;
		if(session != null) {
			user = (User) session.getAttribute(Constants.SESSION_USER);
		}

		// this will be the full path of the request
		// like http://localhost:8080/Monolith_Dev/api/engine/runPixel
		String fullUrl = ((HttpServletRequest) arg0).getRequestURL().toString();

		// REALLY DISLIKE THIS CHECK!!!
		if (!ResourceUtility.allowAccessWithoutLogin(fullUrl)) {
			// how you got here without a user, i am unsure given the other filters
			// but just in case
			// i will redirect you to login
			if (user == null || user.getLogins().isEmpty()) {
				((HttpServletResponse) arg1).setStatus(302);

				String redirectUrl = ((HttpServletRequest) arg0).getHeader("referer");
				redirectUrl = redirectUrl + "#!/login";
				String encodedRedirectUrl = Encode.forHtml(redirectUrl);
				((HttpServletResponse) arg1).setHeader("redirect", encodedRedirectUrl);
				((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + encodedRedirectUrl);
				return;
			} else {
				// okay, need to make sure the user is a valid one
				AccessToken token = user.getAccessToken(user.getLogins().get(0));
				
				if(!areGroupsValid(token)) {
					session.removeAttribute(Constants.SESSION_USER);
					((HttpServletResponse) arg1).sendError(HttpServletResponse.SC_FORBIDDEN, "User lacks permissions for this resource" );
					// log the user login
					classLogger.info(ResourceUtility.getLogMessage((HttpServletRequest)arg0, session, User.getSingleLogginName(user), "is trying to login BUT doesn't have access with provider " +  token.getProvider()));

					return;
				}
				
				if(!onlyPerformGroupCheck() && !userExists(token)) {
					session.removeAttribute(Constants.SESSION_USER);
					((HttpServletResponse) arg1).setStatus(302);
					String redirectUrl = ((HttpServletRequest) arg0).getHeader("referer");
					redirectUrl = redirectUrl + "#!/login";
					String encodedRedirectUrl = Encode.forHtml(redirectUrl);
					((HttpServletResponse) arg1).setHeader("redirect", encodedRedirectUrl);
					((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + encodedRedirectUrl);
					
					// log the user login
					classLogger.info(ResourceUtility.getLogMessage((HttpServletRequest)arg0, session, User.getSingleLogginName(user), "is trying to login BUT doesn't have access with provider " +  token.getProvider()));

					return;
				}
			}
		} else if(user != null && user.getLogins().size() == 1) {
			// you might be SSO
			// so need to check your login even if you haven't gone through the normal flow
			AccessToken token = user.getAccessToken(user.getLogins().get(0));
			if(!areGroupsValid(token)) {
				session.removeAttribute(Constants.SESSION_USER);
				((HttpServletResponse) arg1).sendError(HttpServletResponse.SC_FORBIDDEN, "User lacks permissions for this resource" );
				return;
			} 
			if(!onlyPerformGroupCheck() && !userExists(token)) {
				session.removeAttribute(Constants.SESSION_USER);
				((HttpServletResponse) arg1).sendError(HttpServletResponse.SC_FORBIDDEN, "User lacks permissions for this resource" );
				return;
			}
		}

		arg2.doFilter(arg0, arg1);
	}
	
	private boolean areGroupsValid(AccessToken token) {
		boolean groupsAreValid = true;
		boolean checkGroups = Boolean.parseBoolean(filterConfig.getInitParameter("useGroupWhitelist"))
				|| Boolean.parseBoolean(filterConfig.getInitParameter("useSAMLGroupWhitelist")); // just in case this key is used - should update and only use useGroupWhitelist
		if (checkGroups) {
			groupsAreValid = false;
			Collection<String> groups = token.getUserGroups();
			String groupType = token.getUserGroupType();
			if(groups != null && !groups.isEmpty() && groupType != null) {
				Set<String> validGroups = null;
				try {
					validGroups = AdminSecurityGroupUtils.getMatchingGroupsByType(groups, groupType);
				} catch (Exception e) {
					classLogger.error(Constants.STACKTRACE, e);
					throw new IllegalArgumentException("Error occurred to retrieve the valid groups for SAML login");
				}
				groupsAreValid = !validGroups.isEmpty();
			}
		}
		return groupsAreValid;
	}
	
	private boolean userExists(AccessToken token) {
		return SecurityQueryUtils.checkUserExist(token.getId());
	}
	
	private boolean onlyPerformGroupCheck() {
		return Boolean.parseBoolean(filterConfig.getInitParameter("onlyGroupCheck"));
	}
	
	@Override
	public void destroy() {
		// destroy

	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		UserExistsFilter.filterConfig = arg0;
	}
}
