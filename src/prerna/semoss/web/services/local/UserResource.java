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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Vector;
import java.util.stream.Collectors;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kohsuke.github.GHMyself;
import org.kohsuke.github.GitHub;
import org.owasp.encoder.Encode;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import jodd.util.URLDecoder;
import prerna.auth.AccessToken;
import prerna.auth.AppTokens;
import prerna.auth.AuthProvider;
import prerna.auth.InsightToken;
import prerna.auth.SyncUserAppsThread;
import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.NativeUserSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
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
import prerna.sablecc2.reactor.mgmt.MgmtUtil;
import prerna.security.AbstractHttpHelper;
import prerna.util.BeanFiller;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;
import prerna.util.git.GitRepoUtils;
import prerna.web.conf.DBLoader;
import prerna.web.conf.NoUserInSessionFilter;
import prerna.web.services.util.WebUtility;
import waffle.servlet.WindowsPrincipal;

@Path("/auth")
public class UserResource {

	private static final Logger logger = LogManager.getLogger(UserResource.class);

	private static Properties socialData = null;
	private static Map<String, Boolean> loginsAllowedMap;
	
	static {
		loadSocialProperties();
		AppTokens.setSocial(socialData);
	}

	private static void loadSocialProperties() {
		FileInputStream fis = null;
		File f = new File(DIHelper.getInstance().getProperty("SOCIAL"));
		try {
			if (f.exists()) {
				socialData = new Properties();
				fis = new FileInputStream(f);
				socialData.load(fis);
				setLoginsAllowed();
			}
		} catch (FileNotFoundException fnfe) {
			logger.error(Constants.STACKTRACE, fnfe);
		} catch (IOException ioe) {
			logger.error(Constants.STACKTRACE, ioe);
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
		} finally {
			if (fis != null) {
				try {
					fis.close();
				} catch (IOException e) {
					logger.error(Constants.STACKTRACE, e);
				}
			}
		}
	}

	/**
	 * Method to get the redirect URL if defined in the social properties
	 * @return
	 */
	public static String getLoginRedirect() {
		return socialData.getProperty("redirect") + "/login";
	}

	private static void setLoginsAllowed() {
		UserResource.loginsAllowedMap = new HashMap<>();
		// define the default provider set
		Set<String> defaultProviders = AuthProvider.getSocialPropKeys();
		
		// get all _login props
	    Set<String> loginProps = socialData.stringPropertyNames().stream().filter(str->str.endsWith("_login")).collect(Collectors.toSet());
		for( String prop : loginProps) {
			//prop ex. ms_login
			//get provider from prop by split on _
			String provider = prop.split("_")[0];
		
			UserResource.loginsAllowedMap.put(provider,  Boolean.parseBoolean(socialData.getProperty(prop)));
			//remove the provider from the defaultProvider list
			defaultProviders.remove(provider);
		}
		
		// for loop through the defaultProviders list to make sure we set the rest to false
		for(String provider: defaultProviders) {
			UserResource.loginsAllowedMap.put(provider,  false);
		}

		// get if registration is allowed
		boolean registration = Boolean.parseBoolean(socialData.getProperty("native_registration"));
		UserResource.loginsAllowedMap.put("registration", registration);
	}

	public static Map<String, Boolean> getLoginsAllowed() {
		return UserResource.loginsAllowedMap;
	}

	@GET
	@Path("/logins")
	public Response getAllLogins(@Context HttpServletRequest request) {
		List<NewCookie> newCookies = new Vector<>();
		HttpSession session = request.getSession(false);
		User semossUser = null;
		if (session != null) {
			semossUser = (User) request.getSession().getAttribute(Constants.SESSION_USER);
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
		}

		Map<String, String> retMap = User.getLoginNames(semossUser);
		return WebUtility.getResponse(retMap, 200, newCookies.toArray(new NewCookie[] {}));
	}

