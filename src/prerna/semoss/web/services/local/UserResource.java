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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
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
import javax.ws.rs.core.StreamingOutput;

import org.apache.http.client.ClientProtocolException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kohsuke.github.GHMyself;
import org.kohsuke.github.GitHub;
import org.owasp.encoder.Encode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import jodd.util.URLDecoder;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.InsightToken;
import prerna.auth.SyncUserAppsThread;
import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAPIUserUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityNativeUserUtils;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.io.connector.GenericProfile;
import prerna.io.connector.IConnectorIOp;
import prerna.io.connector.google.GoogleEntityResolver;
import prerna.io.connector.google.GoogleFileRetriever;
import prerna.io.connector.google.GoogleLatLongGetter;
import prerna.io.connector.google.GoogleListFiles;
import prerna.io.connector.google.GoogleProfile;
import prerna.io.connector.ms.MSProfile;
import prerna.io.connector.surveymonkey.MonkeyProfile;
import prerna.io.connector.twitter.TwitterSearcher;
import prerna.om.NLPDocumentInput;
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
public class UserResource {

	private static final Logger classLogger = LogManager.getLogger(UserResource.class);

	private static final String CUSTOM_REDIRECT_SESSION_KEY = "custom_redirect";

	private static SocialPropertiesUtil socialData = null;
	static {
		socialData = SocialPropertiesUtil.getInstance();
	}

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
	public Response logout(@PathParam("provider") String provider, @QueryParam("disableRedirect") boolean disableRedirect,
			@Context HttpServletRequest request,
			@Context HttpServletResponse response) throws IOException {
		boolean noUser = false;
		boolean removed = false;

		HttpSession session = request.getSession();
		User thisUser = (User) session.getAttribute(Constants.SESSION_USER);
		if(thisUser == null) {
			Map<String, Object> ret = new Hashtable<>();
			ret.put("success", false);
			ret.put(Constants.ERROR_MESSAGE, "No user is currently logged in the session");
			return WebUtility.getResponse(ret, 400);
		}

		// log the user logout
		classLogger.info(ResourceUtility.getLogMessage(request, session, User.getSingleLogginName(thisUser), "is logging out of provider " +  provider));

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
		if(noUser) {
			// when we call session.invalidate() the UserSessionLoader will properly log is user logout or tomcat ending
			session.setAttribute(UserSessionLoader.IS_USER_LOGOUT, true);
			classLogger.info(ResourceUtility.getLogMessage(request, session, User.getSingleLogginName(thisUser), "has logged out from all providers in the session"));
			// well, you have logged out and we always require a login
			// so i will redirect you by default unless you specifically say not to
			if(!disableRedirect) {
				response.setStatus(302);

				String customUrl = DBLoader.getCustomLogoutUrl();
				if (customUrl != null && !customUrl.isEmpty()) {
					response.setHeader("redirect", customUrl);
					response.sendError(302, "Need to redirect to " + customUrl);
				} else {
					String redirectUrl = request.getHeader("referer");
					if (DBLoader.useLogoutPage()) {
						String scheme = request.getScheme();
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
						response.setHeader("redirect", redirectUrl);
						response.sendError(302, "Need to redirect to " + redirectUrl);
					} else {
						redirectUrl = redirectUrl + "#!/login";
						String encodedRedirectUrl = Encode.forHtml(redirectUrl);
						response.setHeader("redirect", encodedRedirectUrl);
						response.sendError(302, "Need to redirect to " + encodedRedirectUrl);
					}
				}
			}

			// remove the cookie from the browser
			// for the session id
			classLogger.info("Removing session token");
			Cookie[] cookies = request.getCookies();
			nullCookies = new ArrayList<>();
			if (cookies != null) {
				for (Cookie c : cookies) {
					if (DBLoader.getSessionIdKey().equals(c.getName())) {
						// we need to null this out
						NewCookie nullC = new NewCookie(c.getName(), c.getValue(), c.getPath(), 
								c.getDomain(), c.getComment(), 0, c.getSecure());
						nullCookies.add(nullC);
					}
				}
			}
			// invalidate the session
			session.invalidate();
		}

		Map<String, Boolean> ret = new Hashtable<>();
		ret.put("success", removed);
		if(nullCookies == null) {
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
	private void addAccessToken(AccessToken token, HttpServletRequest request, boolean autoAdd) {
		HttpSession session = request.getSession();
		User semossUser = (User) session.getAttribute(Constants.SESSION_USER);
		// all of this is now in the user
		if (semossUser == null) {
			semossUser = new User();
			session.setAttribute(Constants.SESSION_USER_ID_LOG, token.getId());
		}
		semossUser.setAccessToken(token);
		semossUser.setAnonymous(false);
		session.setAttribute(Constants.SESSION_USER, semossUser);

		UserResource.userTrackingLogin(request, semossUser, token.getProvider());

		// log the user login
		classLogger.info(ResourceUtility.getLogMessage(request, session, User.getSingleLogginName(semossUser), "is logging in with provider " +  token.getProvider()));

		// add new users into the database
		if(autoAdd) {
			SecurityUpdateUtils.addOAuthUser(token);
		}
	}

	public static void userTrackingLogin(HttpServletRequest request, User semossUser, AuthProvider ap) {
		String ip = ResourceUtility.getClientIp(request);
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
		Map<String, String> ret = new Hashtable<>();
		HttpSession session = request.getSession(false);
		User semossUser = null;
		if (session != null) {
			semossUser = (User) session.getAttribute(Constants.SESSION_USER);
		}

		if (semossUser == null) {
			List<NewCookie> newCookies = new ArrayList<>();
			// not authenticated
			// remove any cookies we shouldn't have
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
		Hashtable params = new Hashtable();
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
	public Response userinfoMs(@Context HttpServletRequest request) {
		Map<String, String> ret = new Hashtable<>();
		HttpSession session = request.getSession(false);
		User semossUser = null;
		if (session != null) {
			semossUser = (User) session.getAttribute(Constants.SESSION_USER);
		}

		if (semossUser == null) {
			List<NewCookie> newCookies = new ArrayList<>();
			// not authenticated
			// remove any cookies we shouldn't have
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
			AccessToken msToken = semossUser.getAccessToken(AuthProvider.MS);
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
		Map<String, String> ret = new Hashtable<>();
		HttpSession session = request.getSession(false);
		User semossUser = null;
		if (session != null) {
			semossUser = (User) session.getAttribute(Constants.SESSION_USER);
		}

		if (semossUser == null) {
			List<NewCookie> newCookies = new ArrayList<>();
			// not authenticated
			// remove any cookies we shouldn't have
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

			if (session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
				session.invalidate();
			}

			ret.put(Constants.ERROR_MESSAGE, "Log into your ADFS account");
			return WebUtility.getResponseNoCache(ret, 200, newCookies.toArray(new NewCookie[] {}));
		}

		String prefix="adfs_";
		String beanProps = socialData.getProperty(prefix + "beanProps");
		String jsonPattern = socialData.getProperty(prefix + "jsonPattern");

		String[] beanPropsArr = beanProps.split(",", -1);

		String accessString = null;
		try {
			AccessToken adfsToken = semossUser.getAccessToken(AuthProvider.ADFS);
			accessString = adfsToken.getAccess_token();
			String json = decodeTokenPayload(adfsToken.getAccess_token());
			adfsToken = (AccessToken)BeanFiller.fillFromJson(json, jsonPattern, beanPropsArr, adfsToken);
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
		Map<String, String> ret = new Hashtable<>();
		HttpSession session = request.getSession(false);
		User semossUser = null;
		if (session != null) {
			semossUser = (User) session.getAttribute(Constants.SESSION_USER);
		}

		if (semossUser == null) {
			List<NewCookie> newCookies = new ArrayList<>();
			// not authenticated
			// remove any cookies we shouldn't have
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
		Map<String, String> ret = new Hashtable<>();
		HttpSession session = request.getSession(false);
		User semossUser = null;
		if (session != null) {
			semossUser = (User) session.getAttribute(Constants.SESSION_USER);
		}

		if (semossUser == null) {
			List<NewCookie> newCookies = new ArrayList<>();
			// not authenticated
			// remove any cookies we shouldn't have
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

	/**
	 * Gets user info for Generic Providers
	 */
	@GET
	@Produces("application/json")
	@Path("/userinfo/{provider}")
	public Response userinfoGeneric(@PathParam("provider") String provider, @Context HttpServletRequest request) {
		AuthProvider providerEnum = AuthProvider.getProviderFromString(provider.toUpperCase());
		Map<String, String> ret = new Hashtable<>();
		HttpSession session = request.getSession(false);
		User semossUser = null;
		if (session != null) {
			semossUser = (User) session.getAttribute(Constants.SESSION_USER);
		}

		if (semossUser == null) {
			List<NewCookie> newCookies = new ArrayList<>();
			// not authenticated
			// remove any cookies we shouldn't have
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

			if (session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
				session.invalidate();
			}

			ret.put(Constants.ERROR_MESSAGE, "Log into your " + providerEnum.toString() + " account");
			return WebUtility.getResponseNoCache(ret, 200, newCookies.toArray(new NewCookie[] {}));
		}

		String prefix = provider+"_";
		String userInfoURL = socialData.getProperty(prefix + "userinfo_url");
		//"name","id","email"
		String beanProps = socialData.getProperty(prefix + "beanProps");
		String jsonPattern = socialData.getProperty(prefix + "jsonPattern");

		String[] beanPropsArr = beanProps.split(",", -1);

		String accessString = null;
		try {
			AccessToken genericToken = semossUser.getAccessToken(providerEnum);
			accessString = genericToken.getAccess_token();
			//String url = "https://graph.microsoft.com/v1.0/me/";
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
	@Path("/login/sf")
	public Response loginSF(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException {
		/*
		 * Try to log in the user
		 * If they are not logged in
		 * Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if(session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = Utility.cleanHttpResponse(request.getParameter("redirect"));
		if(customRedirect != null && !customRedirect.isEmpty()) {
			if(session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}
		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.SF) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				String prefix = "sf_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");
				boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

				if(classLogger.isDebugEnabled()) {
					classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
				}

				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("grant_type", "authorization_code");
				params.put("redirect_uri", redirectUri);
				params.put("code", URLDecoder.decode(outputs[0]));
				params.put("client_secret", clientSecret);

				String url = "https://login.salesforce.com/services/oauth2/token";

				AccessToken accessToken = HttpHelperUtility.getAccessToken(url, params, true, true);
				if (accessToken == null) {
					// not authenticated
					response.setStatus(302);
					response.sendRedirect(getSFRedirect(request));
					return null;
				}
				accessToken.setProvider(AuthProvider.SF);
				addAccessToken(accessToken, request, autoAdd);

				if(classLogger.isDebugEnabled()) {
					classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
			}
		}

		// grab the user again
		if(session != null || (session=request.getSession(false)) != null) {
			userObj = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if (userObj == null || userObj.getAccessToken(AuthProvider.SF) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getSFRedirect(request));
			return null;
		}

		setMainPageRedirect(request, response);
		return null;
	}

	private String getSFRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "sf_";
		String clientId = socialData.getProperty(prefix + "client_id");
		String redirectUri = socialData.getProperty(prefix + "redirect_uri");

		String redirectUrl = "https://login.salesforce.com/services/oauth2/authorize?" + "client_id=" + clientId
				+ "&response_type=code" + "&redirect_uri=" + redirectUri + "&scope="
				+ URLEncoder.encode("api", "UTF-8");

		if(classLogger.isDebugEnabled()) {
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
	public Response loginSurveyMonkey(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException {
		/*
		 * Try to log in the user
		 * If they are not logged in
		 * Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if(session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = Utility.cleanHttpResponse(request.getParameter("redirect"));
		if(customRedirect != null && !customRedirect.isEmpty()) {
			if(session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}
		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.SURVEYMONKEY) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				String prefix = "surveymonkey_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");
				boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

				if(classLogger.isDebugEnabled()) {
					classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
				}

				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("grant_type", "authorization_code");
				params.put("redirect_uri", redirectUri);
				params.put("code", URLDecoder.decode(outputs[0]));
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

				if(classLogger.isDebugEnabled()) {
					classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
			}
		}

		// grab the user again
		if(session != null || (session=request.getSession(false)) != null) {
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

		if(classLogger.isDebugEnabled()) {
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
	public Response loginGithub(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException {
		/*
		 * Try to log in the user
		 * If they are not logged in
		 * Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if(session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = Utility.cleanHttpResponse(request.getParameter("redirect"));
		if(customRedirect != null && !customRedirect.isEmpty()) {
			if(session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}

		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.GITHUB) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				String prefix = "github_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");
				boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

				if(classLogger.isDebugEnabled()) {
					classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
				}

				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("state", outputs[1]);
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

				GitRepoUtils.addCertForDomain(url);
				// add specific Git values
				GHMyself myGit = GitHub.connectUsingOAuth(accessToken.getAccess_token()).getMyself();
				accessToken.setId(myGit.getId() + "");
				accessToken.setEmail(myGit.getEmail());
				accessToken.setName(myGit.getName());
				accessToken.setLocale(myGit.getLocation());
				accessToken.setUsername(myGit.getLogin());
				addAccessToken(accessToken, request, autoAdd);

				if(classLogger.isDebugEnabled()) {
					classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
			}
		}

		// grab the user again
		if(session != null || (session=request.getSession(false)) != null) {
			userObj = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if (userObj == null || userObj.getAccessToken(AuthProvider.GITHUB) == null) {
			// not authenticated
			GitRepoUtils.addCertForDomain("https://github.com");
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
				+ URLEncoder.encode(scope, "UTF-8");

		if(classLogger.isDebugEnabled()) {
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
	public Response loginGitlab(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException {
		/*
		 * Try to log in the user
		 * If they are not logged in
		 * Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if(session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = Utility.cleanHttpResponse(request.getParameter("redirect"));
		if(customRedirect != null && !customRedirect.isEmpty()) {
			if(session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}

		String queryString = request.getQueryString();
		String prefix = "gitlab_";

		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.GITLAB) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");
				String token_url = socialData.getProperty(prefix + "token_url");
				boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

				if(classLogger.isDebugEnabled()) {
					classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
				}

				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("state", outputs[1]);
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

				//GitRepoUtils.addCertForDomain(url);
				// add specific Git values

				String beanProps = socialData.getProperty(prefix + "beanProps");
				String jsonPattern = socialData.getProperty(prefix + "jsonPattern");
				String userinfo_url = socialData.getProperty(prefix + "userinfo_url");
				String[] beanPropsArr = beanProps.split(",", -1);


				String output = HttpHelperUtility.makeGetCall(userinfo_url, accessToken.getAccess_token(), null, true);
				accessToken = (AccessToken)BeanFiller.fillFromJson(output, jsonPattern, beanPropsArr, accessToken);

				addAccessToken(accessToken, request, autoAdd);

				if(classLogger.isDebugEnabled()) {
					classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
			}
		}

		// grab the user again
		if(session != null || (session=request.getSession(false)) != null) {
			userObj = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if (userObj == null || userObj.getAccessToken(AuthProvider.GITLAB) == null) {
			// not authenticated
			//			GitRepoUtils.addCertForDomain("https://github.com");
			response.setStatus(302);
			response.sendRedirect(getGitlabRedirect(request));
			return null;
		}

		if(Boolean.parseBoolean(socialData.getProperty(prefix + "groups"))){
			//get groups
			String group_url = socialData.getProperty(prefix + "group_url");
			String groupsJson = HttpHelperUtility.makeGetCall(group_url,  userObj.getAccessToken(AuthProvider.GITLAB).getAccess_token());
			System.out.println(groupsJson);
			String groupJsonPattern = socialData.getProperty(prefix + "groupJsonPattern");
			//String beanProps = socialData.getProperty(prefix + "groupBeanProps");
			//String[] beanPropsArr = beanProps.split(",", -1);
			Set<String> userGroups = new HashSet<String>();


			JsonNode result = BeanFiller.getJmesResult(groupsJson, groupJsonPattern);
			if((result instanceof ArrayNode) && result.get(0) instanceof ObjectNode) {
				throw new SemossPixelException("Group result must return flat array. Please check groupJsonPatter");
			}
			for(int inputIndex = 0;result != null && inputIndex < result.size();inputIndex++) {
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

		String redirectUrl = auth_url + "?" + "client_id="
				+ clientId + "&response_type=code" + "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8")
				+ "&scope=" + scope + "&state=" + state;

		classLogger.info("Sending redirect.. " + Utility.cleanLogString(redirectUrl));

		if(classLogger.isDebugEnabled()) {
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
	public Response loginMS(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException {
		/*
		 * Try to log in the user
		 * If they are not logged in
		 * Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if(session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = Utility.cleanHttpResponse(request.getParameter("redirect"));
		if(customRedirect != null && !customRedirect.isEmpty()) {
			if(session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}

		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || ((User) userObj).getAccessToken(AuthProvider.MS) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				String prefix = "ms_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");
				String tenant = socialData.getProperty(prefix + "tenant");
				String scope = socialData.getProperty(prefix + "scope");
				String token_url = socialData.getProperty(prefix + "token_url");
				boolean login_external_allowed = Boolean.parseBoolean(socialData.getProperty(prefix + "login_external"));

				boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

				if(classLogger.isDebugEnabled()) {
					classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
				}

				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("scope", scope);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("grant_type", "authorization_code");
				params.put("client_secret", clientSecret);

				if(Strings.isNullOrEmpty(token_url)){
					token_url = "https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/token";
				}

				AccessToken accessToken = HttpHelperUtility.getAccessToken(token_url, params, true, true);
				if (accessToken == null) {
					// not authenticated
					response.setStatus(302);
					response.sendRedirect(getMSRedirect(request));
					return null;
				}

				accessToken.setProvider(AuthProvider.MS);
				MSProfile.fillAccessToken(accessToken, null);
				if(!login_external_allowed) {
					if(accessToken.getName().contains("External")) {
						accessToken = null;
						throw new IllegalArgumentException("External users are not allowed");
					}
				}
				addAccessToken(accessToken, request, autoAdd);

				if(classLogger.isDebugEnabled()) {
					classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
			}
		}

		// grab the user again
		if(session != null || (session=request.getSession(false)) != null) {
			userObj = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if (userObj == null || userObj.getAccessToken(AuthProvider.MS) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getMSRedirect(request));
			return null;
		}

		setMainPageRedirect(request, response);
		return null;
	}

	private String getMSRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "ms_";
		String clientId = socialData.getProperty(prefix + "client_id");
		String redirectUri = socialData.getProperty(prefix + "redirect_uri");
		String tenant = socialData.getProperty(prefix + "tenant");
		String scope = socialData.getProperty(prefix + "scope"); // need to set this up and reuse
		String auth_url = socialData.getProperty(prefix + "auth_url");

		String state = UUID.randomUUID().toString();

		if(Strings.isNullOrEmpty(auth_url)){
			auth_url = "https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/authorize";
		}
		String redirectUrl = auth_url + "?" + "client_id="
				+ clientId + "&response_type=code" + "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8")
				+ "&response_mode=query" + "&scope=" + URLEncoder.encode(scope) + "&state=" + state;

		if(classLogger.isDebugEnabled()) {
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
	public Response loginADFS(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException {
		/*
		 * Try to log in the user
		 * If they are not logged in
		 * Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if(session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = Utility.cleanHttpResponse(request.getParameter("redirect"));
		if(customRedirect != null && !customRedirect.isEmpty()) {
			if(session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}

		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || ((User) userObj).getAccessToken(AuthProvider.ADFS) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				String prefix = "adfs_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");
				String scope = socialData.getProperty(prefix + "scope");
				String token_url = socialData.getProperty(prefix + "token_url");
				boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

				if(classLogger.isDebugEnabled()) {
					classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
				}

				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("scope", scope);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("grant_type", "authorization_code");
				params.put("client_secret", clientSecret);


				if(Strings.isNullOrEmpty(token_url)){
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
				accessToken = (AccessToken)BeanFiller.fillFromJson(json, jsonPattern, beanPropsArr, accessToken);

				addAccessToken(accessToken, request, autoAdd);

				if(classLogger.isDebugEnabled()) {
					classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
			}
		}

		// grab the user again
		if(session != null || (session=request.getSession(false)) != null) {
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

	public String  decodeTokenPayload(String token)
	{
		String[] parts = token.split("\\.", 0);

		//	    for (String part : parts) {
		//	        byte[] bytes = Base64.getUrlDecoder().decode(part);
		//	        String decodedString = new String(bytes, StandardCharsets.UTF_8);
		//
		//	        System.out.println("Decoded: " + decodedString);
		//	    }
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

		if(Strings.isNullOrEmpty(auth_url)){
			throw new IllegalArgumentException("A URL can not be null or empty");
		}
		String redirectUrl = auth_url + "?" + "client_id="
				+ clientId + "&response_type=code" + "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8")
				+ "&response_mode=query" + "&scope=" + URLEncoder.encode(scope) + "&state=" + state;

		if(classLogger.isDebugEnabled()) {
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
	public Response loginSiteminder(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException {
		/*
		 * Try to log in the user
		 * If they are not logged in
		 * Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if(session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = Utility.cleanHttpResponse(request.getParameter("redirect"));
		if(customRedirect != null && !customRedirect.isEmpty()) {
			if(session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}

		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || ((User) userObj).getAccessToken(AuthProvider.SITEMINDER) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				String prefix = "siteminder_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");
				String tenant = socialData.getProperty(prefix + "tenant");
				String scope = socialData.getProperty(prefix + "scope");
				String token_url = socialData.getProperty(prefix + "token_url");
				boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

				if(classLogger.isDebugEnabled()) {
					classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
				}

				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("scope", scope);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("grant_type", "authorization_code");
				params.put("client_secret", clientSecret);

				if(Strings.isNullOrEmpty(token_url)){
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
				MSProfile.fillAccessToken(accessToken, null);
				addAccessToken(accessToken, request, autoAdd);

				if(classLogger.isDebugEnabled()) {
					classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
			}
		}

		// grab the user again
		if(session != null || (session=request.getSession(false)) != null) {
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

		if(Strings.isNullOrEmpty(auth_url)){
			auth_url = "https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/authorize";
		}

		String redirectUrl = auth_url + "?" + "client_id="
				+ clientId + "&response_type=code" + "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8")
				+ "&response_mode=query" + "&scope=" + URLEncoder.encode(scope) + "&state=" + state;

		if(classLogger.isDebugEnabled()) {
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
	public Response loginDropBox(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException {
		/*
		 * Try to log in the user
		 * If they are not logged in
		 * Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if(session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = Utility.cleanHttpResponse(request.getParameter("redirect"));
		if(customRedirect != null && !customRedirect.isEmpty()) {
			if(session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}

		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.DROPBOX) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				String prefix = "dropbox_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");
				boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

				if(classLogger.isDebugEnabled()) {
					classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
				}

				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
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

				if(classLogger.isDebugEnabled()) {
					classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
			}
		}

		// grab the user again
		if(session != null || (session=request.getSession(false)) != null) {
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
				+ "&response_type=code" + "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8") + "&require_role="
				+ role + "&disable_signup=false";

		if(classLogger.isDebugEnabled()) {
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
	public Response loginGoogle(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException {
		/*
		 * Try to log in the user
		 * If they are not logged in
		 * Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if(session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = Utility.cleanHttpResponse(request.getParameter("redirect"));
		if(customRedirect != null && !customRedirect.isEmpty()) {
			if(session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}

		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.GOOGLE) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				String prefix = "google_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");
				boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

				if(classLogger.isDebugEnabled()) {
					classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
				}

				// I need to decode the return code from google since the default param's are
				// encoded on the post of getAccessToken
				String codeDecode = URLDecoder.decode(outputs[0]);
				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("redirect_uri", redirectUri);
				params.put("code", codeDecode);
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
				GoogleProfile.fillAccessToken(accessToken, null);
				addAccessToken(accessToken, request, autoAdd);

				// Shows how to make a google credential from an access token
				if(classLogger.isDebugEnabled()) {
					classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
				}

				// this is just for testing...
				// but i will get yelled at if i remove it so here it is...
				// TODO: adding this todo to easily locate it
				//				performGoogleOps(request, ret);
			}
		}

		// grab the user again
		if(session != null || (session=request.getSession(false)) != null) {
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
				+ "&response_type=code" + "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8") + "&access_type="
				+ accessType + "&scope=" + URLEncoder.encode(scope, "UTF-8") + "&state=" + state;

		if(classLogger.isDebugEnabled()) {
			classLogger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
		}
		return redirectUrl;
	}

	/**
	 * METHOD IS USED FOR TESTING
	 * 
	 * @param request
	 * @param ret
	 */
	private void performGoogleOps(HttpServletRequest request, Map ret) {
		// get the user details
		IConnectorIOp prof = new GoogleProfile();
		prof.execute((User) request.getSession().getAttribute(Constants.SESSION_USER), null);

		IConnectorIOp lister = new GoogleListFiles();
		List fileList = (List) lister.execute((User) request.getSession().getAttribute(Constants.SESSION_USER), null);

		// get the file
		IConnectorIOp getter = new GoogleFileRetriever();
		Hashtable params2 = new Hashtable();
		params2.put("exportFormat", "csv");
		params2.put("id", "1it40jNFcRo1ur2dHIYUk18XmXdd37j4gmJm_Sg7KLjI");
		params2.put("target", "c:\\users\\pkapaleeswaran\\workspacej3\\datasets\\googlefile.csv");

		getter.execute((User) request.getSession().getAttribute(Constants.SESSION_USER), params2);

		IConnectorIOp ner = new GoogleEntityResolver();

		NLPDocumentInput docInput = new NLPDocumentInput();
		docInput.setContent("Obama is staying in the whitehouse !!");

		params2 = new Hashtable();

		// Hashtable docInputShell = new Hashtable();
		params2.put("encodingType", "UTF8");
		params2.put("document", docInput);

		// params2.put("input", docInputShell);
		ner.execute((User) request.getSession().getAttribute(Constants.SESSION_USER), params2);

		try {
			ret.put("files", BeanFiller.getJson(fileList));
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
		}

		IConnectorIOp lat = new GoogleLatLongGetter();
		params2 = new Hashtable();
		params2.put("address", "1919 N Lynn Street, Arlington, VA");

		lat.execute((User) request.getSession().getAttribute(Constants.SESSION_USER), params2);

		IConnectorIOp ts = new TwitterSearcher();
		params2 = new Hashtable();
		params2.put("q", "Anlaytics");
		params2.put("lang", "en");
		params2.put("count", "10");

		Object vp = ts.execute((User) request.getSession().getAttribute(Constants.SESSION_USER), params2);
	}

	@GET
	@Produces("application/json")
	@Path("/login/producthunt")
	public Response loginProducthunt(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException {
		/*
		 * Try to log in the user
		 * If they are not logged in
		 * Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if(session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = Utility.cleanHttpResponse(request.getParameter("redirect"));
		if(customRedirect != null && !customRedirect.isEmpty()) {
			if(session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}

		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.PRODUCT_HUNT) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				String prefix = "producthunt_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");
				boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

				if(classLogger.isDebugEnabled()) {
					classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
				}

				Hashtable params = new Hashtable();

				params.put("client_id", clientId);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
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

				if(classLogger.isDebugEnabled()) {
					classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
			}
		}

		// grab the user again
		if(session != null || (session=request.getSession(false)) != null) {
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
				+ "&response_type=code" + "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8") + "&scope="
				+ URLEncoder.encode(scope, "UTF-8");

		if(classLogger.isDebugEnabled()) {
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
	public Response loginIn(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException {
		/*
		 * Try to log in the user
		 * If they are not logged in
		 * Redirect the FE
		 */

		HttpSession session = request.getSession(false);
		User userObj = null;
		if(session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = Utility.cleanHttpResponse(request.getParameter("redirect"));
		if(customRedirect != null && !customRedirect.isEmpty()) {
			if(session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}

		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.IN) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				String prefix = "in_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");
				boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

				if(classLogger.isDebugEnabled()) {
					classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
				}

				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("grant_type", "authorization_code");
				params.put("client_secret", clientSecret);

				String url = "https://www.linkedin.com/oauth/v2/accessToken";

				AccessToken accessToken = HttpHelperUtility.getAccessToken(url, params, true, true);
				if (accessToken == null) {
					// not authenticated
					response.setStatus(302);
					response.sendRedirect(getInRedirect(request));
					return null;
				}
				accessToken.setProvider(AuthProvider.IN);
				addAccessToken(accessToken, request, autoAdd);

				if(classLogger.isDebugEnabled()) {
					classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
			}
		}

		// grab the user again
		if(session != null || (session=request.getSession(false)) != null) {
			userObj = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if (userObj == null || userObj.getAccessToken(AuthProvider.IN) == null) {
			response.setStatus(302);
			response.sendRedirect(getInRedirect(request));
			return null;
		}

		setMainPageRedirect(request, response);
		return null;
	}

	private String getInRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "in_";
		String clientId = socialData.getProperty(prefix + "client_id");
		String redirectUri = socialData.getProperty(prefix + "redirect_uri");
		String scope = socialData.getProperty(prefix + "scope");

		String redirectUrl = "https://www.linkedin.com/oauth/v2/authorization?" + "client_id=" + clientId
				+ "&redirect_uri=" + redirectUri + "&state=" + UUID.randomUUID().toString() + "&response_type=code"
				+ "&scope=" + URLEncoder.encode(scope, "UTF-8");

		if(classLogger.isDebugEnabled()) {
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
	public Response loginTwitter(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException {
		// getting the bearer token on twitter for app authentication is a lot simpler
		// need to just combine the id and secret
		// base 64 and send as authorization

		// https://developer.github.com/apps/building-oauth-apps/authorization-options-for-oauth-apps/

		HttpSession session = request.getSession(false);
		User userObj = null;
		if(session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = Utility.cleanHttpResponse(request.getParameter("redirect"));
		if(customRedirect != null && !customRedirect.isEmpty()) {
			if(session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}

		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.GITHUB) == null) {

				String[] outputs = HttpHelperUtility.getCodes(queryString);
				String prefix = "git_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");
				boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));

				if(classLogger.isDebugEnabled()) {
					classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
				}

				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("state", outputs[1]);
				params.put("client_secret", clientSecret);

				String url = "https://github.com/login/oauth/access_token";

				AccessToken accessToken = HttpHelperUtility.getAccessToken(url, params, false, true);
				if (accessToken == null) {
					// not authenticated
					response.setStatus(302);
					response.sendRedirect(getTwitterRedirect(request));
					return null;
				}

				accessToken.setProvider(AuthProvider.GITHUB);
				addAccessToken(accessToken, request, autoAdd);

				if(classLogger.isDebugEnabled()) {
					classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
			}
		}

		// grab the user again
		if(session != null || (session=request.getSession(false)) != null) {
			userObj = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if (userObj == null || userObj.getAccessToken(AuthProvider.GITHUB) == null) {
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
		//String clientSecret = socialData.getProperty(prefix + "secret_key");
		String redirectUri = socialData.getProperty(prefix + "redirect_uri");
		String scope = socialData.getProperty(prefix + "scope");
		String nonce = UUID.randomUUID().toString();
		String timestamp = System.currentTimeMillis() + "";

		StringBuffer signatureString = new StringBuffer("GET").append("&");
		signatureString.append(URLEncoder.encode("https://api.twitter.com/oauth/authorize", "UTF-8")).append("&");

		StringBuffer parameterString = new StringBuffer("");
		parameterString.append("oauth_callback=").append(URLEncoder.encode(redirectUri, "UTF-8")).append("&");
		parameterString.append("oauth_consumer_key=").append(clientId).append("&");
		parameterString.append("oauth_nonce=").append(nonce).append("&");
		parameterString.append("oauth_timestamp=").append(timestamp);

		//String finalString = signatureString.toString() + parameterString.toString();

		// String signature =

		String redirectUrl = "https://github.com/login/oauth/authorize?" + "oauth_consumer_key=" + clientId
				+ "&oauth_callback=" + redirectUri + "&oauth_nonce=" + nonce + "&oauth_timestamp=" + timestamp
				+ "&scope=" + URLEncoder.encode(scope, "UTF-8");

		if(classLogger.isDebugEnabled()) {
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
	public Response loginGeneric(@PathParam("provider") String provider, @Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException {
		/*
		 * Try to log in the user
		 * If they are not logged in
		 * Redirect the FE
		 */

		AuthProvider providerEnum = AuthProvider.getProviderFromString(provider.toUpperCase());

		HttpSession session = request.getSession(false);
		User userObj = null;
		if(session != null) {
			userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		String customRedirect = Utility.cleanHttpResponse(request.getParameter("redirect"));
		if(customRedirect != null && !customRedirect.isEmpty()) {
			if(session == null) {
				session = request.getSession();
			}
			session.setAttribute(CUSTOM_REDIRECT_SESSION_KEY, customRedirect);
		}

		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || ((User) userObj).getAccessToken(providerEnum) == null) {
				String[] outputs = HttpHelperUtility.getCodes(queryString);

				String prefix = provider+"_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");
				boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(prefix + "auto_add", "true"));
				//String tenant = socialData.getProperty(prefix + "tenant");

				//removing scope for now as it isn't needed in token call usually
				//String scope = socialData.getProperty(prefix + "scope");
				String token_url = socialData.getProperty(prefix + "token_url");

				if(classLogger.isDebugEnabled()) {
					classLogger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
				}

				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				//	params.put("scope", scope);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("grant_type", "authorization_code");
				params.put("client_secret", clientSecret);


				if(Strings.isNullOrEmpty(token_url)){
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
				//"name","id","email"
				String beanProps = socialData.getProperty(prefix + "beanProps");
				String jsonPattern = socialData.getProperty(prefix + "jsonPattern");

				accessToken.setProvider(providerEnum);
				
				// this is a check for sanitizing a response back from an IAM provider - not common and should be false
				// examples would be unescaped special chars in the response that then can't be parsed into a json. 
				// this is not very common
				boolean sanitizeResponse = Boolean.parseBoolean(socialData.getProperty(prefix + "sanitizeUserResponse"));
				
				GenericProfile.fillAccessToken(accessToken,userInfoURL, beanProps, jsonPattern, null, sanitizeResponse);
				addAccessToken(accessToken, request, autoAdd);

				if(classLogger.isDebugEnabled()) {
					classLogger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
			}
		}

		// grab the user again
		if(session != null || (session=request.getSession(false)) != null) {
			userObj = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if (userObj == null || userObj.getAccessToken(providerEnum) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getGenericRedirect(provider, request));
			return null;
		}

		String prefix = provider+"_";

		if(Boolean.parseBoolean(socialData.getProperty(prefix + "groups"))){
			//get groups
			String group_url = socialData.getProperty(prefix + "group_url");

			// make the call to get the groups
			String groupsJson = HttpHelperUtility.makeGetCall(group_url,  userObj.getAccessToken(providerEnum).getAccess_token());

			// this is a check for sanitizing a response back from an IAM provider - not common and should be false
			// examples would be unescaped special chars in the response that then can't be parsed into a json. 
			boolean sanitizeGroupResponse = Boolean.parseBoolean(socialData.getProperty(prefix + "sanitizeGroupResponse"));

			if(sanitizeGroupResponse) {
				groupsJson = groupsJson.replace("\\", "\\\\");
				// add more replacements as need be in the future
			}
			
			
			
			Set<String> userGroups = new HashSet<String>();

			// are groups returned as a single string or an array in a json. Usually it is an array in a json.
			boolean groupStringResponse = Boolean.parseBoolean(socialData.getProperty(prefix + "group_string_return"));
			if(groupStringResponse) {
				//this json pattern should return a single string with groups concat
				// ""fakeGroups":"CN=group1, CN=group2, CN=group3"
				String groupJsonPattern = socialData.getProperty(prefix + "groupJsonPattern");
				JsonNode result = BeanFiller.getJmesResult(groupsJson, groupJsonPattern);
				try {
					//get the single string and the regex pattern. validate the pattern
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
				//this json pattern should return an array
				String groupJsonPattern = socialData.getProperty(prefix + "groupJsonPattern");
				JsonNode result = BeanFiller.getJmesResult(groupsJson, groupJsonPattern);
				if((result instanceof ArrayNode) && result.get(0) instanceof ObjectNode) {
					throw new SemossPixelException("Group result must return flat array. Please check groupJsonPatter");
				}
				for(int inputIndex = 0;result != null && inputIndex < result.size();inputIndex++) {
					String thisInput = result.get(inputIndex).asText();
					userGroups.add(thisInput);
				}	
			}


			userObj.getAccessToken(providerEnum).setUserGroups(userGroups);
			userObj.getAccessToken(providerEnum).setUserGroupType(providerEnum.toString());			
		}

		setMainPageRedirect(request, response);
		return null;
	}

	private String getGenericRedirect(String provider, HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = provider+"_";
		String clientId = socialData.getProperty(prefix + "client_id");
		String redirectUri = socialData.getProperty(prefix + "redirect_uri");
		//String tenant = socialData.getProperty(prefix + "tenant");
		String scope = socialData.getProperty(prefix + "scope"); // need to set this up and reuse
		String auth_url = socialData.getProperty(prefix + "auth_url");

		String state = UUID.randomUUID().toString();

		if(Strings.isNullOrEmpty(auth_url)){
			throw new IllegalArgumentException("Authorize URL can not be null or empty");
		}

		String redirectUrl; 

		//are you already adding custom props? if so I will skip adding the ?
		if(auth_url.contains("?")) {
			redirectUrl = auth_url + "&client_id="
					+ clientId + "&response_type=code" + "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8")
					+ "&response_mode=query" + "&scope=" + URLEncoder.encode(scope) + "&state=" + state;
		} else{
			redirectUrl = auth_url + "?" + "client_id="
					+ clientId + "&response_type=code" + "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8")
					+ "&response_mode=query" + "&scope=" + URLEncoder.encode(scope) + "&state=" + state;
		}


		if(classLogger.isDebugEnabled()) {
			classLogger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
		}

		return redirectUrl;
	}
	//	public static String calculateRFC2104HMAC(String data, String key) throws SignatureException, NoSuchAlgorithmException, InvalidKeyException {
	//		SecretKeySpec signingKey = new SecretKeySpec(key.getBytes(), "HmacSHA1");
	//		Mac mac = Mac.getInstance("HmacSHA1");
	//		mac.init(signingKey);
	//		return toHexString(mac.doFinal(data.getBytes()));
	//	}
	//
	//	private static String toHexString(byte[] bytes) {
	//		Formatter formatter = new Formatter();
	//		for (byte b : bytes) {
	//			formatter.format("%02x", b);
	//		}
	//		return formatter.toString();
	//	}

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

		if(socialData.getLoginsAllowed().get("native") == null || !socialData.getLoginsAllowed().get("native")) {
			ret.put(Constants.ERROR_MESSAGE, "Native login is not allowed");
			return WebUtility.getResponse(ret, 400);
		}

		try {
			String username = request.getParameter("username");
			String password = request.getParameter("password");
			String redirect = Utility.cleanHttpResponse(request.getParameter("redirect"));
			// so that the default is to redirect
			Boolean disableRedirect = Boolean.parseBoolean(request.getParameter("disableRedirect") + "");

			if(username == null || password == null || username.isEmpty() || password.isEmpty()) {
				classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), null, "is trying to login using username='"+username+"' but user name or password are empty"));
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
				SecurityUpdateUtils.validateUserLogin(authToken);

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
				if(session != null) {
					user = (User) session.getAttribute(Constants.SESSION_USER);
					if(!AbstractSecurityUtils.anonymousUsersEnabled() && user != null && user.getLogins().isEmpty()) {
						session.invalidate();
					}
				}
				classLogger.warn(ResourceUtility.getLogMessage(request, request.getSession(false), User.getSingleLogginName(user), "is trying to login using username='"+username+"' but user name or password are empty"));
				ret.put(Constants.ERROR_MESSAGE, "The user name or password are invalid.");
				return WebUtility.getResponse(ret, 401);
			}
		} catch (Exception e) {
			HttpSession session = request.getSession(false);
			if(session != null) {
				User user = (User) session.getAttribute(Constants.SESSION_USER);
				if(!AbstractSecurityUtils.anonymousUsersEnabled() && user != null && user.getLogins().isEmpty()) {
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
	 * @param request
	 * @return true if the information provided to log in is valid otherwise error.
	 */
	@POST
	@Produces("application/json")
	@Path("/loginLDAP")
	public Response loginLDAP(@Context HttpServletRequest request, @Context HttpServletResponse response) {
		Map<String, Object> ret = new HashMap<>();
		if(socialData.getLoginsAllowed().get("ldap")==null || !socialData.getLoginsAllowed().get("ldap")) {
			ret.put(Constants.ERROR_MESSAGE, "LDAP login is not allowed");
			return WebUtility.getResponse(ret, 400);
		}

		ILdapAuthenticator authenticator = null;
		try {
			String username = request.getParameter("username");
			String password = request.getParameter("password");
			// so that the default is to redirect
			Boolean disableRedirect = Boolean.parseBoolean(request.getParameter("disableRedirect") + "");

			if(username == null || password == null || username.isEmpty() || password.isEmpty()) {
				ret.put(Constants.ERROR_MESSAGE, "The user name or password are empty");
				return WebUtility.getResponse(ret, 401);
			}

			authenticator = socialData.getLdapAuthenticator();
			AccessToken authToken = authenticator.authenticate(username, password);
			if(authToken == null) {
				throw new IllegalArgumentException("Unable to parse any user attributes");
			}
			boolean autoAdd = Boolean.parseBoolean(socialData.getProperty(ILdapAuthenticator.LDAP + "auto_add", "true"));
			addAccessToken(authToken, request, autoAdd);
			SecurityUpdateUtils.validateUserLogin(authToken);
			ret.put("success", "true");
			ret.put("username", username);
			// log the log in
			if (!disableRedirect) {
				setMainPageRedirect(request, response);
			}
		} catch(LDAPPasswordChangeRequiredException e) {
			HttpSession session = request.getSession(false);
			if(session != null) {
				User user = (User) session.getAttribute(Constants.SESSION_USER);
				if(!AbstractSecurityUtils.anonymousUsersEnabled() && user != null && user.getLogins().isEmpty()) {
					session.invalidate();
				}
			}
			classLogger.error(Constants.STACKTRACE, e);
			ret.put(Constants.ERROR_MESSAGE, "User must change their password before login");
			ret.put(ILdapAuthenticator.LDAP_PASSWORD_CHANGE_RETURN_KEY, true);
			return WebUtility.getResponse(ret, 401);
		} catch (Exception e) {
			HttpSession session = request.getSession(false);
			if(session != null) {
				User user = (User) session.getAttribute(Constants.SESSION_USER);
				if(!AbstractSecurityUtils.anonymousUsersEnabled() && user != null && user.getLogins().isEmpty()) {
					session.invalidate();
				}
			}
			classLogger.error(Constants.STACKTRACE, e);
			ret.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(ret, 500);
		} finally {
			if(authenticator != null) {
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
	 * @param request
	 * @return true if the information provided to log in is valid otherwise error.
	 */
	@POST
	@Produces("application/json")
	@Path("/changeADPassword")
	public Response changeADPassword(@Context HttpServletRequest request, @Context HttpServletResponse response) {
		Map<String, String> ret = new HashMap<>();
		if( (socialData.getLoginsAllowed().get("ldap")==null || !socialData.getLoginsAllowed().get("ldap"))
				&& !Boolean.parseBoolean(socialData.getProperty("linotp_check_ad", "false"))
				){
			ret.put(Constants.ERROR_MESSAGE, "LDAP change password is not allowed/configured");
			return WebUtility.getResponse(ret, 400);
		}

		ILdapAuthenticator authenticator = null;
		try {
			String username = request.getParameter("username");
			String curPassword = request.getParameter("curPassword");
			String newPassword = request.getParameter("newPassword");

			if(username == null || curPassword == null || newPassword == null
					|| username.isEmpty() || curPassword.isEmpty() || newPassword.isEmpty()) {
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
			if(session != null) {
				User user = (User) session.getAttribute(Constants.SESSION_USER);
				if(!AbstractSecurityUtils.anonymousUsersEnabled() && user != null && user.getLogins().isEmpty()) {
					session.invalidate();
				}
			}
			classLogger.error(Constants.STACKTRACE, e);
			ret.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(ret, 500);
		} finally {
			if(authenticator != null) {
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
	public Response loginLinOTP(@Context HttpServletRequest request, @Context HttpServletResponse response) throws ClientProtocolException, IOException {
		Map<String, Object> returnMap = new HashMap<>();
		if(socialData.getLoginsAllowed().get("linotp")==null || !socialData.getLoginsAllowed().get("linotp")) {
			returnMap.put(Constants.ERROR_MESSAGE, "LinOTP login is not allowed");
			return WebUtility.getResponse(returnMap, 400);
		}

		String redirect = Utility.cleanHttpResponse(request.getParameter("redirect"));
		// so that the default is to redirect
		Boolean disableRedirect = Boolean.parseBoolean(request.getParameter("disableRedirect") + "");
		boolean autoAdd = Boolean.parseBoolean(socialData.getProperty("linotp_auto_add", "true"));

		LinOTPResponse linotpResponse = LinOTPUtil.login(request);
		returnMap = linotpResponse.getReturnMap();
		int responseCode = linotpResponse.getResponseCode();
		if(responseCode != 200) {
			// we just throw the error
			return WebUtility.getResponse(returnMap, responseCode);
		} else {
			// are we logged in?
			AccessToken token = linotpResponse.getToken();
			if(token == null) {
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
	public Response resetLinOTPFailCounter(@Context HttpServletRequest request, @Context HttpServletResponse response) throws ClientProtocolException, IOException {
		try {
			LinOTPResponse linotpResponse = LinOTPUtil.resetCounter(request);
			Map<String, Object> returnMap = linotpResponse.getReturnMap();
			int responseCode = linotpResponse.getResponseCode();
			// this is simple, take response code and message and return
			return WebUtility.getResponse(returnMap, responseCode);
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, Object> errorMessage = new HashMap<>();
			errorMessage.put(Constants.ERROR_MESSAGE, "Error occurred resetting the pin. Error message = " + e.getMessage());
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
		Hashtable<String, String> ret = new Hashtable<>();

		if(socialData.getLoginsAllowed().get("native")==null || !socialData.getLoginsAllowed().get("native")) {
			ret.put(Constants.ERROR_MESSAGE, "Native login is not allowed");
			return WebUtility.getResponse(ret, 400);
		}

		if(socialData.getLoginsAllowed().get("registration")==null || !socialData.getLoginsAllowed().get("registration")) {
			ret.put(Constants.ERROR_MESSAGE, "Native registration is not allowed");
			return WebUtility.getResponse(ret, 400);
		}

		try {
			// Note - for native users
			// the id and the username are always the same
			String username = request.getParameter("username");
			String name = request.getParameter("name");
			String password = request.getParameter("password");
			String email = request.getParameter("email");
			String phone = request.getParameter("phone");
			String phoneExtension = request.getParameter("phoneextension");
			String countryCode = request.getParameter("countrycode");

			AccessToken newUser = new AccessToken();
			newUser.setProvider(AuthProvider.NATIVE);
			newUser.setId(username);
			newUser.setUsername(username);
			newUser.setEmail(email);
			newUser.setName(name);
			newUser.setPhone(phone);
			newUser.setPhoneExtension(phoneExtension);
			newUser.setCountryCode(countryCode);
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
	 * Create an user according to the information provided 
	 * (user name, password, email)
	 * 
	 * @param request
	 * @return true if the user is created otherwise error.
	 */
	@POST
	@Produces("application/json")
	@Path("createAPIUser")
	public Response createAPIUser(@Context HttpServletRequest request) {
		if(socialData.getLoginsAllowed().get("api_user") == null || !socialData.getLoginsAllowed().get("api_user")) {
			Map<String, String> ret = new Hashtable<>();
			ret.put(Constants.ERROR_MESSAGE, "API User is not allowed for login");
			return WebUtility.getResponse(ret, 400);
		}

		if(Utility.getApplicationAdminOnlyCreateAPIUser()) {
			User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
			if (user == null) {
				Map<String, String> ret = new Hashtable<>();
				ret.put(Constants.ERROR_MESSAGE, "No active session. Please login as an admin");
				return WebUtility.getResponse(ret, 401);
			}
			if (!SecurityAdminUtils.userIsAdmin(user)) {
				Map<String, String> ret = new Hashtable<>();
				ret.put(Constants.ERROR_MESSAGE, "User is not an admin and does not have access. Please login as an admin");
				return WebUtility.getResponse(ret, 401);
			}
		}

		String name = request.getParameter("name");
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
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		if (user == null) {
			return WebUtility.getResponse("No user defined to access properties. Please login as an admin", 400);
		}
		if (!SecurityAdminUtils.userIsAdmin(user)) {
			return WebUtility.getResponse("User is not an admin and does not have access. Please login as an admin", 400);
		}

		Gson gson = new Gson();
		String modStr = form.getFirst("modifications");
		Map<String, String> mods = gson.fromJson(modStr, new TypeToken<Map<String, String>>() {}.getType());
		try {
			socialData.updateSocialProperties(provider, mods);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Hashtable<String, String> errorRet = new Hashtable<>();
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
			return WebUtility.getResponse("User is not an admin and does not have access. Please login as an admin", 400);
		}

		String newSocialProperties = request.getParameter("socialProperties");
		try {
			socialData.updateAllProperties(newSocialProperties);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Hashtable<String, String> errorRet = new Hashtable<>();
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
			return WebUtility.getResponse("User is not an admin and does not have access. Please login as an admin", 400);
		}

		try {
			String fileContent = socialData.getFileContents();
			Map<String, String> retMap = new HashMap<>();
			retMap.put("content", fileContent);
			return WebUtility.getResponse(retMap, 200);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Hashtable<String, String> errorRet = new Hashtable<>();
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
		String customRedirect = Utility.cleanHttpResponse(request.getParameter("redirect"));
		if(customRedirect == null || customRedirect.isEmpty()) {
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
	private void setMainPageRedirect(@Context HttpServletRequest request, @Context HttpServletResponse response, String customRedirect) {
		// see if we have a location to redirect the user
		// if so, we will send them back to that URL
		// otherwise, we send them back to the FE
		HttpSession session = request.getSession();
		String contextPath = request.getContextPath();

		boolean useCustom = customRedirect != null && !customRedirect.isEmpty();
		boolean endpoint = session.getAttribute(Constants.ENDPOINT_REDIRECT_KEY) != null;
		response.setStatus(302);
		try {
			// add the cookie to the header directly
			// to allow for cross site login when embedded as iframe
			String setCookieString = DBLoader.getSessionIdKey() + "=" + session.getId() 
			+ "; Path=" + contextPath 
			+ "; HttpOnly"
			+ ( (ClusterUtil.IS_CLUSTER || request.isSecure()) ? ("; Secure; SameSite="+Utility.getSameSiteCookieValue()) : "")
			;
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

	//////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////

	/**
	 * Sharing session
	 */

	@POST
	@Produces("application/json")
	@Path("/cookie")
	public StreamingOutput manCookie(@Context HttpServletRequest request, @Context HttpServletResponse response) {
		String insightId = request.getParameter("i");
		String secret = request.getParameter("s");

		// https://nuwanbando.com/2010/05/07/sharing-https-http-sessions-in-tomcat/
		/*
		 * When the user clicks on connect to tableau.. I need to give the user a link
		 * to that insight primarily
		 * the question is do I land on the same insight or a different one
		 * that link should have insight id and session id
		 * 
		 * a. Launches a new browser with this redirect along with pseudo session id, session id hashed with insight id
		 * b. Redirects the user to a URL with the insight id and the pseudo session id / or something that sits in the user object. some random number
		 * c. We pick the session.. go to the user object to see if the secret can be verified. Basically you take the session id which came in hash it with the insight id to see if it is allowable
		 * d. We redirect the user to the embedded URL for the insight >>
		 * e. We need someway to repull the recipe 
		 * 
		 * I need first something that will take me to http and then from there on take me into my insight
		 * 
		 */

		// get the session
		HttpSession session = request.getSession();
		String sessionId = session.getId();
		User user = (User) session.getAttribute(Constants.SESSION_USER);

		Cookie k = new Cookie(DBLoader.getSessionIdKey(), sessionId);
		k.setSecure(request.isSecure());
		k.setHttpOnly(true);
		k.setPath(request.getContextPath());
		response.addCookie(k);

		if(classLogger.isDebugEnabled()) {
			classLogger.debug("Session id set to " + sessionId);
		}

		InsightToken token = new InsightToken();
		Hashtable outputHash = new Hashtable();
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			// create the insight token and add to the user
			// the user has secret and salt
			token.setSecret(secret);
			user.addInsight(insightId, token);

			String finalData = token.getSalt() + token.getSecret();

			byte[] digest = md.digest(finalData.getBytes()); // .toString().getBytes();
			StringBuffer sb = new StringBuffer();
			for (int i = 0; i < digest.length; i++) {
				sb.append(Integer.toString((digest[i] & 0xff) + 0x100, 16).substring(1));
			}
			// String redir = "http://localhost:9090/Monolith/api/engine/all?JSESSIONID=" +
			// sessionId;
			String redir = "?" + DBLoader.getSessionIdKey() + "=" + sessionId + "&hash=" + sb + "&i=" + insightId;

			// add the route if this is server deployment
			Map<String, String> envMap = System.getenv();
			// the environment variable for this box will tell me which route variable
			// is for this specific box
			if (envMap.containsKey(Constants.MONOLITH_ROUTE)) {
				String routeCookieName = envMap.get(Constants.MONOLITH_ROUTE);
				Cookie[] curCookies = request.getCookies();
				if (curCookies != null) {
					for (Cookie c : curCookies) {
						if (c.getName().equals(routeCookieName)) {
							redir += "&" + c.getName() + "=" + c.getValue();
						}
					}
				}
			}

			if(classLogger.isDebugEnabled()) {
				classLogger.debug("Redirect URL " + Utility.cleanLogString(redir));
			}

			outputHash.put("PARAM", redir);
			// also tell the system that this session is not fully validated so if someone
			// comes without secret on this session
			// dont allow
			user.addShare(sessionId);
		} catch (NoSuchAlgorithmException e) {
			classLogger.error(Constants.STACKTRACE, e);
		}

		return WebUtility.getSO(outputHash);
	}

	@GET
	@Produces("application/json")
	@Path("/whoami")
	public Response show(@Context HttpServletRequest request, @Context HttpServletResponse response) {
		Principal principal = request.getUserPrincipal();
		Map<String, Object> output = new HashMap<>();
		if(principal != null) {
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
			if(session != null) {
				User user = (User) session.getAttribute(Constants.SESSION_USER);
				if(user != null) {
					AccessToken token = user.getAccessToken(user.getPrimaryLogin());
					output.put("name", token.getId());
					output.put("warning", "null principal - grab from user");
				}
			}
			if(output.isEmpty()) {
				output.put(Constants.ERROR_MESSAGE, "null principal");
			}
		}
		return WebUtility.getResponse(output, 200);
	}

}
