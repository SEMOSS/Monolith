package prerna.web.conf;

import java.io.IOException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.DLSequence;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.ds.util.RdbmsQueryBuilder;
import prerna.engine.api.IRawSelectWrapper;
import prerna.engine.impl.rdbms.RDBMSNativeEngine;
import prerna.forms.FormBuilder;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.semoss.web.services.local.UserResource;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.conf.util.CACTrackingUtil;
import prerna.web.conf.util.UserFileLogUtil;

public class CACFilter implements Filter {

	private static final Logger classLogger = LogManager.getLogger(CACFilter.class);

	// filter init params
	private static final String AUTO_ADD = "autoAdd";
	private static final String UPDATE_USER_INFO = "updateUserInfo";
	private static final String COUNT_USER_ENTRY = "countUserEntry";
	private static final String COUNT_USER_ENTRY_DATABASE = "countUserEntryDb";
	private static final String LOG_USER_INFO = "logUserInfo";
	private static final String LOG_USER_INFO_PATH = "logUserInfoPath";
	private static final String LOG_USER_INFO_SEP = "logUserInfoSep";

	// realization of init params
	private static Boolean autoAdd = null;
	private static Boolean updateUser = null;
	private static CACTrackingUtil tracker = null;
	private static UserFileLogUtil userLogger = null;

	private static FilterConfig filterConfig;

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
				String cacId = null;
				String name = null;
				String email = null;

				// loop through all the certs
				CERT_LOOP : for(int i = 0; i < certs.length; i++) {
					X509Certificate cert = certs[i];

					String fullName = cert.getSubjectX500Principal().getName();
					classLogger.info("REQUEST COMING FROM " +  Utility.cleanLogString(fullName));

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
								cacId = value;
								name = "TOPAZ";
								// THIS IS FOR TOPAZ HITTING MHS
								// GIVE IT ACCESS
								token.setId(cacId);
								token.setName(name);
								// now set the other properties
								token.setToken_type(cert.getIssuerDN().getName());
								token.setExpires_in((int) cert.getNotAfter().getTime());
								classLogger.info("Request coming from TOPAZ");
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
							cacId = split[split.length-1];
							if(cacId.length() < 10) {
								// didn't pass
								// try next rdn
								continue;
							}

							// if we got to here, we have a valid cac!
							name = Stream.of(split).limit(split.length-1).collect(Collectors.joining(" "));
							// we also need to get the email since that is what we will store
							email = null;

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
								classLogger.error(Constants.STACKTRACE, e);
							}

							if(email != null) {
								// lower case the email
								// and try to update it in all the different locations
								email = email.toLowerCase();
								updateCacUsersStorage(email, cacId);
							}
							token.setId(cacId);
							token.setEmail(email);
							token.setName(name);
							String upn = getUPN(cert);
							if(upn != null && !upn.isEmpty()) {
								token.setSAN("UPN", upn);
							}

