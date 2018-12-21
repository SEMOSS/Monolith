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
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.kohsuke.github.GHMyself;
import org.kohsuke.github.GitHub;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import jodd.util.URLDecoder;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.InsightToken;
import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.NativeUserSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.ds.py.PyExecutorThread;
import prerna.ds.py.PyUtils;
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
import prerna.security.AbstractHttpHelper;
import prerna.util.BeanFiller;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;
import waffle.servlet.WindowsPrincipal;

@Path("/auth")
public class UserResource {
	
	private static Properties socialData = null;
	private static Map<String, Boolean> loginsAllowed;
	
	static {
		loadSocialProperties();
	}
	
	private static void loadSocialProperties() {
		FileInputStream fis = null;
		File f = new File(DIHelper.getInstance().getProperty("SOCIAL"));
		try {
			if(f.exists()) {
				socialData = new Properties();
				fis = new FileInputStream(f);
				socialData.load(fis);
				setLoginsAllowed();
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if(fis != null) {
				try {
					fis.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	private static void setLoginsAllowed() {
		boolean nativeLogin = Boolean.parseBoolean(socialData.getProperty("native_login"));
		boolean githubLogin = Boolean.parseBoolean(socialData.getProperty("github_login"));
		boolean googleLogin = Boolean.parseBoolean(socialData.getProperty("google_login"));
		boolean onedriveLogin = Boolean.parseBoolean(socialData.getProperty("ms_login"));
		boolean dropboxLogin = Boolean.parseBoolean(socialData.getProperty("dropbox_login"));
		boolean cacLogin = Boolean.parseBoolean(socialData.getProperty("cac_login"));
		boolean registration = Boolean.parseBoolean(socialData.getProperty("native_registration"));
		UserResource.loginsAllowed = new HashMap<String, Boolean>();
		UserResource.loginsAllowed.put("native", nativeLogin);
		UserResource.loginsAllowed.put("google", googleLogin);
		UserResource.loginsAllowed.put("github", githubLogin);
		UserResource.loginsAllowed.put("ms", onedriveLogin);
		UserResource.loginsAllowed.put("dropbox", dropboxLogin);
		UserResource.loginsAllowed.put("cac", cacLogin);
		UserResource.loginsAllowed.put("registration", registration);
	}
	
	public static Map<String, Boolean> getLoginsAllowed() {
		return UserResource.loginsAllowed;
	}
	
	@GET
	@Path("logins")
	public Response getAllLogins(@Context HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		User semossUser = null;
		if(session != null) {
			semossUser = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		}
		Map<String, String> retMap = User.getLoginNames(semossUser);
		return WebUtility.getResponse(retMap, 200);
	}
	
	@GET
	@Produces("application/json")
	@Path("/logout/{provider}")
	public Response logout(@PathParam("provider") String provider, @Context HttpServletRequest request, @Context HttpServletResponse repsonse) throws IOException {
		boolean noUser = false;
		boolean removed = false;
		
		HttpSession session = request.getSession();
		User thisUser = (User) session.getAttribute(Constants.SESSION_USER);
		
		if(provider.equalsIgnoreCase("ALL")) {
			// remove the user from session call it a day
			request.getSession().removeAttribute(Constants.SESSION_USER);
			removed = true;
			noUser = true;
		} else {
			removed = thisUser.dropAccessToken(provider.toUpperCase());
			if(thisUser.getLogins().isEmpty()) {
				noUser = true;
			} else {
				request.getSession().setAttribute(Constants.SESSION_USER, thisUser);
			}
		}
		
		// if there are no users and there is security
		// redirect the user
		// and invalidate the session
		if(noUser && AbstractSecurityUtils.securityEnabled()) {
			session.removeAttribute(Constants.SESSION_USER);
			// well, you have logged out and we always require a login
			// so i will redirect you
			repsonse.setStatus(302);
			String redirectUrl = request.getHeader("referer");
			redirectUrl = redirectUrl + "#!/login";
			repsonse.setHeader("redirect", redirectUrl);
			repsonse.sendError(302, "Need to redirect to " + redirectUrl);
			
			// remove the cookie from the browser
			// for the session id
			Cookie cookie = new Cookie("JSESSIONID", null);
			cookie.setPath("/");
			repsonse.addCookie(cookie);

			// kill the user python thread here
			if(PyUtils.pyEnabled()) {
				PyExecutorThread pyThread = (PyExecutorThread) session.getAttribute(Constants.PYTHON);
				PyUtils.getInstance().killPyThread(pyThread);
			}
			
			// invalidate the session
			session.invalidate();
			return null;
		}
		
		Map<String, Boolean> ret = new Hashtable<String, Boolean>();
		ret.put("success", removed);
		return WebUtility.getResponse(ret, 200);		
	}
	
	/**
	 * Method to add an access token to a user
	 * @param token
	 * @param request
	 */
	private void addAccessToken(AccessToken token, HttpServletRequest request) {
		User semossUser = null;
		HttpSession session = request.getSession();
		Object user = session.getAttribute(Constants.SESSION_USER);
		if(user != null) {
			semossUser = (User)user;
		} else {
			semossUser = new User();
			
			// also add the python thread to this user
			// if security is not on, we have a single py thread for the entire instance
			// and we dont want to override those variables due to user login
			if(AbstractSecurityUtils.securityEnabled() && PyUtils.pyEnabled()) {
				session.setAttribute(Constants.PYTHON, PyUtils.getInstance().getJep());
			}
		}
		semossUser.setAccessToken(token);
		request.getSession().setAttribute(Constants.SESSION_USER, semossUser);
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
		Map<String, String> ret = new Hashtable<String, String>();
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);

		String [] beanProps = {"name", "profile"};
		String jsonPattern = "[name, picture]";
		String accessString = null;
		try {
			if(user == null) {
				ret.put("ERROR", "Log into your Google account");
				return WebUtility.getResponse(ret, 200);
			} else {
				AccessToken googleToken = user.getAccessToken(AuthProvider.GOOGLE);
				accessString = googleToken.getAccess_token();
			}
		} catch (Exception e) {
			ret.put("ERROR", "Log into your Google account");
			return WebUtility.getResponse(ret, 200);
		}
		
		String url = "https://www.googleapis.com/oauth2/v3/userinfo";
		Hashtable params = new Hashtable();
		params.put("access_token", accessString);
		params.put("alt", "json");

		String output = AbstractHttpHelper.makeGetCall(url, accessString, params, true);
		AccessToken accessToken2 = (AccessToken)BeanFiller.fillFromJson(output, jsonPattern, beanProps, new AccessToken());
		try {
			ret.put("name", accessToken2.getName());
			if(accessToken2.getProfile() != null) {
				ret.put("picture", accessToken2.getProfile());
			}
		} catch (Exception e) {
			e.printStackTrace();
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
		Map<String, String> ret = new Hashtable<String, String>();
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);

		String [] beanProps = {"name"};
		String jsonPattern = "[displayName]";

		String accessString = null;
		try{
			if(user == null) {
				ret.put("ERROR", "Log into your Microsoft account");
			} else {
				AccessToken msToken = user.getAccessToken(AuthProvider.MS);
				accessString = msToken.getAccess_token();
				String url = "https://graph.microsoft.com/v1.0/me/";
				String output = AbstractHttpHelper.makeGetCall(url, accessString, null, true);
				AccessToken accessToken2 = (AccessToken)BeanFiller.fillFromJson(output, jsonPattern, beanProps, new AccessToken());
				String name = accessToken2.getName();
				ret.put("name", name);
			}
			return WebUtility.getResponse(ret, 200);
		}
		catch (Exception e) {
			ret.put("ERROR", "Log into your Microsoft account");
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
		Map<String, String> ret = new Hashtable<String, String>();
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);

		String [] beanProps = {"name", "profile"}; // add is done when you have a list
		String jsonPattern = "[name.display_name, profile_photo_url]";
		String accessString = null;
		try {
			if(user == null){
				ret.put("ERROR", "Log into your DropBox account");
				return WebUtility.getResponse(ret, 200);
			} else {
				AccessToken dropToken = user.getAccessToken(AuthProvider.DROPBOX);
				accessString=dropToken.getAccess_token();
			}
		}
		catch (Exception e) {
			ret.put("ERROR", "Log into your DropBox account");
			return WebUtility.getResponse(ret, 200);
		}
		String url = "https://api.dropboxapi.com/2/users/get_current_account";

		String output = AbstractHttpHelper.makePostCall(url, accessString, null, true);
		AccessToken accessToken2 = (AccessToken)BeanFiller.fillFromJson(output, jsonPattern, beanProps, new AccessToken());
		try {
			if(accessToken2.getProfile() == null||accessToken2.getProfile().equalsIgnoreCase("null")) {
				ret.put("picture", "");
			} else {
				ret.put("picture", accessToken2.getProfile());
			}
			ret.put("name", accessToken2.getName());
		} catch (Exception e) {
			e.printStackTrace();
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
		Map<String, String> ret = new Hashtable<String, String>();
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);

		String [] beanProps = {"name", "profile"}; // add is done when you have a list
		String jsonPattern = "[name,login]";

		String accessString = null;
		try{
			if(user == null) {
				ret.put("ERROR", "Log into your Github account");
				return WebUtility.getResponse(ret, 200);
			} else {
				AccessToken gitToken = user.getAccessToken(AuthProvider.GITHUB);
				accessString=gitToken.getAccess_token();
			}
		} catch (Exception e) {
			ret.put("ERROR", "Log into your Github account");
			return WebUtility.getResponse(ret, 200);
		}

		String url = "https://api.github.com/user";
		Hashtable params = new Hashtable();
		params.put("access_token", accessString);

		String output = AbstractHttpHelper.makeGetCall(url, accessString, params, false);
		AccessToken accessToken2 = (AccessToken)BeanFiller.fillFromJson(output, jsonPattern, beanProps, new AccessToken());
		try {
			ret.put("name", accessToken2.getProfile());
		} catch (Exception e) {
			e.printStackTrace();
		}
		return WebUtility.getResponse(ret, 200);
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
		
		User userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		Hashtable<String, Object> ret = new Hashtable<String, Object>();

		String queryString = request.getQueryString();

		if(queryString != null && queryString.contains("code=")) {
			if(userObj == null || userObj.getAccessToken(AuthProvider.SF) == null) {
				String[] outputs = AbstractHttpHelper.getCodes(queryString);

				String prefix = "sf_";
				String clientId = socialData.getProperty(prefix+"client_id");
				String clientSecret = socialData.getProperty(prefix+"secret_key");
				String redirectUri = socialData.getProperty(prefix+"redirect_uri");

				System.out.println(">> " + request.getQueryString());

				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("grant_type", "authorization_code");
				params.put("redirect_uri", redirectUri);
				params.put("code", URLDecoder.decode(outputs[0]));
				params.put("client_secret", clientSecret);

				String url = "https://login.salesforce.com/services/oauth2/token";
				
				AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, true, true);
				if(accessToken == null) {
					// not authenticated
					response.setStatus(302);
					response.sendRedirect(getSFRedirect(request));
					return null;
				}
				accessToken.setProvider(AuthProvider.SF);
				addAccessToken(accessToken, request);

				System.out.println("Access Token is.. " + accessToken.getAccess_token());
			}
		}

		// grab the user again
		userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		if(userObj == null || userObj.getAccessToken(AuthProvider.SF) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getSFRedirect(request));
		} else {
			setMainPageRedirect(request, response, null);
		}
		return null;
	}
	
	private String getSFRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "sf_";
		String clientId = socialData.getProperty(prefix+"client_id");
		String redirectUri = socialData.getProperty(prefix+"redirect_uri");

		String redirectUrl = "https://login.salesforce.com/services/oauth2/authorize?" +
		"client_id=" + clientId +
		"&response_type=code" +
		"&redirect_uri=" + redirectUri +
		"&scope=" + URLEncoder.encode("api", "UTF-8");

		System.out.println("Sending redirect.. " + redirectUrl);
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
		
		User userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);

		String queryString = request.getQueryString();
		if(queryString != null && queryString.contains("code=")) {
			if(userObj == null || userObj.getAccessToken(AuthProvider.SURVEYMONKEY) == null) {
				String[] outputs = AbstractHttpHelper.getCodes(queryString);

				String prefix = "surveymonkey_";
				String clientId = socialData.getProperty(prefix+"client_id");
				String clientSecret = socialData.getProperty(prefix+"secret_key");
				String redirectUri = socialData.getProperty(prefix+"redirect_uri");

				System.out.println(">> " + request.getQueryString());

				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("grant_type", "authorization_code");
				params.put("redirect_uri", redirectUri);
				params.put("code", URLDecoder.decode(outputs[0]));
				params.put("client_secret", clientSecret);

				String url = "https://api.surveymonkey.com/oauth/token";
				
				AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, true, true);
				if(accessToken == null) {
					// not authenticated
					response.setStatus(302);
					response.sendRedirect(getSurveyMonkeyRedirect(request));
					return null;
				}
				accessToken.setProvider(AuthProvider.SURVEYMONKEY);
				MonkeyProfile.fillAccessToken(accessToken, null);
				addAccessToken(accessToken, request);

				System.out.println("Access Token is.. " + accessToken.getAccess_token());
			}
		}

