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
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

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
import javax.ws.rs.core.Response;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.kohsuke.github.GHMyself;
import org.kohsuke.github.GitHub;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import jodd.util.URLDecoder;
import prerna.auth.AccessToken;
import prerna.auth.AppTokens;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.NativeUserSecurityUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.io.connector.IConnectorIOp;
import prerna.io.connector.google.GoogleEntityResolver;
import prerna.io.connector.google.GoogleFileRetriever;
import prerna.io.connector.google.GoogleLatLongGetter;
import prerna.io.connector.google.GoogleListFiles;
import prerna.io.connector.google.GoogleProfile;
import prerna.io.connector.ms.MSProfile;
import prerna.io.connector.twitter.TwitterSearcher;
import prerna.om.NLPDocumentInput;
import prerna.security.AbstractHttpHelper;
import prerna.util.BeanFiller;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.web.services.util.WebUtility;

@Path("/auth")
public class UserResource {
	
	private static Properties socialData = null;

//	private static AccessToken twitToken = null;
//	private static AccessToken googAppToken = null; 

//	private static ArrayList<String> userEmails = new ArrayList<String>();
//	static {
//		String whitelistPath = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER) + "/db/" + Constants.SECURITY_DB + "/" + Constants.AUTH_WHITELIST_FILE;
//		try {
//			File whitelistFile = new File(whitelistPath);
//			if(whitelistFile.exists()) {
//				Scanner whitelist = new Scanner(new FileReader(whitelistFile));
//				while(whitelist.hasNext()) {
//					userEmails.add(whitelist.nextLine().trim());
//				}
//				whitelist.close();
//			}
//		} catch (FileNotFoundException e) {
//			e.printStackTrace();
//		}
//		loadConnectors();
//	}
//	
//	private boolean checkWhitelistForEmail(String email) {
//		if(userEmails.isEmpty() || userEmails.contains(email)) {
//			return true;
//		} else {
//			return false;
//		}
//	}
	
