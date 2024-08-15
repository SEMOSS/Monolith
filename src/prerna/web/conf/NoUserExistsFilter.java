package prerna.web.conf;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.engine.api.IDatabaseEngine;
import prerna.engine.api.IRawSelectWrapper;
import prerna.query.querystruct.SelectQueryStruct;
import prerna.query.querystruct.selectors.QueryColumnSelector;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.semoss.web.services.config.AdminConfigService;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

public class NoUserExistsFilter implements Filter {

	private static final Logger logger = LogManager.getLogger(NoUserExistsFilter.class);

	private static final String SET_ADMIN_HTML = "/setAdmin/";
	private static boolean userDefined = false;

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		// i do not want to run this query for every single call
		// just gets annoying 
		if(!NoUserExistsFilter.userDefined) {
			// this will be the full path of the request
			// like http://localhost:8080/Monolith_Dev/api/engine/runPixel
			String fullUrl = WebUtility.cleanHttpResponse(((HttpServletRequest) arg0).getRequestURL().toString());
			if (!ResourceUtility.allowAccessWithoutUsers(fullUrl)) {
				IDatabaseEngine engine = Utility.getDatabase(Constants.SECURITY_DB);
				SelectQueryStruct qs = new SelectQueryStruct();
				qs.addSelector(new QueryColumnSelector("SMSS_USER__ID"));
				qs.setLimit(1);
				IRawSelectWrapper wrapper = null;
				try {
					wrapper = WrapperManager.getInstance().getRawWrapper(engine, qs);
					boolean hasUser = wrapper.hasNext();

					// no users at all registered, we need to send to the admin page
					if(!hasUser) {
						
						
						//Is there a env var for initial admin
						if (System.getenv("SMSS_INITIAL_ADMIN") != null) {
							String id = System.getenv("SMSS_INITIAL_ADMIN");
							AdminConfigService.setInitialAdminViaENV(id);
						}
						
						if (System.getenv("SMSS_INITIAL_ADMIN") == null) {
						//else redirect
							
							// we need to store information in the session
							// so that we can properly come back to the referer once an admin has been added
							String referer = ((HttpServletRequest) arg0).getHeader("referer");
							referer = referer + "#!/login";
							((HttpServletRequest) arg0).getSession(true).setAttribute(AdminConfigService.ADMIN_REDIRECT_KEY, referer);
	
							// this will be the deployment name of the app
							String contextPath = arg0.getServletContext().getContextPath();
	
							// we redirect to the index.html page where we have pushed the admin page
							String redirectUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length()) + SET_ADMIN_HTML;
							((HttpServletResponse) arg1).setHeader("redirect", redirectUrl);
							((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + redirectUrl);
							return;
						}
						
						} else {
							// set boolean so we dont keep querying all the time
							NoUserExistsFilter.userDefined = true;
						}
				} catch (Exception e) {
					logger.error(Constants.STACKTRACE,e);
				} finally {
					if(wrapper != null) {
						try {
							wrapper.close();
						} catch(IOException e) {
							logger.error(Constants.STACKTRACE, e);
						}
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
		// TODO Auto-generated method stub

	}
}
