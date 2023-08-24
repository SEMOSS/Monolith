package prerna.web.conf;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.owasp.encoder.Encode;

import prerna.engine.api.IDatabaseEngine;
import prerna.engine.api.IRawSelectWrapper;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.Constants;
import prerna.util.Utility;

public class AdminStartupFilter implements Filter {
	
	private static final Logger logger = LogManager.getLogger(AdminStartupFilter.class);
	private static String initialRedirect;

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		IDatabaseEngine engine = Utility.getDatabase(Constants.SECURITY_DB);
		String q = "SELECT * FROM SMSS_USER LIMIT 1";
		IRawSelectWrapper wrapper = null;
		try {
			wrapper = WrapperManager.getInstance().getRawWrapper(engine, q);
			boolean hasUser = wrapper.hasNext();
			// if there are users, redirect to the main semoss page
			// we do not want to allow the person to make any admin requests
			if (hasUser) {
				if (initialRedirect != null) {
					String encodedRedirectUrl = Encode.forHtml(initialRedirect);
					((HttpServletResponse) arg1).setHeader("redirect", encodedRedirectUrl);
					((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + encodedRedirectUrl);
				} else {
					((HttpServletResponse) arg1).sendError(404, "Page Not Found");
				}
			}
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
		} finally {
			if (wrapper != null) {
				try {
					wrapper.close();
				} catch(IOException e) {
					logger.error(Constants.STACKTRACE, e);
				}
			}
		}

		arg2.doFilter(arg0, arg1);
	}

	@Override
	public void destroy() {
		// destroy
	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		// initialize
	}

	public static void setSuccessfulRedirectUrl(String initialRedirect) {
		AdminStartupFilter.initialRedirect = initialRedirect;
	}

}
