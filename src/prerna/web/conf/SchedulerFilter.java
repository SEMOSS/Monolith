package prerna.web.conf;

import java.io.IOException;

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
import prerna.rpa.config.JobConfigKeys;
import prerna.sablecc2.reactor.scheduler.SchedulerH2DatabaseUtility;
import prerna.util.Constants;

public class SchedulerFilter implements Filter {

	private static final Logger logger = LogManager.getLogger(SchedulerFilter.class);
	
	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		HttpServletRequest request = (HttpServletRequest) arg0;

		String execId = request.getParameter(JobConfigKeys.EXEC_ID);
		String jobName = request.getParameter(JobConfigKeys.JOB_NAME);
		String jobGroup = request.getParameter(JobConfigKeys.JOB_GROUP);

		// make sure the request is valid
		String[] jobInfo = SchedulerH2DatabaseUtility.executionIdExists(execId);
		if(jobInfo == null) {
			// error
			logger.info("Could not find the scheduler execution id");
			return;
		}
		if(!jobInfo[0].equals(jobName) || !jobInfo[1].equals(jobGroup)) {
			// error
			logger.info("Found scheduler execution id but could not match to details");
			return;
		}
		
		HttpSession session = request.getSession(true);
		String userAccess = request.getParameter(JobConfigKeys.USER_ACCESS);
		// Add user info to the insight
		User user = new User();
		String[] accessPairs = userAccess.split(",");
		for (String accessPair : accessPairs) {
			String[] providerAndId = accessPair.split(":");

			// Get the auth provider
			AuthProvider provider = AuthProvider.valueOf(providerAndId[0]);

			// Get the id
			String id = providerAndId[1];

			// Create the access token
			AccessToken token = new AccessToken();
			token.setProvider(provider);
			token.setId(id);

			user.setAccessToken(token);
		}
		session.setAttribute(Constants.SESSION_USER, user);
		
		// clean up the table
		SchedulerH2DatabaseUtility.removeExecutionId(execId);
		
		// continue
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