	@GET
	@Produces("application/json")
	@Path("/logout/{provider}")
	public Response logout(@PathParam("provider") String provider, @Context HttpServletRequest request,
			@Context HttpServletResponse response) throws IOException {
		boolean noUser = false;
		boolean removed = false;

		HttpSession session = request.getSession();
		User thisUser = (User) session.getAttribute(Constants.SESSION_USER);
		if(thisUser == null) {
			Map<String, Object> ret = new Hashtable<>();
			ret.put("success", false);
			ret.put("errorMessage", "No user is currently logged in the session");
			return WebUtility.getResponse(ret, 400);
		}
		
		// log the user logout
		logger.info(ResourceUtility.getLogMessage(request, session, User.getSingleLogginName(thisUser), "is logging out of provider " +  provider));
		
		if (provider.equalsIgnoreCase("ALL")) {
			removed = true;
			noUser = true;
			thisUser.removeUserMemory();
		} else {
			AuthProvider token = AuthProvider.valueOf(provider.toUpperCase());
			String assetEngineId = null;
			String workspaceEngineId = null;

			if (thisUser.getLogins().size() == 1) {
				thisUser.getAssetEngineId(token);
				thisUser.getWorkspaceEngineId(token);
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

		if(noUser) {
			// if there are no users and there is security
			// redirect the user
			// and invalidate the session
			// session invalidation will go to UserSessionLoader
			// which will close R and Python on the user object
			if (AbstractSecurityUtils.securityEnabled()) {
				logger.info(ResourceUtility.getLogMessage(request, session, User.getSingleLogginName(thisUser), "has logged out from all providers in the session"));
				// well, you have logged out and we always require a login
				// so i will redirect you
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

				// remove the cookie from the browser
				// for the session id
				logger.info("Removing session token");
				Cookie[] cookies = request.getCookies();
				if (cookies != null) {
					for (Cookie c : cookies) {
						if (DBLoader.getSessionIdKey().equals(c.getName())) {
							// we need to null this out
							Cookie nullC = new Cookie(c.getName(), c.getValue());
							nullC.setPath(c.getPath());
							nullC.setSecure(request.isSecure());
							nullC.setHttpOnly(true);
							nullC.setVersion(c.getVersion());
							if (c.getDomain() != null) {
								nullC.setDomain(c.getDomain());
							}
							nullC.setMaxAge(0);
							response.addCookie(nullC);
						}
					}
				}
			}
			// invalidate the session
			session.invalidate();
			return null;
		}
		
		Map<String, Boolean> ret = new Hashtable<>();
		ret.put("success", removed);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Method to add an access token to a user
	 * 
	 * @param token
	 * @param request
	 */
	private void addAccessToken(AccessToken token, HttpServletRequest request) {
		User semossUser = null;
		HttpSession session = request.getSession();
		Object user = session.getAttribute(Constants.SESSION_USER);
		// all of this is now in the user
		if (user != null) {
			semossUser = (User) user;
		} else {
			semossUser = new User();
		}
		semossUser.setAccessToken(token);
		semossUser.setAnonymous(false);
		request.getSession().setAttribute(Constants.SESSION_USER, semossUser);

		// log the user login
		logger.info(ResourceUtility.getLogMessage(request, session, User.getSingleLogginName(semossUser), "is logging in with provider " +  token.getProvider()));

		// add new users into the database
		SecurityUpdateUtils.addOAuthUser(token);
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
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);

		String[] beanProps = { "name", "profile" };
		String jsonPattern = "[name, picture]";
		String accessString = null;
		try {
			if (user == null) {
				ret.put(Constants.ERROR_MESSAGE, "Log into your Google account");
				return WebUtility.getResponse(ret, 200);
			} else {
				AccessToken googleToken = user.getAccessToken(AuthProvider.GOOGLE);
				accessString = googleToken.getAccess_token();
			}
		} catch (Exception e) {
			ret.put(Constants.ERROR_MESSAGE, "Log into your Google account");
			return WebUtility.getResponse(ret, 200);
		}

		String url = "https://www.googleapis.com/oauth2/v3/userinfo";
		Hashtable params = new Hashtable();
		params.put("access_token", accessString);
		params.put("alt", "json");

		String output = AbstractHttpHelper.makeGetCall(url, accessString, params, true);
		AccessToken accessToken2 = (AccessToken) BeanFiller.fillFromJson(output, jsonPattern, beanProps,
				new AccessToken());
		try {
			ret.put("name", accessToken2.getName());
			if (accessToken2.getProfile() != null) {
				ret.put("picture", accessToken2.getProfile());
			}
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
		}

		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Gets user info for OneDrive
	 */
	@GET
	@Produces("application/json")
	@Path("/userinfo/ms")
	public Response userinfoOneDrive(@Context HttpServletRequest request) {
		Map<String, String> ret = new Hashtable<>();
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);

		String[] beanProps = { "name" };
		String jsonPattern = "[displayName]";

		String accessString = null;
		try {
			if (user == null) {
				ret.put(Constants.ERROR_MESSAGE, "Log into your Microsoft account");
			} else {
				AccessToken msToken = user.getAccessToken(AuthProvider.MS);
				accessString = msToken.getAccess_token();
				String url = "https://graph.microsoft.com/v1.0/me/";
				String output = AbstractHttpHelper.makeGetCall(url, accessString, null, true);
				AccessToken accessToken2 = (AccessToken) BeanFiller.fillFromJson(output, jsonPattern, beanProps,
						new AccessToken());
				String name = accessToken2.getName();
				ret.put("name", name);
			}
			return WebUtility.getResponse(ret, 200);
		} catch (Exception e) {
			ret.put(Constants.ERROR_MESSAGE, "Log into your Microsoft account");
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
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);

		String[] beanProps = { "name", "profile" }; // add is done when you have a list
		String jsonPattern = "[name.display_name, profile_photo_url]";
		String accessString = null;
		try {
			if (user == null) {
				ret.put(Constants.ERROR_MESSAGE, "Log into your DropBox account");
				return WebUtility.getResponse(ret, 200);
			} else {
				AccessToken dropToken = user.getAccessToken(AuthProvider.DROPBOX);
				accessString = dropToken.getAccess_token();
			}
		} catch (Exception e) {
			ret.put(Constants.ERROR_MESSAGE, "Log into your DropBox account");
			return WebUtility.getResponse(ret, 200);
		}
		String url = "https://api.dropboxapi.com/2/users/get_current_account";

		String output = AbstractHttpHelper.makePostCall(url, accessString, null, true);
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
			logger.error(Constants.STACKTRACE, e);
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
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);

		String[] beanProps = { "name", "profile" }; // add is done when you have a list
		String jsonPattern = "[name,login]";

		String accessString = null;
		try {
			if (user == null) {
				ret.put(Constants.ERROR_MESSAGE, "Log into your Github account");
				return WebUtility.getResponse(ret, 200);
			} else {
				AccessToken gitToken = user.getAccessToken(AuthProvider.GITHUB);
				accessString = gitToken.getAccess_token();
			}
		} catch (Exception e) {
			ret.put(Constants.ERROR_MESSAGE, "Log into your Github account");
			return WebUtility.getResponse(ret, 200);
		}

		String url = "https://api.github.com/user";
	

		String output = AbstractHttpHelper.makeGetCall(url, accessString, null, true);
		AccessToken accessToken2 = (AccessToken) BeanFiller.fillFromJson(output, jsonPattern, beanProps,
				new AccessToken());
		try {
			ret.put("name", accessToken2.getProfile());
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
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
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		String prefix = provider+"_";
		String userInfoURL = socialData.getProperty(prefix + "userinfo_url");
		//"name","id","email"
		String beanProps = socialData.getProperty(prefix + "beanProps");
		String jsonPattern = socialData.getProperty(prefix + "jsonPattern");

		String[] beanPropsArr = beanProps.split(",", -1);

		String accessString = null;
		try {
			if (user == null) {
				ret.put(Constants.ERROR_MESSAGE, "Log into your " + providerEnum.toString() + " account");
			} else {
				AccessToken genericToken = user.getAccessToken(providerEnum);
				accessString = genericToken.getAccess_token();
				//String url = "https://graph.microsoft.com/v1.0/me/";
				String output = AbstractHttpHelper.makeGetCall(userInfoURL, accessString, null, true);
				AccessToken accessToken2 = (AccessToken) BeanFiller.fillFromJson(output, jsonPattern, beanPropsArr,
						new AccessToken());
				String name = accessToken2.getName();
				ret.put("name", name);
			}
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
	public Response loginSF(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws IOException {
		/*
		 * Try to log in the user
		 * If they are not logged in
		 * Redirect the FE
		 */

		User userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.SF) == null) {
				String[] outputs = AbstractHttpHelper.getCodes(queryString);

				String prefix = "sf_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");

				if(logger.isDebugEnabled()) {
					logger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
				}
				
				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("grant_type", "authorization_code");
				params.put("redirect_uri", redirectUri);
				params.put("code", URLDecoder.decode(outputs[0]));
				params.put("client_secret", clientSecret);

				String url = "https://login.salesforce.com/services/oauth2/token";

				AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, true, true);
				if (accessToken == null) {
					// not authenticated
					response.setStatus(302);
					response.sendRedirect(getSFRedirect(request));
					return null;
				}
				accessToken.setProvider(AuthProvider.SF);
				addAccessToken(accessToken, request);

				if(logger.isDebugEnabled()) {
					logger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
			}
		}

		// grab the user again
		userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
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

		if(logger.isDebugEnabled()) {
			logger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
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
		/*
		 * Try to log in the user
		 * If they are not logged in
		 * Redirect the FE
		 */

		User userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.SURVEYMONKEY) == null) {
				String[] outputs = AbstractHttpHelper.getCodes(queryString);

				String prefix = "surveymonkey_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");

				if(logger.isDebugEnabled()) {
					logger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
				}
				
				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("grant_type", "authorization_code");
				params.put("redirect_uri", redirectUri);
				params.put("code", URLDecoder.decode(outputs[0]));
				params.put("client_secret", clientSecret);

				String url = "https://api.surveymonkey.com/oauth/token";

				AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, true, true);
				if (accessToken == null) {
					// not authenticated
					response.setStatus(302);
					response.sendRedirect(getSurveyMonkeyRedirect(request));
					return null;
				}
				accessToken.setProvider(AuthProvider.SURVEYMONKEY);
				MonkeyProfile.fillAccessToken(accessToken, null);
				addAccessToken(accessToken, request);