							// if we get here, we have a valid cac
							break CERT_LOOP;
						} // end rdn loop
					} catch (InvalidNameException e) {
						classLogger.error("ERROR WITH PARSING CAC INFORMATION!");
						classLogger.error(Constants.STACKTRACE, e);
					}
				}

				// if we have the token
				// and it has values filled in
				// we know we can populate the user
				if(token.getName() != null) {
					classLogger.info("Valid request coming from user " + token.getName());
					user.setAccessToken(token);
					session.setAttribute(Constants.SESSION_USER, user);
					session.setAttribute(Constants.SESSION_USER_ID_LOG, token.getId());

					// add the user if they do not exist
					if(CACFilter.autoAdd) {
						SecurityUpdateUtils.addOAuthUser(token);
					}
					// do we need to update credentials?
					// might be useful for when we add users 
					// but the cert has different values we want to use
					if(CACFilter.updateUser) {
						SecurityUpdateUtils.updateOAuthUser(token);
					}

					// new user has entered!
					// do we need to count?
					if(tracker != null && !token.getName().equals("TOPAZ")) {
						tracker.addToQueue(LocalDate.now());
					}

					// are we logging their information?
					if(userLogger != null && !token.getName().equals("TOPAZ")) {
						// grab the ip address
						userLogger.addToQueue(new String[] {cacId, name, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), ResourceUtility.getClientIp((HttpServletRequest)arg0)});
					}

					// log the user login
					classLogger.info(ResourceUtility.getLogMessage((HttpServletRequest)arg0, session, User.getSingleLogginName(user), "is logging in with provider " +  token.getProvider()));

					// store if db tracking
					UserResource.userTrackingLogin((HttpServletRequest) arg0, user, token.getProvider());
				}
			}
		}

		arg2.doFilter(arg0, arg1);
	}

	public String getUPN(X509Certificate cert) {
		Collection<List<?>> sans;
		try {
			sans = JcaX509ExtensionUtils.getSubjectAlternativeNames(cert);
			//get all subject alt names
			if (sans != null) {
				classLogger.info("Subject Alternative Names: " + sans.toString());
				for (List<?> l : sans) {
					
					//expected size is 2
					if (l.size() == 2) {
						
						// expected type 0 for the san
						Integer type = (Integer) l.get(0);
						if (type.equals(new Integer(0))) {
							ASN1Primitive value = ((DLSequence) l.get(1)).toASN1Primitive();
							ASN1Sequence asn1seq = ASN1Sequence.getInstance(value);
							ASN1ObjectIdentifier oid = ASN1ObjectIdentifier.getInstance(asn1seq.getObjectAt(0));

							//IF THE OID is the UPN value - grab it and we are good, else continue
							if(oid.getId().equalsIgnoreCase("1.3.6.1.4.1.311.20.2.3")) {
								ASN1TaggedObject tag = ASN1TaggedObject.getInstance(asn1seq.getObjectAt(1).toASN1Primitive());
								if (tag.getTagNo() != 0) {
									//this should be 0 for UPN
									continue;
								}
								 return DERUTF8String.getInstance(tag, true).toString();

							} else {
								continue;
							}
						}
					}
				}
			}
		} catch (CertificateParsingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return null;
	
	}

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}


	@Override
	public void init(FilterConfig arg0) throws ServletException {
		CACFilter.filterConfig = arg0;
	}

	private void setInitParams(ServletRequest arg0) {
		if(CACFilter.autoAdd == null) {
			String autoAddStr = CACFilter.filterConfig.getInitParameter(AUTO_ADD);
			if(autoAddStr != null) {
				CACFilter.autoAdd = Boolean.parseBoolean(autoAddStr);
			} else {
				// Default value is true
				CACFilter.autoAdd = true;
			}

			String updateUserStr = CACFilter.filterConfig.getInitParameter(UPDATE_USER_INFO);
			if(updateUserStr != null) {
				CACFilter.updateUser = Boolean.parseBoolean(updateUserStr);
			} else {
				// Default value is false
				CACFilter.updateUser = false;
			}

			boolean logUsers = false;
			String logUserInfoStr = CACFilter.filterConfig.getInitParameter(LOG_USER_INFO);
			if(logUserInfoStr != null) {
				logUsers = Boolean.parseBoolean(logUserInfoStr);
			}
			if(logUsers) {
				String logInfoPath = CACFilter.filterConfig.getInitParameter(LOG_USER_INFO_PATH);
				String logInfoSep = CACFilter.filterConfig.getInitParameter(LOG_USER_INFO_SEP);
				if(logInfoPath == null) {
					classLogger.info("SYSTEM HAS REGISTERED TO PERFORM A USER FILE LOG BUT NOT FILE PATH HAS BEEN ENTERED!!!");
					classLogger.info("SYSTEM HAS REGISTERED TO PERFORM A USER FILE LOG BUT NOT FILE PATH HAS BEEN ENTERED!!!");
					classLogger.info("SYSTEM HAS REGISTERED TO PERFORM A USER FILE LOG BUT NOT FILE PATH HAS BEEN ENTERED!!!");
					classLogger.info("SYSTEM HAS REGISTERED TO PERFORM A USER FILE LOG BUT NOT FILE PATH HAS BEEN ENTERED!!!");
				}
				try {
					userLogger = UserFileLogUtil.getInstance(logInfoPath, logInfoSep);
				} catch(Exception e) {
					classLogger.info(e.getMessage());
					classLogger.info(e.getMessage());
					classLogger.info(e.getMessage());
					classLogger.info(e.getMessage());
				}
			}

			boolean countUsers = false;
			String countUsersStr = CACFilter.filterConfig.getInitParameter(COUNT_USER_ENTRY);
			if(countUsersStr != null) {
				countUsers = Boolean.parseBoolean(countUsersStr);
			} else {
				countUsers = false;
			}

			if(countUsers) {
				String countDatabaseId = CACFilter.filterConfig.getInitParameter(COUNT_USER_ENTRY_DATABASE);
				if(countDatabaseId == null) {
					classLogger.info("SYSTEM HAS REGISTERED TO PERFORM A COUNT BUT NO DATABASE ID HAS BEEN ENTERED!!!");
					classLogger.info("SYSTEM HAS REGISTERED TO PERFORM A COUNT BUT NO DATABASE ID HAS BEEN ENTERED!!!");
					classLogger.info("SYSTEM HAS REGISTERED TO PERFORM A COUNT BUT NO DATABASE ID HAS BEEN ENTERED!!!");
					classLogger.info("SYSTEM HAS REGISTERED TO PERFORM A COUNT BUT NO DATABASE ID HAS BEEN ENTERED!!!");
				}
				try {
					tracker = CACTrackingUtil.getInstance(countDatabaseId);
				} catch(Exception e) {
					classLogger.info(e.getMessage());
					classLogger.info(e.getMessage());
					classLogger.info(e.getMessage());
					classLogger.info(e.getMessage());
				}
			}
		}
	}

	/**
	 * We only have this because we need to update the way we store these users
	 */
	@Deprecated
	private void updateCacUsersStorage(String previousId, String newId) {
		String cleanOldId = RdbmsQueryBuilder.escapeForSQLStatement(previousId);
		String cleanNewId = RdbmsQueryBuilder.escapeForSQLStatement(newId);
		{
			// security update block
			RDBMSNativeEngine securityDb = (RDBMSNativeEngine) Utility.getDatabase(Constants.SECURITY_DB);

			// let us not try to run this multiple times...
			String requireUpdateQuery = "SELECT * FROM SMSS_USER WHERE ID='" + cleanOldId +"'";
			IRawSelectWrapper wrapper = null;
			try {
				wrapper = WrapperManager.getInstance().getRawWrapper(securityDb, requireUpdateQuery);
				// if we have next
				// that means we need to update
				// from id to email
				if(wrapper.hasNext()) {
					// need to update all the places the user id is used
					String updateQuery = "UPDATE SMSS_USER SET ID='" +  cleanNewId +"' WHERE ID='" + cleanOldId + "'";
					try {
						securityDb.insertData(updateQuery);
					} catch (SQLException e) {
						classLogger.error(Constants.STACKTRACE, e);
					}

					// need to update all the places the user id is used
					updateQuery = "UPDATE ENGINEPERMISSION SET USERID='" +  cleanNewId +"' WHERE USERID='" + cleanOldId + "'";
					try {
						securityDb.insertData(updateQuery);
					} catch (SQLException e) {
						classLogger.error(Constants.STACKTRACE, e);
					}

					// need to update all the places the user id is used
					updateQuery = "UPDATE USERINSIGHTPERMISSION SET USERID='" +  cleanNewId +"' WHERE USERID='" + cleanOldId + "'";
					try {
						securityDb.insertData(updateQuery);
					} catch (SQLException e) {
						classLogger.error(Constants.STACKTRACE, e);
					}
				}
			} catch (Exception e1) {
				classLogger.error(Constants.STACKTRACE, e1);
			} finally {
				if(wrapper != null) {
					try {
						wrapper.close();
					} catch (IOException e) {
						classLogger.error(Constants.STACKTRACE, e);
					}
				}
			}
		}

		{
			RDBMSNativeEngine formEngine = (RDBMSNativeEngine) Utility.getDatabase(FormBuilder.FORM_BUILDER_ENGINE_NAME);
			if(formEngine != null) {
				// let us not try to run this multiple times...
				String requireUpdateQuery = "SELECT * FROM FORMS_USER_ACCESS WHERE USER_ID='" + cleanOldId +"'";
				IRawSelectWrapper wrapper = null;
				try {
					wrapper = WrapperManager.getInstance().getRawWrapper(formEngine, requireUpdateQuery);
					// if we have next
					// that means we need to update
					// from id to email
					if(wrapper.hasNext()) {

						// form builder update block
						String emailColumnExists = "SELECT COLUMN_NAME, TYPE_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'FORMS_USER_ACCESS' AND COLUMN_NAME='EMAIL'";
						IRawSelectWrapper requireFormUpdateWrapper = null;
						try {
							requireFormUpdateWrapper = WrapperManager.getInstance().getRawWrapper(formEngine, emailColumnExists);
							if(!requireFormUpdateWrapper.hasNext()) {
								formEngine.insertData("ALTER TABLE FORMS_USER_ACCESS ADD COLUMN IF NOT EXISTS EMAIL VARCHAR(200);");
								formEngine.insertData("UPDATE FORMS_USER_ACCESS SET EMAIL = USER_ID;");
							}
						} catch (Exception e) {
							classLogger.error(Constants.STACKTRACE, e);
						} finally {
							if(requireFormUpdateWrapper != null) {
								try {
									requireFormUpdateWrapper.close();
								} catch(IOException e) {
									classLogger.error(Constants.STACKTRACE, e);
								}
							}
						}

						// now that the schema is in place, lets update the values
						String updateQuery = "UPDATE FORMS_USER_ACCESS SET USER_ID='" +  cleanNewId +"' WHERE USER_ID='" + cleanOldId + "'";
						try {
							formEngine.insertData(updateQuery);
						} catch (SQLException e) {
							classLogger.error(Constants.STACKTRACE, e);
						}

					}
				} catch (Exception e1) {
					classLogger.error(Constants.STACKTRACE, e1);
				} finally {
					if(wrapper != null) {
						try {
							wrapper.close();
						} catch (IOException e) {
							classLogger.error(Constants.STACKTRACE, e);
						}
					}
				}
			}
		}
	}
}