		// grab the user again
		userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		if(userObj == null || userObj.getAccessToken(AuthProvider.SURVEYMONKEY) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getSurveyMonkeyRedirect(request));
		} else {
			setMainPageRedirect(request, response, null);
		}
		return null;
	}
	
	private String getSurveyMonkeyRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "surveymonkey_";
		String clientId = socialData.getProperty(prefix+"client_id");
		String redirectUri = socialData.getProperty(prefix+"redirect_uri");
		
		String redirectUrl = "https://api.surveymonkey.com/oauth/authorize?" +
		"client_id=" + clientId +
		"&response_type=code" +
		"&redirect_uri=" + redirectUri;

		System.out.println("Sending redirect.. " + redirectUrl);
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

		Map<String, Object> ret = new Hashtable<String, Object>();
		User userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);

		String queryString = request.getQueryString();
		if(queryString != null && queryString.contains("code=")) {
			if(userObj == null || userObj.getAccessToken(AuthProvider.GITHUB) == null) {
				String [] outputs = AbstractHttpHelper.getCodes(queryString);

				String prefix = "github_";
				String clientId = socialData.getProperty(prefix+"client_id");
				String clientSecret = socialData.getProperty(prefix+"secret_key");
				String redirectUri = socialData.getProperty(prefix+"redirect_uri");

				System.out.println(">> " + request.getQueryString());

				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("state", outputs[1]);
				params.put("client_secret", clientSecret);

				String url = "https://github.com/login/oauth/access_token";

				AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, false, true);
				if(accessToken == null) {
					// not authenticated
					response.setStatus(302);
					response.sendRedirect(getGithubRedirect(request));
					return null;
				}
				accessToken.setProvider(AuthProvider.GITHUB);

				// add specific Git values
				GHMyself myGit = GitHub.connectUsingOAuth(accessToken.getAccess_token()).getMyself();
				accessToken.setId(myGit.getId() + "");
				accessToken.setEmail(myGit.getEmail());
				accessToken.setName(myGit.getName());
				accessToken.setLocale(myGit.getLocation());
				accessToken.setUsername(myGit.getLogin());
				addAccessToken(accessToken, request);

				System.out.println("Access Token is.. " + accessToken.getAccess_token());
			}
		}
		
		// grab the user again
		userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		if(userObj == null || userObj.getAccessToken(AuthProvider.GITHUB) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getGithubRedirect(request));
		} else {
			setMainPageRedirect(request, response, null);
		}
		return null;
	}
	
	private String getGithubRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "github_";
		String clientId = socialData.getProperty(prefix+"client_id");
		String redirectUri = socialData.getProperty(prefix+"redirect_uri");
		String scope = socialData.getProperty(prefix+"scope");

		String redirectUrl = "https://github.com/login/oauth/authorize?" +
				"client_id=" + clientId +
				"&redirect_uri=" + redirectUri +
				"&state=" + UUID.randomUUID().toString() +
				"&allow_signup=true" + 
				"&scope=" + URLEncoder.encode(scope, "UTF-8");

		System.out.println("Sending redirect.. " + redirectUrl);
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

		Map<String, Object> ret = new Hashtable<String, Object>();
		User userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);

		String queryString = request.getQueryString();
		if(queryString != null && queryString.contains("code=")) {
			if(userObj == null || ((User)userObj).getAccessToken(AuthProvider.MS) == null) {
				String [] outputs = AbstractHttpHelper.getCodes(queryString);

				String prefix = "ms_";
				String clientId = socialData.getProperty(prefix+"client_id");
				String clientSecret = socialData.getProperty(prefix+"secret_key");
				String redirectUri = socialData.getProperty(prefix+"redirect_uri");
				String tenant = socialData.getProperty(prefix+"tenant");
				String scope = socialData.getProperty(prefix+"scope");

				System.out.println(">> " + request.getQueryString());

				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("scope", scope);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("grant_type", "authorization_code");
				params.put("client_secret", clientSecret);

				String url = "https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/token";

				AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, true, true);
				if(accessToken == null) {
					// not authenticated
					response.setStatus(302);
					response.sendRedirect(getMSRedirect(request));
					return null;
				}
				
				accessToken.setProvider(AuthProvider.MS);
				MSProfile.fillAccessToken(accessToken, null);
				addAccessToken(accessToken, request);

				System.out.println("Access Token is.. " + accessToken.getAccess_token());
			}
		}
		
		// grab the user again
		userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		if(userObj == null || userObj.getAccessToken(AuthProvider.MS) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getMSRedirect(request));
		} else {
			setMainPageRedirect(request, response, null);
		}
		return null;
	}
	
	private String getMSRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "ms_";
		String clientId = socialData.getProperty(prefix+"client_id");
		String redirectUri = socialData.getProperty(prefix+"redirect_uri");
		String tenant = socialData.getProperty(prefix+"tenant");
		String scope = socialData.getProperty(prefix+"scope"); // need to set this up and reuse

		String state = UUID.randomUUID().toString();
		String redirectUrl = "https://login.microsoftonline.com/" + tenant
				+ "/oauth2/v2.0/authorize?" + "client_id=" + clientId
				+ "&response_type=code" + "&redirect_uri="
				+ URLEncoder.encode(redirectUri, "UTF-8")
				+ "&response_mode=query" + "&scope=" + URLEncoder.encode(scope) + "&state="
				+ state;
		
		System.out.println("Sending redirect.. " + redirectUrl);

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
		
		Map<String, Object> ret = new Hashtable<String, Object>();
		User userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		
		String queryString = request.getQueryString();
		if(queryString != null && queryString.contains("code=")) {
			if(userObj == null || userObj.getAccessToken(AuthProvider.DROPBOX) == null) {
				String [] outputs = AbstractHttpHelper.getCodes(queryString);

				String prefix = "dropbox_";
				String clientId = socialData.getProperty(prefix+"client_id");
				String clientSecret = socialData.getProperty(prefix+"secret_key");
				String redirectUri = socialData.getProperty(prefix+"redirect_uri");

				System.out.println(">> " + request.getQueryString());

				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("grant_type", "authorization_code");
				params.put("client_secret", clientSecret);

				String url = "https://www.dropbox.com/oauth2/token";

				AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, true, true);
				if(accessToken == null) {
					// not authenticated
					response.setStatus(302);
					response.sendRedirect(getDBRedirect(request));
					return null;
				}
				accessToken.setProvider(AuthProvider.DROPBOX);
				addAccessToken(accessToken, request);

				System.out.println("Access Token is.. " + accessToken.getAccess_token());			
			}
		}
		
		// grab the user again
		userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		if(userObj == null || userObj.getAccessToken(AuthProvider.DROPBOX) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getDBRedirect(request));
		} else {
			setMainPageRedirect(request, response, null);
		}
		return null;
	}
	
	private String getDBRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "dropbox_";
		
		String clientId = socialData.getProperty(prefix+"client_id");
		String redirectUri = socialData.getProperty(prefix+"redirect_uri");
		String role = socialData.getProperty(prefix+"role"); // need to set this up and reuse
		String redirectUrl = "https://www.dropbox.com/oauth2/authorize?" + 
				"client_id=" + clientId+
				"&response_type=code" + 
				"&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8")+ 
				"&require_role=" + role + 
				"&disable_signup=false";		

		System.out.println("Sending redirect.. " + redirectUrl);
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

		Map<String, Object> ret = new Hashtable<String, Object>();
		User userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);

		String queryString = request.getQueryString();
		if(queryString != null && queryString.contains("code=")) {
			if(userObj == null || userObj.getAccessToken(AuthProvider.GOOGLE) == null) {
				String [] outputs = AbstractHttpHelper.getCodes(queryString);
				
				String prefix = "google_";
				String clientId = socialData.getProperty(prefix+"client_id");
				String clientSecret = socialData.getProperty(prefix+"secret_key");
				String redirectUri = socialData.getProperty(prefix+"redirect_uri");
				
				System.out.println(">> " + request.getQueryString());
				
				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("grant_type", "authorization_code");
				params.put("client_secret", clientSecret);
	
				String url = "https://www.googleapis.com/oauth2/v4/token";
				
				//https://developers.google.com/api-client-library/java/google-api-java-client/oauth2
				AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, true, true);
				if(accessToken == null) {
					// not authenticated
					response.setStatus(302);
					response.sendRedirect(getGoogleRedirect(request));
					return null;
				}
				accessToken.setProvider(AuthProvider.GOOGLE);

				// fill the access token with the other properties so we can properly create the user
				GoogleProfile.fillAccessToken(accessToken, null);
				addAccessToken(accessToken, request);
				
				// Shows how to make a google credential from an access token
				System.out.println("Access Token is.. " + accessToken.getAccess_token());
				
				// this is just for testing...
				// but i will get yelled at if i remove it so here it is...
				// TODO: adding this todo to easily locate it
//				performGoogleOps(request, ret);
			}
		}

		// grab the user again
		userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		if(userObj == null || userObj.getAccessToken(AuthProvider.GOOGLE) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getGoogleRedirect(request));
		} else {
			setMainPageRedirect(request, response, null);
		}
		return null;
	}
	
	private String getGoogleRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "google_";
		String clientId = socialData.getProperty(prefix+"client_id");
		String redirectUri = socialData.getProperty(prefix+"redirect_uri");
		String scope = socialData.getProperty(prefix+"scope"); // need to set this up and reuse
		String accessType = socialData.getProperty(prefix+"access_type"); // need to set this up and reuse
		String state = UUID.randomUUID().toString();
		
		String redirectUrl = "https://accounts.google.com/o/oauth2/v2/auth?"
				+ "client_id=" + clientId
				+ "&response_type=code" 
				+ "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8")
				+ "&access_type=" + accessType
				+ "&scope=" + URLEncoder.encode(scope, "UTF-8")
				+ "&state="	+ state;

		System.out.println("Sending redirect.. " + redirectUrl);
		return redirectUrl;
	}
	
	/**
	 * METHOD IS USED FOR TESTING
	 * @param request
	 * @param ret
	 */
	private void performGoogleOps(HttpServletRequest request, Map ret)
	{
		// get the user details
		IConnectorIOp prof = new GoogleProfile();
		prof.execute((User)request.getSession().getAttribute(Constants.SESSION_USER), null);
		
		IConnectorIOp lister = new GoogleListFiles();
		List fileList = (List)lister.execute((User)request.getSession().getAttribute(Constants.SESSION_USER), null);

		// get the file
		IConnectorIOp getter = new GoogleFileRetriever();
		Hashtable params2 = new Hashtable();
		params2.put("exportFormat", "csv");
		params2.put("id", "1it40jNFcRo1ur2dHIYUk18XmXdd37j4gmJm_Sg7KLjI");
		params2.put("target", "c:\\users\\pkapaleeswaran\\workspacej3\\datasets\\googlefile.csv");

		getter.execute((User)request.getSession().getAttribute(Constants.SESSION_USER), params2);

		IConnectorIOp ner = new GoogleEntityResolver();

		NLPDocumentInput docInput = new NLPDocumentInput();
		docInput.setContent("Obama is staying in the whitehouse !!");

		params2 = new Hashtable();
		
		//Hashtable docInputShell = new Hashtable();
		params2.put("encodingType", "UTF8");
		params2.put("document", docInput);
		
		//params2.put("input", docInputShell);
		ner.execute((User)request.getSession().getAttribute(Constants.SESSION_USER), params2);
		
		try {
			ret.put("files", BeanFiller.getJson(fileList));
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		IConnectorIOp lat = new GoogleLatLongGetter();
		params2 = new Hashtable();
		params2.put("address", "1919 N Lynn Street, Arlington, VA");
		
		lat.execute((User)request.getSession().getAttribute(Constants.SESSION_USER), params2);

		IConnectorIOp ts = new TwitterSearcher();
		params2 = new Hashtable();
		params2.put("q", "Anlaytics");
		params2.put("lang", "en");
		params2.put("count", "10");
		
		Object vp = ts.execute((User)request.getSession().getAttribute(Constants.SESSION_USER), params2);
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
		
		Map<String, Object> ret = new Hashtable<String, Object>();
		User userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		
		String queryString = request.getQueryString();
		if(queryString != null && queryString.contains("code=")) {
			if(userObj == null || userObj.getAccessToken(AuthProvider.PRODUCT_HUNT) == null) {
				String [] outputs = AbstractHttpHelper.getCodes(queryString);
				
				String prefix = "producthunt_";
				String clientId = socialData.getProperty(prefix+"client_id");
				String clientSecret = socialData.getProperty(prefix+"secret_key");
				String redirectUri = socialData.getProperty(prefix+"redirect_uri");
				
				System.out.println(">> " + request.getQueryString());
				
				Hashtable params = new Hashtable();
	
				params.put("client_id", clientId);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("grant_type", "authorization_code");
				params.put("client_secret", clientSecret);

				String url = "https://api.producthunt.com/v1/oauth/token";

				AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, true, true);
				if(accessToken == null) {
					// not authenticated
					response.setStatus(302);
					response.sendRedirect(getProducthuntRedirect(request));
					return null;
				}
				accessToken.setProvider(AuthProvider.PRODUCT_HUNT);
				addAccessToken(accessToken, request);

				System.out.println("Access Token is.. " + accessToken.getAccess_token());			
			}
		}

		// grab the user again
		userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		if(userObj == null || userObj.getAccessToken(AuthProvider.PRODUCT_HUNT) == null) {
			response.setStatus(302);
			response.sendRedirect(getProducthuntRedirect(request));
		} else {
			setMainPageRedirect(request, response, null);
		}
		return null;
	}
	
	private String getProducthuntRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "producthunt_";
		
		String clientId = socialData.getProperty(prefix+"client_id");
		String redirectUri = socialData.getProperty(prefix+"redirect_uri");
		String scope = socialData.getProperty(prefix+"scope");

		String redirectUrl = "https://api.producthunt.com/v1/oauth/authorize?" + 
				"client_id=" + clientId+
				"&response_type=code" + 
				"&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8")+ 
				"&scope=" + URLEncoder.encode(scope, "UTF-8") ;

		System.out.println("Sending redirect.. " + redirectUrl);
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
		
		Map<String, Object> ret = new Hashtable<String, Object>();
		User userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		
		String queryString = request.getQueryString();
		if(queryString != null && queryString.contains("code=")) {
			if(userObj == null || userObj.getAccessToken(AuthProvider.IN) == null) {
				String [] outputs = AbstractHttpHelper.getCodes(queryString);

				String prefix = "in_";
				String clientId = socialData.getProperty(prefix+"client_id");
				String clientSecret = socialData.getProperty(prefix+"secret_key");
				String redirectUri = socialData.getProperty(prefix+"redirect_uri");

				System.out.println(">> " + request.getQueryString());

				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("grant_type", "authorization_code");
				params.put("client_secret", clientSecret);

				String url = "https://www.linkedin.com/oauth/v2/accessToken";

				AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, true, true);
				if(accessToken == null) {
					// not authenticated
					response.setStatus(302);
					response.sendRedirect(getInRedirect(request));
					return null;
				}
				accessToken.setProvider(AuthProvider.IN);
				addAccessToken(accessToken, request);
				System.out.println("Access Token is.. " + accessToken.getAccess_token());
			}
		}
		
		// grab the user again
		userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		if(userObj == null || userObj.getAccessToken(AuthProvider.IN) == null) {
			response.setStatus(302);
			response.sendRedirect(getInRedirect(request));
		} else {
			setMainPageRedirect(request, response, null);
		}
		return null;
	}
	
	private String getInRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "in_";
		String clientId = socialData.getProperty(prefix+"client_id");
		String redirectUri = socialData.getProperty(prefix+"redirect_uri");
		String scope = socialData.getProperty(prefix+"scope");

		String redirectUrl = "https://www.linkedin.com/oauth/v2/authorization?" +
				"client_id=" + clientId +
				"&redirect_uri=" + redirectUri +
				"&state=" + UUID.randomUUID().toString() +
				"&response_type=code" +
				"&scope=" + URLEncoder.encode(scope, "UTF-8");

		System.out.println("Sending redirect.. " + redirectUrl);
		return redirectUrl;
	}

	
	
	
	


	/**
	 * WHY IS THIS GIT?!?!?!
	 * WHY IS THIS GIT?!?!?!
	 * WHY IS THIS GIT?!?!?!
	 * WHY IS THIS GIT?!?!?!
	 * WHY IS THIS GIT?!?!?!
	 * WHY IS THIS GIT?!?!?!
	 * WHY IS THIS GIT?!?!?!
	 * @param request
	 * @param response
	 * @return
	 * @throws IOException
	 */
	@GET
	@Produces("application/json")
	@Path("/login/twitter")
	public Response loginTwitter(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException 
	{
		// redirect if query string not there
		Object userObj = request.getSession().getAttribute(Constants.SESSION_USER);
		Hashtable<String, Object> ret = new Hashtable<String, Object>();
		
		// getting the bearer token on twitter for app authentication is a lot simpler
		// need to just combine the id and secret
		// base 64 and send as authorization
		
		
		//https://developer.github.com/apps/building-oauth-apps/authorization-options-for-oauth-apps/
		String queryString = request.getQueryString();
		
		if(queryString != null && queryString.contains("code=")) {
			if(userObj == null || ((User)userObj).getAccessToken(AuthProvider.GITHUB) == null) {

				String [] outputs = AbstractHttpHelper.getCodes(queryString);

				String prefix = "git_";

				String clientId = socialData.getProperty(prefix+"client_id");
				String clientSecret = socialData.getProperty(prefix+"secret_key");
				String redirectUri = socialData.getProperty(prefix+"redirect_uri");

				System.out.println(">> " + request.getQueryString());

				Hashtable params = new Hashtable();
				params.put("client_id", clientId);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("state", outputs[1]);
				params.put("client_secret", clientSecret);

				String url = "https://github.com/login/oauth/access_token";

				AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, false, true);
				accessToken.setProvider(AuthProvider.GITHUB);
				addAccessToken(accessToken, request);

				System.out.println("Access Token is.. " + accessToken.getAccess_token());
			}
		}
		
		// grab the user again
		userObj = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		if(userObj == null || ((User)userObj).getAccessToken(AuthProvider.GITHUB) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getGithubRedirect(request));
		} else {
			setMainPageRedirect(request, response, null);
		}
		return null;
	}
	
	private String getTwitterRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
		String prefix = "twitter_";
		
		String clientId = socialData.getProperty(prefix+"client_id");
		String clientSecret = socialData.getProperty(prefix+"secret_key");
		String redirectUri = socialData.getProperty(prefix+"redirect_uri");
		String scope = socialData.getProperty(prefix+"scope");
		String nonce = UUID.randomUUID().toString() ;
		String timestamp = System.currentTimeMillis() +"";

		
		StringBuffer signatureString = new StringBuffer("GET").append("&");
		signatureString.append(URLEncoder.encode("https://api.twitter.com/oauth/authorize", "UTF-8")).append("&");
		
		StringBuffer parameterString = new StringBuffer("");
		parameterString.append("oauth_callback=").append(URLEncoder.encode(redirectUri, "UTF-8")).append("&");
		parameterString.append("oauth_consumer_key=").append(clientId).append("&");
		parameterString.append("oauth_nonce=").append(nonce).append("&");
		parameterString.append("oauth_timestamp=").append(timestamp);
		
		String finalString = signatureString.toString() + parameterString.toString();
		
		//String signature = 
	
		String redirectUrl = "https://github.com/login/oauth/authorize?" +
				"oauth_consumer_key=" + clientId +
				"&oauth_callback=" + redirectUri +
				"&oauth_nonce=" + nonce +
				"&oauth_timestamp=" + timestamp + 
				"&scope=" + URLEncoder.encode(scope, "UTF-8");
	
		System.out.println("Sending redirect.. " + redirectUrl);

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
	 * @param request
	 * @return true if the information provided to log in is valid otherwise error.
	 */
	@POST
	@Produces("application/json")
	@Path("login")
	public Response loginNative(@Context HttpServletRequest request, @Context HttpServletResponse response) {
		Hashtable<String, String> ret = new Hashtable<String, String>();
		try{
			String username = request.getParameter("username");
			String password = request.getParameter("password");
			String redirect = request.getParameter("redirect");
			
			boolean emptyCredentials = (username == null || password == null || username.isEmpty() || password.isEmpty());
			boolean canLogin = !emptyCredentials && NativeUserSecurityUtils.logIn(username, password);
			if(canLogin){
				ret.put("success", "true");
				ret.put("username", username);
				String name = NativeUserSecurityUtils.getNameUser(username);
				ret.put("name", name);
				String id = NativeUserSecurityUtils.getUserId(username);
				AccessToken authToken = new AccessToken();
				authToken.setProvider(AuthProvider.NATIVE);
				authToken.setId(id);
				authToken.setName(username);				
				addAccessToken(authToken, request);
				
				setMainPageRedirect(request, response, null);
			} else {
				ret.put("error", "The user name or password are invalid.");
				return WebUtility.getResponse(ret, 401);
			}
		} catch(Exception e){
			e.printStackTrace();
			ret.put("error", "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(ret, 500);
		}
		
		return null;
	}
	
	/**
	 * Logs user out when authenticated in a native way.
	 */
	@GET
	@Produces("application/json")
	@Path("/logout")
	public Response logoutNative(@Context HttpServletRequest request) throws IOException {
		Hashtable<String, String> ret = new Hashtable<String, String>();
		try{
			if(request.getSession(false) != null){
				HttpSession session =  request.getSession(false);
				User print = (User) session.getAttribute(Constants.SESSION_USER);
				//LOGGER.info("Logging out with: " + print);
				print.dropAccessToken(AuthProvider.NATIVE.name().toUpperCase());
				request.getSession().setAttribute(Constants.SESSION_USER, print);
			} else {
				ret.put("error", "User is not connected.");
				return WebUtility.getResponse(ret, 401);
			}
		// Only disconnect a connected user.
		} catch (Exception ex) {
			ex.printStackTrace();
			ret.put("error", "Unexpected error.");
			return WebUtility.getResponse(ret, 500);
		}
		ret.put("success", "true");
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Create an user according to the information provided (user name, password, email)
	 * @param request
	 * @return true if the user is created otherwise error.
	 */
	@POST
	@Produces("application/json")
	@Path("createUser")
	public Response createUser(@Context HttpServletRequest request) {
		Hashtable<String, String> ret = new Hashtable<String, String>();
		try{
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
			if(userCreated){
				ret.put("success", "true");
				ret.put("username", username);
				return WebUtility.getResponse(ret, 200);
			} else {
				ret.put("error", "The user name or email aready exists.");
				return WebUtility.getResponse(ret, 400);
			}
		} catch (IllegalArgumentException e){
			e.printStackTrace();
			ret.put("error", e.getMessage());
			return WebUtility.getResponse(ret, 500);
		} catch (Exception e){
			e.printStackTrace();
			ret.put("error", "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(ret, 500);
		}
	}
	
	//////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////
	//////////////////////////////////////////////////////////////////////

	/**
	 * Setting / Getting Information from the social properties
	 * That is needed by the FE
	 */
	
	@GET
	@Produces("application/json")
	@Path("/loginsAllowed/")
	public Response loginsAllowed(@Context HttpServletRequest request) throws IOException {	
		return WebUtility.getResponse(UserResource.loginsAllowed, 200);	
	}
	
	@GET
	@Produces("application/json")
	@Path("/loginProperties/")
	public Response loginProperties(@Context HttpServletRequest request) throws IOException {
		if(AbstractSecurityUtils.securityEnabled()) {
			User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
			if(user == null) {
				return WebUtility.getResponse("No user defined to access properties. Please login as an admin", 400);	
			}
			if(!SecurityAdminUtils.userIsAdmin(user)){
				return WebUtility.getResponse("User is not an admin and does not have access. Please login as an admin", 400);	
			}
		}
		
		// you have access - lets send all the info
		
		Map<String, Map<String, String>> loginApps = new TreeMap<String, Map<String, String>>();
		
		Set<String> propSet = socialData.stringPropertyNames();
		for(String prop : propSet) {
			if(!prop.contains("_")) {
				// bad format
				// ignore
				continue;
			}
			String[] split =  prop.toString().split("_", 2);
			String type = split[0];
			String type_key = split[1];
			
			if(!loginApps.containsKey(type)) {
				loginApps.put(type, new TreeMap<String, String>());
			}
			
			loginApps.get(type).put(type_key, socialData.getProperty(prop).toString());
		}
		
		return WebUtility.getResponse(loginApps, 200);	
	}
	
	@POST
	@Produces("application/json")
	@Path("/modifyLoginProperties/{provider}")
	public synchronized Response modifyLoginProperties(@PathParam("provider") String provider, MultivaluedMap<String, String> form, @Context HttpServletRequest request) throws IOException {	
		if(AbstractSecurityUtils.securityEnabled()) {
			User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
			if(user == null) {
				return WebUtility.getResponse("No user defined to access properties. Please login as an admin", 400);	
			}
			if(!SecurityAdminUtils.userIsAdmin(user)){
				return WebUtility.getResponse("User is not an admin and does not have access. Please login as an admin", 400);	
			}
		}
		
		Gson gson = new Gson();
		String modStr = form.getFirst("modifications");
		Map<String, String> mods = gson.fromJson(modStr, new TypeToken<Map<String, String>>() {}.getType());
		
		PropertiesConfiguration config = null;
		try {
			config = new PropertiesConfiguration(DIHelper.getInstance().getProperty("SOCIAL"));
		} catch (ConfigurationException e1) {
			e1.printStackTrace();
			Hashtable<String, String> errorRet = new Hashtable<String, String>();
			errorRet.put("error", "An unexpected error happened trying to access the properties. Please try again or reach out to server admin.");
			return WebUtility.getResponse(errorRet, 500);
		}
		
		for(String mod : mods.keySet()) {
			config.setProperty(provider + "_" + mod, mods.get(mod));
		}

		try {
			config.save();
			loadSocialProperties();
		} catch (ConfigurationException e1) {
			e1.printStackTrace();
			Hashtable<String, String> errorRet = new Hashtable<String, String>();
			errorRet.put("error", "An unexpected error happened when saving the new login properties. Please try again or reach out to server admin.");
			return WebUtility.getResponse(errorRet, 500);
		}
		
		return WebUtility.getResponse(true, 200);
	}
	
	/**
	 * Redirect the login back to the main app page
	 * @param response
	 */
	private void setMainPageRedirect(@Context HttpServletRequest request, @Context HttpServletResponse response, String redirect) {
		response.setStatus(302);
		try {
			Cookie cookie = new Cookie("JSESSIONID", request.getSession().getId());
			response.addCookie(cookie);
			if(redirect == null) {
				response.sendRedirect(socialData.getProperty("redirect"));
			} else {
				response.sendRedirect(redirect);
			}
		} catch (IOException e) {
			e.printStackTrace();
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
	@Path("/cookie")
	public StreamingOutput manCookie(@Context HttpServletRequest request, @Context HttpServletResponse response, @QueryParam("i") String insightId, @QueryParam("s") String secret) {
	
		//https://nuwanbando.com/2010/05/07/sharing-https-http-sessions-in-tomcat/

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
		User user = (User)session.getAttribute(Constants.SESSION_USER);

		// need to set this random in some place in user
		String random = Utility.getRandomString(10);

		// add the random to the insight id
		
		// Create a hash of the insight id
		
		// send the hash with the redirect as a parameter
		
		// 
		
		String sessionId = session.getId();

        Cookie k = new Cookie("JSESSIONID", sessionId);
	    // cool blink show if you enable the lower one
	    //k.setPath(request.getContextPath());
	    //k.setPath("/appui");
	    //response.addCookie(k);
		
	    k.setPath(request.getContextPath());
	    response.addCookie(k);

	    /*
		Cookie[] cookies = request.getCookies();
		boolean done = false;
	    String sessionId = "";
	    if (cookies != null) {
	        for (Cookie c : cookies) {
	            if (c.getName().equals("JSESSIONID")) {
	                sessionId = c.getValue();
	                System.out.println("Session id " + sessionId);

	                Cookie k = new Cookie("JSESSIONID", sessionId);
	        	    // cool blink show if you enable the lower one
	        	    //k.setPath(request.getContextPath());
	        	    //k.setPath("/appui");
	        	    //response.addCookie(k);
	        		
	        	    k.setPath(request.getContextPath());
	        	    response.addCookie(k);
	        	    break;
	            }
	        }
	    }
	*/

	    System.out.println("Session id set to " + sessionId);
	    
	   /* Cookie p = new Cookie("USER", "prabhuk");
	    p.setPath(request.getContextPath());
	    response.addCookie(p);
	   */ 
	   
	    InsightToken token = new InsightToken();
	    
	    Hashtable outputHash = new Hashtable();
	    
	    try {
			//response.sendRedirect("http://localhost:9090/Monolith/api/engine/all");
		    MessageDigest md = MessageDigest.getInstance("MD5");
		    // create the insight token and add to the user
		    // the user has secret and salt
		    token.setSecret(secret);
		    user.addInsight(insightId, token);
		    
		    String finalData = token.getSalt()+token.getSecret();
		    
		    byte [] digest = md.digest(finalData.getBytes()) ;//.toString().getBytes();

			StringBuffer sb = new StringBuffer();
	        for (int i = 0; i < digest.length; i++) {
	          sb.append(Integer.toString((digest[i] & 0xff) + 0x100, 16).substring(1));
	        }
	    	//String redir = "http://localhost:9090/Monolith/api/engine/all?JSESSIONID=" + sessionId;
	        String redir = "?JSESSIONID=" + sessionId+"&hash=" + sb+"&i="+ insightId;
	        
	        // add the route if this is server deployment
	        Cookie[] curCookies = request.getCookies();
	        if(curCookies != null) {
	        	for(Cookie c : curCookies) {
	        		if(c.getName().equals("route")) {
	        			redir += "&route=" + c.getValue();
	        		}
	        	}
	        }
	        
	    	System.out.println("Redirect URL " + redir);
	    	outputHash.put("PARAM", redir);

	    	// also tell the system that this session is not fully validated so if someone comes without secret on this session
	    	// dont allow
	    	user.addShare(sessionId);
			//response.sendRedirect(redir);
		} catch (NoSuchAlgorithmException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	    
	    return WebUtility.getSO(outputHash);
	}
	
	@GET
	@Produces("application/json")
	@Path("/whoami")
	public Response show(@Context HttpServletRequest request, @Context HttpServletResponse response) {
		Principal principal = request.getUserPrincipal();
		Map<String, Object> output = new HashMap<String, Object>();
		output.put("name", principal.getName());
		if (principal instanceof WindowsPrincipal) {
			WindowsPrincipal windowsPrincipal = (WindowsPrincipal) principal;
			List<Map<String, Object>> gropus = new Vector<Map<String, Object>>();
			for(waffle.windows.auth.WindowsAccount account : windowsPrincipal.getGroups().values()) {
				Map<String, Object> m = new HashMap<String, Object>();
				m.put("name", account.getName());
				m.put("domain", account.getDomain());
				m.put("fqn", account.getFqn());
				m.put("sid", account.getSidString());
				gropus.add(m);
			}
			output.put("groups", gropus);
		}
		return WebUtility.getResponse(output, 200);
	}
}
