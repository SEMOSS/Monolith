/*******************************************************************************
 * Copyright 2015 Defense Health Agency (DHA)
 *
 * If your use of this software does not include any GPLv2 components:
 * 	Licensed under the Apache License, Version 2.0 (the "License");
 * 	you may not use this file except in compliance with the License.
 * 	You may obtain a copy of the License at
 *
 * 	  http://www.apache.org/licenses/LICENSE-2.0
 *
 * 	Unless required by applicable law or agreed to in writing, software
 * 	distributed under the License is distributed on an "AS IS" BASIS,
 * 	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * 	See the License for the specific language governing permissions and
 * 	limitations under the License.
 * ----------------------------------------------------------------------------
 * If your use of this software includes any GPLv2 components:
 * 	This program is free software; you can redistribute it and/or
 * 	modify it under the terms of the GNU General Public License
 * 	as published by the Free Software Foundation; either version 2
 * 	of the License, or (at your option) any later version.
 *
 * 	This program is distributed in the hope that it will be useful,
 * 	but WITHOUT ANY WARRANTY; without even the implied warranty of
 * 	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * 	GNU General Public License for more details.
 *******************************************************************************/
package prerna.semoss.web.services.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.security.PermitAll;
import javax.servlet.FilterChain;
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
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.date.SemossDate;
import prerna.ds.py.PyUtils;
import prerna.reactor.cluster.VersionReactor;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.theme.AdminThemeUtils;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;
import prerna.util.Utility;
import prerna.web.conf.DBLoader;
import prerna.web.services.util.WebUtility;

@Path("/config")
@PermitAll
public class ServerConfigurationResource {

