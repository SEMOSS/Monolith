package prerna.semoss.web.services.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.ds.py.PyUtils;
import prerna.sablecc2.reactor.cluster.VersionReactor;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.semoss.web.services.local.UserResource;
import prerna.theme.AdminThemeUtils;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.web.conf.DBLoader;
import prerna.web.services.util.WebUtility;

@Path("/config")
public class ServerConfigurationResource {
	
	private static final Logger logger = LogManager.getLogger(ServerConfigurationResource.class); 


	private static volatile Map<String, Object> config = null;

	/**
	 * Generate the configuration options for this instance Only need to make this
	 * once
	 * 
	 * @param request
	 * @return
	 */
	private static Map<String, Object> getConfig(@Context HttpServletRequest request) {
		HttpSession session = request.getSession(true);

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			//ignoring user here because it is okay to. This is for health checks or server checks even when users havent been instantiated.
		}
		
		if(config != null) {
			return getConfiguration(session, user);
		}
		
		// make thread safe
		synchronized (ServerConfigurationResource.class) {
			if (config == null) {
				loadConfig(session);
			}
		}

		return getConfiguration(session, user);
	}
	
	private static void loadConfig(HttpSession session) {
		Map<String, Object> loadConfig = new HashMap<>();
		// session timeout
		loadConfig.put("timeout", (double) session.getMaxInactiveInterval() / 60);

		// r enabled
		boolean useR = true;
		String useRStr = DIHelper.getInstance().getProperty(Constants.USE_R);
		if (useRStr != null) {
			useR = Boolean.parseBoolean(useRStr);
		}
		loadConfig.put("r", useR);

		// python enabled
		loadConfig.put("python", PyUtils.pyEnabled());

		// security enabled
		loadConfig.put("security", AbstractSecurityUtils.securityEnabled());
		loadConfig.put("anonymousUsers", AbstractSecurityUtils.anonymousUsersEnabled());
		loadConfig.put("anonymousUserUploadData", AbstractSecurityUtils.anonymousUserUploadData());
		// admin set public enabled
		boolean adminSetPublic = false;
		String adminSetPublicStr = (String) DIHelper.getInstance().getLocalProp(Constants.ADMIN_SET_PUBLIC);
		if (adminSetPublicStr != null) {
			adminSetPublic = Boolean.parseBoolean(adminSetPublicStr);
		}
		loadConfig.put("adminSetPublic", adminSetPublic);

		// return a boolean if we want to use a dedicated logout page
		// instead of redirecting to the login page
		loadConfig.put("useLogoutPage", DBLoader.useLogoutPage());

		// max file transfer size
		String fileTransferMax = DIHelper.getInstance().getProperty(Constants.FILE_TRANSFER_LIMIT);
		if (fileTransferMax != null) {
			try {
				loadConfig.put("file-limit", Integer.parseInt(fileTransferMax));
			} catch (Exception e) {
				logger.error("Stack Trace: ", e);
			}
		}

		// version of the application
		try {
			Map<String, String> versionMap = VersionReactor.getVersionMap();
			loadConfig.put("version", versionMap);
		} catch (Exception e) {
			logger.error("Stack Trace: ", e);
		}

		// send the default frame type
		String defaultFrameType = DIHelper.getInstance().getProperty(Constants.DEFAULT_FRAME_TYPE);
		if (defaultFrameType == null) {
			defaultFrameType = "GRID";
		}
		loadConfig.put("defaultFrameType", defaultFrameType);

		String defaultScriptingLanguage = DIHelper.getInstance()
				.getProperty(Constants.DEFAULT_SCRIPTING_LANGUAGE);
		if (defaultScriptingLanguage == null) {
			defaultScriptingLanguage = "R";
		}
		loadConfig.put("defaultScriptingLanguage", defaultScriptingLanguage);

		// local mode
		boolean localMode = false;
		String localModeStr = DIHelper.getInstance().getProperty(Constants.LOCAL_DEPLOYMENT);
		if (localModeStr != null) {
			localMode = Boolean.parseBoolean(localModeStr);
		}
		loadConfig.put("localDeployment", localMode);
		
		ServerConfigurationResource.config = loadConfig;
	}

	private static Map<String, Object> getConfiguration(HttpSession session, User user) {
		// do not keep this session
		// if no user and it is new
		if (session.isNew() && user == null) {
			session.invalidate();
		}

		Map<String, Object> myConfiguration = new HashMap<>();
		myConfiguration.putAll(config);
		// append values that can change without restarting the server
		// logins allowed
		myConfiguration.put("loginsAllowed", UserResource.getLoginsAllowed());
		// current logins
		myConfiguration.put("logins", User.getLoginNames(user));
		// themes
		myConfiguration.put("theme", AdminThemeUtils.getActiveAdminTheme());
		return myConfiguration;
	}
	
	@GET
	@Path("/")
	@Produces("application/json")
	public Response getServerConfig(@Context HttpServletRequest request, @Context HttpServletResponse response) {
		List<NewCookie> newCookies = new Vector<>();

		try {
			ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			// this errors when no user exists in the session
			// should happen every time this is called
			// since FE only calls this method on browser startup
			// clean up any invalid cookies on the browser
			Cookie[] cookies = request.getCookies();
			if (cookies != null) {
				for (Cookie c : cookies) {
					if (DBLoader.getSessionIdKey().equals(c.getName())) {
						// we need to null this out
						NewCookie nullC = new NewCookie(c.getName(), c.getValue(), c.getPath(), c.getDomain(),
								c.getComment(), 0, c.getSecure());
						newCookies.add(nullC);
					}
				}
			}
		}
		
		return WebUtility.getResponseNoCache(getConfig(request), 200, newCookies.toArray(new NewCookie[] {}));
	}

}
