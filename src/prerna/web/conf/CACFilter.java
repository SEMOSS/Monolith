package prerna.web.conf;

import java.io.IOException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import prerna.ds.util.RdbmsQueryBuilder;
import prerna.engine.api.IRawSelectWrapper;
import prerna.engine.impl.rdbms.RDBMSNativeEngine;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.conf.util.CACTrackingUtil;

public class CACFilter implements Filter {

	private static final Logger LOGGER = LogManager.getLogger(CACFilter.class.getName()); 

	// filter init params
	private static final String AUTO_ADD = "autoAdd";
	private static final String COUNT_USER_ENTRY = "countUserEntry";
	private static final String COUNT_USER_ENTRY_DATABASE = "countUserEntryDb";

	// realization of init params
	private static Boolean autoAdd = null;
	private CACTrackingUtil tracker = null;
	
	private FilterConfig filterConfig;
	
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
				// loop through all the certs
				CERT_LOOP : for(int i = 0; i < certs.length; i++) {
					X509Certificate cert = certs[i];

					String fullName = cert.getSubjectX500Principal().getName();
					System.out.println("REQUEST COMING FROM " + fullName);
					
					LdapName ldapDN;
					try {
						ldapDN = new LdapName(fullName);
						for(Rdn rdn: ldapDN.getRdns()) {
							// only care about CN
							if(!rdn.getType().equals("CN")) {
								// try next rdn
								continue;
							}
							
							// get the full value
							String value = rdn.getValue().toString();

							// account for topaz
							if(value.equals("topazbpm001.mhse2e.med.osd.mil")) {
								// THIS IS FOR TOPAZ HITTING MHS
								// GIVE IT ACCESS
								token.setId(value);
								token.setName("TOPAZ");
								// now set the other properties
								token.setToken_type(cert.getIssuerDN().getName());
								token.setExpires_in((int) cert.getNotAfter().getTime());
								LOGGER.info("Request coming from TOPAZ");
								break CERT_LOOP;
							}

							// make sure we have a valid user
							// that means the value ends with a dod number
							// and its the users name where each portion is period separated
							// must have at least first name and last name and dod number
							// need to account for middle name present
							// and any other distinction like Jr. after the last name
							String[] split = value.split("\\.");
							if(split.length < 3) {
								// didn't pass
								// try next rdn
								continue;
							}
							
							// just going to validate the cac has length 10
							String cacId = split[split.length-1];
							if(cacId.length() < 10) {
								// didn't pass
								// try next rdn
								continue;
							}
							
							// if we got to here, we have a valid cac!
							String name = Stream.of(split).limit(split.length-1).collect(Collectors.joining(" "));
							// we also need to get the email since that is what we will store
							String email = null;

							try {
								EMAIL_LOOP : for(List<?> altNames : cert.getSubjectAlternativeNames()) {
									for(Object alternative : altNames) {
										if(alternative instanceof String) {
											String altStr = alternative.toString();
											// really simple email check...
											if(altStr.contains("@")) {
												email = altStr;
												break EMAIL_LOOP;
											}
										}
									}
								}
							} catch (CertificateParsingException e) {
								e.printStackTrace();
							}

							// lets make sure we have all the stuff
							if(email != null & name != null) {
								// we have everything!
								// this is the only time we populate the token
								// and then exit the cert loop
								
								token.setId(email);
								token.setEmail(email);
								token.setName(name);
								
								// if we get here, we have a valid cac
								updateCacUsersStorage(cacId, email);
								break CERT_LOOP;
							}
						} // end rdn loop
					} catch (InvalidNameException e) {
						LOGGER.error("ERROR WITH PARSING CAC INFORMATION!");
						e.printStackTrace();
					}
				}