	private static final Logger classLogger = LogManager.getLogger(ServerConfigurationResource.class);

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
			// ignoring user here because it is okay to. This is for health checks or server
			// checks even when users havent been instantiated.
		}

		if (config != null) {
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
		String useRStr = Utility.getDIHelperProperty(Constants.USE_R);
		if (useRStr != null) {
			useR = Boolean.parseBoolean(useRStr);
		}
		loadConfig.put("r", useR);

		// python enabled
		loadConfig.put("python", PyUtils.pyEnabled());

		// security is always enabled...
		loadConfig.put("security", true);
		loadConfig.put("anonymousUsers", AbstractSecurityUtils.anonymousUsersEnabled());
		loadConfig.put("anonymousUserUploadData", AbstractSecurityUtils.anonymousUserUploadData());
		// admin only for project actions
		loadConfig.put("adminOnlyProjectAdd", AbstractSecurityUtils.adminOnlyProjectAdd());
		loadConfig.put("adminOnlyProjectDelete", AbstractSecurityUtils.adminOnlyProjectDelete());
		loadConfig.put("adminOnlyProjectAddAccess", AbstractSecurityUtils.adminOnlyProjectAddAccess());
		loadConfig.put("adminOnlyProjectSetPublic", AbstractSecurityUtils.adminOnlyProjectSetPublic());
		loadConfig.put("adminOnlyProjectSetDiscoverable", AbstractSecurityUtils.adminOnlyProjectSetDiscoverable());
		// admin only for engine actions
		loadConfig.put("adminOnlyDbAdd", AbstractSecurityUtils.adminOnlyDatabaseAdd());
		loadConfig.put("adminOnlyDbDelete", AbstractSecurityUtils.adminOnlyDatabaseDelete());
		loadConfig.put("adminOnlyDbAddAccess", AbstractSecurityUtils.adminOnlyDatabaseAddAccess());
		loadConfig.put("adminOnlyDbSetPublic", AbstractSecurityUtils.adminOnlyDatabaseSetPublic());
		loadConfig.put("adminOnlyDbSetDiscoverable", AbstractSecurityUtils.adminOnlyDatabaseSetDiscoverable());
		loadConfig.put("adminOnlyModelAdd", AbstractSecurityUtils.adminOnlyModelAdd());
		loadConfig.put("adminOnlyModelDelete", AbstractSecurityUtils.adminOnlyModelDelete());
		loadConfig.put("adminOnlyModelAddAccess", AbstractSecurityUtils.adminOnlyModelAddAccess());
		loadConfig.put("adminOnlyModelSetPublic", AbstractSecurityUtils.adminOnlyModelSetPublic());
		loadConfig.put("adminOnlyModelSetDiscoverable", AbstractSecurityUtils.adminOnlyModelSetDiscoverable());
		loadConfig.put("adminOnlyStorageAdd", AbstractSecurityUtils.adminOnlyStorageAdd());
		loadConfig.put("adminOnlyStorageDelete", AbstractSecurityUtils.adminOnlyStorageDelete());
		loadConfig.put("adminOnlyStorageAddAccess", AbstractSecurityUtils.adminOnlyStorageAddAccess());
		loadConfig.put("adminOnlyStorageSetPublic", AbstractSecurityUtils.adminOnlyStorageSetPublic());
		loadConfig.put("adminOnlyStorageSetDiscoverable", AbstractSecurityUtils.adminOnlyStorageSetDiscoverable());
		loadConfig.put("adminOnlyVectorAdd", AbstractSecurityUtils.adminOnlyVectorAdd());
		loadConfig.put("adminOnlyVectorDelete", AbstractSecurityUtils.adminOnlyVectorDelete());
		loadConfig.put("adminOnlyVectorAddAccess", AbstractSecurityUtils.adminOnlyVectorAddAccess());
		loadConfig.put("adminOnlyVectorSetPublic", AbstractSecurityUtils.adminOnlyVectorSetPublic());
		loadConfig.put("adminOnlyVectorSetDiscoverable", AbstractSecurityUtils.adminOnlyVectorSetDiscoverable());
		loadConfig.put("adminOnlyFunctionAdd", AbstractSecurityUtils.adminOnlyFunctionAdd());
		loadConfig.put("adminOnlyFunctionDelete", AbstractSecurityUtils.adminOnlyFunctionDelete());
		loadConfig.put("adminOnlyFunctionAddAccess", AbstractSecurityUtils.adminOnlyFunctionAddAccess());
		loadConfig.put("adminOnlyFunctionSetPublic", AbstractSecurityUtils.adminOnlyFunctionSetPublic());
		loadConfig.put("adminOnlyFunctionSetDiscoverable", AbstractSecurityUtils.adminOnlyFunctionSetDiscoverable());
		// admin only for insight actions
		loadConfig.put("adminOnlyInsightAddAccess", AbstractSecurityUtils.adminOnlyInsightAddAccess());
		loadConfig.put("adminOnlyInsightSetPublic", AbstractSecurityUtils.adminOnlyInsightSetPublic());
		loadConfig.put("adminOnlyInsightShare", AbstractSecurityUtils.adminOnlyInsightShare());

		// return a boolean if we want to use a dedicated logout page
		// instead of redirecting to the login page
		loadConfig.put("useLogoutPage", DBLoader.useLogoutPage());

		// max file transfer size
		String fileTransferMax = Utility.getDIHelperProperty(Constants.FILE_TRANSFER_LIMIT);
		if (fileTransferMax != null) {
			try {
				loadConfig.put("file-limit", Integer.parseInt(fileTransferMax));
			} catch (Exception e) {
				classLogger.error(Constants.STACKTRACE, e);
			}
		}

		// shared file path
		String sharedFilePath = Utility.getDIHelperProperty(Constants.SHARED_FILE_PATH);
		if (sharedFilePath != null && !sharedFilePath.isEmpty()) {
			try {
				loadConfig.put("fileSharedPath", sharedFilePath);
			} catch (Exception e) {
				classLogger.error(Constants.STACKTRACE, e);
			}
		}

		// version of the application
		try {
			Map<String, String> versionMap = VersionReactor.getVersionMap(false);
			loadConfig.put("version", versionMap);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
		}

		// send the default frame type
		String defaultFrameType = Utility.getDIHelperProperty(Constants.DEFAULT_FRAME_TYPE);
		if (defaultFrameType == null) {
			defaultFrameType = "GRID";
		}
		loadConfig.put("defaultFrameType", defaultFrameType);

		String defaultScriptingLanguage = Utility.getDIHelperProperty(Constants.DEFAULT_SCRIPTING_LANGUAGE);
		if (defaultScriptingLanguage == null) {
			defaultScriptingLanguage = "R";
		}
		loadConfig.put("defaultScriptingLanguage", defaultScriptingLanguage);

		// local mode
		boolean localMode = false;
		String localModeStr = Utility.getDIHelperProperty(Constants.LOCAL_DEPLOYMENT);
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

		// some initial pipeline / widget-menu filtering
		loadConfig.put("pipelineLandingFilter", Utility.getApplicationPipelineLandingFilter());
		loadConfig.put("pipelineSourceFilter", Utility.getApplicationPipelineSourceFilter());
		loadConfig.put("widgetTabShareExportList", Utility.getApplicationWidgetTabShareExportList());
//		loadConfig.put("widgetTabExportDashboard", Utility.getApplicationWidgetTabExportDashboard());
		loadConfig.put("adminOnlyViewMenuBarFlag", Utility.getAdminOnlyViewMenuBarFlag());
		loadConfig.put("adminOnlyNonAprrovedFlag", Utility.getAdminOnlyNonApprovedFlag());

		loadConfig.put("applicationUrl", Utility.getApplicationUrl());
		ServerConfigurationResource.config = loadConfig;
	}

	private static Map<String, Object> getConfiguration(@Context HttpServletRequest request, User user) {
		HttpSession session = request.getSession();

		Map<String, Object> clientConfig = new HashMap<>();
		clientConfig.putAll(config);
		// session timeout
		// in case we have different timeout for the admin
		// we have this grab for this session what the timeout value is
		clientConfig.put("timeout", (double) session.getMaxInactiveInterval() / 60);
		// append values that can change without restarting the server
		// logins allowed
		clientConfig.put("loginsAllowed", SocialPropertiesUtil.getInstance().getLoginsAllowed());
		// connections allowed
		clientConfig.put("connectionsAllowed", SocialPropertiesUtil.getInstance().getConnectionsAllowed());
		// get a list of all the logins and the display name and if it is oauth
		clientConfig.put("availableProviders", SocialPropertiesUtil.getInstance().getAvailableProviders());
		// get a list of all the resource providers and the display name if it is oauth
		clientConfig.put("availableResourceProviders",
				SocialPropertiesUtil.getInstance().getAvailableResourceProviders());
		// is native registration allowed
		clientConfig.put("nativeRegistration", SocialPropertiesUtil.getInstance().isNativeRegistrationAllowed());

		// password requirements
		try {
			clientConfig.put("passwordRequirements", PasswordRequirements.getInstance().getAllPasswordRequirements());
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
		}
		// current logins
		// TODO: added 2022-02-25
		// TODO: should move away from logins cause sometimes people are using this as
		// if the name is the ID
		// TODO: but not sure where this is all happening, so sending both keys for now
		clientConfig.put("logins", User.getLoginNames(user));
		clientConfig.put("loginDetails", User.getLoginDetails(user));
		// current resource connections
		clientConfig.put("connections", User.getConnectionsNames(user));
		clientConfig.put("connectionDetails", User.getConnectionDetails(user));
		// themes
		clientConfig.put("theme", AdminThemeUtils.getActiveAdminTheme());
		// add if we are using csrf
		clientConfig.put("csrf", Boolean.parseBoolean(session.getAttribute("csrf") + ""));
		// add metakey options
		clientConfig.put("databaseMetaKeys", SecurityEngineUtils.getMetakeyOptions(null));
		clientConfig.put("engineMetaKeys", SecurityEngineUtils.getMetakeyOptions(null));
		clientConfig.put("projectMetaKeys", SecurityProjectUtils.getMetakeyOptions(null));
		clientConfig.put("insightMetaKeys", SecurityInsightUtils.getMetakeyOptions(null));
		// current date
		clientConfig.put("systemDate", new SemossDate(Utility.getCurrentZonedDateTimeUTC()));
		// do not keep this session
		// if no user and it is new
		if (user == null && (session.isNew() || request.isRequestedSessionIdValid())) {
			session.invalidate();
		}

		return clientConfig;
	}

	@GET
	@Path("/")
	@Produces("application/json")
	public Response getServerConfig(@Context HttpServletRequest request, @Context HttpServletResponse response,
			@Context FilterChain filterChain) {
		List<NewCookie> newCookies = new ArrayList<>();

		try {
			ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			// this errors when no user exists in the session
			// should happen every time this is called
			// since FE only calls this method on browser startup
			// clean up any invalid cookies on the browser
			WebUtility.expireSessionCookies(request, newCookies);
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
