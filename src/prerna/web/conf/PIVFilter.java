package prerna.web.conf;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
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
import prerna.util.Utility;
import prerna.web.conf.util.CACTrackingUtil;
import prerna.web.conf.util.UserFileLogUtil;

public class PIVFilter implements Filter {

	private static final Logger logger = LogManager.getLogger(PIVFilter.class); 
	private static final String STACKTRACE = "StackTrace: ";

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
	
	// TODO >>>timb: WORKSPACE - call logic to pull workspace here, or make into a reactor
	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		setInitParams(arg0);
		
		X509Certificate[] certs = (X509Certificate[]) arg0.getAttribute("javax.servlet.request.X509Certificate");
		HttpSession session = ((HttpServletRequest)arg0).getSession(true);

		User user = null;
		AccessToken token = null;

		if(certs != null) {
			user = (User) session.getAttribute(Constants.SESSION_USER);
			if(user == null) {
				user = new User();
				token = new AccessToken();
				token.setProvider(AuthProvider.CAC);
				
				// values we are trying to grab
				String name = null;
				String email = null;
				
				// loop through all the certs
				CERT_LOOP : for(int i = 0; i < certs.length; i++) {
					X509Certificate cert = certs[i];

					String fullName = cert.getSubjectX500Principal().getName();
					logger.info("REQUEST COMING FROM " + Utility.cleanLogString(fullName));
					
					LdapName ldapDN;
					try {
						ldapDN = new LdapName(fullName);
						for(Rdn rdn: ldapDN.getRdns()) {
							// only care about UID
							if(!rdn.getType().equals("UID")) {
								// try next rdn
								continue;
							}
							
							// get the full value
							// this should be an email
							String value = rdn.getValue().toString();
							email = value;
							name = value;

							// lets make sure we have all the stuff
							if(email != null & name != null) {
								// we have everything!
								// this is the only time we populate the token
								// and then exit the cert loop
								
								// lower case the email
								email = email.toLowerCase();
								
								token.setId(email);
								token.setEmail(email);
								token.setName(name);
								
								// if we get here, we have a valid piv
								break CERT_LOOP;
							}
						} // end rdn loop
					} catch (InvalidNameException e) {
						logger.error("ERROR WITH PARSING CAC INFORMATION!");
						logger.error(STACKTRACE,e);
					}
				}

				// if we have the token
				// and it has values filled in
				// we know we can populate the user
				if(token.getName() != null) {
					logger.info("Valid request coming from user " + token.getName());
					user.setAccessToken(token);
					session.setAttribute(Constants.SESSION_USER, user);

					// add the user if they do not exist
					if(PIVFilter.autoAdd) {
						SecurityUpdateUtils.addOAuthUser(token);
					}
					
					// new user has entered!
					// do we need to count?
					if(tracker != null) {
						tracker.addToQueue(LocalDate.now());
					}
					
					// are we logging their information?
					if(userLogger != null) {
						// grab the ip address
						userLogger.addToQueue(new String[] {email, name, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), ResourceUtility.getClientIp((HttpServletRequest)arg0)});
					}
					
					// log the user login
					logger.info(ResourceUtility.getLogMessage((HttpServletRequest)arg0, session, User.getSingleLogginName(user), "is logging in with provider " +  token.getProvider()));
				}
			}
		}

		arg2.doFilter(arg0, arg1);
	}

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		PIVFilter.filterConfig = arg0;
	}
	
	private void setInitParams(ServletRequest arg0) {
		if(PIVFilter.autoAdd == null) {
			String autoAddStr = PIVFilter.filterConfig.getInitParameter(AUTO_ADD);
			if(autoAddStr != null) {
				PIVFilter.autoAdd = Boolean.parseBoolean(autoAddStr);
			} else {
				// Default value is true
				PIVFilter.autoAdd = true;
			}
			
			boolean logUsers = false;
			String logUserInfoStr = PIVFilter.filterConfig.getInitParameter(LOG_USER_INFO);
			if(logUserInfoStr != null) {
				logUsers = Boolean.parseBoolean(logUserInfoStr);
			}
			if(logUsers) {
				String logInfoPath = PIVFilter.filterConfig.getInitParameter(LOG_USER_INFO_PATH);
				String logInfoSep = PIVFilter.filterConfig.getInitParameter(LOG_USER_INFO_SEP);
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
			String countUsersStr = PIVFilter.filterConfig.getInitParameter(COUNT_USER_ENTRY);
			if(countUsersStr != null) {
				countUsers = Boolean.parseBoolean(countUsersStr);
			} else {
				countUsers = false;
			}
			
			if(countUsers) {
				String countDatabaseId = PIVFilter.filterConfig.getInitParameter(COUNT_USER_ENTRY_DATABASE);
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