				// if we have the token
				// and it has values filled in
				// we know we can populate the user
				if(token.getName() != null) {
					LOGGER.info("Valid request coming from user " + token.getName());
					user.setAccessToken(token);
					session.setAttribute(Constants.SESSION_USER, user);

					// add the user if they do not exist
					if(CACFilter.autoAdd) {
						SecurityUpdateUtils.addOAuthUser(token);
					}
					
					// new user has entered!
					// do we need to count?
					if(tracker != null) {
						tracker.addToQueue(LocalDate.now());
					}
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
		this.filterConfig = arg0;
	}
	
	private void setInitParams(ServletRequest arg0) {
		if(CACFilter.autoAdd == null) {
			String autoAddStr = this.filterConfig.getInitParameter(AUTO_ADD);
			if(autoAddStr != null) {
				CACFilter.autoAdd = Boolean.parseBoolean(autoAddStr);
			} else {
				// Default value is true
				CACFilter.autoAdd = true;
			}
			
			boolean countUsers = false;
			String countUsersStr = this.filterConfig.getInitParameter(COUNT_USER_ENTRY);
			if(countUsersStr != null) {
				countUsers = Boolean.parseBoolean(countUsersStr);
			} else {
				countUsers = false;
			}
			
			if(countUsers) {
				String countDatabaseId = this.filterConfig.getInitParameter(COUNT_USER_ENTRY_DATABASE);
				if(countDatabaseId == null) {
					LOGGER.info("SYSTEM HAS REGISTERED TO PERFORM A COUNT BUT NO DATABASE ID HAS BEEN ENTERED!!!");
					LOGGER.info("SYSTEM HAS REGISTERED TO PERFORM A COUNT BUT NO DATABASE ID HAS BEEN ENTERED!!!");
					LOGGER.info("SYSTEM HAS REGISTERED TO PERFORM A COUNT BUT NO DATABASE ID HAS BEEN ENTERED!!!");
					LOGGER.info("SYSTEM HAS REGISTERED TO PERFORM A COUNT BUT NO DATABASE ID HAS BEEN ENTERED!!!");
				}
				try {
					tracker = CACTrackingUtil.getInstance(countDatabaseId);
				} catch(Exception e) {
					LOGGER.info(e.getMessage());
					LOGGER.info(e.getMessage());
					LOGGER.info(e.getMessage());
					LOGGER.info(e.getMessage());
				}
			}
		}
	}

	@Deprecated
	/**
	 * We only have this because we need to update the way we store these users
	 */
	private void updateCacUsersStorage(String previousId, String email) {
		String cleanEmail = RdbmsQueryBuilder.escapeForSQLStatement(email);
		RDBMSNativeEngine securityDb = (RDBMSNativeEngine) Utility.getEngine(Constants.SECURITY_DB);
		
		// let us not try to run this multiple times...
		String requireUpdateQuery = "SELECT * FROM USER WHERE ID='" + previousId +"'";
		IRawSelectWrapper wrapper = WrapperManager.getInstance().getRawWrapper(securityDb, requireUpdateQuery);
		try {
			// if we have next
			// that means we need to update
			// from id to email
			if(wrapper.hasNext()) {
				// need to update all the places the user id is used
				String updateQuery = "UPDATE USER SET ID='" +  cleanEmail +"', EMAIL='" + cleanEmail + "' WHERE ID='" + previousId + "'";
				try {
					securityDb.insertData(updateQuery);
				} catch (SQLException e) {
					e.printStackTrace();
				}
				
				// need to update all the places the user id is used
				updateQuery = "UPDATE ENGINEPERMISSION SET USERID='" +  cleanEmail +"' WHERE USERID='" + previousId + "'";
				try {
					securityDb.insertData(updateQuery);
				} catch (SQLException e) {
					e.printStackTrace();
				}
				
				// need to update all the places the user id is used
				updateQuery = "UPDATE USERINSIGHTPERMISSION SET USERID='" +  cleanEmail +"' WHERE USERID='" + previousId + "'";
				try {
					securityDb.insertData(updateQuery);
				} catch (SQLException e) {
					e.printStackTrace();
				}
			} 
		} finally {
			wrapper.cleanUp();
		}
	}
}
