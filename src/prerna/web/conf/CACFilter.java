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
import javax.servlet.http.HttpSession;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User2;

public class CACFilter implements Filter {

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		X509Certificate[] certs = (X509Certificate[]) arg0.getAttribute("javax.servlet.request.X509Certificate");
		HttpSession session = ((HttpServletRequest)arg0).getSession(false);

		if(session != null && certs != null) {
			User2 user = (User2) session.getAttribute("semoss_user");
			if(user == null) {
				user = new User2();
			}
			
			AccessToken token = new AccessToken();

			for(int i = 0; i < certs.length; i++) {
				X509Certificate cert = certs[i];

				token.setToken_type(AuthProvider.CAC.toString());
				token.setProvider(cert.getIssuerDN().getName());
				token.setExpires_in((int) cert.getNotAfter().getTime());
				
				String fullName = cert.getSubjectX500Principal().getName();
				LdapName ldapDN;
				try {
					ldapDN = new LdapName(fullName);
					for(Rdn rdn: ldapDN.getRdns()) {
						if(rdn.getType().equals("CN")) {
							token.setName(rdn.getValue().toString());
						}
					}
				} catch (InvalidNameException e) {
					token.setName(fullName);
					e.printStackTrace();
				}
			}
			
			user.setAccessToken(token);
			session.setAttribute("semoss-user", user);
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
