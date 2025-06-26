package prerna.web.conf;

import java.io.IOException;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.logging.log4j.ThreadContext;

import prerna.auth.User;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;


public class StartUpSuccessFilter implements Filter {

	private static boolean startUpSuccess = true;
	private static final String FAIL_HTML = "/startUpFail/";
	private static final String LOG_REQUEST_ID = "requestId";
	private static final String LOG_SESSION_ID = "sessionId";
	private static final String LOG_IP = "IP";
	private static final String LOG_SERVICE_NAME = "serviceName";
	private static final String LOG_METHOD = "method";
	private static final String LOG_ENDPOINT = "endpoint";
	private static final String LOG_HOST = "host";
	private static final String LOG_USER_ID = "userId";
	private static final String REQUEST_TIMESTAMP = "requestTime";
	private static final String LOG_TIMESTAMP = "logTimestamp";
	
	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		ServletContext context = arg0.getServletContext();
		ThreadContext.put(REQUEST_TIMESTAMP, LocalTime.now().toString());
		loggingContext(arg0);
		if(!startUpSuccess) {
			// this will be the deployment name of the app
			String contextPath = context.getContextPath();
			
			// this will be the full path of the request
			// like http://localhost:8080/Monolith_Dev/api/engine/runPixel
			String fullUrl = WebUtility.cleanHttpResponse(((HttpServletRequest) arg0).getRequestURL().toString());

			if(!fullUrl.endsWith(FAIL_HTML)) {
				// we redirect to the index.html page where we have pushed the admin page
				String redirectUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length()) + FAIL_HTML;
				((HttpServletResponse) arg1).setHeader("redirect", redirectUrl);
				((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + redirectUrl);
				return;
			}
		}
		
		arg2.doFilter(arg0, arg1);
	}
	
	private void loggingContext(ServletRequest servletRequest){
		HttpServletRequest request = (HttpServletRequest) servletRequest;
		HttpSession session = request.getSession(false);
		String sessionId = "NO SESSION";
		String userId = "NO USER";
		String reqId = UUID.randomUUID().toString();
		ThreadContext.put(LOG_REQUEST_ID, reqId);
		ThreadContext.put("logId", UUID.randomUUID().toString());
		if(!Objects.isNull(session)) {
			sessionId = session.getId();
			User user = (User) session.getAttribute(Constants.SESSION_USER);
			userId = User.getSingleLogginName(user);
		}
		String IP =Utility.cleanLogString(ResourceUtility.getClientIp(request));
		ThreadContext.put(LOG_USER_ID, userId);
		ThreadContext.put(LOG_SESSION_ID, sessionId);
		ThreadContext.put(LOG_IP, IP);
		ThreadContext.put(LOG_SERVICE_NAME, "MONOLITH");
		ThreadContext.put(LOG_METHOD, request.getMethod());
		ThreadContext.put(LOG_ENDPOINT, request.getRequestURI());
		ThreadContext.put(LOG_HOST, request.getHeader("Host"));
		ThreadContext.put(LOG_TIMESTAMP, LocalTime.now().toString());
	}

	static void setStartUpSuccess(boolean startUpSuccess) {
		StartUpSuccessFilter.startUpSuccess = startUpSuccess;
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