	static {
		FileInputStream fis = null;
		File f = new File(DIHelper.getInstance().getProperty("SOCIAL"));
		try {
			if(socialData == null && f.exists()) {
				socialData = new Properties();
				fis = new FileInputStream(f);
				socialData.load(fis);

//				loginTwitterApp();
//				loginGoogleApp();
				// also make the twit token and such
//				AppTokens.getInstance().setAccessToken(twitToken);
//				AppTokens.getInstance().setAccessToken(googAppToken);
				AppTokens.setSocial(socialData);
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
	
//	private static void loginTwitterApp() {
//		// getting the bearer token on twitter for app authentication is a lot simpler
//		// need to just combine the id and secret
//		// base 64 and send as authorization
//		
//		InputStream is = null;
//		InputStreamReader isr = null;
//		BufferedReader rd = null;
//		if(twitToken == null) {
//			try {
//				String prefix = "twitter_";
//				String clientId = "I09t8iaHy4UogHfDVFppZiUpo";
//				String clientSecret = "MGKH5Pm6ChWDmrLK7IaJsLhSn57ckmHiMghtim8qZ46wnXrxJY";
//				if(socialData.containsKey(prefix+"client_id")) {
//					clientId = socialData.getProperty(prefix+"client_id");
//				}
//				if(socialData.containsKey(prefix+"secret_key")) {
//					clientSecret = socialData.getProperty(prefix+"secret_key");
//				}
//				
//				// make a joint string
//				String jointString = clientId + ":" + clientSecret;
//
//				// encde this base 64
//				String encodedJointString = new String(Base64.getEncoder().encode(jointString.getBytes()));
//				CloseableHttpClient httpclient = HttpClients.createDefault();
//				HttpPost httppost = new HttpPost("https://api.twitter.com/oauth2/token");
//				httppost.addHeader("Authorization", "Basic " + encodedJointString);
//
//				List<NameValuePair> paramList = new ArrayList<NameValuePair>();
//				paramList.add(new BasicNameValuePair("grant_type", "client_credentials"));
//				httppost.setEntity(new UrlEncodedFormEntity(paramList));
//
//				CloseableHttpResponse authResp = httpclient.execute(httppost);
//
//				System.out.println("Response Code " + authResp.getStatusLine().getStatusCode());
//
//				is = authResp.getEntity().getContent();
//				isr = new InputStreamReader(is);
//				rd = new BufferedReader(isr);
//				StringBuffer result = new StringBuffer();
//				String line = "";
//				while ((line = rd.readLine()) != null) {
//					result.append(line);
//				}
//
//				twitToken = AbstractHttpHelper.getJAccessToken(result.toString());
//				twitToken.setProvider(AuthProvider.TWITTER);
//			} catch(Exception ex) {
//				ex.printStackTrace();
//			} finally {
//				if(is != null) {
//					try {
//						is.close();
//					} catch(IOException e) {
//						// ignore
//					}
//				}
//				if(isr != null) {
//					try {
//						isr.close();
//					} catch(IOException e) {
//						// ignore
//					}
//				}
//				if(rd != null) {
//					try {
//						rd.close();
//					} catch(IOException e) {
//						// ignore
//					}
//				}
//			}
//			System.out.println("Access Token is.. " + twitToken.getAccess_token());
//		}
//	}
//	
//	private static void loginGoogleApp() {
//		// nothing big here
//		// set the name on accesstoken
//		if(googAppToken == null) {
//			googAppToken = new AccessToken();
//			googAppToken.setAccess_token(socialData.getProperty("google_maps_api"));
//			googAppToken.setProvider(AuthProvider.GOOGLE_MAP);
//		}
//	}

	@GET
	@Path("logins")
	public Response getAllLogins(@Context HttpServletRequest request) {
		Map<String, String> retMap = new HashMap<String, String>();
		User semossUser = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		if(semossUser == null) {
			return WebUtility.getResponse(retMap, 200);
		}
		List<AuthProvider> logins = semossUser.getLogins();
		for(AuthProvider p : logins) {
			String name = semossUser.getAccessToken(p).getName();
			if(name == null) {
				// need to send something
				name = "";
			}
			retMap.put(p.toString().toUpperCase(), name);
		}
		return WebUtility.getResponse(retMap, 200);
	}
	
	@GET
	@Produces("application/json")
	@Path("/logout/{provider}")
	public Response logout(@PathParam("provider") String provider, @Context HttpServletRequest request, @Context HttpServletResponse repsonse) throws IOException {
		boolean removed = false;
		if(provider.equalsIgnoreCase("ALL")) {
			// remove the user from session call it a day
			request.getSession().removeAttribute(Constants.SESSION_USER);
			removed = true;
		} else {
			User thisUser = (User) request.getSession().getAttribute(Constants.SESSION_USER);
			removed = thisUser.dropAccessToken(provider.toUpperCase());
			if(thisUser.getLogins().isEmpty()) {
				request.getSession().removeAttribute(Constants.SESSION_USER);
				
				if(AbstractSecurityUtils.securityEnabled()) {
					// well, you have logged out and we always require a login
					// so i will redirect you
					repsonse.setStatus(302);
					String redirectUrl = request.getHeader("referer");
					redirectUrl = redirectUrl + "#!/login";
					((HttpServletResponse) repsonse).setHeader("redirect", redirectUrl);
					((HttpServletResponse) repsonse).sendError(302, "Need to redirect to " + redirectUrl);
					return null;
				}
			} else {
				request.getSession().setAttribute(Constants.SESSION_USER, thisUser);
			}
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
		Object user = request.getSession().getAttribute(Constants.SESSION_USER);
		if(user != null) {
			semossUser = (User)user;
		} else {
			semossUser = new User();
//			if(twitToken != null) {
//				semossUser.setGlobalAccessToken(twitToken);
//			}
//			if(googAppToken != null) {
//				semossUser.setGlobalAccessToken(googAppToken);
//			}
		}
		semossUser.setAccessToken(token);
		request.getSession().setAttribute(Constants.SESSION_USER, semossUser);
		// add new users into the database
		SecurityUpdateUtils.addOAuthUser(token);
	}
	
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
				accessToken.setProvider(AuthProvider.SF);
				addAccessToken(accessToken, request);

				System.out.println("Access Token is.. " + accessToken.getAccess_token());
				ret.put("success", true);
				return WebUtility.getResponse(ret, 200);
			} else {
				ret.put("success", true);
				ret.put("Already_Authenticated", true);
				return WebUtility.getResponse(ret, 200);
			}
		}
		else if(userObj == null || userObj.getAccessToken(AuthProvider.SF) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getSFRedirect(request));
		} 
		else if(userObj != null && userObj.getAccessToken(AuthProvider.SF) != null) {
			ret.put("success", true);
			return WebUtility.getResponse(ret, 200);
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
					response.sendRedirect(getGoogleRedirect(request));
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
				ret.put("success", true);
				return WebUtility.getResponse(ret, 200);
			} else {
				ret.put("success", true);
				ret.put("Already_Authenticated", true);
				return WebUtility.getResponse(ret, 200);
			}
		}
		else if(userObj == null || userObj.getAccessToken(AuthProvider.GITHUB) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getGitRedirect(request));
		} 
		// else if user object is there and git is there
		else if(userObj != null && userObj.getAccessToken(AuthProvider.GITHUB) != null)
		{
			ret.put("success", true);
			return WebUtility.getResponse(ret, 200);
		}
		return null;
	}
	
	private String getGitRedirect(HttpServletRequest request) throws UnsupportedEncodingException {
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
					response.sendRedirect(getGoogleRedirect(request));
					return null;
				}
				
				accessToken.setProvider(AuthProvider.MS);
				MSProfile.fillAccessToken(accessToken, null);
				addAccessToken(accessToken, request);

				System.out.println("Access Token is.. " + accessToken.getAccess_token());

				ret.put("success", true);
				return WebUtility.getResponse(ret, 200);
			} else {
				ret.put("success", true);
				ret.put("Already_Authenticated", true);
				return WebUtility.getResponse(ret, 200);
			}
		} else if(userObj == null || userObj.getAccessToken(AuthProvider.MS) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getMSRedirect(request));
		}
		// else if user object is there and ms is there
		else if(userObj != null && userObj.getAccessToken(AuthProvider.MS) != null) {
			ret.put("success", true);
			return WebUtility.getResponse(ret, 200);
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
				accessToken.setProvider(AuthProvider.DROPBOX);
				addAccessToken(accessToken, request);

				System.out.println("Access Token is.. " + accessToken.getAccess_token());			
				ret.put("success", true);
				return WebUtility.getResponse(ret, 200);
			} else {
				ret.put("success", true);
				ret.put("Already_Authenticated", true);
				return WebUtility.getResponse(ret, 200);
			}
		}
		else if(userObj == null || userObj.getAccessToken(AuthProvider.DROPBOX) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getDBRedirect(request));
		}
		// else if user object is there and dropbox is there
		else if(userObj != null && userObj.getAccessToken(AuthProvider.DROPBOX) != null) {
			ret.put("success", true);
			return WebUtility.getResponse(ret, 200);
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
				
				AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, true, true);
				if(accessToken == null) {
					// not authenticated
					response.setStatus(302);
					response.sendRedirect(getGoogleRedirect(request));
					return null;
				}
				//https://developers.google.com/api-client-library/java/google-api-java-client/oauth2
				// Shows how to make a google credential from an access token
				System.out.println("Access Token is.. " + accessToken.getAccess_token());
				accessToken.setProvider(AuthProvider.GOOGLE);

				// fill the access token with the other properties so we can properly create the user
				GoogleProfile.fillAccessToken(accessToken, null);
				addAccessToken(accessToken, request);
				
				// this is just for testing...
				// but i will get yelled at if i remove it so here it is...
				// TODO: adding this todo to easily locate it
