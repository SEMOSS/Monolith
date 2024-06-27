package prerna.semoss.web.services.local;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.catalina.core.ApplicationContext;
import org.apache.catalina.core.ApplicationContextFacade;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.session.StandardManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.owasp.encoder.Encode;

import prerna.auth.User;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.util.insight.InsightUtility;
import prerna.web.conf.DBLoader;
import prerna.web.services.util.WebUtility;

@Path("/session")
@PermitAll
public class SessionResource {
	
	private static final Logger logger = LogManager.getLogger(SessionResource.class);
	private static final String CANCEL_INVALIDATION = "cancelInvalidation";
	private Object lock = new Object();
	
	/**
	 * Returns the number of active servers related to a given manager. 
	 * @param request
	 * @return
	 */
	
	@GET
	@Path("/active")
	@Produces("application/json;charset=utf-8")
	public Response getActiveSessions(@Context HttpServletRequest request) {
		Map<String, Object> ret = new HashMap<>();
		HttpSession session = request.getSession(true);
		try {
			User user = null;
			if(session != null) {
				user = (User) session.getAttribute(Constants.SESSION_USER);
			}
			logger.info(ResourceUtility.getLogMessage(request, session, User.getSingleLogginName(user), "is pulling the # of active sessions"));
		
			StandardManager manager = getManager(session);
			if(manager != null) {
				int sessions = manager.getActiveSessions();
				if(session.isNew()) {
					sessions -= 1;
				}
				ret.put("activeSessions", sessions);
			} else {
				ret.put("activeSessions", "Error in getting manager context");
			}
		} finally {
			if(session.isNew()) {
				session.invalidate();
			}
		}
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Getting the manager
	 * @param request
	 */
	public static StandardManager getManager(HttpSession session) {
		ApplicationContextFacade applicationContextFacade = (ApplicationContextFacade) session.getServletContext();
		try {
			Field applicationContextField = applicationContextFacade.getClass().getDeclaredField("context");
			applicationContextField.setAccessible(true);
			ApplicationContext appContext = (ApplicationContext) applicationContextField.get(applicationContextFacade);
			
			Field standardContextField = appContext.getClass().getDeclaredField("context");
			standardContextField.setAccessible(true);
			StandardContext standardContext = (StandardContext) standardContextField.get(appContext);
			return (StandardManager) standardContext.getManager();			
		} catch (IllegalAccessException | IllegalArgumentException | NoSuchFieldException | SecurityException e) {
			logger.error(Constants.STACKTRACE, e);
		}
		
		return null;
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
		if (session == null) {
			logger.info("Invalid session for cleaning");
			Map<String, String> ret = new HashMap<>();
			ret.put("output", "Invalid session");
			return WebUtility.getResponse(ret, 400);
		}
		logger.info("Start invalidation of session");
		String sessionId = session.getId();
		// clear up insight store
		InsightStore inStore = InsightStore.getInstance();
		Set<String> insightIDs = inStore.getInsightIDsForSession(sessionId);
		if (insightIDs != null) {
			Set<String> copy = new HashSet<>(insightIDs);
			for (String insightId : copy) {
				Insight insight = InsightStore.getInstance().get(insightId);
				if (insight == null) {
					continue;
				}
				InsightUtility.dropInsight(insight);
			}
			logger.info("Successfully removed insight information from session");

			// clear the current session store
			insightIDs.removeAll(copy);
		}

		// wait 10 seconds
		try {
			Thread.sleep(10_000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			logger.error(Constants.STACKTRACE, e);
		}

		String output = null;
		if (request.isRequestedSessionIdValid()) {
			// did the FE at any point cancel this clean?
			Date cancelTime = null;
			synchronized (lock) {
				cancelTime = (Date) session.getAttribute(CANCEL_INVALIDATION);
			}
			if (cancelTime == null) {
				// kill the entire session
				logger.info("Invalidating session");
				session.invalidate();
				output = "invalidated";
			} else {
				boolean isCancelled = execTime.before(cancelTime);
				if (isCancelled) {
					logger.info("Cancelled invalidating session");
					output = "cancelled";
				} else {
					// kill the entire session
					logger.info("Invalidating session");
					session.invalidate();
					output = "invalidated";
				}
			}
		} else {
			// in case during the time this was called
			// the session has been invalidated by some other means
			// like a logout
			logger.info("Session has already been invalidated");
			output = "invalidated";
		}

		Map<String, String> ret = new HashMap<>();
		ret.put("output", output);
		return WebUtility.getResponse(ret, 200);
	}

	@POST
	@Path("/cancelCleanSession")
	@Produces("application/json;charset=utf-8")
	public Response cancelCleanSession(@Context HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			Map<String, String> ret = new HashMap<>();
			ret.put("output", "Invalid session");
			return WebUtility.getResponse(ret, 400);
		}
		logger.info("Cancelling invalidation...");
		Date d = new Date();
		synchronized (lock) {
			session.setAttribute(CANCEL_INVALIDATION, d);
		}

		Map<String, String> ret = new HashMap<>();
		ret.put("output", "cancel");
		return WebUtility.getResponse(ret, 200);
	}

	@GET
	@Path("/invalidateSession")
	@Produces("application/json;charset=utf-8")
	public void invalidateSession(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws IOException {
		String redirectUrl = request.getHeader("referer");
		response.setStatus(302);
		
		HttpSession session = request.getSession(false);
		User thisUser = null;
		if(session != null) {
			thisUser = (User) session.getAttribute(Constants.SESSION_USER);
		}
		
		// log the user logout
		logger.info(ResourceUtility.getLogMessage(request, session, User.getSingleLogginName(thisUser), "is being forcibly invalidated"));
		
		// redirect to login/logout page
		if (DBLoader.useLogoutPage()) {
			logger.info("Session ended. Redirect to logout page");

			String customUrl = DBLoader.getCustomLogoutUrl();
			if (customUrl != null && !customUrl.isEmpty()) {
				response.setHeader("redirect", customUrl);
				response.sendError(302, "Need to redirect to " + customUrl);
			} else {
				String scheme = request.getScheme(); // http

				if (!scheme.trim().equalsIgnoreCase("https") &&
					!scheme.trim().equalsIgnoreCase("http")) {
					throw new IllegalArgumentException("scheme is invalid, please input proper scheme");
				}

				String serverName = request.getServerName(); // hostname.com
				int serverPort = request.getServerPort(); // 8080
				String contextPath = request.getContextPath(); // /Monolith

				redirectUrl = "";
				redirectUrl += scheme + "://" + serverName;
				if (serverPort != 80 && serverPort != 443) {
					redirectUrl += ":" + serverPort;
				}
				redirectUrl += contextPath + "/logout/";
				redirectUrl = WebUtility.cleanHttpResponse(redirectUrl);
				response.setHeader("redirect", redirectUrl);
				response.sendError(302, "Need to redirect to " + redirectUrl);
			}
		} else {
			logger.info("Session ended. Redirect to login page");

			redirectUrl = redirectUrl + "#/login";
			String encodedRedirectUrl = Encode.forHtml(redirectUrl);
			response.setHeader("redirect", encodedRedirectUrl);
			response.sendError(302, "Need to redirect to " + encodedRedirectUrl);
		}

		if (session != null) {
			logger.info("Invalidating session");
			session.invalidate();
		}
	}

	@GET
	@Path("/insights")
	@Produces("application/json;charset=utf-8")
	public Response getInsights(@Context HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			Map<String, String> ret = new HashMap<>();
			ret.put("output", "Invalid session");
			return WebUtility.getResponse(ret, 400);
		}
		String sessionId = session.getId();
		Set<String> ids = InsightStore.getInstance().getInsightIDsForSession(sessionId);
		return WebUtility.getResponse(ids, 200);
	}

}