				if(logger.isDebugEnabled()) {
					logger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
			}
		}

		// grab the user again
		userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
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

		if(logger.isDebugEnabled()) {
			logger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
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
		/*
		 * Try to log in the user
		 * If they are not logged in
		 * Redirect the FE
		 */

		User userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.GITHUB) == null) {
				String[] outputs = AbstractHttpHelper.getCodes(queryString);

				String prefix = "github_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");

				if(logger.isDebugEnabled()) {
					logger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
				}
				
				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("state", outputs[1]);
				params.put("client_secret", clientSecret);

				String url = "https://github.com/login/oauth/access_token";

				AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, false, true);
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
				addAccessToken(accessToken, request);

				if(logger.isDebugEnabled()) {
					logger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
			}
		}

		// grab the user again
		userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
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

		if(logger.isDebugEnabled()) {
			logger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
		}
		return redirectUrl;
	}

	/**
	 * Logs user in through ms
	 */
	@GET
	@Produces("application/json")
	@Path("/login/ms")
	public Response loginMS(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws IOException {
		/*
		 * Try to log in the user
		 * If they are not logged in
		 * Redirect the FE
		 */

		User userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || ((User) userObj).getAccessToken(AuthProvider.MS) == null) {
				String[] outputs = AbstractHttpHelper.getCodes(queryString);

				String prefix = "ms_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");
				String tenant = socialData.getProperty(prefix + "tenant");
				String scope = socialData.getProperty(prefix + "scope");
				String token_url = socialData.getProperty(prefix + "token_url");

				if(logger.isDebugEnabled()) {
					logger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
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

				AccessToken accessToken = AbstractHttpHelper.getAccessToken(token_url, params, true, true);
				if (accessToken == null) {
					// not authenticated
					response.setStatus(302);
					response.sendRedirect(getMSRedirect(request));
					return null;
				}

				accessToken.setProvider(AuthProvider.MS);
				MSProfile.fillAccessToken(accessToken, null);
				addAccessToken(accessToken, request);

				if(logger.isDebugEnabled()) {
					logger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
			}
		}

		// grab the user again
		userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
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

		if(logger.isDebugEnabled()) {
			logger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
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
		/*
		 * Try to log in the user
		 * If they are not logged in
		 * Redirect the FE
		 */

		User userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || ((User) userObj).getAccessToken(AuthProvider.SITEMINDER) == null) {
				String[] outputs = AbstractHttpHelper.getCodes(queryString);

				String prefix = "siteminder_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");
				String tenant = socialData.getProperty(prefix + "tenant");
				String scope = socialData.getProperty(prefix + "scope");
				String token_url = socialData.getProperty(prefix + "token_url");

				if(logger.isDebugEnabled()) {
					logger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
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
				AccessToken accessToken = AbstractHttpHelper.getAccessToken(token_url, params, true, true);
				if (accessToken == null) {
					// not authenticated
					response.setStatus(302);
					response.sendRedirect(getSiteminderRedirect(request));
					return null;
				}

				accessToken.setProvider(AuthProvider.SITEMINDER);
				MSProfile.fillAccessToken(accessToken, null);
				addAccessToken(accessToken, request);

				if(logger.isDebugEnabled()) {
					logger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
			}
		}

		// grab the user again
		userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
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

		if(logger.isDebugEnabled()) {
			logger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
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
		/*
		 * Try to log in the user
		 * If they are not logged in
		 * Redirect the FE
		 */

		User userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.DROPBOX) == null) {
				String[] outputs = AbstractHttpHelper.getCodes(queryString);

				String prefix = "dropbox_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");

				if(logger.isDebugEnabled()) {
					logger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
				}
				
				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("grant_type", "authorization_code");
				params.put("client_secret", clientSecret);

				String url = "https://www.dropbox.com/oauth2/token";

				AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, true, true);
				if (accessToken == null) {
					// not authenticated
					response.setStatus(302);
					response.sendRedirect(getDBRedirect(request));
					return null;
				}
				accessToken.setProvider(AuthProvider.DROPBOX);
				addAccessToken(accessToken, request);

				if(logger.isDebugEnabled()) {
					logger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
			}
		}

		// grab the user again
		userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
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

		if(logger.isDebugEnabled()) {
			logger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
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
		/*
		 * Try to log in the user
		 * If they are not logged in
		 * Redirect the FE
		 */

		User userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.GOOGLE) == null) {
				String[] outputs = AbstractHttpHelper.getCodes(queryString);

				String prefix = "google_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");

				if(logger.isDebugEnabled()) {
					logger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
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
				AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, true, true);
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
				addAccessToken(accessToken, request);

				// Shows how to make a google credential from an access token
				if(logger.isDebugEnabled()) {
					logger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
				
				// this is just for testing...
				// but i will get yelled at if i remove it so here it is...
				// TODO: adding this todo to easily locate it
//				performGoogleOps(request, ret);
			}
		}

		// grab the user again
		userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
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

		if(logger.isDebugEnabled()) {
			logger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
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
			logger.error(Constants.STACKTRACE, e);
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
	public Response loginProducthunt(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws IOException {
		/*
		 * Try to log in the user
		 * If they are not logged in
		 * Redirect the FE
		 */

		User userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);

		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.PRODUCT_HUNT) == null) {
				String[] outputs = AbstractHttpHelper.getCodes(queryString);

				String prefix = "producthunt_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");

				if(logger.isDebugEnabled()) {
					logger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
				}
				
				Hashtable params = new Hashtable();

				params.put("client_id", clientId);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("grant_type", "authorization_code");
				params.put("client_secret", clientSecret);

				String url = "https://api.producthunt.com/v1/oauth/token";

				AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, true, true);
				if (accessToken == null) {
					// not authenticated
					response.setStatus(302);
					response.sendRedirect(getProducthuntRedirect(request));
					return null;
				}
				accessToken.setProvider(AuthProvider.PRODUCT_HUNT);
				addAccessToken(accessToken, request);

				if(logger.isDebugEnabled()) {
					logger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
			}
		}

		// grab the user again
		userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
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

		if(logger.isDebugEnabled()) {
			logger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
		}
		
		return redirectUrl;
	}

	/**
	 * Logs user in through linkedin
	 */
	@GET
	@Produces("application/json")
	@Path("/login/linkedin")
	public Response loginIn(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws IOException {
		/*
		 * Try to log in the user
		 * If they are not logged in
		 * Redirect the FE
		 */

		User userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.IN) == null) {
				String[] outputs = AbstractHttpHelper.getCodes(queryString);

				String prefix = "in_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");

				if(logger.isDebugEnabled()) {
					logger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
				}
				
				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("grant_type", "authorization_code");
				params.put("client_secret", clientSecret);

				String url = "https://www.linkedin.com/oauth/v2/accessToken";

				AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, true, true);
				if (accessToken == null) {
					// not authenticated
					response.setStatus(302);
					response.sendRedirect(getInRedirect(request));
					return null;
				}
				accessToken.setProvider(AuthProvider.IN);
				addAccessToken(accessToken, request);
				
				if(logger.isDebugEnabled()) {
					logger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
			}
		}

		// grab the user again
		userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
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
		
		if(logger.isDebugEnabled()) {
			logger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
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
		// getting the bearer token on twitter for app authentication is a lot simpler
		// need to just combine the id and secret
		// base 64 and send as authorization

		// https://developer.github.com/apps/building-oauth-apps/authorization-options-for-oauth-apps/

		User userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || userObj.getAccessToken(AuthProvider.GITHUB) == null) {

				String[] outputs = AbstractHttpHelper.getCodes(queryString);
				String prefix = "git_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");

				if(logger.isDebugEnabled()) {
					logger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
				}
				
				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("state", outputs[1]);
				params.put("client_secret", clientSecret);

				String url = "https://github.com/login/oauth/access_token";

				AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, false, true);
				if (accessToken == null) {
					// not authenticated
					response.setStatus(302);
					response.sendRedirect(getTwitterRedirect(request));
					return null;
				}

				accessToken.setProvider(AuthProvider.GITHUB);
				addAccessToken(accessToken, request);

				if(logger.isDebugEnabled()) {
					logger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
			}
		}

		// grab the user again
		userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
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

		if(logger.isDebugEnabled()) {
			logger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
		}
		
		return redirectUrl;
	}

	
	
	/**
	 * Logs user in through generic provider
	 */
	@GET
	@Produces("application/json")
	@Path("/login/{provider}")
	public Response loginGeneric(@PathParam("provider") String provider, @Context HttpServletRequest request, @Context HttpServletResponse response)
			throws IOException {
		/*
		 * Try to log in the user
		 * If they are not logged in
		 * Redirect the FE
		 */
		
		AuthProvider providerEnum = AuthProvider.getProviderFromString(provider.toUpperCase());

		User userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		String queryString = request.getQueryString();
		if (queryString != null && queryString.contains("code=")) {
			if (userObj == null || ((User) userObj).getAccessToken(providerEnum) == null) {
				String[] outputs = AbstractHttpHelper.getCodes(queryString);

				String prefix = provider+"_";
				String clientId = socialData.getProperty(prefix + "client_id");
				String clientSecret = socialData.getProperty(prefix + "secret_key");
				String redirectUri = socialData.getProperty(prefix + "redirect_uri");
				//String tenant = socialData.getProperty(prefix + "tenant");
				
				//removing scope for now as it isn't needed in token call usually
				//String scope = socialData.getProperty(prefix + "scope");
				String token_url = socialData.getProperty(prefix + "token_url");

				if(logger.isDebugEnabled()) {
					logger.debug(">> " + Utility.cleanLogString(request.getQueryString()));
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

				AccessToken accessToken = AbstractHttpHelper.getAccessToken(token_url, params, true, true);
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
				GenericProfile.fillAccessToken(accessToken,userInfoURL, beanProps, jsonPattern, null);
				addAccessToken(accessToken, request);

				if(logger.isDebugEnabled()) {
					logger.debug("Access Token is.. " + accessToken.getAccess_token());
				}
			}
		}

		// grab the user again
		userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
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
		String redirectUrl = auth_url + "?" + "client_id="
				+ clientId + "&response_type=code" + "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8")
				+ "&response_mode=query" + "&scope=" + URLEncoder.encode(scope) + "&state=" + state;

		if(logger.isDebugEnabled()) {
			logger.debug("Sending redirect.. " + Utility.cleanLogString(redirectUrl));
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
	@Path("login")
	public Response loginNative(@Context HttpServletRequest request, @Context HttpServletResponse response) {
		Map<String, String> ret = new HashMap<>();
		try {
			String username = request.getParameter("username");
			String password = request.getParameter("password");
			String redirect = Utility.cleanHttpResponse(request.getParameter("redirect"));
			Boolean disableRedirect = Boolean.parseBoolean(request.getParameter("enableRedirect") + "");

			if(username == null || password == null || username.isEmpty() || password.isEmpty()) {
				ret.put(Constants.ERROR_MESSAGE, "The user name or password are empty");
				return WebUtility.getResponse(ret, 401);
			}
			
			boolean canLogin = NativeUserSecurityUtils.logIn(username, password);
			if (canLogin) {
				ret.put("success", "true");
				ret.put("username", username);
				String name = NativeUserSecurityUtils.getNameUser(username);
				String email = NativeUserSecurityUtils.getUserEmail(username);

				ret.put("name", name);
				ret.put("email", email);
				String id = NativeUserSecurityUtils.getUserId(username);
				AccessToken authToken = new AccessToken();
				authToken.setProvider(AuthProvider.NATIVE);
				authToken.setId(id);
				authToken.setName(username);
				authToken.setEmail(email);
				addAccessToken(authToken, request);

				// log the log in
				logger.info(ResourceUtility.getLogMessage(request, request.getSession(), id, " is logging out of provider " +  AuthProvider.NATIVE));
				if (!disableRedirect) {
					setMainPageRedirect(request, response, redirect);
				}
			} else {
				ret.put(Constants.ERROR_MESSAGE, "The user name or password are invalid.");
				return WebUtility.getResponse(ret, 401);
			}
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
			ret.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(ret, 500);
		}

		return WebUtility.getResponse(ret, 200);
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
	public Response createUser(@Context HttpServletRequest request) {
		Hashtable<String, String> ret = new Hashtable<>();
		try {
			// Note - for native users
			// the id and the username are always the same
			String username = request.getParameter("username");
			String name = request.getParameter("name");
			String password = request.getParameter("password");
			String email = request.getParameter("email");
			AccessToken newUser = new AccessToken();
			newUser.setProvider(AuthProvider.NATIVE);
			newUser.setId(username);
			newUser.setUsername(username);
			newUser.setEmail(email);
			newUser.setName(name);
			boolean userCreated = NativeUserSecurityUtils.addNativeUser(newUser, password);
			if (userCreated) {
				ret.put("success", "true");
				ret.put("username", username);
				return WebUtility.getResponse(ret, 200);
			} else {
				ret.put(Constants.ERROR_MESSAGE, "The user name or email aready exists.");
				return WebUtility.getResponse(ret, 400);
			}
		} catch (IllegalArgumentException iae) {
			logger.error(Constants.STACKTRACE, iae);
			ret.put(Constants.ERROR_MESSAGE, iae.getMessage());
			return WebUtility.getResponse(ret, 500);
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
			ret.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(ret, 500);
		}
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
	public Response loginsAllowed(@Context HttpServletRequest request) throws IOException {
		return WebUtility.getResponse(UserResource.loginsAllowedMap, 200);
	}

	@GET
	@Produces("application/json")
	@Path("/loginProperties/")
	public Response loginProperties(@Context HttpServletRequest request) throws IOException {
		if (AbstractSecurityUtils.securityEnabled()) {
			User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
			if (user == null) {
				return WebUtility.getResponse("No user defined to access properties. Please login as an admin", 400);
			}
			if (!SecurityAdminUtils.userIsAdmin(user)) {
				return WebUtility.getResponse("User is not an admin and does not have access. Please login as an admin",
						400);
			}
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
			MultivaluedMap<String, String> form, @Context HttpServletRequest request) throws IOException {
		if (AbstractSecurityUtils.securityEnabled()) {
			User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
			if (user == null) {
				return WebUtility.getResponse("No user defined to access properties. Please login as an admin", 400);
			}
			if (!SecurityAdminUtils.userIsAdmin(user)) {
				return WebUtility.getResponse("User is not an admin and does not have access. Please login as an admin",
						400);
			}
		}

		Gson gson = new Gson();
		String modStr = form.getFirst("modifications");
		Map<String, String> mods = gson.fromJson(modStr, new TypeToken<Map<String, String>>() {
		}.getType());

		PropertiesConfiguration config = null;
		try {
			config = new PropertiesConfiguration(DIHelper.getInstance().getProperty("SOCIAL"));
		} catch (ConfigurationException e1) {
			logger.error(Constants.STACKTRACE, e1);
			Hashtable<String, String> errorRet = new Hashtable<>();
			errorRet.put(Constants.ERROR_MESSAGE,
					"An unexpected error happened trying to access the properties. Please try again or reach out to server admin.");
			return WebUtility.getResponse(errorRet, 500);
		}

		for (String mod : mods.keySet()) {
			config.setProperty(provider + "_" + mod, mods.get(mod));
		}

		try {
			config.save();
			loadSocialProperties();
		} catch (ConfigurationException e1) {
			logger.error(Constants.STACKTRACE, e1);
			Hashtable<String, String> errorRet = new Hashtable<>();
			errorRet.put(Constants.ERROR_MESSAGE,
					"An unexpected error happened when saving the new login properties. Please try again or reach out to server admin.");
			return WebUtility.getResponse(errorRet, 500);
		}

		return WebUtility.getResponse(true, 200);
	}

	/**
	 * Redirect the login back to the main app page
	 * 
	 * @param response
	 */
	private void setMainPageRedirect(@Context HttpServletRequest request, @Context HttpServletResponse response) {
		setMainPageRedirect(request, response, null);
	}

	/**
	 * Redirect the login back to the main app page
	 * 
	 * @param response
	 */
	private void setMainPageRedirect(@Context HttpServletRequest request, 
			@Context HttpServletResponse response,
			String customRedirect) {
		// see if we have a location to redirect the user
		// if so, we will send them back to that URL
		// otherwise, we send them back to the FE
		HttpSession session = request.getSession();
		String contextPath = request.getContextPath();

		boolean useCustom = customRedirect != null && !customRedirect.isEmpty();
		boolean endpoint = session.getAttribute(NoUserInSessionFilter.ENDPOINT_REDIRECT_KEY) != null;
		response.setStatus(302);
		try {
			// add the cookie to the header directly
			// to allow for cross site login when embedded as iframe
			String setCookieString = DBLoader.getSessionIdKey() + "=" + session.getId() 
					+ "; Path=" + contextPath 
					+ "; HttpOnly"
					+ ( (ClusterUtil.IS_CLUSTER || request.isSecure()) ? "; Secure; SameSite=None" : "")
					;
			response.addHeader("Set-Cookie", setCookieString);
			if (useCustom) {
				response.addHeader("redirect", customRedirect);
				String encodedCustomRedirect = Encode.forHtml(customRedirect);
				response.sendError(302, "Need to redirect to " + encodedCustomRedirect);
			} else if (endpoint) {
				String redirectUrl = session.getAttribute(NoUserInSessionFilter.ENDPOINT_REDIRECT_KEY) + "";
				response.addHeader("redirect", redirectUrl);
				response.sendError(302, "Need to redirect to " + redirectUrl);
			} else {
				response.sendRedirect(socialData.getProperty("redirect"));
			}
		} catch (IOException e) {
			logger.error(Constants.STACKTRACE, e);
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

		if(logger.isDebugEnabled()) {
			logger.debug("Session id set to " + sessionId);
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
			if (envMap.containsKey(NoUserInSessionFilter.MONOLITH_ROUTE)) {
				String routeCookieName = envMap.get(NoUserInSessionFilter.MONOLITH_ROUTE);
				Cookie[] curCookies = request.getCookies();
				if (curCookies != null) {
					for (Cookie c : curCookies) {
						if (c.getName().equals(routeCookieName)) {
							redir += "&" + c.getName() + "=" + c.getValue();
						}
					}
				}
			}

			if(logger.isDebugEnabled()) {
				logger.debug("Redirect URL " + Utility.cleanLogString(redir));
			}
			
			outputHash.put("PARAM", redir);
			// also tell the system that this session is not fully validated so if someone
			// comes without secret on this session
			// dont allow
			user.addShare(sessionId);
		} catch (NoSuchAlgorithmException e) {
			logger.error(Constants.STACKTRACE, e);
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
				List<Map<String, Object>> gropus = new Vector<>();
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
	
	/**
	 * Get the SEMOSS user id to the list of SAML attributes that generate the value
	 * @return
	 */
	public static Map<String, String[]> getSamlAttributeNames() {
		final String NULL_INPUT = "NULL";
		
		String prefix = Constants.SAML + "_";
		Map<String, String[]> samlAttrMap = new HashMap<>();
	    Set<String> samlProps = socialData.stringPropertyNames().stream().filter(str->str.startsWith(prefix)).collect(Collectors.toSet());
	    for(String samlKey : samlProps) {
	    	// key
	    	String socialKey = samlKey.replaceFirst(prefix, "").toLowerCase();
	    	// value
	    	if(socialData.get(samlKey) == null) {
	    		continue;
	    	}
	    	String socialValue = socialData.get(samlKey).toString().trim();
	    	if( socialValue.isEmpty() || socialValue.equals(NULL_INPUT)) {
	    		continue;
	    	}
	    	socialValue = socialValue.toLowerCase();
	    	
	    	String[] keyGeneratedBy = socialValue.split("\\+");
			samlAttrMap.putIfAbsent(socialKey, keyGeneratedBy);
	    }
		return samlAttrMap;
	}
}
