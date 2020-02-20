package prerna.semoss.web.services.local;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.util.Constants;
import prerna.util.insight.InsightUtility;
import prerna.web.conf.DBLoader;
import prerna.web.conf.SessionCounter;
import prerna.web.services.util.WebUtility;

@Path("/session")
public class SessionResource {

	private static final Logger LOGGER = LogManager.getLogger(SessionResource.class.getName());
	private static final String CANCEL_INVALIDATION = "cancelInvalidation";
	private Object lock = new Object();
	
	@GET
	@Path("/active")
	@Produces("application/json;charset=utf-8")
	public Response getActiveSessions(@Context HttpServletRequest request) {
		if(AbstractSecurityUtils.securityEnabled()) {
			User user;
			try {
				user = ResourceUtility.getUser(request);
			} catch (IllegalAccessException e) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("error", "User session is invalid");
				return WebUtility.getResponse(errorMap, 401);
			}
			if(!SecurityAdminUtils.userIsAdmin(user)) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("error", "User is not an admin");
				return WebUtility.getResponse(errorMap, 401);
			}
		}

		Map<String, Integer> ret = new HashMap<String, Integer>();
		ret.put("activeSessions", SessionCounter.getActiveSessions());
		return WebUtility.getResponse(ret, 200);
	}
	
	/*
	 * End points for cleanup of the thread to account for closing/opening
	 */

	@POST
	@Path("/cleanSession")
	@Produces("application/json;charset=utf-8")
	public Response cleanSession(@Context HttpServletRequest request) {
		// need to compare when this method was called
		// to a potential cancellation
		Date execTime = new Date();

		HttpSession session = request.getSession(false);
		if(session == null) {
			LOGGER.info("Invalid session for cleaning");
			Map<String, String> ret = new HashMap<String, String>();
			ret.put("output", "Invalid session");
			return WebUtility.getResponse(ret, 400);
		}
		LOGGER.info("Start invalidation of session");
		String sessionId = session.getId();
		// clear up insight store
		InsightStore inStore = InsightStore.getInstance();
		Set<String> insightIDs = inStore.getInsightIDsForSession(sessionId);
		if(insightIDs != null) {
			Set<String> copy = new HashSet<String>(insightIDs);
			for(String insightId : copy) {
				Insight insight = InsightStore.getInstance().get(insightId);
				if(insight == null) {
					continue;
				}
				InsightUtility.dropInsight(insight);
			}
			LOGGER.info("Successfully removed insight information from session");

			// clear the current session store
			insightIDs.removeAll(copy);
		}

		// wait 10 seconds
		try {
			Thread.sleep(10_000);
		} catch (InterruptedException e1) {
			e1.printStackTrace();
		}

		String output = null;
		if(request.isRequestedSessionIdValid()) {
			// did the FE at any point cancel this clean?
			Date cancelTime = null;
			synchronized(lock) {
				cancelTime = (Date) session.getAttribute(CANCEL_INVALIDATION);
			}
			if(cancelTime == null) {
				// kill the entire session
				LOGGER.info("Invalidating session");
				session.invalidate();
				output = "invalidated";
			} else {
				boolean isCancelled = execTime.before(cancelTime);
				if(isCancelled) {
					LOGGER.info("Cancelled invalidating session");
					output = "cancelled";
				} else {
					// kill the entire session
					LOGGER.info("Invalidating session");
					session.invalidate();
					output = "invalidated";
				}
			}
		} else {
			// in case during the time this was called
			// the session has been invalidated by some other means
			// like a logout
			LOGGER.info("Session has already been invalidated");
			output = "invalidated";
		}

		Map<String, String> ret = new HashMap<String, String>();
		ret.put("output", output);
		return WebUtility.getResponse(ret, 200);
	}

	@POST
	@Path("/cancelCleanSession")
	@Produces("application/json;charset=utf-8")
	public Response cancelCleanSession(@Context HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if(session == null) {
			Map<String, String> ret = new HashMap<String, String>();
			ret.put("output", "Invalid session");
			return WebUtility.getResponse(ret, 400);
		}
		LOGGER.info("Cancelling invalidation...");
		Date d = new Date();
		synchronized(lock) {
			session.setAttribute(CANCEL_INVALIDATION, d);
		}
		
		Map<String, String> ret = new HashMap<String, String>();
		ret.put("output", "cancel");
		return WebUtility.getResponse(ret, 200);
	}
	
	@GET
	@Path("/invalidateSession")
	@Produces("application/json;charset=utf-8")
	public void invalidateSession(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException {
		String redirectUrl = request.getHeader("referer");
		response.setStatus(302);

		// redirect to login/logout page
		if(DBLoader.useLogoutPage()) {
			LOGGER.info("Session ended. Redirect to logout page");

			String customUrl = DBLoader.getCustomLogoutUrl();
			if(customUrl != null && !customUrl.isEmpty()) {
				response.setHeader("redirect", customUrl);
				response.sendError(302, "Need to redirect to " + customUrl);
			} else {
				String scheme = request.getScheme();             // http
			    String serverName = request.getServerName();     // hostname.com
			    int serverPort = request.getServerPort();        // 8080
			    String contextPath = request.getContextPath();   // /Monolith
				
			    redirectUrl = "";
			    redirectUrl += scheme + "://" + serverName;
			    if (serverPort != 80 && serverPort != 443) {
			    	redirectUrl += ":" + serverPort;
			    }
			    redirectUrl += contextPath + "/logout/";
				response.setHeader("redirect", redirectUrl);
				response.sendError(302, "Need to redirect to " + redirectUrl);
			}
		} else {
			LOGGER.info("Session ended. Redirect to login page");

			redirectUrl = redirectUrl + "#!/login";
			response.setHeader("redirect", redirectUrl);
			response.sendError(302, "Need to redirect to " + redirectUrl);
		}
		
		HttpSession session = request.getSession(false);
		if(session != null) {
			LOGGER.info("User is no longer logged in");
			LOGGER.info("Removing user object from session");
			session.removeAttribute(Constants.SESSION_USER);
			
			session.invalidate();
		}
	}
	
	@GET
	@Path("/insights")
	@Produces("application/json;charset=utf-8")
	public Response getInsights(@Context HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if(session == null) {
			Map<String, String> ret = new HashMap<String, String>();
			ret.put("output", "Invalid session");
			return WebUtility.getResponse(ret, 400);
		}
		String sessionId = session.getId();
		Set<String> ids = InsightStore.getInstance().getInsightIDsForSession(sessionId);
		return WebUtility.getResponse(ids, 200);
	}
	
}
