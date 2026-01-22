/*******************************************************************************
 * Copyright 2015 SEMOSS.ORG
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
package prerna.semoss.web.services.local;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;

import org.apache.http.client.ClientProtocolException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.owasp.encoder.Encode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.SyncUserAppsThread;
import prerna.auth.User;
import prerna.auth.external.ExternalAuthorizationHelper;
import prerna.auth.mcp.MCPAuthorizationCodeStore;
import prerna.auth.mcp.MCPTokenStore;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAPIUserUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityNativeUserUtils;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.auth.utils.SecurityUserUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.io.connector.GenericTokenFiller;
import prerna.io.connector.github.GithubTokenFiller;
import prerna.io.connector.google.GoogleTokenFiller;
import prerna.io.connector.ms.MicrosoftTokenFiller;
import prerna.io.connector.okta.OktaTokenFiller;
import prerna.io.connector.surveymonkey.MonkeyProfile;
import prerna.sablecc2.om.execptions.SemossPixelException;
import prerna.security.HttpHelperUtility;
import prerna.usertracking.UserTrackingUtils;
import prerna.util.BeanFiller;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;
import prerna.util.Utility;
import prerna.util.git.GitRepoUtils;
import prerna.util.ldap.ILdapAuthenticator;
import prerna.util.ldap.LDAPPasswordChangeRequiredException;
import prerna.util.linotp.LinOTPResponse;
import prerna.util.linotp.LinOTPUtil;
import prerna.web.conf.DBLoader;
import prerna.web.conf.UserSessionLoader;
import prerna.web.services.util.WebUtility;
import waffle.servlet.WindowsPrincipal;

@Path("/auth")
@PermitAll
public class UserResource {

	private static final Logger classLogger = LogManager.getLogger(UserResource.class);

	private static final String CUSTOM_REDIRECT_SESSION_KEY = "custom_redirect";

	private static SocialPropertiesUtil socialData = null;
	static {
		socialData = SocialPropertiesUtil.getInstance();
	}

	private static final Charset UTF8 = Charset.forName("UTF-8");

	@GET
	@Path("/logins")
	public Response getAllLogins(@Context HttpServletRequest request) {
		List<NewCookie> newCookies = new ArrayList<>();
		HttpSession session = request.getSession(false);
		User semossUser = null;
		if (session != null) {
			semossUser = (User) session.getAttribute(Constants.SESSION_USER);
		}

		if (semossUser == null) {
			// not authenticated
			// remove any cookies we shouldn't have
			WebUtility.expireSessionCookies(request, newCookies);

			if (session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
				session.invalidate();
			}
		}

		Map<String, String> retMap = User.getLoginNames(semossUser);
		return WebUtility.getResponse(retMap, 200, newCookies.toArray(new NewCookie[] {}));
	}

	@GET
	@Produces("application/json")
	@Path("/logout/{provider}")
	public Response logout(@PathParam("provider") String provider,
			@QueryParam("disableRedirect") boolean disableRedirect, @Context HttpServletRequest request,
			@Context HttpServletResponse response) throws IOException {
		boolean noUser = false;
		boolean removed = false;

		provider = WebUtility.inputSanitizer(provider);

		HttpSession session = request.getSession();
		User thisUser = (User) session.getAttribute(Constants.SESSION_USER);
		if (thisUser == null) {
			Map<String, Object> ret = new HashMap<>();
			ret.put("success", false);
			ret.put(Constants.ERROR_MESSAGE, "No user is currently logged in the session");
			return WebUtility.getResponse(ret, 400);
		}

		// log the user logout
		classLogger.info("User is logging out of provider " + provider);
		provider = WebUtility.inputSanitizer(provider);
		if (provider.equalsIgnoreCase("ALL")) {
			removed = true;
			noUser = true;
		} else {
			AuthProvider token = AuthProvider.valueOf(provider.toUpperCase());
			String assetEngineId = null;
			String workspaceEngineId = null;

			// TODO: what does this part do?
			// TODO: feel like when logout need to adjust the asset id
			if (thisUser.getLogins().size() == 1) {
				thisUser.getAssetProjectId(token);
				thisUser.getWorkspaceProjectId(token);
			}
			removed = thisUser.dropAccessToken(token);
			if (thisUser.getLogins().isEmpty()) {
				noUser = true;
			} else {
				request.getSession().setAttribute(Constants.SESSION_USER, thisUser);

				SyncUserAppsThread sync = new SyncUserAppsThread(workspaceEngineId, assetEngineId);
				Thread t = new Thread(sync);
				t.start();

				// put the new map for the user space
				session.setAttribute(Constants.USER_WORKSPACE_IDS, thisUser.getWorkspaceEngineMap());
				session.setAttribute(Constants.USER_ASSET_IDS, thisUser.getAssetEngineMap());
			}
		}

		List<NewCookie> nullCookies = null;
		if (noUser) {
			// when we call session.invalidate() the UserSessionLoader will properly log is
			// user logout or tomcat ending
			session.setAttribute(UserSessionLoader.IS_USER_LOGOUT, true);
			classLogger.info("User has logged out from all providers in the session");
			// well, you have logged out and we always require a login
			// so i will redirect you by default unless you specifically say not to
			if (!disableRedirect) {
				response.setStatus(302);

				String customUrl = DBLoader.getCustomLogoutUrl();
				if (customUrl != null && !customUrl.isEmpty()) {
					response.setHeader("redirect", customUrl);
					response.sendError(302, "Need to redirect to " + customUrl);
				} else {
					String redirectUrl = WebUtility.inputSanitizer(request.getHeader("referer"));
					if (DBLoader.useLogoutPage()) {
						String scheme = WebUtility.inputSanitizer(request.getScheme());
						if (!scheme.trim().equalsIgnoreCase("https") && !scheme.trim().equalsIgnoreCase("http")) {
							throw new IllegalArgumentException("scheme is invalid, please input proper scheme");
						}
						String serverName = WebUtility.inputSanitizer(request.getServerName()); // hostname.com
						int serverPort = request.getServerPort(); // 8080
						String contextPath = WebUtility.inputSanitizer(request.getContextPath()); // /Monolith

						redirectUrl = "";
						redirectUrl += scheme + "://" + serverName;
						if (serverPort != 80 && serverPort != 443) {
							redirectUrl += ":" + serverPort;
						}
						redirectUrl += contextPath + "/logout/";
						response.setHeader("redirect", redirectUrl);
						response.sendError(302, "Need to redirect to " + redirectUrl);
					} else {
						redirectUrl = redirectUrl + WebUtility.determineLoginExtension(request);
						String encodedRedirectUrl = Encode.forHtml(redirectUrl);
						response.setHeader("redirect", encodedRedirectUrl);
						response.sendError(302, "Need to redirect to " + encodedRedirectUrl);
					}
				}
			}

			// remove the cookie from the browser
			// for the session id
			classLogger.info("Removing session token");
			nullCookies = new ArrayList<>();
			WebUtility.expireSessionCookies(request, nullCookies);
			// invalidate the session
			session.invalidate();
		}

		Map<String, Boolean> ret = new HashMap<>();
		ret.put("success", removed);
		if (nullCookies == null) {
			return WebUtility.getResponse(ret, 200);
		}

		return WebUtility.getResponseNoCache(ret, 200, nullCookies.toArray(new NewCookie[] {}));
	}

	/**
	 * Method to add an access token to a user
	 * 
	 * @param token
	 * @param request
	 */
	public static void addAccessToken(AccessToken token, HttpServletRequest request, boolean autoAdd) {
		HttpSession session = request.getSession();
		User semossUser = (User) session.getAttribute(Constants.SESSION_USER);
		// all of this is now in the user
		boolean firstLogin = semossUser == null;
		if (firstLogin) {
			semossUser = new User();
			session.setAttribute(Constants.SESSION_USER_ID_LOG, token.getId());
		}
		// add new users into the database
		if (autoAdd) {
			SecurityUpdateUtils.addOAuthUser(token);
		}
		// validate the user's login
		// that they are not locked
		// and to update the last login date
		try {
			SecurityUpdateUtils.validateUserLogin(token);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
		}
		semossUser.setAccessToken(token);
		semossUser.setAnonymous(false);

		// also set the user metadata
		SecurityUserUtils.loadUserMetadata(token);

		session.setAttribute(Constants.SESSION_USER, semossUser);
		WebUtility.loggingContextLoginEvent(session);

		UserResource.userTrackingLogin(request, semossUser, token.getProvider());

		// log the user login
		classLogger.info("User is logging in with provider " + token.getProvider());

		// only for first login
		// lets see if there is an external auth
		// that we should be loading
		if (firstLogin && Boolean
				.parseBoolean(Utility.getDIHelperProperty(Constants.EXTERNAL_PERMISSION_MANAGEMENT_ENABLED) + "")) {
			try {
				ExternalAuthorizationHelper.updateEnginePermissionsBasedOnApiCall(semossUser);
			} catch (Exception e) {
				classLogger.error(Constants.STACKTRACE, e);
			}
		}
	}

	public static void userTrackingLogin(HttpServletRequest request, User semossUser, AuthProvider ap) {
		String ip = WebUtility.inputSanitizer(ResourceUtility.getClientIp(request));
		if (request.getSession() != null && request.getSession().getId() != null) {
			UserTrackingUtils.registerLogin(request.getSession().getId(), ip, semossUser, ap);
		} else {
			UserTrackingUtils.registerLogin("NO_SESSION_ID", ip, semossUser, ap);
		}
	}

	//////////////////////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////////////////////

	/**
	 * User info method calls
	 */

	/**
	 * Gets user info for GoogleDrive
	 */
	@GET
	@Produces("application/json")
	@Path("/userinfo/google")
	public Response userinfoGoogle(@Context HttpServletRequest request) {
		Map<String, String> ret = new HashMap<>();
		HttpSession session = request.getSession(false);
		User semossUser = null;
		if (session != null) {
			semossUser = (User) session.getAttribute(Constants.SESSION_USER);
		}

		if (semossUser == null) {
			List<NewCookie> newCookies = new ArrayList<>();
			// not authenticated
			// remove any cookies we shouldn't have
			WebUtility.expireSessionCookies(request, newCookies);

			if (session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
				session.invalidate();
			}

			ret.put(Constants.ERROR_MESSAGE, "Log into your Google account");
			return WebUtility.getResponseNoCache(ret, 200, newCookies.toArray(new NewCookie[] {}));
		}

		String[] beanProps = { "name", "profile" };
		String jsonPattern = "[name, picture]";
		String accessString = null;
		try {
			AccessToken googleToken = semossUser.getAccessToken(AuthProvider.GOOGLE);
			accessString = googleToken.getAccess_token();
		} catch (Exception e) {
			ret.put(Constants.ERROR_MESSAGE, "Log into your Google account");
			return WebUtility.getResponse(ret, 200);
		}

		String url = "https://www.googleapis.com/oauth2/v3/userinfo";
		Map<String, Object> params = new HashMap<>();
		params.put("access_token", accessString);
		params.put("alt", "json");

		String output = HttpHelperUtility.makeGetCall(url, accessString, params, true);
		AccessToken accessToken2 = (AccessToken) BeanFiller.fillFromJson(output, jsonPattern, beanProps,
				new AccessToken());
		try {
			ret.put("name", accessToken2.getName());
			if (accessToken2.getProfile() != null) {
				ret.put("picture", accessToken2.getProfile());
			}
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
		}

		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Gets user info for OneDrive
	 */
	@GET
	@Produces("application/json")
	@Path("/userinfo/ms")
	@Deprecated
	public Response userinfoMs(@Context HttpServletRequest request) {
		return userinfoMicrosoft(request);
	}

	@GET
	@Produces("application/json")
	@Path("/userinfo/microsoft")
	public Response userinfoMicrosoft(@Context HttpServletRequest request) {
		Map<String, String> ret = new HashMap<>();
		HttpSession session = request.getSession(false);
		User semossUser = null;
		if (session != null) {
			semossUser = (User) session.getAttribute(Constants.SESSION_USER);
		}

		if (semossUser == null) {
			List<NewCookie> newCookies = new ArrayList<>();
			// not authenticated
			// remove any cookies we shouldn't have
			WebUtility.expireSessionCookies(request, newCookies);

			if (session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
				session.invalidate();
			}

			ret.put(Constants.ERROR_MESSAGE, "Log into your Microsoft account");
			return WebUtility.getResponseNoCache(ret, 200, newCookies.toArray(new NewCookie[] {}));
		}

		String[] beanProps = { "name" };
		String jsonPattern = "[displayName]";

		String accessString = null;
		try {
			AccessToken msToken = semossUser.getAccessToken(AuthProvider.MICROSOFT);
			accessString = msToken.getAccess_token();
			String url = "https://graph.microsoft.com/v1.0/me/";
			String output = HttpHelperUtility.makeGetCall(url, accessString, null, true);
			AccessToken accessToken2 = (AccessToken) BeanFiller.fillFromJson(output, jsonPattern, beanProps,
					new AccessToken());
			String name = accessToken2.getName();
			ret.put("name", name);
			return WebUtility.getResponse(ret, 200);

		} catch (Exception e) {
			ret.put(Constants.ERROR_MESSAGE, "Log into your Microsoft account");
			return WebUtility.getResponse(ret, 200);
		}
	}

	/**
	 * Gets user info for ADFS
	 */
	@GET
	@Produces("application/json")
	@Path("/userinfo/adfs")
	public Response userinfoADFS(@Context HttpServletRequest request) {
		Map<String, String> ret = new HashMap<>();
		HttpSession session = request.getSession(false);
		User semossUser = null;
		if (session != null) {
			semossUser = (User) session.getAttribute(Constants.SESSION_USER);
		}

		if (semossUser == null) {
			List<NewCookie> newCookies = new ArrayList<>();
			// not authenticated
			// remove any cookies we shouldn't have
			WebUtility.expireSessionCookies(request, newCookies);

			if (session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
				session.invalidate();
			}

			ret.put(Constants.ERROR_MESSAGE, "Log into your ADFS account");
			return WebUtility.getResponseNoCache(ret, 200, newCookies.toArray(new NewCookie[] {}));
		}

		String prefix = "adfs_";
		String beanProps = socialData.getProperty(prefix + "beanProps");
		String jsonPattern = socialData.getProperty(prefix + "jsonPattern");

		String[] beanPropsArr = beanProps.split(",", -1);

		String accessString = null;
		try {
			AccessToken adfsToken = semossUser.getAccessToken(AuthProvider.ADFS);
			accessString = adfsToken.getAccess_token();
			String json = decodeTokenPayload(adfsToken.getAccess_token());
			adfsToken = (AccessToken) BeanFiller.fillFromJson(json, jsonPattern, beanPropsArr, adfsToken);
			String name = adfsToken.getName();
			ret.put("name", name);

			return WebUtility.getResponse(ret, 200);
		} catch (Exception e) {
			ret.put(Constants.ERROR_MESSAGE, "Log into your ADFS account");
			return WebUtility.getResponse(ret, 200);
		}
	}

	/**
	 * Gets user info for DropBox
	 */
	@GET
	@Produces("application/json")
	@Path("/userinfo/dropbox")
	public Response userinfoDropbox(@Context HttpServletRequest request) {
		Map<String, String> ret = new HashMap<>();
		HttpSession session = request.getSession(false);
		User semossUser = null;
		if (session != null) {
			semossUser = (User) session.getAttribute(Constants.SESSION_USER);
		}

		if (semossUser == null) {
			List<NewCookie> newCookies = new ArrayList<>();
			// not authenticated
			// remove any cookies we shouldn't have
			WebUtility.expireSessionCookies(request, newCookies);

			if (session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
				session.invalidate();
			}

			ret.put(Constants.ERROR_MESSAGE, "Log into your DropBox account");
			return WebUtility.getResponseNoCache(ret, 200, newCookies.toArray(new NewCookie[] {}));
		}

		String[] beanProps = { "name", "profile" }; // add is done when you have a list
		String jsonPattern = "[name.display_name, profile_photo_url]";
		String accessString = null;
		try {
			AccessToken dropToken = semossUser.getAccessToken(AuthProvider.DROPBOX);
			accessString = dropToken.getAccess_token();
		} catch (Exception e) {
			ret.put(Constants.ERROR_MESSAGE, "Log into your DropBox account");
			return WebUtility.getResponse(ret, 200);
		}
		String url = "https://api.dropboxapi.com/2/users/get_current_account";

		String output = HttpHelperUtility.makePostCall(url, accessString, null, true);
		AccessToken accessToken2 = (AccessToken) BeanFiller.fillFromJson(output, jsonPattern, beanProps,
				new AccessToken());
		try {
			if (accessToken2.getProfile() == null || accessToken2.getProfile().equalsIgnoreCase("null")) {
				ret.put("picture", "");
			} else {
				ret.put("picture", accessToken2.getProfile());
			}
			ret.put("name", accessToken2.getName());
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
		}
		return WebUtility.getResponse(ret, 200);

	}

	/**
	 * Gets user info for Github
	 */
	@GET
	@Produces("application/json")
	@Path("/userinfo/github")
	public Response userinfoGithub(@Context HttpServletRequest request) {
		Map<String, String> ret = new HashMap<>();
		HttpSession session = request.getSession(false);
		User semossUser = null;
		if (session != null) {
			semossUser = (User) session.getAttribute(Constants.SESSION_USER);
		}

		if (semossUser == null) {
			List<NewCookie> newCookies = new ArrayList<>();
			// not authenticated
			// remove any cookies we shouldn't have
			WebUtility.expireSessionCookies(request, newCookies);

			if (session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
				session.invalidate();
			}

			ret.put(Constants.ERROR_MESSAGE, "Log into your Github account");
			return WebUtility.getResponseNoCache(ret, 200, newCookies.toArray(new NewCookie[] {}));
		}

		String[] beanProps = { "name", "profile" }; // add is done when you have a list
		String jsonPattern = "[name,login]";

		String accessString = null;
		try {
			AccessToken gitToken = semossUser.getAccessToken(AuthProvider.GITHUB);
			accessString = gitToken.getAccess_token();
		} catch (Exception e) {
			ret.put(Constants.ERROR_MESSAGE, "Log into your Github account");
			return WebUtility.getResponse(ret, 200);
		}

		String url = "https://api.github.com/user";

		String output = HttpHelperUtility.makeGetCall(url, accessString, null, true);
		AccessToken accessToken2 = (AccessToken) BeanFiller.fillFromJson(output, jsonPattern, beanProps,
				new AccessToken());
		try {
			ret.put("name", accessToken2.getProfile());
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
		}
		return WebUtility.getResponse(ret, 200);
	}

	@GET
	@Produces("application/json")
	@Path("/userinfo/okta")
	public Response userinfoOkta(@Context HttpServletRequest request) {
		String prefix = "okta_";

		Map<String, String> ret = new HashMap<>();
		HttpSession session = request.getSession(false);
		User semossUser = null;
		if (session != null) {
			semossUser = (User) session.getAttribute(Constants.SESSION_USER);
		}

		if (semossUser == null) {
			List<NewCookie> newCookies = new ArrayList<>();
			// not authenticated
			// remove any cookies we shouldn't have
			WebUtility.expireSessionCookies(request, newCookies);

			if (session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
				session.invalidate();
			}

			ret.put(Constants.ERROR_MESSAGE, "Log into your Okta account");
			return WebUtility.getResponseNoCache(ret, 200, newCookies.toArray(new NewCookie[] {}));
		}

		String[] beanPropsArr = { "id", "name", "email", "phone" };
		String jsonPattern = "[sub,name,email,phone_number]";

		String accessString = null;
		try {
			AccessToken accessToken = semossUser.getAccessToken(AuthProvider.OKTA);
			accessString = accessToken.getAccess_token();
			String userInfoURL = socialData.getProperty(prefix + "userinfo_url");
			String socialBeanProps = socialData.getProperty(prefix + "beanProps");

			if (socialBeanProps != null && !socialBeanProps.trim().isEmpty()) {
				beanPropsArr = socialBeanProps.split(",", -1);
			}

			String socialJsonPattern = socialData.getProperty(prefix + "jsonPattern");

			if (socialJsonPattern != null && !socialJsonPattern.trim().isEmpty()) {
				jsonPattern = socialJsonPattern;
			}

			String output = HttpHelperUtility.makeGetCall(userInfoURL, accessString, null, true);
			AccessToken accessToken2 = (AccessToken) BeanFiller.fillFromJson(output, jsonPattern, beanPropsArr,
					new AccessToken());
			String name = accessToken2.getName();
			ret.put("name", name);
			return WebUtility.getResponse(ret, 200);
		} catch (Exception e) {
			ret.put(Constants.ERROR_MESSAGE, "Log into your Okta account");
			return WebUtility.getResponse(ret, 200);
		}
	}

	/**
	 * Gets user info for Generic Providers
	 */
	@GET
	@Produces("application/json")
	@Path("/userinfo/{provider}")
	public Response userinfoGeneric(@PathParam("provider") String provider, @Context HttpServletRequest request) {
		provider = WebUtility.inputSanitizer(provider);

		AuthProvider providerEnum = AuthProvider.getProviderFromString(provider.toUpperCase());
		Map<String, String> ret = new HashMap<>();
		HttpSession session = request.getSession(false);

		User semossUser = null;
		if (session != null) {
			semossUser = (User) session.getAttribute(Constants.SESSION_USER);
		}

		if (semossUser == null) {
			List<NewCookie> newCookies = new ArrayList<>();
			// not authenticated
			// remove any cookies we shouldn't have
			WebUtility.expireSessionCookies(request, newCookies);

			if (session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
				session.invalidate();
			}

			ret.put(Constants.ERROR_MESSAGE, "Log into your " + providerEnum.name() + " account");
			return WebUtility.getResponseNoCache(ret, 200, newCookies.toArray(new NewCookie[] {}));
		}

		String prefix = provider + "_";
		String userInfoURL = socialData.getProperty(prefix + "userinfo_url");
		// "name","id","email"
		String beanProps = socialData.getProperty(prefix + "beanProps");
		String jsonPattern = socialData.getProperty(prefix + "jsonPattern");

		String[] beanPropsArr = beanProps.split(",", -1);

		String accessString = null;
		try {
			AccessToken genericToken = semossUser.getAccessToken(providerEnum);
			accessString = genericToken.getAccess_token();
			// String url = "https://graph.microsoft.com/v1.0/me/";
			String output = HttpHelperUtility.makeGetCall(userInfoURL, accessString, null, true);
			AccessToken accessToken2 = (AccessToken) BeanFiller.fillFromJson(output, jsonPattern, beanPropsArr,
					new AccessToken());
			String name = accessToken2.getName();
			ret.put("name", name);

			return WebUtility.getResponse(ret, 200);
		} catch (Exception e) {
			ret.put(Constants.ERROR_MESSAGE, "Log into your " + providerEnum.toString() + " account");
			return WebUtility.getResponse(ret, 200);
		}
	}

	//////////////////////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////////////////////

	/**
	 * Login methods + redirect methods
	 */

	/**
	 * Logs user in through salesforce
	 */
	@GET
	@Produces("application/json")
	@Path("/login/salesforce")
	public Response loginSalesforce(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws IOException {
		if (socialData.getLoginsAllowed().get("salesforce") == null
				|| !socialData.getLoginsAllowed().get("salesforce")) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "Salesforce login is not allowed");
			return WebUtility.getResponse(ret, 400);
		}
		/*
		 * Try to log in the user If they are not logged in Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if (session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = WebUtility.cleanHttpResponse(request.getParameter("redirect"));
		if (customRedirect != null && !customRedirect.isEmpty()) {
			if (session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}
		String queryString = WebUtility.encodeHTTPUri(request.getQueryString());
		if (queryString != null && queryString.contains("code")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.SALESFORCE) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				// oauth code should match [ -~]+ (1 or more ascii)
				// https://www.rfc-editor.org/rfc/rfc6749#appendix-A.11
				String code = URLDecoder.decode(outputs[0]);
				if (code.matches("[ -~]+")) {
					String prefix = "salesforce_";
					String clientId = socialData.getProperty(prefix + "client_id");
					String clientSecret = socialData.getProperty(prefix + "secret_key");
					String redirectUri = socialData.getProperty(prefix + "redirect_uri");
					boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

					if (classLogger.isDebugEnabled()) {
						classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
					}

					Map<String, String> params = new HashMap<>();
					params.put("client_id", clientId);
					params.put("grant_type", "authorization_code");
					params.put("redirect_uri", redirectUri);
					params.put("code", code);
					params.put("client_secret", clientSecret);

					String url = "https://login.salesforce.com/services/oauth2/token";

					AccessToken accessToken = HttpHelperUtility.getAccessToken(url, params, true, true);
					if (accessToken == null) {
						// not authenticated
						response.setStatus(302);
						response.sendRedirect(getSalesforceRedirect(request));
						return null;
					}
					accessToken.setProvider(AuthProvider.SALESFORCE);
					addAccessToken(accessToken, request, autoAdd);

					if (classLogger.isDebugEnabled()) {
						classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
					}
				}
			}
		}

		// grab the user again
		if (session != null || (session = request.getSession(false)) != null) {
			userObj = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if (userObj == null || userObj.getAccessToken(AuthProvider.SALESFORCE) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getSalesforceRedirect(request));
			return null;
		}

		setMainPageRedirect(request, response);
		return null;
	}

	private String getSalesforceRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "salesforce_";
		String clientId = socialData.getProperty(prefix + "client_id");
		String redirectUri = socialData.getProperty(prefix + "redirect_uri");

		String redirectUrl = "https://login.salesforce.com/services/oauth2/authorize?" + "client_id=" + clientId
				+ "&response_type=code" + "&redirect_uri=" + redirectUri + "&scope=" + URLEncoder.encode("api", UTF8);

		if (classLogger.isDebugEnabled()) {
			classLogger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
		}
		return redirectUrl;
	}

	/**
	 * Logs user in through survey monkey
	 */
	@GET
	@Produces("application/json")
	@Path("/login/surveymonkey")
	public Response loginSurveyMonkey(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws IOException {
		if (socialData.getLoginsAllowed().get("surveymonkey") == null
				|| !socialData.getLoginsAllowed().get("surveymonkey")) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "Surveymonkey login is not allowed");
			return WebUtility.getResponse(ret, 400);
		}
		/*
		 * Try to log in the user If they are not logged in Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if (session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = WebUtility.cleanHttpResponse(request.getParameter("redirect"));
		if (customRedirect != null && !customRedirect.isEmpty()) {
			if (session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}
		String queryString = WebUtility.encodeHTTPUri(request.getQueryString());
		if (queryString != null && queryString.contains("code")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.SURVEYMONKEY) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				// oauth code should match [ -~]+ (1 or more ascii)
				// https://www.rfc-editor.org/rfc/rfc6749#appendix-A.11
				String code = URLDecoder.decode(outputs[0]);
				if (code.matches("[ -~]+")) {
					String prefix = "surveymonkey_";
					String clientId = socialData.getProperty(prefix + "client_id");
					String clientSecret = socialData.getProperty(prefix + "secret_key");
					String redirectUri = socialData.getProperty(prefix + "redirect_uri");
					boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

					if (classLogger.isDebugEnabled()) {
						classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
					}

					Map<String, String> params = new HashMap<>();
					params.put("client_id", clientId);
					params.put("grant_type", "authorization_code");
					params.put("redirect_uri", redirectUri);
					params.put("code", code);
					params.put("client_secret", clientSecret);

					String url = "https://api.surveymonkey.com/oauth/token";

					AccessToken accessToken = HttpHelperUtility.getAccessToken(url, params, true, true);
					if (accessToken == null) {
						// not authenticated
						response.setStatus(302);
						response.sendRedirect(getSurveyMonkeyRedirect(request));
						return null;
					}
					accessToken.setProvider(AuthProvider.SURVEYMONKEY);
					MonkeyProfile.fillAccessToken(accessToken, null);
					addAccessToken(accessToken, request, autoAdd);

					if (classLogger.isDebugEnabled()) {
						classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
					}
				}
			}
		}

		// grab the user again
		if (session != null || (session = request.getSession(false)) != null) {
			userObj = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if (userObj == null || userObj.getAccessToken(AuthProvider.SURVEYMONKEY) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getSurveyMonkeyRedirect(request));
			return null;
		}

		setMainPageRedirect(request, response);
		return null;
	}

	private String getSurveyMonkeyRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "surveymonkey_";
		String clientId = socialData.getProperty(prefix + "client_id");
		String redirectUri = socialData.getProperty(prefix + "redirect_uri");

		String redirectUrl = "https://api.surveymonkey.com/oauth/authorize?" + "client_id=" + clientId
				+ "&response_type=code" + "&redirect_uri=" + redirectUri;

		if (classLogger.isDebugEnabled()) {
			classLogger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
		}
		return redirectUrl;
	}

	/**
	 * Logs user in through git
	 * https://developer.github.com/apps/building-oauth-apps/authorization-options-for-oauth-apps/
	 */
	@GET
	@Produces("application/json")
	@Path("/login/github")
	public Response loginGithub(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws IOException {
		if (socialData.getLoginsAllowed().get("github") == null || !socialData.getLoginsAllowed().get("github")) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "GitHub login is not allowed");
			return WebUtility.getResponse(ret, 400);
		}
		/*
		 * Try to log in the user If they are not logged in Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if (session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = WebUtility.cleanHttpResponse(request.getParameter("redirect"));
		if (customRedirect != null && !customRedirect.isEmpty()) {
			if (session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}

		String queryString = WebUtility.encodeHTTPUri(request.getQueryString());
		if (queryString != null && queryString.contains("code")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.GITHUB) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				// oauth code and state should match [ -~]+ (1 or more ascii)
				// https://www.rfc-editor.org/rfc/rfc6749#appendix-A.5
				// https://www.rfc-editor.org/rfc/rfc6749#appendix-A.11
				String code = URLDecoder.decode(outputs[0]);
				String state = URLDecoder.decode(outputs[1]);
				if (code.matches("[ -~]+") && state.matches("[ -~]+")) {
					String prefix = "github_";
					String clientId = socialData.getProperty(prefix + "client_id");
					String clientSecret = socialData.getProperty(prefix + "secret_key");
					String redirectUri = socialData.getProperty(prefix + "redirect_uri");
					boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

					if (classLogger.isDebugEnabled()) {
						classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
					}

					Map<String, String> params = new HashMap<>();
					params.put("client_id", clientId);
					params.put("redirect_uri", redirectUri);
					params.put("code", code);
					params.put("state", state);
					params.put("client_secret", clientSecret);

					String url = "https://github.com/login/oauth/access_token";

					AccessToken accessToken = HttpHelperUtility.getAccessToken(url, params, false, true);
					if (accessToken == null) {
						// not authenticated
						response.setStatus(302);
						response.sendRedirect(getGithubRedirect(request));
						return null;
					}
					accessToken.setProvider(AuthProvider.GITHUB);

					try {
						GitRepoUtils.addCertForDomain(url);
					} catch (Exception e) {
						classLogger.error(Constants.STACKTRACE, e);
					}
					// add specific Git values
					GithubTokenFiller profiler = new GithubTokenFiller();
					profiler.fillAccessToken(accessToken, null, null, null, null);

					addAccessToken(accessToken, request, autoAdd);

					if (classLogger.isDebugEnabled()) {
						classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
					}
				}
			}
		}

		// grab the user again
		if (session != null || (session = request.getSession(false)) != null) {
			userObj = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if (userObj == null || userObj.getAccessToken(AuthProvider.GITHUB) == null) {
			// not authenticated
			try {
				GitRepoUtils.addCertForDomain("https://github.com");
			} catch (Exception e) {
				classLogger.error(Constants.STACKTRACE, e);
			}
			response.setStatus(302);
			response.sendRedirect(getGithubRedirect(request));
			return null;
		}

		setMainPageRedirect(request, response);
		return null;
	}

	private String getGithubRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "github_";
		String clientId = socialData.getProperty(prefix + "client_id");
		String redirectUri = socialData.getProperty(prefix + "redirect_uri");
		String scope = socialData.getProperty(prefix + "scope");

		String redirectUrl = "https://github.com/login/oauth/authorize?" + "client_id=" + clientId + "&redirect_uri="
				+ redirectUri + "&state=" + UUID.randomUUID().toString() + "&allow_signup=true" + "&scope="
				+ URLEncoder.encode(scope, UTF8);

		if (classLogger.isDebugEnabled()) {
			classLogger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
		}
		return redirectUrl;
	}

	/**
	 * Logs user in through gitlab
	 * https://docs.gitlab.com/13.12/ee/integration/oauth_provider.html
	 * https://docs.gitlab.com/13.12/ee/api/oauth2.html
	 */
	@GET
	@Produces("application/json")
	@Path("/login/gitlab")
	public Response loginGitlab(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws IOException {
		if (socialData.getLoginsAllowed().get("gitlab") == null || !socialData.getLoginsAllowed().get("gitlab")) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "GitLab login is not allowed");
			return WebUtility.getResponse(ret, 400);
		}
		/*
		 * Try to log in the user If they are not logged in Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if (session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = WebUtility.cleanHttpResponse(request.getParameter("redirect"));
		if (customRedirect != null && !customRedirect.isEmpty()) {
			if (session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}

		String queryString = WebUtility.encodeHTTPUri(request.getQueryString());
		String prefix = "gitlab_";

		if (queryString != null && queryString.contains("code")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.GITLAB) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				// oauth code and state should match [ -~]+ (1 or more ascii)
				// https://www.rfc-editor.org/rfc/rfc6749#appendix-A.5
				// https://www.rfc-editor.org/rfc/rfc6749#appendix-A.11
				String code = URLDecoder.decode(outputs[0]);
				String state = URLDecoder.decode(outputs[1]);
				if (code.matches("[ -~]+") && state.matches("[ -~]+")) {
					String clientId = socialData.getProperty(prefix + "client_id");
					String clientSecret = socialData.getProperty(prefix + "secret_key");
					String redirectUri = socialData.getProperty(prefix + "redirect_uri");
					String token_url = socialData.getProperty(prefix + "token_url");
					boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

					if (classLogger.isDebugEnabled()) {
						classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
					}

					Map<String, String> params = new HashMap<>();
					params.put("client_id", clientId);
					params.put("redirect_uri", redirectUri);
					params.put("code", code);
					params.put("state", state);
					params.put("client_secret", clientSecret);
					params.put("grant_type", "authorization_code");

					AccessToken accessToken = HttpHelperUtility.getAccessToken(token_url, params, true, true);
					if (accessToken == null) {
						// not authenticated
						response.setStatus(302);
						response.sendRedirect(getGitlabRedirect(request));
						return null;
					}
					accessToken.setProvider(AuthProvider.GITLAB);

					String beanProps = socialData.getProperty(prefix + "beanProps");
					String jsonPattern = socialData.getProperty(prefix + "jsonPattern");
					String userinfo_url = socialData.getProperty(prefix + "userinfo_url");
					String[] beanPropsArr = beanProps.split(",", -1);

					String output = HttpHelperUtility.makeGetCall(userinfo_url, accessToken.getAccess_token(), null,
							true);
					accessToken = (AccessToken) BeanFiller.fillFromJson(output, jsonPattern, beanPropsArr, accessToken);

					addAccessToken(accessToken, request, autoAdd);

					if (classLogger.isDebugEnabled()) {
						classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
					}
				}
			}
		}

		// grab the user again
		if (session != null || (session = request.getSession(false)) != null) {
			userObj = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if (userObj == null || userObj.getAccessToken(AuthProvider.GITLAB) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getGitlabRedirect(request));
			return null;
		}

		if (Boolean.parseBoolean(socialData.getProperty(prefix + "groups"))) {
			// get groups
			String group_url = socialData.getProperty(prefix + "group_url");
			String groupsJson = HttpHelperUtility.makeGetCall(group_url,
					userObj.getAccessToken(AuthProvider.GITLAB).getAccess_token());
			System.out.println(groupsJson);
			String groupJsonPattern = socialData.getProperty(prefix + "groupJsonPattern");
			// String beanProps = socialData.getProperty(prefix + "groupBeanProps");
			// String[] beanPropsArr = beanProps.split(",", -1);
			Set<String> userGroups = new HashSet<String>();

			JsonNode result = BeanFiller.getJmesResult(groupsJson, groupJsonPattern);
			if ((result instanceof ArrayNode) && result.get(0) instanceof ObjectNode) {
				throw new SemossPixelException("Group result must return flat array. Please check groupJsonPatter");
			}
			for (int inputIndex = 0; result != null && inputIndex < result.size(); inputIndex++) {
				String thisInput = result.get(inputIndex).asText();
				userGroups.add(thisInput);
			}

			userObj.getAccessToken(AuthProvider.GITLAB).setUserGroups(userGroups);
			userObj.getAccessToken(AuthProvider.GITLAB).setUserGroupType(AuthProvider.GITLAB.toString());
		}

		setMainPageRedirect(request, response);
		return null;
	}

	private String getGitlabRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "gitlab_";
		String clientId = socialData.getProperty(prefix + "client_id");
		String redirectUri = socialData.getProperty(prefix + "redirect_uri");
		String scope = socialData.getProperty(prefix + "scope");
		String auth_url = socialData.getProperty(prefix + "auth_url");

		scope = scope.replaceAll(" ", "+");
		String state = UUID.randomUUID().toString();

		String redirectUrl = auth_url + "?" + "client_id=" + clientId + "&response_type=code" + "&redirect_uri="
				+ URLEncoder.encode(redirectUri, UTF8) + "&scope=" + scope + "&state=" + state;

		classLogger.info("Sending redirect.. " + Utility.cleanLogString(redirectUrl));

		if (classLogger.isDebugEnabled()) {
			classLogger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
		}
		return redirectUrl;
	}

	/**
	 * Logs user in through ms
	 */
	@GET
	@Produces("application/json")
	@Path("/login/ms")
	@Deprecated
	public Response loginMS(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws IOException {
		return loginMicrosoft(request, response);
	}

	@GET
	@Produces("application/json")
	@Path("/login/microsoft")
	public Response loginMicrosoft(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws IOException {
		if (socialData.getLoginsAllowed().get("ms") == null || !socialData.getLoginsAllowed().get("ms")) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "Microsoft login is not allowed");
			return WebUtility.getResponse(ret, 400);
		}
		/*
		 * Try to log in the user If they are not logged in Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if (session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = WebUtility.cleanHttpResponse(request.getParameter("redirect"));
		if (customRedirect != null && !customRedirect.isEmpty()) {
			if (session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}
		String queryString = WebUtility.encodeHTTPUri(request.getQueryString());
		if (queryString != null && queryString.contains("code")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.MICROSOFT) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				// oauth code should match [ -~]+ (1 or more ascii)
				// https://www.rfc-editor.org/rfc/rfc6749#appendix-A.11
				String code = URLDecoder.decode(outputs[0]);
				if (code.matches("[ -~]+")) {
					String prefix = "ms_";
					String clientId = socialData.getProperty(prefix + "client_id");
					String clientSecret = socialData.getProperty(prefix + "secret_key");
					String redirectUri = socialData.getProperty(prefix + "redirect_uri");
					String tenant = socialData.getProperty(prefix + "tenant");
					String scope = socialData.getProperty(prefix + "scope");
					String token_url = socialData.getProperty(prefix + "token_url");
					boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));
					boolean login_external_allowed = Boolean
							.parseBoolean(socialData.getProperty(prefix + "login_external"));

					if (classLogger.isDebugEnabled()) {
						classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
					}

					Map<String, String> params = new HashMap<>();
					params.put("client_id", clientId);
					params.put("scope", scope);
					params.put("redirect_uri", redirectUri);
					params.put("code", code);
					params.put("grant_type", "authorization_code");
					params.put("client_secret", clientSecret);

					if (Strings.isNullOrEmpty(token_url)) {
						token_url = "https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/token";
					}

					AccessToken accessToken = HttpHelperUtility.getAccessToken(token_url, params, true, true);
					if (accessToken == null) {
						// not authenticated
						response.setStatus(302);
						response.sendRedirect(getMicrosoftRedirect(request));
						return null;
					}

					accessToken.setProvider(AuthProvider.MICROSOFT);
					MicrosoftTokenFiller profiler = new MicrosoftTokenFiller();
					profiler.fillAccessToken(accessToken, null, null, null, null);
					if (!login_external_allowed) {
						if (accessToken.getName().contains("External")) {
							accessToken = null;
							throw new IllegalArgumentException("External users are not allowed");
						}
					}
					addAccessToken(accessToken, request, autoAdd);

					if (classLogger.isDebugEnabled()) {
						classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
					}
				}
			}
		}

		// grab the user again
		if (session != null || (session = request.getSession(false)) != null) {
			userObj = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if (userObj == null || userObj.getAccessToken(AuthProvider.MICROSOFT) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getMicrosoftRedirect(request));
			return null;
		}

		setMainPageRedirect(request, response);
		return null;
	}

	private String getMicrosoftRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "ms_";
		String clientId = socialData.getProperty(prefix + "client_id");
		String redirectUri = socialData.getProperty(prefix + "redirect_uri");
		String tenant = socialData.getProperty(prefix + "tenant");
		String scope = socialData.getProperty(prefix + "scope"); // need to set this up and reuse
		String auth_url = socialData.getProperty(prefix + "auth_url");

		String state = UUID.randomUUID().toString();

		if (Strings.isNullOrEmpty(auth_url)) {
			auth_url = "https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/authorize";
		}
		String redirectUrl = auth_url + "?" + "client_id=" + clientId + "&response_type=code" + "&redirect_uri="
				+ URLEncoder.encode(redirectUri, UTF8) + "&response_mode=query" + "&scope="
				+ URLEncoder.encode(scope, UTF8) + "&state=" + state;

		if (classLogger.isDebugEnabled()) {
			classLogger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
		}

		return redirectUrl;
	}

	/**
	 * Logs user in through adfs
	 */
	@GET
	@Produces("application/json")
	@Path("/login/adfs")
	public Response loginADFS(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws IOException {
		if (socialData.getLoginsAllowed().get("adfs") == null || !socialData.getLoginsAllowed().get("adfs")) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "ADFS login is not allowed");
			return WebUtility.getResponse(ret, 400);
		}
		/*
		 * Try to log in the user If they are not logged in Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if (session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = WebUtility.cleanHttpResponse(request.getParameter("redirect"));
		if (customRedirect != null && !customRedirect.isEmpty()) {
			if (session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}

		String queryString = WebUtility.encodeHTTPUri(request.getQueryString());
		if (queryString != null && queryString.contains("code")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.ADFS) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				// oauth code should match [ -~]+ (1 or more ascii)
				// https://www.rfc-editor.org/rfc/rfc6749#appendix-A.11
				String code = URLDecoder.decode(outputs[0]);
				if (code.matches("[ -~]+")) {
					String prefix = "adfs_";
					String clientId = socialData.getProperty(prefix + "client_id");
					String clientSecret = socialData.getProperty(prefix + "secret_key");
					String redirectUri = socialData.getProperty(prefix + "redirect_uri");
					String scope = socialData.getProperty(prefix + "scope");
					String token_url = socialData.getProperty(prefix + "token_url");
					boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

					if (classLogger.isDebugEnabled()) {
						classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
					}

					Map<String, String> params = new HashMap<>();
					params.put("client_id", clientId);
					params.put("scope", scope);
					params.put("redirect_uri", redirectUri);
					params.put("code", code);
					params.put("grant_type", "authorization_code");
					params.put("client_secret", clientSecret);

					if (Strings.isNullOrEmpty(token_url)) {
						throw new IllegalArgumentException("Token URL can not be null or empty");
					}

					AccessToken accessToken = HttpHelperUtility.getIdToken(token_url, params, true, true);
					if (accessToken == null) {
						// not authenticated
						response.setStatus(302);
						response.sendRedirect(getADFSRedirect(request));
						return null;
					}
					String json = decodeTokenPayload(accessToken.getAccess_token());

					accessToken.setProvider(AuthProvider.ADFS);
					String beanProps = socialData.getProperty(prefix + "beanProps");
					String jsonPattern = socialData.getProperty(prefix + "jsonPattern");
					String[] beanPropsArr = beanProps.split(",", -1);
					accessToken = (AccessToken) BeanFiller.fillFromJson(json, jsonPattern, beanPropsArr, accessToken);

					addAccessToken(accessToken, request, autoAdd);

					if (classLogger.isDebugEnabled()) {
						classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
					}
				}
			}
		}

		// grab the user again
		if (session != null || (session = request.getSession(false)) != null) {
			userObj = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if (userObj == null || userObj.getAccessToken(AuthProvider.ADFS) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getADFSRedirect(request));
			return null;
		}

		setMainPageRedirect(request, response);
		return null;
	}

	public String decodeTokenPayload(String token) {
		String[] parts = token.split("\\.", 0);

		// for (String part : parts) {
		// byte[] bytes = Base64.getUrlDecoder().decode(part);
		// String decodedString = new String(bytes, StandardCharsets.UTF_8);
		//
		// System.out.println("Decoded: " + decodedString);
		// }
		byte[] bytes = Base64.getUrlDecoder().decode(parts[1]);

		String payload = new String(bytes, StandardCharsets.UTF_8);

		return payload;
	}

	private String getADFSRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "adfs_";
		String clientId = socialData.getProperty(prefix + "client_id");
		String redirectUri = socialData.getProperty(prefix + "redirect_uri");
		String scope = socialData.getProperty(prefix + "scope"); // need to set this up and reuse
		String auth_url = socialData.getProperty(prefix + "auth_url");

		String state = UUID.randomUUID().toString();

		if (Strings.isNullOrEmpty(auth_url)) {
			throw new IllegalArgumentException("A URL can not be null or empty");
		}
		String redirectUrl = auth_url + "?" + "client_id=" + clientId + "&response_type=code" + "&redirect_uri="
				+ URLEncoder.encode(redirectUri, UTF8) + "&response_mode=query" + "&scope="
				+ URLEncoder.encode(scope, UTF8) + "&state=" + state;

		if (classLogger.isDebugEnabled()) {
			classLogger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
		}

		return redirectUrl;
	}

	/**
	 * Logs user in through ms
	 */
	@GET
	@Produces("application/json")
	@Path("/login/okta")
	public Response loginOkta(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws IOException {
		if (socialData.getLoginsAllowed().get("okta") == null || !socialData.getLoginsAllowed().get("okta")) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "Okta login is not allowed");
			return WebUtility.getResponse(ret, 400);
		}
		/*
		 * Try to log in the user If they are not logged in Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if (session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = WebUtility.cleanHttpResponse(request.getParameter("redirect"));
		if (customRedirect != null && !customRedirect.isEmpty()) {
			if (session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}
		String queryString = WebUtility.encodeHTTPUri(request.getQueryString());
		if (queryString != null && queryString.contains("code")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.MICROSOFT) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				// oauth code should match [ -~]+ (1 or more ascii)
				// https://www.rfc-editor.org/rfc/rfc6749#appendix-A.11
				String code = URLDecoder.decode(outputs[0]);
				if (code.matches("[ -~]+")) {
					String prefix = "okta_";
					String clientId = socialData.getProperty(prefix + "client_id");
					String clientSecret = socialData.getProperty(prefix + "secret_key");
					String redirectUri = socialData.getProperty(prefix + "redirect_uri");
					String scope = socialData.getProperty(prefix + "scope");
					String token_url = socialData.getProperty(prefix + "token_url");
					boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

					if (classLogger.isDebugEnabled()) {
						classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
					}

					Map<String, String> params = new HashMap<>();
					params.put("client_id", clientId);
					params.put("scope", scope);
					params.put("redirect_uri", redirectUri);
					params.put("code", code);
					params.put("grant_type", "authorization_code");
					params.put("client_secret", clientSecret);

					AccessToken accessToken = HttpHelperUtility.getAccessToken(token_url, params, true, true);
					if (accessToken == null) {
						// not authenticated
						response.setStatus(302);
						response.sendRedirect(getOktaRedirect(request));
						return null;
					}

					accessToken.setProvider(AuthProvider.OKTA);

					// sub is the unique id for a user in okta
					String userinfo_url = socialData.getProperty(prefix + "userinfo_url");
					String beanProps = socialData.getProperty(prefix + "beanProps");
					String[] beanPropsArr = beanProps.split(",", -1);
					String jsonPattern = socialData.getProperty(prefix + "jsonPattern");

					OktaTokenFiller profiler = new OktaTokenFiller();
					profiler.fillAccessToken(accessToken, userinfo_url, jsonPattern, beanPropsArr, null);
					addAccessToken(accessToken, request, autoAdd);
					if (classLogger.isDebugEnabled()) {
						classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
					}
				}
			}
		}

		// grab the user again
		if (session != null || (session = request.getSession(false)) != null) {
			userObj = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if (userObj == null || userObj.getAccessToken(AuthProvider.OKTA) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getOktaRedirect(request));
			return null;
		}

		setMainPageRedirect(request, response);
		return null;
	}

	private String getOktaRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "okta_";
		String clientId = socialData.getProperty(prefix + "client_id");
		String redirectUri = socialData.getProperty(prefix + "redirect_uri");
		String scope = socialData.getProperty(prefix + "scope"); // need to set this up and reuse
		String auth_url = socialData.getProperty(prefix + "auth_url");
		String state = UUID.randomUUID().toString();

		String redirectUrl = auth_url + "?" + "client_id=" + clientId + "&response_type=code" + "&redirect_uri="
				+ URLEncoder.encode(redirectUri, UTF8) + "&response_mode=query" + "&scope="
				+ URLEncoder.encode(scope, UTF8) + "&state=" + state;

		if (classLogger.isDebugEnabled()) {
			classLogger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
		}

		return redirectUrl;
	}

	/**
	 * Logs user in through siteminder
	 */
	@GET
	@Produces("application/json")
	@Path("/login/siteminder")
	public Response loginSiteminder(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws IOException {
		if (socialData.getLoginsAllowed().get("siteminder") == null
				|| !socialData.getLoginsAllowed().get("siteminder")) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "Siteminder login is not allowed");
			return WebUtility.getResponse(ret, 400);
		}
		/*
		 * Try to log in the user If they are not logged in Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if (session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = WebUtility.cleanHttpResponse(request.getParameter("redirect"));
		if (customRedirect != null && !customRedirect.isEmpty()) {
			if (session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}

		String queryString = WebUtility.encodeHTTPUri(request.getQueryString());
		if (queryString != null && queryString.contains("code")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.SITEMINDER) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				// oauth code should match [ -~]+ (1 or more ascii)
				// https://www.rfc-editor.org/rfc/rfc6749#appendix-A.11
				String code = URLDecoder.decode(outputs[0]);
				if (code.matches("[ -~]+")) {
					String prefix = "siteminder_";
					String clientId = socialData.getProperty(prefix + "client_id");
					String clientSecret = socialData.getProperty(prefix + "secret_key");
					String redirectUri = socialData.getProperty(prefix + "redirect_uri");
					String tenant = socialData.getProperty(prefix + "tenant");
					String scope = socialData.getProperty(prefix + "scope");
					String token_url = socialData.getProperty(prefix + "token_url");
					boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

					if (classLogger.isDebugEnabled()) {
						classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
					}

					Map<String, String> params = new HashMap<>();
					params.put("client_id", clientId);
					params.put("scope", scope);
					params.put("redirect_uri", redirectUri);
					params.put("code", code);
					params.put("grant_type", "authorization_code");
					params.put("client_secret", clientSecret);

					if (Strings.isNullOrEmpty(token_url)) {
						token_url = "https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/token";
					}
					AccessToken accessToken = HttpHelperUtility.getAccessToken(token_url, params, true, true);
					if (accessToken == null) {
						// not authenticated
						response.setStatus(302);
						response.sendRedirect(getSiteminderRedirect(request));
						return null;
					}

					accessToken.setProvider(AuthProvider.SITEMINDER);
					addAccessToken(accessToken, request, autoAdd);
					if (classLogger.isDebugEnabled()) {
						classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
					}
				}
			}
		}

		// grab the user again
		if (session != null || (session = request.getSession(false)) != null) {
			userObj = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if (userObj == null || userObj.getAccessToken(AuthProvider.SITEMINDER) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getSiteminderRedirect(request));
			return null;
		}

		setMainPageRedirect(request, response);
		return null;
	}

	private String getSiteminderRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "siteminder_";
		String clientId = socialData.getProperty(prefix + "client_id");
		String redirectUri = socialData.getProperty(prefix + "redirect_uri");
		String tenant = socialData.getProperty(prefix + "tenant");
		String scope = socialData.getProperty(prefix + "scope"); // need to set this up and reuse
		String auth_url = socialData.getProperty(prefix + "auth_url");
		String state = UUID.randomUUID().toString();

		if (Strings.isNullOrEmpty(auth_url)) {
			auth_url = "https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/authorize";
		}

		String redirectUrl = auth_url + "?" + "client_id=" + clientId + "&response_type=code" + "&redirect_uri="
				+ URLEncoder.encode(redirectUri, UTF8) + "&response_mode=query" + "&scope="
				+ URLEncoder.encode(scope, UTF8) + "&state=" + state;

		if (classLogger.isDebugEnabled()) {
			classLogger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
		}

		return redirectUrl;
	}

	/**
	 * Logs user in through drop box
	 */
	@GET
	@Produces("application/json")
	@Path("/login/dropbox")
	public Response loginDropBox(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws IOException {
		if (socialData.getLoginsAllowed().get("dropbox") == null || !socialData.getLoginsAllowed().get("dropbox")) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "Dropbox login is not allowed");
			return WebUtility.getResponse(ret, 400);
		}
		/*
		 * Try to log in the user If they are not logged in Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if (session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = WebUtility.cleanHttpResponse(request.getParameter("redirect"));
		if (customRedirect != null && !customRedirect.isEmpty()) {
			if (session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}

		String queryString = WebUtility.encodeHTTPUri(request.getQueryString());
		if (queryString != null && queryString.contains("code")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.DROPBOX) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				// oauth code should match [ -~]+ (1 or more ascii)
				// https://www.rfc-editor.org/rfc/rfc6749#appendix-A.11
				String code = URLDecoder.decode(outputs[0]);
				if (code.matches("[ -~]+")) {
					String prefix = "dropbox_";
					String clientId = socialData.getProperty(prefix + "client_id");
					String clientSecret = socialData.getProperty(prefix + "secret_key");
					String redirectUri = socialData.getProperty(prefix + "redirect_uri");
					boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

					if (classLogger.isDebugEnabled()) {
						classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
					}

					Map<String, String> params = new HashMap<>();
					params.put("client_id", clientId);
					params.put("redirect_uri", redirectUri);
					params.put("code", code);
					params.put("grant_type", "authorization_code");
					params.put("client_secret", clientSecret);

					String url = "https://www.dropbox.com/oauth2/token";

					AccessToken accessToken = HttpHelperUtility.getAccessToken(url, params, true, true);
					if (accessToken == null) {
						// not authenticated
						response.setStatus(302);
						response.sendRedirect(getDBRedirect(request));
						return null;
					}
					accessToken.setProvider(AuthProvider.DROPBOX);
					addAccessToken(accessToken, request, autoAdd);

					if (classLogger.isDebugEnabled()) {
						classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
					}
				}
			}
		}

		// grab the user again
		if (session != null || (session = request.getSession(false)) != null) {
			userObj = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if (userObj == null || userObj.getAccessToken(AuthProvider.DROPBOX) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getDBRedirect(request));
			return null;
		}

		setMainPageRedirect(request, response);
		return null;
	}

	private String getDBRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "dropbox_";

		String clientId = socialData.getProperty(prefix + "client_id");
		String redirectUri = socialData.getProperty(prefix + "redirect_uri");
		String role = socialData.getProperty(prefix + "role"); // need to set this up and reuse
		String redirectUrl = "https://www.dropbox.com/oauth2/authorize?" + "client_id=" + clientId
				+ "&response_type=code" + "&redirect_uri=" + URLEncoder.encode(redirectUri, UTF8) + "&require_role="
				+ role + "&disable_signup=false";

		if (classLogger.isDebugEnabled()) {
			classLogger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
		}

		return redirectUrl;
	}

	/**
	 * Logs user in through google
	 * https://developers.google.com/api-client-library/java/google-api-java-client/oauth2
	 */
	@GET
	@Produces("application/json")
	@Path("/login/google")
	public Response loginGoogle(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws IOException {
		if (socialData.getLoginsAllowed().get("google") == null || !socialData.getLoginsAllowed().get("google")) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "Google login is not allowed");
			return WebUtility.getResponse(ret, 400);
		}
		/*
		 * Try to log in the user If they are not logged in Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if (session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = WebUtility.cleanHttpResponse(request.getParameter("redirect"));
		if (customRedirect != null && !customRedirect.isEmpty()) {
			if (session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}

		String queryString = WebUtility.encodeHTTPUri(request.getQueryString());
		if (queryString != null && queryString.contains("code")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.GOOGLE) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				// oauth code should match [ -~]+ (1 or more ascii)
				// https://www.rfc-editor.org/rfc/rfc6749#appendix-A.11
				String code = URLDecoder.decode(outputs[0]);
				if (code.matches("[ -~]+")) {
					String prefix = "google_";
					String clientId = socialData.getProperty(prefix + "client_id");
					String clientSecret = socialData.getProperty(prefix + "secret_key");
					String redirectUri = socialData.getProperty(prefix + "redirect_uri");
					boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

					if (classLogger.isDebugEnabled()) {
						classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
					}

					// I need to decode the return code from google since the default param's are
					// encoded on the post of getAccessToken
					Map<String, String> params = new HashMap<>();
					params.put("client_id", clientId);
					params.put("redirect_uri", redirectUri);
					params.put("code", code);
					params.put("grant_type", "authorization_code");
					params.put("client_secret", clientSecret);

					String url = "https://www.googleapis.com/oauth2/v4/token";

					// https://developers.google.com/api-client-library/java/google-api-java-client/oauth2
					AccessToken accessToken = HttpHelperUtility.getAccessToken(url, params, true, true);
					if (accessToken == null) {
						// not authenticated
						response.setStatus(302);
						response.sendRedirect(getGoogleRedirect(request));
						return null;
					}
					accessToken.setProvider(AuthProvider.GOOGLE);

					// fill the access token with the other properties so we can properly create the
					// user
					GoogleTokenFiller profiler = new GoogleTokenFiller();
					profiler.fillAccessToken(accessToken, null, null, null, null);
					addAccessToken(accessToken, request, autoAdd);

					// Shows how to make a google credential from an access token
					if (classLogger.isDebugEnabled()) {
						classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
					}
				}
			}
		}

		// grab the user again
		if (session != null || (session = request.getSession(false)) != null) {
			userObj = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if (userObj == null || userObj.getAccessToken(AuthProvider.GOOGLE) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getGoogleRedirect(request));
			return null;
		}

		setMainPageRedirect(request, response);
		return null;
	}

	private String getGoogleRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "google_";
		String clientId = socialData.getProperty(prefix + "client_id");
		String redirectUri = socialData.getProperty(prefix + "redirect_uri");
		String scope = socialData.getProperty(prefix + "scope"); // need to set this up and reuse
		String accessType = socialData.getProperty(prefix + "access_type"); // need to set this up and reuse
		String state = UUID.randomUUID().toString();

		String redirectUrl = "https://accounts.google.com/o/oauth2/v2/auth?" + "client_id=" + clientId
				+ "&response_type=code" + "&redirect_uri=" + URLEncoder.encode(redirectUri, UTF8) + "&access_type="
				+ accessType + "&scope=" + URLEncoder.encode(scope, UTF8) + "&state=" + state;

		if (classLogger.isDebugEnabled()) {
			classLogger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
		}
		return redirectUrl;
	}

//	/**
//	 * METHOD IS USED FOR TESTING
//	 * 
//	 * @param request
//	 * @param ret
//	 */
//	private void performGoogleOps(HttpServletRequest request, Map<String, Object> ret) {
//		// get the user details
//		IConnectorIOp prof = new GoogleProfile();
//		prof.execute((User) request.getSession().getAttribute(Constants.SESSION_USER), null);
//
//		IConnectorIOp lister = new GoogleListFiles();
//		List fileList = (List) lister.execute((User) request.getSession().getAttribute(Constants.SESSION_USER), null);
//
//		// get the file
//		IConnectorIOp getter = new GoogleFileRetriever();
//		Map<String, Object> params2 = new HashMap<>();
//		params2.put("exportFormat", "csv");
//		params2.put("id", "1it40jNFcRo1ur2dHIYUk18XmXdd37j4gmJm_Sg7KLjI");
//		params2.put("target", "c:\\users\\pkapaleeswaran\\workspacej3\\datasets\\googlefile.csv");
//
//		getter.execute((User) request.getSession().getAttribute(Constants.SESSION_USER), params2);
//
//		IConnectorIOp ner = new GoogleEntityResolver();
//
//		NLPDocumentInput docInput = new NLPDocumentInput();
//		docInput.setContent("Obama is staying in the whitehouse !!");
//
//		params2 = new HashMap<>();
//
//		// Hashtable docInputShell = new Hashtable();
//		params2.put("encodingType", "UTF8");
//		params2.put("document", docInput);
//
//		// params2.put("input", docInputShell);
//		ner.execute((User) request.getSession().getAttribute(Constants.SESSION_USER), params2);
//
//		try {
//			ret.put("files", BeanFiller.getJson(fileList));
//		} catch (Exception e) {
//			classLogger.error(Constants.STACKTRACE, e);
//		}
//
//		IConnectorIOp lat = new GoogleLatLongGetter();
//		params2 = new HashMap<>();
//		params2.put("address", "1919 N Lynn Street, Arlington, VA");
//
//		lat.execute((User) request.getSession().getAttribute(Constants.SESSION_USER), params2);
//
//		IConnectorIOp ts = new TwitterSearcher();
//		params2 = new HashMap<>();
//		params2.put("q", "Anlaytics");
//		params2.put("lang", "en");
//		params2.put("count", "10");
//
//		Object vp = ts.execute((User) request.getSession().getAttribute(Constants.SESSION_USER), params2);
//	}

	@GET
	@Produces("application/json")
	@Path("/login/producthunt")
	public Response loginProducthunt(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws IOException {
		if (socialData.getLoginsAllowed().get("producthunt") == null
				|| !socialData.getLoginsAllowed().get("producthunt")) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "Producthunt login is not allowed");
			return WebUtility.getResponse(ret, 400);
		}
		/*
		 * Try to log in the user If they are not logged in Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if (session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = WebUtility.cleanHttpResponse(request.getParameter("redirect"));
		if (customRedirect != null && !customRedirect.isEmpty()) {
			if (session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}

		String queryString = WebUtility.encodeHTTPUri(request.getQueryString());
		if (queryString != null && queryString.contains("code")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.PRODUCT_HUNT) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				// oauth code should match [ -~]+ (1 or more ascii)
				// https://www.rfc-editor.org/rfc/rfc6749#appendix-A.11
				String code = URLDecoder.decode(outputs[0]);
				if (code.matches("[ -~]+")) {
					String prefix = "producthunt_";
					String clientId = socialData.getProperty(prefix + "client_id");
					String clientSecret = socialData.getProperty(prefix + "secret_key");
					String redirectUri = socialData.getProperty(prefix + "redirect_uri");
					boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

					if (classLogger.isDebugEnabled()) {
						classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
					}

					Map<String, String> params = new HashMap<>();
					params.put("client_id", clientId);
					params.put("redirect_uri", redirectUri);
					params.put("code", code);
					params.put("grant_type", "authorization_code");
					params.put("client_secret", clientSecret);

					String url = "https://api.producthunt.com/v1/oauth/token";

					AccessToken accessToken = HttpHelperUtility.getAccessToken(url, params, true, true);
					if (accessToken == null) {
						// not authenticated
						response.setStatus(302);
						response.sendRedirect(getProducthuntRedirect(request));
						return null;
					}
					accessToken.setProvider(AuthProvider.PRODUCT_HUNT);
					addAccessToken(accessToken, request, autoAdd);

					if (classLogger.isDebugEnabled()) {
						classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
					}
				}
			}
		}

		// grab the user again
		if (session != null || (session = request.getSession(false)) != null) {
			userObj = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if (userObj == null || userObj.getAccessToken(AuthProvider.PRODUCT_HUNT) == null) {
			response.setStatus(302);
			response.sendRedirect(getProducthuntRedirect(request));
			return null;
		}

		setMainPageRedirect(request, response);
		return null;
	}

	private String getProducthuntRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "producthunt_";

		String clientId = socialData.getProperty(prefix + "client_id");
		String redirectUri = socialData.getProperty(prefix + "redirect_uri");
		String scope = socialData.getProperty(prefix + "scope");

		String redirectUrl = "https://api.producthunt.com/v1/oauth/authorize?" + "client_id=" + clientId
				+ "&response_type=code" + "&redirect_uri=" + URLEncoder.encode(redirectUri, UTF8) + "&scope="
				+ URLEncoder.encode(scope, UTF8);

		if (classLogger.isDebugEnabled()) {
			classLogger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
		}

		return redirectUrl;
	}

	/**
	 * Logs user in through linkedin
	 */
	@GET
	@Produces("application/json")
	@Path("/login/linkedin")
	public Response loginLinkedin(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws IOException {
		if (socialData.getLoginsAllowed().get("linkedin") == null || !socialData.getLoginsAllowed().get("linkedin")) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "LinkedIn login is not allowed");
			return WebUtility.getResponse(ret, 400);
		}
		/*
		 * Try to log in the user If they are not logged in Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if (session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = WebUtility.cleanHttpResponse(request.getParameter("redirect"));
		if (customRedirect != null && !customRedirect.isEmpty()) {
			if (session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}

		String queryString = WebUtility.encodeHTTPUri(request.getQueryString());
		if (queryString != null && queryString.contains("code")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.LINKEDIN) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				// oauth code should match [ -~]+ (1 or more ascii)
				// https://www.rfc-editor.org/rfc/rfc6749#appendix-A.11
				String code = URLDecoder.decode(outputs[0]);
				if (code.matches("[ -~]+")) {
					String prefix = "linkedin_";
					String clientId = socialData.getProperty(prefix + "client_id");
					String clientSecret = socialData.getProperty(prefix + "secret_key");
					String redirectUri = socialData.getProperty(prefix + "redirect_uri");
					boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

					if (classLogger.isDebugEnabled()) {
						classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
					}

					Map<String, String> params = new HashMap<>();
					params.put("client_id", clientId);
					params.put("redirect_uri", redirectUri);
					params.put("code", code);
					params.put("grant_type", "authorization_code");
					params.put("client_secret", clientSecret);

					String url = "https://www.linkedin.com/oauth/v2/accessToken";

					AccessToken accessToken = HttpHelperUtility.getAccessToken(url, params, true, true);
					if (accessToken == null) {
						// not authenticated
						response.setStatus(302);
						response.sendRedirect(getLinkedinRedirect(request));
						return null;
					}
					accessToken.setProvider(AuthProvider.LINKEDIN);
					addAccessToken(accessToken, request, autoAdd);

					if (classLogger.isDebugEnabled()) {
						classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
					}
				}
			}
		}

		// grab the user again
		if (session != null || (session = request.getSession(false)) != null) {
			userObj = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if (userObj == null || userObj.getAccessToken(AuthProvider.LINKEDIN) == null) {
			response.setStatus(302);
			response.sendRedirect(getLinkedinRedirect(request));
			return null;
		}

		setMainPageRedirect(request, response);
		return null;
	}

	private String getLinkedinRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "linkedin_";
		String clientId = socialData.getProperty(prefix + "client_id");
		String redirectUri = socialData.getProperty(prefix + "redirect_uri");
		String scope = socialData.getProperty(prefix + "scope");

		String redirectUrl = "https://www.linkedin.com/oauth/v2/authorization?" + "client_id=" + clientId
				+ "&redirect_uri=" + redirectUri + "&state=" + UUID.randomUUID().toString() + "&response_type=code"
				+ "&scope=" + URLEncoder.encode(scope, UTF8);

		if (classLogger.isDebugEnabled()) {
			classLogger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
		}

		return redirectUrl;
	}

	/**
	 * WHY IS THIS GIT?!?!?!
	 * 
	 * @param request
	 * @param response
	 * @return
	 * @throws IOException
	 */
	@GET
	@Produces("application/json")
	@Path("/login/twitter")
	public Response loginTwitter(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws IOException {
		if (socialData.getLoginsAllowed().get("twitter") == null || !socialData.getLoginsAllowed().get("twitter")) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "Twitter login is not allowed");
			return WebUtility.getResponse(ret, 400);
		}
		// getting the bearer token on twitter for app authentication is a lot simpler
		// need to just combine the id and secret
		// base 64 and send as authorization

		// https://developer.github.com/apps/building-oauth-apps/authorization-options-for-oauth-apps/

		HttpSession session = request.getSession(false);
		User userObj = null;
		if (session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = WebUtility.cleanHttpResponse(request.getParameter("redirect"));
		if (customRedirect != null && !customRedirect.isEmpty()) {
			if (session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}

		String queryString = WebUtility.encodeHTTPUri(request.getQueryString());
		if (queryString != null && queryString.contains("code")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.GITHUB) == null) {

				String[] outputs = HttpHelperUtility.getCodes(queryString);
				// oauth code and state should match [ -~]+ (1 or more ascii)
				// https://www.rfc-editor.org/rfc/rfc6749#appendix-A.5
				// https://www.rfc-editor.org/rfc/rfc6749#appendix-A.11
				String code = URLDecoder.decode(outputs[0]);
				String state = URLDecoder.decode(outputs[1]);
				if (code.matches("[ -~]+") && state.matches("[ -~]+")) {
					String prefix = "twitter_";
					String clientId = socialData.getProperty(prefix + "client_id");
					String clientSecret = socialData.getProperty(prefix + "secret_key");
					String redirectUri = socialData.getProperty(prefix + "redirect_uri");
					boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

					if (classLogger.isDebugEnabled()) {
						classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
					}

					Map<String, String> params = new HashMap<>();
					params.put("client_id", clientId);
					params.put("redirect_uri", redirectUri);
					params.put("code", code);
					params.put("state", state);
					params.put("client_secret", clientSecret);

					String url = "https://api.twitter.com/oauth/access_token";

					AccessToken accessToken = HttpHelperUtility.getAccessToken(url, params, false, true);
					if (accessToken == null) {
						// not authenticated
						response.setStatus(302);
						response.sendRedirect(getTwitterRedirect(request));
						return null;
					}

					accessToken.setProvider(AuthProvider.TWITTER);
					addAccessToken(accessToken, request, autoAdd);

					if (classLogger.isDebugEnabled()) {
						classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
					}
				}
			}
		}

		// grab the user again
		if (session != null || (session = request.getSession(false)) != null) {
			userObj = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if (userObj == null || userObj.getAccessToken(AuthProvider.TWITTER) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getTwitterRedirect(request));
			return null;
		}

		setMainPageRedirect(request, response);
		return null;
	}

	private String getTwitterRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "twitter_";

		String clientId = socialData.getProperty(prefix + "client_id");
		// String clientSecret = socialData.getProperty(prefix + "secret_key");
		String redirectUri = socialData.getProperty(prefix + "redirect_uri");
		String scope = socialData.getProperty(prefix + "scope");
		String nonce = UUID.randomUUID().toString();
		String timestamp = System.currentTimeMillis() + "";

		StringBuffer signatureString = new StringBuffer("GET").append("&");
		signatureString.append(URLEncoder.encode("https://api.twitter.com/oauth/authorize", UTF8)).append("&");

		StringBuffer parameterString = new StringBuffer("");
		parameterString.append("oauth_callback=").append(URLEncoder.encode(redirectUri, UTF8)).append("&");
		parameterString.append("oauth_consumer_key=").append(clientId).append("&");
		parameterString.append("oauth_nonce=").append(nonce).append("&");
		parameterString.append("oauth_timestamp=").append(timestamp);

		// String finalString = signatureString.toString() + parameterString.toString();

		// String signature =

		String redirectUrl = "https://github.com/login/oauth/authorize?" + "oauth_consumer_key=" + clientId
				+ "&oauth_callback=" + redirectUri + "&oauth_nonce=" + nonce + "&oauth_timestamp=" + timestamp
				+ "&scope=" + URLEncoder.encode(scope, UTF8);

		if (classLogger.isDebugEnabled()) {
			classLogger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
		}

		return redirectUrl;
	}

	/**
	 * Logs user in through generic provider
	 */
	@GET
	@Produces("application/json")
	@Path("/login/{provider}")
	public Response loginGeneric(@PathParam("provider") String provider, @Context HttpServletRequest request,
			@Context HttpServletResponse response) throws IOException {
		/*
		 * Try to log in the user If they are not logged in Redirect the FE
		 */

		provider = WebUtility.inputSanitizer(provider);
		if (socialData.getLoginsAllowed().get(provider) == null || !socialData.getLoginsAllowed().get(provider)) {
			Map<String, Object> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, provider + " login is not allowed");
			return WebUtility.getResponse(ret, 400);
		}

		AuthProvider providerEnum = AuthProvider.getProviderFromString(provider.toUpperCase());

		HttpSession session = request.getSession(false);
		User userObj = null;
		if (session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = WebUtility.cleanHttpResponse(request.getParameter("redirect"));
		if (customRedirect != null && !customRedirect.isEmpty()) {
			if (session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}

		String queryString = WebUtility.encodeHTTPUri(request.getQueryString());
		if (queryString != null && queryString.contains("code")) {
			if (userObj == null || userObj.getAccessToken(providerEnum) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				// oauth code should match [ -~]+ (1 or more ascii)
				// https://www.rfc-editor.org/rfc/rfc6749#appendix-A.11
				String code = URLDecoder.decode(outputs[0]);
				if (code.matches("[ -~]+")) {
					String prefix = provider + "_";
					String clientId = socialData.getProperty(prefix + "client_id");
					String clientSecret = socialData.getProperty(prefix + "secret_key");
					String redirectUri = socialData.getProperty(prefix + "redirect_uri");
					boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));
					// String tenant = socialData.getProperty(prefix + "tenant");

					// removing scope for now as it isn't needed in token call usually
					// String scope = socialData.getProperty(prefix + "scope");
					String token_url = socialData.getProperty(prefix + "token_url");

					if (classLogger.isDebugEnabled()) {
						classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
					}

					Map<String, String> params = new HashMap<>();
					params.put("client_id", clientId);
					// params.put("scope", scope);
					params.put("redirect_uri", redirectUri);
					params.put("code", code);
					params.put("grant_type", "authorization_code");
					params.put("client_secret", clientSecret);

					if (Strings.isNullOrEmpty(token_url)) {
						throw new IllegalArgumentException("Token URL can not be null or empty");
					}

					AccessToken accessToken = HttpHelperUtility.getAccessToken(token_url, params, true, true);
					if (accessToken == null) {
						// not authenticated
						response.setStatus(302);
						response.sendRedirect(getGenericRedirect(provider, request));
						return null;
					}

					String userInfoURL = socialData.getProperty(prefix + "userinfo_url");
					// "name","id","email"
					String beanProps = socialData.getProperty(prefix + "beanProps");
					String[] beanPropsArr = beanProps.split(",", -1);
					String jsonPattern = socialData.getProperty(prefix + "jsonPattern");

					accessToken.setProvider(providerEnum);

					// this is a check for sanitizing a response back from an IAM provider - not
					// common and should be false
					// examples would be unescaped special chars in the response that then can't be
					// parsed into a json.
					// this is not very common.
					boolean sanitizeResponse = Boolean
							.parseBoolean(socialData.getProperty(prefix + "sanitizeUserResponse"));

					GenericTokenFiller profiler = new GenericTokenFiller();
					profiler.fillAccessToken(accessToken, userInfoURL, jsonPattern, beanPropsArr, null,
							sanitizeResponse);

					if (Boolean.parseBoolean(socialData.getProperty(prefix + "groups"))) {
						// get groups
						String group_url = socialData.getProperty(prefix + "group_url");
						// make the call to get the groups
						String groupsJson = HttpHelperUtility.makeGetCall(group_url, accessToken.getAccess_token());
						// this is a check for sanitizing a response back from an IAM provider - not
						// common and should be false
						// examples would be unescaped special chars in the response that then can't be
						// parsed into a json.
						boolean sanitizeGroupResponse = Boolean
								.parseBoolean(socialData.getProperty(prefix + "sanitizeGroupResponse"));
						if (sanitizeGroupResponse) {
							groupsJson = groupsJson.replace("\\", "\\\\");
							// add more replacements as need be in the future
						}

						Set<String> userGroups = new HashSet<String>();
						// are groups returned as a single string or an array in a json. Usually it is
						// an array in a json.
						boolean groupStringResponse = Boolean
								.parseBoolean(socialData.getProperty(prefix + "group_string_return"));
						if (groupStringResponse) {
							// this json pattern should return a single string with groups concat
							// ""fakeGroups":"CN=group1, CN=group2, CN=group3"
							String groupJsonPattern = socialData.getProperty(prefix + "groupJsonPattern");
							JsonNode result = BeanFiller.getJmesResult(groupsJson, groupJsonPattern);
							try {
								// get the single string and the regex pattern. validate the pattern
								String groupText = result.asText();
								String regexPattern = socialData.getProperty(prefix + "group_string_regex");
								try {
									Pattern pattern = Pattern.compile(regexPattern);
								} catch (PatternSyntaxException e) {
									classLogger.error(Constants.STACKTRACE, e);
									throw new SemossPixelException("Pattern input is not a valid regex");
								}

								// split the groups
								String[] groups = groupText.split(regexPattern);
								for (String group : groups) {
									userGroups.add(group);
								}
							} catch (Exception e) {
								classLogger.error(Constants.STACKTRACE, e);
								throw new SemossPixelException("Could not parse response as string");
							}

						} else {
							// this json pattern should return an array
							String groupJsonPattern = socialData.getProperty(prefix + "groupJsonPattern");
							JsonNode result = BeanFiller.getJmesResult(groupsJson, groupJsonPattern);
							if ((result instanceof ArrayNode) && result.get(0) instanceof ObjectNode) {
								throw new SemossPixelException(
										"Group result must return flat array. Please check groupJsonPatter");
							}
							for (int inputIndex = 0; result != null && inputIndex < result.size(); inputIndex++) {
								String thisInput = result.get(inputIndex).asText();
								userGroups.add(thisInput);
							}
						}

						accessToken.setUserGroups(userGroups);
						accessToken.setUserGroupType(providerEnum.toString());
					}

					addAccessToken(accessToken, request, autoAdd);

					if (classLogger.isDebugEnabled()) {
						classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
					}
				}
			}
		}

		// grab the user again
		if (session != null || (session = request.getSession(false)) != null) {
			userObj = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if (userObj == null || userObj.getAccessToken(providerEnum) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getGenericRedirect(provider, request));
			return null;
		}

		setMainPageRedirect(request, response);
		return null;
	}

	private String getGenericRedirect(String provider, HttpServletRequest request) throws UnsupportedEncodingException {
		provider = WebUtility.inputSanitizer(provider);
		String prefix = provider + "_";
		String clientId = socialData.getProperty(prefix + "client_id");
		String redirectUri = socialData.getProperty(prefix + "redirect_uri");
		// String tenant = socialData.getProperty(prefix + "tenant");
		String scope = socialData.getProperty(prefix + "scope"); // need to set this up and reuse
		String auth_url = socialData.getProperty(prefix + "auth_url");

		String state = UUID.randomUUID().toString();

		if (Strings.isNullOrEmpty(auth_url)) {
			throw new IllegalArgumentException("Authorize URL can not be null or empty");
		}

		String redirectUrl;

		// are you already adding custom props? if so I will skip adding the ?
		if (auth_url.contains("?")) {
			redirectUrl = auth_url + "&client_id=" + clientId + "&response_type=code" + "&redirect_uri="
					+ URLEncoder.encode(redirectUri, UTF8) + "&response_mode=query" + "&scope="
					+ URLEncoder.encode(scope, UTF8) + "&state=" + state;
		} else {
			redirectUrl = auth_url + "?" + "client_id=" + clientId + "&response_type=code" + "&redirect_uri="
					+ URLEncoder.encode(redirectUri, UTF8) + "&response_mode=query" + "&scope="
					+ URLEncoder.encode(scope, UTF8) + "&state=" + state;
		}

		if (classLogger.isDebugEnabled()) {
			classLogger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
		}

		return redirectUrl;
	}
	// public static String calculateRFC2104HMAC(String data, String key) throws
	// SignatureException, NoSuchAlgorithmException, InvalidKeyException {
	// SecretKeySpec signingKey = new SecretKeySpec(key.getBytes(), "HmacSHA1");
	// Mac mac = Mac.getInstance("HmacSHA1");
	// mac.init(signingKey);
	// return toHexString(mac.doFinal(data.getBytes()));
	// }
	//
	// private static String toHexString(byte[] bytes) {
	// Formatter formatter = new Formatter();
	// for (byte b : bytes) {
	// formatter.format("%02x", b);
	// }
	// return formatter.toString();
	// }

	/////////////////////////////////////////////////////////////////

	/**
	 * This portion of code is for semoss generated users
	 */

	/**
	 * Authenticates an user that's trying to log in.
	 * 
	 * @param request
	 * @return true if the information provided to log in is valid otherwise error.
	 */
	@POST
	@Produces("application/json")
	@Path("/login")
	public Response loginNative(@Context HttpServletRequest request, @Context HttpServletResponse response) {
		Map<String, String> ret = new HashMap<>();

		if (socialData.getLoginsAllowed().get("native") == null || !socialData.getLoginsAllowed().get("native")) {
			ret.put(Constants.ERROR_MESSAGE, "Native login is not allowed");
			return WebUtility.getResponse(ret, 400);
		}

		try {
			String username = WebUtility.inputSQLSanitizer(request.getParameter("username"));
			String password = WebUtility.inputSQLSanitizer(request.getParameter("password"));
			String redirect = WebUtility.cleanHttpResponse(request.getParameter("redirect"));
			// so that the default is to redirect
			Boolean disableRedirect = Boolean.parseBoolean(request.getParameter("disableRedirect") + "");

			if (username == null || password == null || username.isEmpty() || password.isEmpty()) {
				classLogger.warn("User is trying to login using username='" + username
						+ "' but user name or password are empty");
				ret.put(Constants.ERROR_MESSAGE, "The user name or password are empty");
				return WebUtility.getResponse(ret, 401);
			}

			boolean canLogin = SecurityNativeUserUtils.logIn(username, password);
			if (canLogin) {
				String name = SecurityNativeUserUtils.getNameUser(username);
				String email = SecurityNativeUserUtils.getUserEmail(username);

				String id = SecurityNativeUserUtils.getUserId(username);
				AccessToken authToken = new AccessToken();
				authToken.setProvider(AuthProvider.NATIVE);
				authToken.setId(id);
				authToken.setName(name);
				authToken.setUsername(username);
				authToken.setEmail(email);
				// no need to auto-add since to login native you must already exist
				addAccessToken(authToken, request, false);

				// add these to the return
				ret.put("success", "true");
				ret.put("username", username);
				ret.put("name", name);
				ret.put("email", email);

				// log the log in
				if (!disableRedirect) {
					setMainPageRedirect(request, response, redirect);
				}
			} else {
				HttpSession session = request.getSession(false);
				User user = null;
				if (session != null) {
					user = (User) session.getAttribute(Constants.SESSION_USER);
					if (!AbstractSecurityUtils.anonymousUsersEnabled() && user != null && user.getLogins().isEmpty()) {
						session.invalidate();
					}
				}
				classLogger.warn("User is trying to login using username='" + username
						+ "' but user name or password are empty");
				ret.put(Constants.ERROR_MESSAGE, "The user name or password are invalid.");
				return WebUtility.getResponse(ret, 401);
			}
		} catch (Exception e) {
			HttpSession session = request.getSession(false);
			if (session != null) {
				User user = (User) session.getAttribute(Constants.SESSION_USER);
				if (!AbstractSecurityUtils.anonymousUsersEnabled() && user != null && user.getLogins().isEmpty()) {
					session.invalidate();
				}
			}
			classLogger.error(Constants.STACKTRACE, e);
			ret.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(ret, 500);
		}

		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Authenticates a user that's trying to log in through AD
	 * 
	 * @param request
	 * @return true if the information provided to log in is valid otherwise error.
	 */
	@POST
	@Produces("application/json")
	@Path("/loginLDAP")
	public Response loginLDAP(@Context HttpServletRequest request, @Context HttpServletResponse response) {
		Map<String, Object> ret = new HashMap<>();
		if (socialData.getLoginsAllowed().get("ldap") == null || !socialData.getLoginsAllowed().get("ldap")) {
			ret.put(Constants.ERROR_MESSAGE, "LDAP login is not allowed");
			return WebUtility.getResponse(ret, 400);
		}

		ILdapAuthenticator authenticator = null;
		try {
			String username = WebUtility.inputSQLSanitizer(request.getParameter("username"));
			String password = WebUtility.inputSQLSanitizer(request.getParameter("password"));
			// so that the default is to redirect
			Boolean disableRedirect = Boolean.parseBoolean(request.getParameter("disableRedirect") + "");

			if (username == null || password == null || username.isEmpty() || password.isEmpty()) {
				ret.put(Constants.ERROR_MESSAGE, "The user name or password are empty");
				return WebUtility.getResponse(ret, 401);
			}

			authenticator = socialData.getLdapAuthenticator();
			AccessToken authToken = authenticator.authenticate(username, password);
			if (authToken == null) {
				throw new IllegalArgumentException("Unable to parse any user attributes");
			}
			boolean autoAdd = Boolean
					.parseBoolean(socialData.getProperty(ILdapAuthenticator.LDAP + "auto_add", "true"));
			addAccessToken(authToken, request, autoAdd);
			ret.put("success", "true");
			ret.put("username", username);
			// log the log in
			if (!disableRedirect) {
				setMainPageRedirect(request, response);
			}
		} catch (LDAPPasswordChangeRequiredException e) {
			HttpSession session = request.getSession(false);
			if (session != null) {
				User user = (User) session.getAttribute(Constants.SESSION_USER);
				if (!AbstractSecurityUtils.anonymousUsersEnabled() && user != null && user.getLogins().isEmpty()) {
					session.invalidate();
				}
			}
			classLogger.error(Constants.STACKTRACE, e);
			ret.put(Constants.ERROR_MESSAGE, "User must change their password before login");
			ret.put(ILdapAuthenticator.LDAP_PASSWORD_CHANGE_RETURN_KEY, true);
			return WebUtility.getResponse(ret, 401);
		} catch (Exception e) {
			HttpSession session = request.getSession(false);
			if (session != null) {
				User user = (User) session.getAttribute(Constants.SESSION_USER);
				if (!AbstractSecurityUtils.anonymousUsersEnabled() && user != null && user.getLogins().isEmpty()) {
					session.invalidate();
				}
			}
			classLogger.error(Constants.STACKTRACE, e);
			ret.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(ret, 500);
		} finally {
			if (authenticator != null) {
				try {
					authenticator.close();
				} catch (IOException e) {
					classLogger.error(Constants.STACKTRACE, e);
				}
			}
		}

		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Authenticates a user that's trying to log in through AD
	 * 
	 * @param request
	 * @return true if the information provided to log in is valid otherwise error.
	 */
	@POST
	@Produces("application/json")
	@Path("/changeADPassword")
	public Response changeADPassword(@Context HttpServletRequest request, @Context HttpServletResponse response) {
		Map<String, String> ret = new HashMap<>();
		if ((socialData.getLoginsAllowed().get("ldap") == null || !socialData.getLoginsAllowed().get("ldap"))
				&& !Boolean.parseBoolean(socialData.getProperty("linotp_check_ad", "false"))) {
			ret.put(Constants.ERROR_MESSAGE, "LDAP change password is not allowed/configured");
			return WebUtility.getResponse(ret, 400);
		}

		ILdapAuthenticator authenticator = null;
		try {
			String username = WebUtility.inputSQLSanitizer(request.getParameter("username"));
			String curPassword = WebUtility.inputSQLSanitizer(request.getParameter("curPassword"));
			String newPassword = WebUtility.inputSQLSanitizer(request.getParameter("newPassword"));

			if (username == null || curPassword == null || newPassword == null || username.isEmpty()
					|| curPassword.isEmpty() || newPassword.isEmpty()) {
				ret.put(Constants.ERROR_MESSAGE, "The user name, current password, or new password are empty");
				return WebUtility.getResponse(ret, 401);
			}

			authenticator = socialData.getLdapAuthenticator();
			authenticator.updateUserPassword(username, curPassword, newPassword);
			ret.put("success", "true");
			ret.put("username", username);
			return WebUtility.getResponse(ret, 200);
		} catch (Exception e) {
			HttpSession session = request.getSession(false);
			if (session != null) {
				User user = (User) session.getAttribute(Constants.SESSION_USER);
				if (!AbstractSecurityUtils.anonymousUsersEnabled() && user != null && user.getLogins().isEmpty()) {
					session.invalidate();
				}
			}
			classLogger.error(Constants.STACKTRACE, e);
			ret.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(ret, 500);
		} finally {
			if (authenticator != null) {
				try {
					authenticator.close();
				} catch (IOException e) {
					classLogger.error(Constants.STACKTRACE, e);
				}
			}
		}
	}

	/**
	 * One Time Passcode using LinOTP
	 * 
	 * @param request
	 * @return true if the information provided to log in is valid otherwise error.
	 * @throws IOException
	 * @throws ClientProtocolException
	 */
	@POST
	@Produces("application/json")
	@Path("/loginLinOTP")
	public Response loginLinOTP(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws ClientProtocolException, IOException {
		Map<String, Object> returnMap = new HashMap<>();
		if (socialData.getLoginsAllowed().get("linotp") == null || !socialData.getLoginsAllowed().get("linotp")) {
			returnMap.put(Constants.ERROR_MESSAGE, "LinOTP login is not allowed");
			return WebUtility.getResponse(returnMap, 400);
		}

		String redirect = WebUtility.cleanHttpResponse(request.getParameter("redirect"));
		// so that the default is to redirect
		Boolean disableRedirect = Boolean.parseBoolean(request.getParameter("disableRedirect") + "");
		boolean autoAdd = Boolean.parseBoolean(socialData.getProperty("linotp_auto_add", "true"));

		LinOTPResponse linotpResponse = LinOTPUtil.login(request);
		returnMap = linotpResponse.getReturnMap();
		int responseCode = linotpResponse.getResponseCode();
		if (responseCode != 200) {
			// we just throw the error
			return WebUtility.getResponse(returnMap, responseCode);
		} else {
			// are we logged in?
			AccessToken token = linotpResponse.getToken();
			if (token == null) {
				// it is a 200 but we are not logged in
				// you likely need to enter otp in 2nd response
				return WebUtility.getResponse(returnMap, responseCode);
			}

			// we have a login
			addAccessToken(token, request, autoAdd);
			// log the log in
			if (!disableRedirect) {
				setMainPageRedirect(request, response, redirect);
			}

			return WebUtility.getResponse(returnMap, 200);
		}

	}

	/**
	 * Reset the fail counter for a user
	 * 
	 * @param request
	 * @return true if the information provided to log in is valid otherwise error.
	 * @throws IOException
	 * @throws ClientProtocolException
	 */
	@POST
	@Produces("application/json")
	@Path("/resetLinOTPFailCounter")
	public Response resetLinOTPFailCounter(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws ClientProtocolException, IOException {
		try {
			LinOTPResponse linotpResponse = LinOTPUtil.resetCounter(request);
			Map<String, Object> returnMap = linotpResponse.getReturnMap();
			int responseCode = linotpResponse.getResponseCode();
			// this is simple, take response code and message and return
			return WebUtility.getResponse(returnMap, responseCode);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, Object> errorMessage = new HashMap<>();
			errorMessage.put(Constants.ERROR_MESSAGE,
					"Error occurred resetting the pin. Error message = " + e.getMessage());
			return WebUtility.getResponse(errorMessage, 500);
		}
	}

	/**
	 * Create an user according to the information provided (user name, password,
	 * email)
	 * 
	 * @param request
	 * @return true if the user is created otherwise error.
	 */
	@POST
	@Produces("application/json")
	@Path("createUser")
	public Response createNativeUser(@Context HttpServletRequest request) {
		Map<String, String> ret = new HashMap<>();

		if (socialData.getLoginsAllowed().get("native") == null || !socialData.getLoginsAllowed().get("native")) {
			ret.put(Constants.ERROR_MESSAGE, "Native login is not allowed");
			return WebUtility.getResponse(ret, 400);
		}

		if (socialData.getLoginsAllowed().get("registration") == null
				|| !socialData.getLoginsAllowed().get("registration")) {
			ret.put(Constants.ERROR_MESSAGE, "Native registration is not allowed");
			return WebUtility.getResponse(ret, 400);
		}

		// get the connection to RDF_MAP.prop file to get the user default set values
		try {
			// Note - for native users
			// the id and the username are always the same
			String username = WebUtility.inputSQLSanitizer(request.getParameter("username"));
			String name = WebUtility.inputSQLSanitizer(request.getParameter("name"));
			String password = WebUtility.inputSQLSanitizer(request.getParameter("password"));
			String email = WebUtility.inputSQLSanitizer(request.getParameter("email"));
			String phone = WebUtility.inputSanitizer(request.getParameter("phone"));
			String phoneExtension = WebUtility.inputSanitizer(request.getParameter("phoneextension"));
			String countryCode = WebUtility.inputSanitizer(request.getParameter("countrycode"));

			String modelUsageRestriction = WebUtility.inputSanitizer(request.getParameter("modelUsageRestriction"));
			if (modelUsageRestriction != null) {
				modelUsageRestriction = modelUsageRestriction.trim();
			}
			String modelUsageFrequency = WebUtility.inputSanitizer(request.getParameter("modelUsageFrequency"));
			if (modelUsageFrequency != null) {
				modelUsageFrequency = modelUsageFrequency.trim();
			}
			int modelMaxTokens = 0;
			String modelMaxTokensStr = WebUtility.inputSanitizer(request.getParameter("modelMaxTokens"));
			if (modelMaxTokensStr != null && !(modelMaxTokensStr = modelMaxTokensStr.trim()).isEmpty()) {
				// must be a valid integer
				try {
					modelMaxTokens = Integer.parseInt(modelMaxTokensStr);
				} catch (NumberFormatException e) {
					classLogger.error(Constants.STACKTRACE, e);
					ret.put(Constants.ERROR_MESSAGE, "modelMaxTokens must be a valid integer value");
					return WebUtility.getResponse(ret, 400);
				}
			}
			double modelMaxResponseTime = 0.0;
			String modelMaxResponseTimeStr = WebUtility.inputSanitizer(request.getParameter("modelMaxResponseTime"));
			if (modelMaxResponseTimeStr != null
					&& !(modelMaxResponseTimeStr = modelMaxResponseTimeStr.trim()).isEmpty()) {
				// must be a valid double
				try {
					modelMaxResponseTime = Double.parseDouble(modelMaxResponseTimeStr);
				} catch (NumberFormatException e) {
					classLogger.error(Constants.STACKTRACE, e);
					ret.put(Constants.ERROR_MESSAGE, "modelMaxResponseTime must be a valid double value");
					return WebUtility.getResponse(ret, 400);
				}
			}
			AccessToken newUser = new AccessToken();

			newUser.setProvider(AuthProvider.NATIVE);
			newUser.setId(username);
			newUser.setUsername(username);
			newUser.setEmail(email);
			newUser.setName(name);
			newUser.setPhone(phone);
			newUser.setPhoneExtension(phoneExtension);
			newUser.setCountryCode(countryCode);
			// model restriction values
			newUser.setModelUsageRestriction(modelUsageRestriction);
			newUser.setModelUsageFrequency(modelUsageFrequency);
			newUser.setModelMaxTokens(modelMaxTokens);
			newUser.setModelMaxResponseTime(modelMaxResponseTime);

			boolean userCreated = SecurityNativeUserUtils.addNativeUser(newUser, password);
			if (userCreated) {
				ret.put("success", "true");
				ret.put("username", username);
				return WebUtility.getResponse(ret, 200);
			} else {
				ret.put(Constants.ERROR_MESSAGE, "The user name or email aready exists.");
				return WebUtility.getResponse(ret, 400);
			}
		} catch (IllegalArgumentException iae) {
			classLogger.error(Constants.STACKTRACE, iae);
			ret.put(Constants.ERROR_MESSAGE, iae.getMessage());
			return WebUtility.getResponse(ret, 500);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			ret.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(ret, 500);
		}
	}

	/**
	 * Create an user according to the information provided (user name, password,
	 * email)
	 * 
	 * @param request
	 * @return true if the user is created otherwise error.
	 */
	@POST
	@Produces("application/json")
	@Path("createAPIUser")
	public Response createAPIUser(@Context HttpServletRequest request) {
		if (socialData.getLoginsAllowed().get("api_user") == null || !socialData.getLoginsAllowed().get("api_user")) {
			Map<String, String> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "API User is not allowed for login");
			return WebUtility.getResponse(ret, 400);
		}

		if (Utility.getApplicationAdminOnlyCreateAPIUser()) {
			User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
			if (user == null) {
				Map<String, String> ret = new HashMap<>();
				ret.put(Constants.ERROR_MESSAGE, "No active session. Please login as an admin");
				return WebUtility.getResponse(ret, 401);
			}
			if (!SecurityAdminUtils.userIsAdmin(user)) {
				Map<String, String> ret = new HashMap<>();
				ret.put(Constants.ERROR_MESSAGE,
						"User is not an admin and does not have access. Please login as an admin");
				return WebUtility.getResponse(ret, 401);
			}
		}

		String name = WebUtility.inputSQLSanitizer(request.getParameter("name"));
		Map<String, String> oneTimeDetails = SecurityAPIUserUtils.createAPIUser(name);
		return WebUtility.getResponse(oneTimeDetails, 200);
	}

	//////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////

	/**
	 * Setting / Getting Information from the social properties That is needed by
	 * the FE
	 */

	@GET
	@Produces("application/json")
	@Path("/loginsAllowed/")
	public Response loginsAllowed(@Context HttpServletRequest request) {
		return WebUtility.getResponse(socialData.getLoginsAllowed(), 200);
	}

	@GET
	@Produces("application/json")
	@Path("/loginProperties/")
	public Response loginProperties(@Context HttpServletRequest request) {
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		if (user == null) {
			Map<String, String> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "No user defined to access properties. Please login as an admin");
			return WebUtility.getResponse(ret, 401);
		}
		if (!SecurityAdminUtils.userIsAdmin(user)) {
			Map<String, String> ret = new HashMap<>();
			ret.put(Constants.ERROR_MESSAGE, "User is not an admin and does not have access. Please login as an admin");
			return WebUtility.getResponse(ret, 401);
		}

		// you have access - lets send all the info

		Map<String, Map<String, String>> loginApps = new TreeMap<>();

		Set<String> propSet = socialData.stringPropertyNames();
		for (String prop : propSet) {
			if (!prop.contains("_")) {
				// bad format
				// ignore
				continue;
			}
			String[] split = prop.toString().split("_", 2);
			String type = split[0];
			String type_key = split[1];

			if (!loginApps.containsKey(type)) {
				loginApps.put(type, new TreeMap<String, String>());
			}

			loginApps.get(type).put(type_key, socialData.getProperty(prop));
		}

		return WebUtility.getResponse(loginApps, 200);
	}

	@POST
	@Produces("application/json")
	@Path("/modifyLoginProperties/{provider}")
	public synchronized Response modifyLoginProperties(@PathParam("provider") String provider,
			MultivaluedMap<String, String> form, @Context HttpServletRequest request) {

		provider = WebUtility.inputSanitizer(provider);

		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		if (user == null) {
			return WebUtility.getResponse("No user defined to access properties. Please login as an admin", 400);
		}
		if (!SecurityAdminUtils.userIsAdmin(user)) {
			return WebUtility.getResponse("User is not an admin and does not have access. Please login as an admin",
					400);
		}

		Gson gson = new Gson();
		Map<String, String> mods = gson.fromJson(form.getFirst("modifications"), new TypeToken<Map<String, String>>() {
		}.getType());
		Map<String, String> sanitizedMods = new HashMap<>();

		for (String key : mods.keySet()) {
			sanitizedMods.put(WebUtility.inputSanitizer(key), WebUtility.inputSanitizer(mods.get(key)));
		}

		try {
			socialData.updateSocialProperties(provider, sanitizedMods);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<>();
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 500);
		}

		return WebUtility.getResponse(true, 200);
	}

	@POST
	@Produces("application/json")
	@Path("/modifyAllLoginProperties")
	public synchronized Response modifyAllLoginProperties(@Context HttpServletRequest request) {
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		if (user == null) {
			return WebUtility.getResponse("No user defined to access properties. Please login as an admin", 400);
		}
		if (!SecurityAdminUtils.userIsAdmin(user)) {
			return WebUtility.getResponse("User is not an admin and does not have access. Please login as an admin",
					400);
		}

		String newSocialProperties = request.getParameter("socialProperties");
		try {
			socialData.updateAllProperties(newSocialProperties);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<>();
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 500);
		}

		return WebUtility.getResponse(true, 200);
	}

	@GET
	@Produces("application/json")
	@Path("/getAllLoginProperties")
	public synchronized Response getAllLoginProperties(@Context HttpServletRequest request) {
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		if (user == null) {
			return WebUtility.getResponse("No user defined to access properties. Please login as an admin", 400);
		}
		if (!SecurityAdminUtils.userIsAdmin(user)) {
			return WebUtility.getResponse("User is not an admin and does not have access. Please login as an admin",
					400);
		}

		try {
			String fileContent = socialData.getFileContents();
			Map<String, String> retMap = new HashMap<>();
			retMap.put("content", fileContent);
			return WebUtility.getResponse(retMap, 200);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<>();
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 500);
		}
	}

	/**
	 * Redirect the login back to the main app page
	 * 
	 * @param response
	 */
	private void setMainPageRedirect(@Context HttpServletRequest request, @Context HttpServletResponse response) {
		String customRedirect = WebUtility.cleanHttpResponse(request.getParameter("redirect"));
		if (customRedirect == null || customRedirect.isEmpty()) {
			customRedirect = (String) request.getSession().getAttribute(CUSTOM_REDIRECT_SESSION_KEY);
			// also remove the attribute so additional logins dont do the redirect as well
			request.getSession().removeAttribute(CUSTOM_REDIRECT_SESSION_KEY);
		}
		setMainPageRedirect(request, response, customRedirect);
	}

	/**
	 * Redirect the login back to the main app page
	 *
	 * @param response
	 */
	private void setMainPageRedirect(@Context HttpServletRequest request, @Context HttpServletResponse response,
			String customRedirect) {
		// see if we have a location to redirect the user
		// if so, we will send them back to that URL
		// otherwise, we send them back to the FE
		HttpSession session = request.getSession();
		String contextPath = request.getContextPath();

		// Check for MCP OAuth flow
		@SuppressWarnings("unchecked")
		Map<String, String> mcpRequest = (Map<String, String>) session.getAttribute("mcp_auth_request");
		if (mcpRequest != null) {
			// This is an MCP flow - generate authorization code and redirect to ChatGPT
			handleMCPAuthorizationCallback(request, response, session, mcpRequest);
			return;
		}

		boolean useCustom = customRedirect != null && !customRedirect.isEmpty();
		boolean endpoint = session.getAttribute(Constants.ENDPOINT_REDIRECT_KEY) != null;
		response.setStatus(302);
		try {
			// add the cookie to the header directly
			// to allow for cross site login when embedded as iframe
			String setCookieString = DBLoader.getSessionIdKey() + "=" + session.getId() + "; Path=" + contextPath
					+ "; HttpOnly"
					+ ((ClusterUtil.IS_CLUSTER || request.isSecure())
							? ("; Secure; SameSite=" + Utility.getSameSiteCookieValue())
							: "");
			response.addHeader("Set-Cookie", setCookieString);
			if (useCustom) {
				response.addHeader("redirect", customRedirect);
				String encodedCustomRedirect = Encode.forHtml(customRedirect);
				response.sendError(302, "Need to redirect to " + encodedCustomRedirect);
			} else if (endpoint) {
				String redirectUrl = session.getAttribute(Constants.ENDPOINT_REDIRECT_KEY) + "";
				response.addHeader("redirect", redirectUrl);
				response.sendError(302, "Need to redirect to " + redirectUrl);
			} else {
				response.sendRedirect(socialData.getProperty("redirect"));
			}
		} catch (IOException e) {
			classLogger.error(Constants.STACKTRACE, e);
		}
	}

	/**
	 * Handle MCP OAuth callback - generate authorization code and redirect to ChatGPT
	 */
	private void handleMCPAuthorizationCallback(HttpServletRequest request, HttpServletResponse response,
			HttpSession session, Map<String, String> mcpRequest) {
		try {
			// Get authenticated user from session
			User user = (User) session.getAttribute(Constants.SESSION_USER);
			if (user == null) {
				classLogger.error("MCP callback: No authenticated user in session");
				response.sendError(500, "Authentication failed");
				return;
			}

			// Generate authorization code
			String authorizationCode = UUID.randomUUID().toString();
			String chatgptRedirectUri = mcpRequest.get("redirect_uri");
			String state = mcpRequest.get("state");

			// Store authorization code with user and auth request (contains code_challenge)
			MCPAuthorizationCodeStore.getInstance().storeCode(authorizationCode, user, mcpRequest);

			// Clear MCP request from session
			session.removeAttribute("mcp_auth_request");

			// Redirect to ChatGPT's redirect_uri with code and state
			String redirectUrl = chatgptRedirectUri +
				(chatgptRedirectUri.contains("?") ? "&" : "?") +
				"code=" + authorizationCode +
				"&state=" + state;

			classLogger.info("MCP callback: Redirecting to ChatGPT with authorization code for user: " +
				user.getPrimaryLoginToken().getEmail());
			classLogger.info("MCP callback: Full redirect URL: " + redirectUrl);
			classLogger.info("MCP callback: ChatGPT redirect_uri was: " + chatgptRedirectUri);
			response.setStatus(302);
			response.sendRedirect(redirectUrl);

		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			try {
				response.sendError(500, "MCP callback failed");
			} catch (IOException ioe) {
				classLogger.error(Constants.STACKTRACE, ioe);
			}
		}
	}

	//////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////

	/**
	 * Sharing session
	 */

	@GET
	@Produces("application/json")
	@Path("/whoami")
	public Response show(@Context HttpServletRequest request, @Context HttpServletResponse response) {
		Principal principal = request.getUserPrincipal();
		Map<String, Object> output = new HashMap<>();
		if (principal != null) {
			output.put("name", principal.getName());
			if (principal instanceof WindowsPrincipal) {
				WindowsPrincipal windowsPrincipal = (WindowsPrincipal) principal;
				List<Map<String, Object>> gropus = new ArrayList<>();
				for (waffle.windows.auth.WindowsAccount account : windowsPrincipal.getGroups().values()) {
					Map<String, Object> m = new HashMap<>();
					m.put("name", account.getName());
					m.put("domain", account.getDomain());
					m.put("fqn", account.getFqn());
					m.put("sid", account.getSidString());
					gropus.add(m);
				}
				output.put("groups", gropus);
			}
		} else {
			HttpSession session = request.getSession(false);
			if (session != null) {
				User user = (User) session.getAttribute(Constants.SESSION_USER);
				if (user != null) {
					AccessToken token = user.getAccessToken(user.getPrimaryLogin());
					output.put("name", token.getId());
					output.put("warning", "null principal - grab from user");
				}
			}
			if (output.isEmpty()) {
				output.put(Constants.ERROR_MESSAGE, "null principal");
			}
		}
		return WebUtility.getResponse(output, 200);
	}

	//////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////

	/**
	 * Helper method to construct base URL for MCP OAuth
	 */
	private String getBaseUrlForMCP(HttpServletRequest request) {
		// Check for proxy headers (set by ngrok, cloudflare, etc.)
		String forwardedProto = request.getHeader("X-Forwarded-Proto");
		String forwardedHost = request.getHeader("X-Forwarded-Host");
		String host = request.getHeader("Host");

		classLogger.info("Base URL detection - X-Forwarded-Proto: " + forwardedProto +
			", X-Forwarded-Host: " + forwardedHost + ", Host: " + host);

		// Determine scheme
		String scheme = request.getScheme();
		if (forwardedProto != null) {
			scheme = forwardedProto;
		} else if (host != null && (host.contains(".trycloudflare.com") ||
			host.contains(".ngrok.app") || host.contains(".ngrok.io") ||
			host.contains(".loca.lt") || host.contains(".serveo.net"))) {
			// Tunnel services always use HTTPS
			scheme = "https";
		}

		// Determine server name: prefer X-Forwarded-Host, then Host header, then request server name
		String serverName = request.getServerName();
		if (forwardedHost != null) {
			serverName = forwardedHost;
		} else if (host != null) {
			// Host header includes port, so we need to extract just the hostname
			serverName = host.contains(":") ? host.substring(0, host.indexOf(":")) : host;
		}

		int serverPort = request.getServerPort();
		String contextPath = request.getContextPath();

		StringBuilder url = new StringBuilder();
		url.append(scheme).append("://").append(serverName);

		// Don't include port if:
		// 1. Using a proxy (forwardedHost or forwardedProto is set), OR
		// 2. Using standard HTTP/HTTPS ports, OR
		// 3. Host header already includes port, OR
		// 4. Using a tunnel service
		boolean isTunnel = host != null && (host.contains(".trycloudflare.com") ||
			host.contains(".ngrok.app") || host.contains(".ngrok.io"));
		boolean shouldSkipPort = forwardedHost != null || forwardedProto != null || isTunnel ||
			(host != null && host.contains(":")) ||
			(scheme.equals("http") && serverPort == 80) ||
			(scheme.equals("https") && serverPort == 443);

		if (!shouldSkipPort) {
			url.append(":").append(serverPort);
		}

		url.append(contextPath);

		String finalUrl = url.toString();
		classLogger.info("Constructed base URL: " + finalUrl);
		return finalUrl;
	}

	/**
	 * JWKS (JSON Web Key Set) Endpoint
	 * Returns our public key for verifying JWT tokens
	 */
	@GET
	@Path("/.well-known/jwks.json")
	@Produces("application/json")
	public Response getJWKS(@Context HttpServletRequest request) {
		try {
			Map<String, Object> jwks = prerna.auth.mcp.MCPKeyManager.getInstance().getJWKS();
			return WebUtility.getResponse(jwks, 200);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> error = new HashMap<>();
			error.put("error", "internal_error");
			return WebUtility.getResponse(error, 500);
		}
	}

	/**
	 * Dynamic Client Registration (DCR) Endpoint - RFC 7591
	 * Allows ChatGPT to automatically register as an OAuth client
	 */
	@POST
	@Path("/oauth/register")
	@Consumes("application/json")
	@Produces("application/json")
	public Response registerOAuthClient(String jsonBody, @Context HttpServletRequest request) {
		try {
			classLogger.info("OAuth Dynamic Client Registration request");

			// Parse registration request
			com.google.gson.Gson gson = new com.google.gson.Gson();
			@SuppressWarnings("unchecked")
			Map<String, Object> requestMap = gson.fromJson(jsonBody, Map.class);

			// Extract redirect_uris
			@SuppressWarnings("unchecked")
			List<String> redirectUris = (List<String>) requestMap.get("redirect_uris");
			if (redirectUris == null || redirectUris.isEmpty()) {
				Map<String, String> error = new HashMap<>();
				error.put("error", "invalid_redirect_uri");
				error.put("error_description", "redirect_uris is required");
				return WebUtility.getResponse(error, 400);
			}

			// Extract grant_types (default to authorization_code)
			@SuppressWarnings("unchecked")
			List<String> grantTypes = (List<String>) requestMap.get("grant_types");
			if (grantTypes == null) {
				grantTypes = java.util.Arrays.asList("authorization_code");
			}

			// Extract token_endpoint_auth_method (default to none for public clients)
			String tokenAuthMethod = (String) requestMap.get("token_endpoint_auth_method");
			if (tokenAuthMethod == null) {
				tokenAuthMethod = "none";
			}

			// Generate client_id and client_secret
			String clientId = java.util.UUID.randomUUID().toString();
			String clientSecret = tokenAuthMethod.equals("none") ? null :
				java.util.Base64.getUrlEncoder().withoutPadding()
					.encodeToString(new java.security.SecureRandom().generateSeed(32));

			// Store client registration
			prerna.auth.mcp.MCPClientStore.ClientRegistration registration =
				new prerna.auth.mcp.MCPClientStore.ClientRegistration(
					clientId, clientSecret, redirectUris, grantTypes, tokenAuthMethod);
			prerna.auth.mcp.MCPClientStore.getInstance().registerClient(clientId, registration);

			classLogger.info("Registered OAuth client: " + clientId);

			// Build response per RFC 7591
			Map<String, Object> response = new HashMap<>();
			response.put("client_id", clientId);
			if (clientSecret != null) {
				response.put("client_secret", clientSecret);
			}
			response.put("redirect_uris", redirectUris);
			response.put("grant_types", grantTypes);
			response.put("token_endpoint_auth_method", tokenAuthMethod);
			response.put("client_id_issued_at", System.currentTimeMillis() / 1000);

			return WebUtility.getResponse(response, 201);

		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> error = new HashMap<>();
			error.put("error", "invalid_client_metadata");
			return WebUtility.getResponse(error, 400);
		}
	}

	//////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////

	/**
	 * MCP OAuth Authorization Flow
	 * These endpoints handle the OAuth 2.1 authorization code flow with PKCE
	 */

	/**
	 * MCP Authorization Endpoint - Start OAuth flow
	 * ChatGPT redirects user here to authenticate
	 */
	@GET
	@Path("/mcp/authorize")
	@Produces("application/json")
	public Response initiateMCPAuthorization(
			@QueryParam("client_id") String clientId,
			@QueryParam("redirect_uri") String redirectUri,
			@QueryParam("state") String state,
			@QueryParam("code_challenge") String codeChallenge,
			@QueryParam("code_challenge_method") String codeChallengeMethod,
			@QueryParam("resource") String resource,
			@QueryParam("provider") String provider,
			@Context HttpServletRequest request) {

		try {
			classLogger.info("MCP authorization request from client: " +
				WebUtility.inputSanitizer(clientId));

			// Validate redirect_uri is from ChatGPT
			if (redirectUri == null ||
				(!redirectUri.equals("https://chatgpt.com/connector_platform_oauth_redirect") &&
				 !redirectUri.equals("https://platform.openai.com/apps-manage/oauth"))) {
				Map<String, String> error = new HashMap<>();
				error.put("error", "invalid_request");
				error.put("error_description", "redirect_uri must be a valid ChatGPT callback URL");
				return WebUtility.getResponse(error, 400);
			}

			// Validate PKCE
			if (!"S256".equals(codeChallengeMethod)) {
				Map<String, String> error = new HashMap<>();
				error.put("error", "invalid_request");
				error.put("error_description", "code_challenge_method must be S256");
				return WebUtility.getResponse(error, 400);
			}

			// Choose OAuth provider (default to github if not specified)
			String authProvider = (provider != null && !provider.isEmpty()) ?
				provider.toLowerCase() : "github";

			// Store MCP request in session so /auth/login/<provider> can complete the flow
			HttpSession session = request.getSession(true);
			Map<String, String> mcpRequest = new HashMap<>();
			mcpRequest.put("client_id", clientId);
			mcpRequest.put("redirect_uri", redirectUri);
			mcpRequest.put("state", state);
			mcpRequest.put("code_challenge", codeChallenge);
			mcpRequest.put("resource", resource); // Echo resource parameter
			mcpRequest.put("provider", authProvider);
			session.setAttribute("mcp_auth_request", mcpRequest);

			classLogger.info("Stored MCP auth request in session for provider: " + authProvider);

			// Build the OAuth provider's authorization URL
			// The OAuth provider will redirect back to our /auth/login/<provider> endpoint
			String authUrl = getOAuthProviderUrl(authProvider, request);
			if (authUrl == null) {
				Map<String, String> error = new HashMap<>();
				error.put("error", "unsupported_provider");
				error.put("error_description", "Provider " + authProvider + " is not configured");
				return WebUtility.getResponse(error, 400);
			}

			// Redirect (302) to the OAuth provider's authorization page
			// This is what ChatGPT expects - a redirect, not JSON
			classLogger.info("Redirecting to " + authProvider + " OAuth URL for MCP auth");
			return Response.status(302)
				.header("Location", authUrl)
				.build();

		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> error = new HashMap<>();
			error.put("error", "server_error");
			return WebUtility.getResponse(error, 500);
		}
	}

	/**
	 * Get OAuth provider's authorization URL
	 */
	private String getOAuthProviderUrl(String provider, HttpServletRequest request)
			throws UnsupportedEncodingException {
		String prefix = provider + "_";
		String clientId = socialData.getProperty(prefix + "client_id");

		if (clientId == null) {
			return null;
		}

		// Build redirect URI using the actual base URL from the request
		// This ensures it works with Cloudflare tunnels or any public URL
		String baseUrl = getBaseUrlForMCP(request);
		String oauthRedirectUri = baseUrl + "/api/auth/login/" + provider;

		switch (provider) {
			case "github":
				String githubScope = socialData.getProperty(prefix + "scope");
				return "https://github.com/login/oauth/authorize?" +
					"client_id=" + clientId +
					"&redirect_uri=" + URLEncoder.encode(oauthRedirectUri, UTF8) +
					"&state=" + UUID.randomUUID().toString() +
					"&allow_signup=true" +
					"&scope=" + URLEncoder.encode(githubScope, UTF8);

			case "google":
				String googleScope = socialData.getProperty(prefix + "scope");
				return "https://accounts.google.com/o/oauth2/v2/auth?" +
					"client_id=" + clientId +
					"&redirect_uri=" + URLEncoder.encode(oauthRedirectUri, UTF8) +
					"&response_type=code" +
					"&scope=" + URLEncoder.encode(googleScope, UTF8) +
					"&state=" + UUID.randomUUID().toString();

			case "microsoft":
				String msScope = socialData.getProperty(prefix + "scope");
				String msTenant = socialData.getProperty(prefix + "tenant", "common");
				return "https://login.microsoftonline.com/" + msTenant + "/oauth2/v2.0/authorize?" +
					"client_id=" + clientId +
					"&redirect_uri=" + URLEncoder.encode(oauthRedirectUri, UTF8) +
					"&response_type=code" +
					"&scope=" + URLEncoder.encode(msScope, UTF8) +
					"&state=" + UUID.randomUUID().toString();

			default:
				return null;
		}
	}


	/**
	 * MCP Callback Endpoint - Handle return from Semoss login
	 * User is authenticated, generate authorization code
	 */
	@GET
	@Path("/mcp/callback")
	public Response handleMCPCallback(@Context HttpServletRequest request) {
		try {
			HttpSession session = request.getSession(false);
			if (session == null) {
				classLogger.warn("MCP callback - no session");
				Map<String, String> error = new HashMap<>();
				error.put("error", "session_expired");
				return WebUtility.getResponse(error, 400);
			}

			// Get user from session (set by /login or /loginLDAP)
			User user = (User) session.getAttribute(Constants.SESSION_USER);
			if (user == null) {
				classLogger.warn("MCP callback - user not authenticated");
				Map<String, String> error = new HashMap<>();
				error.put("error", "authentication_required");
				return WebUtility.getResponse(error, 401);
			}

			classLogger.info("MCP callback - user authenticated: " +
				WebUtility.inputSanitizer(user.getPrimaryLogin().toString()));

			// Get stored MCP request
			@SuppressWarnings("unchecked")
			Map<String, String> mcpRequest =
				(Map<String, String>) session.getAttribute("mcp_auth_request");

			if (mcpRequest == null) {
				classLogger.warn("MCP callback - no stored auth request");
				Map<String, String> error = new HashMap<>();
				error.put("error", "invalid_request");
				return WebUtility.getResponse(error, 400);
			}

			// Generate authorization code
			String authCode = generateSecureCode();
			MCPAuthorizationCodeStore.getInstance().storeCode(authCode, user, mcpRequest);

			classLogger.info("MCP callback - generated auth code");

			// Redirect back to ChatGPT
			String redirectUriParam = mcpRequest.get("redirect_uri");
			String stateParam = mcpRequest.get("state");
			String redirectUrl = redirectUriParam + "?code=" + authCode + "&state=" + stateParam;

			return Response.seeOther(URI.create(redirectUrl)).build();

		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> error = new HashMap<>();
			error.put("error", "server_error");
			return WebUtility.getResponse(error, 500);
		}
	}

	/**
	 * MCP Token Exchange Endpoint - Exchange authorization code for access token
	 * ChatGPT calls this with the code to get an access token
	 */
	@POST
	@Path("/mcp/token")
	@Consumes("application/x-www-form-urlencoded")
	@Produces("application/json")
	public Response exchangeMCPToken(
			@FormParam("grant_type") String grantType,
			@FormParam("code") String code,
			@FormParam("code_verifier") String codeVerifier,
			@FormParam("resource") String resource,
			@Context HttpServletRequest request) {

		classLogger.info(">>>>> MCP TOKEN ENDPOINT CALLED <<<<<");
		classLogger.info("MCP token exchange request - grant_type: " + grantType + ", code: " +
			(code != null ? "present" : "null") + ", code_verifier: " +
			(codeVerifier != null ? "present" : "null"));
		try {

			// Validate grant type
			if (!"authorization_code".equals(grantType)) {
				Map<String, String> error = new HashMap<>();
				error.put("error", "unsupported_grant_type");
				return WebUtility.getResponse(error, 400);
			}

			// Consume authorization code (one-time use)
			MCPAuthorizationCodeStore.AuthorizationCode authCode =
				MCPAuthorizationCodeStore.getInstance().consumeCode(code);

			if (authCode == null) {
				classLogger.warn("MCP token exchange - invalid code");
				Map<String, String> error = new HashMap<>();
				error.put("error", "invalid_grant");
				return WebUtility.getResponse(error, 400);
			}

			// Verify PKCE
			if (!verifyPKCE(codeVerifier, authCode.getCodeChallenge())) {
				classLogger.warn("MCP token exchange - PKCE verification failed");
				Map<String, String> error = new HashMap<>();
				error.put("error", "invalid_grant");
				return WebUtility.getResponse(error, 400);
			}

			// Get client_id and resource from auth request
			String clientId = authCode.getAuthRequest().get("client_id");
			if (resource == null) {
				resource = authCode.getAuthRequest().get("resource");
			}

			classLogger.info("MCP token exchange - resource parameter: " + resource);

			// Generate JWT access token
			String baseUrl = getBaseUrlForMCP(request);
			String issuer = baseUrl + "/api/mcp";  // Issuer matches the MCP server endpoint
			String audience = resource != null ? resource : baseUrl + "/api/mcp";
			long expiresInSeconds = 3600; // 1 hour

			classLogger.info("MCP token exchange - JWT issuer: " + issuer);
			classLogger.info("MCP token exchange - JWT audience: " + audience);

			String accessToken = prerna.auth.mcp.MCPJWTHelper.createJWT(
				authCode.getUser(),
				clientId,
				issuer,
				audience,
				expiresInSeconds
			);

			// Store token for validation (JWT tokens are self-contained, but we keep a record)
			MCPTokenStore.getInstance().storeToken(accessToken, authCode.getUser());

			classLogger.info("MCP token exchange - success for user: " +
				WebUtility.inputSanitizer(authCode.getUser().getPrimaryLogin().toString()));

			Map<String, Object> response = new HashMap<>();
			response.put("access_token", accessToken);
			response.put("token_type", "Bearer");
			response.put("expires_in", expiresInSeconds);

			return WebUtility.getResponse(response, 200);

		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> error = new HashMap<>();
			error.put("error", "server_error");
			return WebUtility.getResponse(error, 500);
		}
	}

	/**
	 * Helper method to generate secure random codes/tokens
	 */
	private String generateSecureCode() {
		SecureRandom random = new SecureRandom();
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/**
	 * Helper method for PKCE verification
	 */
	private boolean verifyPKCE(String verifier, String challenge) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
			String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
			return computed.equals(challenge);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			return false;
		}
	}

}
