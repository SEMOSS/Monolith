package prerna.web.conf;

import java.io.IOException;
import java.security.cert.X509Certificate;

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
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;

public class CACFilter implements Filter {

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		X509Certificate[] certs = (X509Certificate[]) arg0.getAttribute("javax.servlet.request.X509Certificate");
		HttpSession session = ((HttpServletRequest)arg0).getSession(true);

		if(certs != null) {
			User user = (User) session.getAttribute("semoss_user");
			if(user == null) {
				user = new User();

				AccessToken token = new AccessToken();
				token.setProvider(AuthProvider.CAC);
				for(int i = 0; i < certs.length; i++) {
					X509Certificate cert = certs[i];

					String fullName = cert.getSubjectX500Principal().getName();
					LdapName ldapDN;
					try {
						ldapDN = new LdapName(fullName);
						for(Rdn rdn: ldapDN.getRdns()) {
							if(rdn.getType().equals("CN")) {
								String value = rdn.getValue().toString();
								String[] split = value.split("\\.");
								if(split.length == 3 || split.length == 4) {
									// no idea if middle name is always there or not
									// just gonna validate the cac has length 10
									String cacId = split[split.length-1];
									if(cacId.length() >= 10) {
										// valid CAC!!!
										token.setName(cacId);
										// now set the other properties
										token.setToken_type(cert.getIssuerDN().getName());
										token.setExpires_in((int) cert.getNotAfter().getTime());
									} else {
										continue;
									}
								} else {
									continue;
								}
							}
						}
					} catch (InvalidNameException e) {
						token.setName(fullName);
						e.printStackTrace();
					}
				}

				if(token.getName() != null) {
					user.setAccessToken(token);
					session.setAttribute("semoss_user", user);
				}
			}
		}
		
		if(session.getAttribute("semoss_user") == null) {
			//TOOD: figure out redirect
			HttpServletResponse httpResponse = (HttpServletResponse) arg1;
			httpResponse.sendRedirect( ((HttpServletRequest)arg0).getHeader("Referer") + "TestRedirect/test.html");
			return;
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
