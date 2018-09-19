package prerna.web.conf;

import java.io.IOException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
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
import prerna.util.Constants;

public class CACFilter implements Filter {

	private static final Logger LOGGER = LogManager.getLogger(CACFilter.class.getName()); 

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
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
				// loop thorugh all the certs
				CERT_LOOP : for(int i = 0; i < certs.length; i++) {
					X509Certificate cert = certs[i];

					String fullName = cert.getSubjectX500Principal().getName();
					System.out.println("REQUEST COMING FROM " + fullName);
					System.out.println("REQUEST COMING FROM " + fullName);
					LdapName ldapDN;
					try {
						ldapDN = new LdapName(fullName);
						for(Rdn rdn: ldapDN.getRdns()) {
							if(rdn.getType().equals("CN")) {
								String value = rdn.getValue().toString();

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


								String[] split = value.split("\\.");
								if(split.length == 3 || split.length == 4) {
									// no idea if middle name is always there or not
									// just gonna validate the cac has length 10
									String cacId = split[split.length-1];
									if(cacId.length() >= 10) {
										// valid CAC!!!
										token.setId(cacId);
										token.setName(Stream.of(split).limit(split.length-2).collect(Collectors.joining(" ")));
										// now set the other properties
										token.setToken_type(cert.getIssuerDN().getName());
										token.setExpires_in((int) cert.getNotAfter().getTime());

										// try to get the email
										try {
											EMAIL_LOOP : for(List<?> altNames : cert.getSubjectAlternativeNames()) {
												for(Object alternative : altNames) {
													if(alternative instanceof String) {
														String altStr = alternative.toString();
														// really simple email check...
														if(altStr.contains("@")) {
															token.setEmail(altStr);
															break EMAIL_LOOP;
														}
													}
												}
											}
										} catch (CertificateParsingException e) {
											e.printStackTrace();
										}

										break CERT_LOOP;
									} else {
										continue;
									}
								} else {
									continue;
								}
							}
						}
					} catch (InvalidNameException e) {
						LOGGER.error("ERROR WITH PARSING CAC INFORMATION!");
						e.printStackTrace();
					}
				}

				if(token.getName() != null) {
					LOGGER.info("Valid request coming from user " + token.getName());
					user.setAccessToken(token);
					session.setAttribute(Constants.SESSION_USER, user);

					// add the user if they do not exist
					SecurityUpdateUtils.addOAuthUser(token);
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
		// TODO Auto-generated method stub

	}

}
