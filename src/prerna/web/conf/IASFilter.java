package prerna.web.conf;

import java.io.IOException;
import java.util.Enumeration;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

public class IASFilter implements Filter {

	private static final Logger LOGGER = LogManager.getLogger(IASFilter.class.getName()); 

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		try {
			Enumeration<String> headers = ((HttpServletRequest)arg0).getHeaderNames();
			while (headers.hasMoreElements()) {
				String headerValue = headers.nextElement();
				LOGGER.info("IAS HEADER= " + headerValue + " ::: VALUE= " + ((HttpServletRequest)arg0).getHeader(headerValue));
			}
		} catch(Exception e) {
			e.printStackTrace();
		}
		
		arg2.doFilter(arg0, arg1);
	}

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		// TODO Auto-generated method stub
		
	}

}
