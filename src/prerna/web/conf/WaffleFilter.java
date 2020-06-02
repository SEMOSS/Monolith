package prerna.web.conf;

import java.io.IOException;
import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.web.conf.util.CACTrackingUtil;
import prerna.web.conf.util.UserFileLogUtil;

public class WaffleFilter implements Filter {

	private static final Logger logger = LogManager.getLogger(WaffleFilter.class.getName()); 

	// filter init params
	private static final String AUTO_ADD = "autoAdd";
	private static final String COUNT_USER_ENTRY = "countUserEntry";
	private static final String COUNT_USER_ENTRY_DATABASE = "countUserEntryDb";
	private static final String LOG_USER_INFO = "logUserInfo";
	private static final String LOG_USER_INFO_PATH = "logUserInfoPath";
	private static final String LOG_USER_INFO_SEP = "logUserInfoSep";

	// realization of init params
	private static Boolean autoAdd = null;
	private static CACTrackingUtil tracker = null;
	private static UserFileLogUtil userLogger = null;

	private static FilterConfig filterConfig;

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		setInitParams(arg0);

		HttpSession session = ((HttpServletRequest)arg0).getSession(true);
		User user = (User) session.getAttribute(Constants.SESSION_USER);
		if(user == null) {
			user = new User();

			// grab the waffle elements
			Principal principal = ((HttpServletRequest) arg0).getUserPrincipal();
			String id = principal.getName();
			String name = id.substring(id.lastIndexOf('\\')+1);

			AccessToken token = new AccessToken();
			token.setProvider(AuthProvider.WINDOWS_USER);
			token.setId(id);
			token.setName(name);
			logger.info("Valid request coming from user " + token.getName());
			// add the user if they do not exist
			if(WaffleFilter.autoAdd) {
				SecurityUpdateUtils.addOAuthUser(token);
			}

			// do we need to count?
			if(tracker != null) {
				tracker.addToQueue(LocalDate.now());
			}

			// are we logging their information?
			if(userLogger != null) {
				userLogger.addToQueue(new String[] {id, name, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))});
			}

			user.setAccessToken(token);
			session.setAttribute(Constants.SESSION_USER, user);
			
			// log the user login
			logger.info("IP " + ResourceUtility.getClientIp((HttpServletRequest)arg0) + " : " + User.getSingleLogginName(user) + " is logging in with provider " +  token.getProvider() + " from session " + session.getId());
		}

		arg2.doFilter(arg0, arg1);
	}

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		WaffleFilter.filterConfig = arg0;
	}

	private void setInitParams(ServletRequest arg0) {
		if(WaffleFilter.autoAdd == null) {
			String autoAddStr = WaffleFilter.filterConfig.getInitParameter(AUTO_ADD);
			if(autoAddStr != null) {
				WaffleFilter.autoAdd = Boolean.parseBoolean(autoAddStr);
			} else {
				// Default value is true
				WaffleFilter.autoAdd = true;
			}

			boolean logUsers = false;
			String logUserInfoStr = WaffleFilter.filterConfig.getInitParameter(LOG_USER_INFO);
			if(logUserInfoStr != null) {
				logUsers = Boolean.parseBoolean(logUserInfoStr);
			}
			if(logUsers) {
				String logInfoPath = WaffleFilter.filterConfig.getInitParameter(LOG_USER_INFO_PATH);
				String logInfoSep = WaffleFilter.filterConfig.getInitParameter(LOG_USER_INFO_SEP);
				if(logInfoPath == null) {
					logger.info("SYSTEM HAS REGISTERED TO PERFORM A USER FILE LOG BUT NOT FILE PATH HAS BEEN ENTERED!!!");
					logger.info("SYSTEM HAS REGISTERED TO PERFORM A USER FILE LOG BUT NOT FILE PATH HAS BEEN ENTERED!!!");
					logger.info("SYSTEM HAS REGISTERED TO PERFORM A USER FILE LOG BUT NOT FILE PATH HAS BEEN ENTERED!!!");
					logger.info("SYSTEM HAS REGISTERED TO PERFORM A USER FILE LOG BUT NOT FILE PATH HAS BEEN ENTERED!!!");
				}
				try {
					userLogger = UserFileLogUtil.getInstance(logInfoPath, logInfoSep);
				} catch(Exception e) {
					logger.info(e.getMessage());
					logger.info(e.getMessage());
					logger.info(e.getMessage());
					logger.info(e.getMessage());
				}
			}

			boolean countUsers = false;
			String countUsersStr = WaffleFilter.filterConfig.getInitParameter(COUNT_USER_ENTRY);
			if(countUsersStr != null) {
				countUsers = Boolean.parseBoolean(countUsersStr);
			} else {
				countUsers = false;
			}

			if(countUsers) {
				String countDatabaseId = WaffleFilter.filterConfig.getInitParameter(COUNT_USER_ENTRY_DATABASE);
				if(countDatabaseId == null) {
					logger.info("SYSTEM HAS REGISTERED TO PERFORM A COUNT BUT NO DATABASE ID HAS BEEN ENTERED!!!");
					logger.info("SYSTEM HAS REGISTERED TO PERFORM A COUNT BUT NO DATABASE ID HAS BEEN ENTERED!!!");
					logger.info("SYSTEM HAS REGISTERED TO PERFORM A COUNT BUT NO DATABASE ID HAS BEEN ENTERED!!!");
					logger.info("SYSTEM HAS REGISTERED TO PERFORM A COUNT BUT NO DATABASE ID HAS BEEN ENTERED!!!");
				}
				try {
					tracker = CACTrackingUtil.getInstance(countDatabaseId);
				} catch(Exception e) {
					logger.info(e.getMessage());
					logger.info(e.getMessage());
					logger.info(e.getMessage());
					logger.info(e.getMessage());
				}
			}
		}
	}

}
