package prerna.web.conf;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

public class CustomRestCsrfPreventionFilter extends org.apache.catalina.filters.RestCsrfPreventionFilter {

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		HttpSession session = ((HttpServletRequest) arg0).getSession();
		session.setAttribute("csrf", true);
		super.doFilter(arg0, arg1, arg2);
	}
}
