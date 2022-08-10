package prerna.semoss.web.services.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.servlet.FilterChain;
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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.AccessPermissionEnum;
import prerna.auth.PasswordRequirements;
import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.ds.py.PyUtils;
import prerna.sablecc2.reactor.cluster.VersionReactor;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.theme.AdminThemeUtils;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.SocialPropertiesUtil;
import prerna.util.Utility;
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
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			//ignoring user here because it is okay to. This is for health checks or server checks even when users havent been instantiated.
		}
		
		if(config != null) {
			return getConfiguration(request, user);
		}
		
		// make thread safe
		synchronized (ServerConfigurationResource.class) {
			if (config == null) {
				loadConfig();
			}
		}

		return getConfiguration(request, user);
	}
	
	private static void loadConfig() {
		Map<String, Object> loadConfig = new HashMap<>();

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
		// admin only for project actions
		loadConfig.put("adminOnlyProjectAdd", AbstractSecurityUtils.adminOnlyProjectAdd());
		loadConfig.put("adminOnlyProjectDelete", AbstractSecurityUtils.adminOnlyProjectDelete());
		loadConfig.put("adminOnlyProjectAddAccess", AbstractSecurityUtils.adminOnlyProjectAddAccess());
		loadConfig.put("adminOnlyProjectSetPublic", AbstractSecurityUtils.adminOnlyProjectSetPublic());
		// admin only for db actions
		loadConfig.put("adminOnlyDbAdd", AbstractSecurityUtils.adminOnlyDbAdd());
		loadConfig.put("adminOnlyDbDelete", AbstractSecurityUtils.adminOnlyDbDelete());
		loadConfig.put("adminOnlyDbAddAccess", AbstractSecurityUtils.adminOnlyDbAddAccess());
		loadConfig.put("adminOnlyDbSetPublic", AbstractSecurityUtils.adminOnlyDbSetPublic());
		loadConfig.put("adminOnlyDbSetDiscoverable", AbstractSecurityUtils.adminOnlyDbSetDiscoverable());
		// admin only for insight actions
		loadConfig.put("adminOnlyInsightSetPublic", AbstractSecurityUtils.adminOnlyInsightSetPublic());
		
		// return a boolean if we want to use a dedicated logout page
		// instead of redirecting to the login page
		loadConfig.put("useLogoutPage", DBLoader.useLogoutPage());

		// max file transfer size
		String fileTransferMax = DIHelper.getInstance().getProperty(Constants.FILE_TRANSFER_LIMIT);
		if (fileTransferMax != null) {
			try {
				loadConfig.put("file-limit", Integer.parseInt(fileTransferMax));
			} catch (Exception e) {
				logger.error(Constants.STACKTRACE, e);
			}
		}
		
		// shared file path
		String sharedFilePath = DIHelper.getInstance().getProperty(Constants.SHARED_FILE_PATH);
		if (sharedFilePath != null && !sharedFilePath.isEmpty()) {
			try {
				loadConfig.put("fileSharedPath", sharedFilePath);
			} catch (Exception e) {
				logger.error(Constants.STACKTRACE, e);
			}
		}
		
		// version of the application
		try {
			Map<String, String> versionMap = VersionReactor.getVersionMap(false);
			loadConfig.put("version", versionMap);
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
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
		
		// insights are cacheable by default
		boolean cacheableOnByDefault = Utility.getApplicationCacheInsight();
		loadConfig.put("cacheInsightByDefault", cacheableOnByDefault);
		int cacheMinutes = Utility.getApplicationCacheInsightMinutes();
		loadConfig.put("cacheInsightMinutes", cacheMinutes);
		boolean cacheEncrypt = Utility.getApplicationCacheEncrypt();
		loadConfig.put("cacheInsightEncrypt", cacheEncrypt);
		String cacheCron = Utility.getApplicationCacheCron();
		loadConfig.put("cacheCron", cacheCron);
		
		// to make welcome dialog optional
		boolean showWelcomeBanner = Utility.getWelcomeBannerOption();
		loadConfig.put("showWelcomeBanner", showWelcomeBanner);

		// send back the permission mapping
		loadConfig.put("permissionMappingString", AccessPermissionEnum.flushEnumString());
		loadConfig.put("permissionMappingInteger", AccessPermissionEnum.flushEnumInteger());
		
		// some initial pipeline filtering
		loadConfig.put("pipelineLandingFilter", Utility.getApplicationPipelineLandingFilter());
		loadConfig.put("pipelineSourceFilter", Utility.getApplicationPipelineSourceFilter());
		
		ServerConfigurationResource.config = loadConfig;
	}

	private static Map<String, Object> getConfiguration(@Context HttpServletRequest request, User user) {
		HttpSession session = request.getSession();

		Map<String, Object> myConfiguration = new HashMap<>();
		myConfiguration.putAll(config);
		// session timeout
		// in case we have different timeout for the admin
		// we have this grab for this session what the timeout value is
		myConfiguration.put("timeout", (double) session.getMaxInactiveInterval() / 60);
		// append values that can change without restarting the server
		// logins allowed
		myConfiguration.put("loginsAllowed", SocialPropertiesUtil.getInstance().getLoginsAllowed());
		// password requirements
		try {
			myConfiguration.put("passwordRequirements", PasswordRequirements.getInstance().getAllPasswordRequirements());
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
		}
		// current logins
		// TODO: added 2022-02-25
		// TODO: should move away from logins cause sometimes people are using this as if the name is the ID
		// TODO: but not sure where this is all happening, so sending both keys for now
		myConfiguration.put("logins", User.getLoginNames(user));
		myConfiguration.put("loginDetails", User.getLoginDetails(user));
		// themes
		myConfiguration.put("theme", AdminThemeUtils.getActiveAdminTheme());
		// add if we are using csrf
		myConfiguration.put("csrf", Boolean.parseBoolean(session.getAttribute("csrf") + ""));
		
		// do not keep this session
		// if no user and it is new
		if (user == null && (session.isNew() || request.isRequestedSessionIdValid())) {
			session.invalidate();
		}
		
		return myConfiguration;
	}
	
	@GET
	@Path("/")
	@Produces("application/json")
	public Response getServerConfig(@Context HttpServletRequest request, @Context HttpServletResponse response, @Context FilterChain filterChain) {
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
	
	@GET
	@Path("/fetchCsrf")
	@Produces("application/json")
	public Response fetchCsrf(@Context HttpServletRequest request, @Context HttpServletResponse response) {
		// create the session
		request.getSession(true);
		return WebUtility.getResponse(true, 200);
	}

}
