package prerna.web.conf;

import java.io.IOException;
import java.security.cert.X509Certificate;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

public class CACFilter implements Filter {

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		X509Certificate[] certs = (X509Certificate[]) arg0.getAttribute("javax.servlet.request.X509Certificate");
		HttpSession session = ((HttpServletRequest)arg0).getSession(false);

		if(session != null) {

			if(certs != null) {
				for(int i = 0; i < certs.length; i++) {
					X509Certificate cert = certs[i];

					System.out.println(" Client certificate " + (i + 1) + ":");
					System.out.println(" Subject DN: " + cert.getSubjectDN());
					System.out.println(" Subject Name: " + cert.getSubjectX500Principal().getName());

					System.out.println(" Signature Algorithm: " + cert.getSigAlgName());
					System.out.println(" Valid from: " + cert.getNotBefore());
					System.out.println(" Valid until: " + cert.getNotAfter());
					System.out.println(" Issuer: " + cert.getIssuerDN());
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