//				performGoogleOps(request, ret);
			} else {
				ret.put("success", true);
				ret.put("Already_Authenticated", true);
				return WebUtility.getResponse(ret, 200);
			}
			
			ret.put("success", true);
			return WebUtility.getResponse(ret, 200);
		}
		// else if the user object is there, but there is no google
		else if(userObj == null || userObj.getAccessToken(AuthProvider.GOOGLE) == null) {
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getGoogleRedirect(request));
		}
		// else if user object is there and google is there
		else if(userObj != null && userObj.getAccessToken(AuthProvider.GOOGLE) != null) {
			ret.put("success", true);
			return WebUtility.getResponse(ret, 200);
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
				accessToken.setProvider(AuthProvider.PRODUCT_HUNT);
				addAccessToken(accessToken, request);

				System.out.println("Access Token is.. " + accessToken.getAccess_token());			
				ret.put("success", true);
				return WebUtility.getResponse(ret, 200);
			} else {
				ret.put("success", true);
				ret.put("Already_Authenticated", true);
				return WebUtility.getResponse(ret, 200);
			}
		}
		else if(userObj == null || userObj.getAccessToken(AuthProvider.PRODUCT_HUNT) == null)
		{
			response.setStatus(302);
			response.sendRedirect(getProducthuntRedirect(request));
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
				accessToken.setProvider(AuthProvider.IN);
				addAccessToken(accessToken, request);

				System.out.println("Access Token is.. " + accessToken.getAccess_token());

				ret.put("success", true);
				return WebUtility.getResponse(ret, 200);
			} else {
				ret.put("success", true);
				ret.put("Already_Authenticated", true);
				return WebUtility.getResponse(ret, 200);
			}
		}
		else if(userObj == null || userObj.getAccessToken(AuthProvider.IN) == null) {
			response.setStatus(302);
			response.sendRedirect(getInRedirect(request));
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
		
		if(queryString != null && queryString.contains("code="))
		{
			if(userObj == null || ((User)userObj).getAccessToken(AuthProvider.GITHUB) == null)
			{

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
			
			ret.put("success", true);
	//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
			return WebUtility.getResponse(ret, 200);
			}
			else
			{
				ret.put("success", true);
				ret.put("Already_Authenticated", true);
				//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
				return WebUtility.getResponse(ret, 200);

			}
		}
		else if(userObj == null || ((User)userObj).getAccessToken(AuthProvider.GITHUB) == null)
		{
			// not authenticated

			response.setStatus(302);
			response.sendRedirect(getGitRedirect(request));
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
	
	/*
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
	public Response authentication(@Context HttpServletRequest request) {
		Hashtable<String, String> ret = new Hashtable<String, String>();
		try{
			String username = request.getParameter("username");
			String password = request.getParameter("password");
			boolean emptyCredentials = username == null || password == null || username.isEmpty() || password.isEmpty();
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
				//User print = (User) session.getAttribute(Constants.SESSION_USER);
				//LOGGER.info("Logging in with: " + print);
				
				return WebUtility.getResponse(ret, 200);
			} else {
				ret.put("error", "The user name or password are invalid.");
				return WebUtility.getResponse(ret, 401);
			}
		} catch(Exception e){
			e.printStackTrace();
			ret.put("error", "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(ret, 500);
		}
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
			newUser.setEmail(email);
			newUser.setName(name);
			newUser.setUsername(username);
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

	/*
	 * Setting / Getting Information from the social properties
	 * That is needed by the FE
	 */
	
	@GET
	@Produces("application/json")
	@Path("/loginsAllowed/")
	public Response loginsAllowed(@Context HttpServletRequest request) throws IOException {	
		boolean nativeLogin = Boolean.parseBoolean(socialData.getProperty("native_login"));
		boolean githubLogin = Boolean.parseBoolean(socialData.getProperty("github_login"));
		boolean googleLogin = Boolean.parseBoolean(socialData.getProperty("google_login"));
		boolean onedriveLogin = Boolean.parseBoolean(socialData.getProperty("ms_login"));
		boolean dropboxLogin = Boolean.parseBoolean(socialData.getProperty("dropbox_login"));
		boolean cacLogin = Boolean.parseBoolean(socialData.getProperty("cac_login"));
		boolean registration = Boolean.parseBoolean(socialData.getProperty("native_registration"));
		Map<String, Boolean> logins = new HashMap<>();
		logins.put("native", nativeLogin);
		logins.put("google", googleLogin);
		logins.put("github", githubLogin);
		logins.put("ms", onedriveLogin);
		logins.put("dropbox", dropboxLogin);
		logins.put("cac", cacLogin);
		logins.put("registration", registration);
		return WebUtility.getResponse(logins, 200);	
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
			if(SecurityQueryUtils.userIsAdmin(user)){
				return WebUtility.getResponse("User is not an admin and does not have access. Please login as an admin", 400);	
			}
		}
		
		// you have access - lets send all the info
		
		Map<String, Map<String, String>> loginApps = new TreeMap<String, Map<String, String>>();
		
		Set<String> propSet = socialData.stringPropertyNames();
		for(String prop : propSet) {
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
			if(SecurityQueryUtils.userIsAdmin(user)){
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
		} catch (ConfigurationException e1) {
			e1.printStackTrace();
			Hashtable<String, String> errorRet = new Hashtable<String, String>();
			errorRet.put("error", "An unexpected error happened when saving the new login properties. Please try again or reach out to server admin.");
			return WebUtility.getResponse(errorRet, 500);
		}
		
		return WebUtility.getResponse(true, 200);
	}
}
