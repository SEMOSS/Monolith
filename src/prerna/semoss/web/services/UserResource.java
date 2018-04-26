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
package prerna.semoss.web.services;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.SignatureException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Formatter;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import jodd.util.URLDecoder;

import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import prerna.auth.AccessToken;
import prerna.auth.AppTokens;
import prerna.auth.AuthProvider;
import prerna.auth.CACReader;
import prerna.auth.User;
import prerna.auth.User2;
import prerna.auth.UserPermissionsMasterDB;
import prerna.io.connector.IConnectorIOp;
import prerna.io.connector.google.GoogleEntityResolver;
import prerna.io.connector.google.GoogleFileRetriever;
import prerna.io.connector.google.GoogleLatLongGetter;
import prerna.io.connector.google.GoogleListFiles;
import prerna.io.connector.google.GoogleProfile;
import prerna.io.connector.twitter.TwitterSearcher;
import prerna.om.NLPDocumentInput;
import prerna.om.Viewpoint;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.PixelOperationType;
import prerna.sablecc2.om.execptions.SemossPixelException;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.security.AbstractHttpHelper;
import prerna.util.BeanFiller;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.web.services.util.WebUtility;
import waffle.servlet.WindowsPrincipal;

import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.oauth2.Oauth2;
import com.google.api.services.oauth2.model.Userinfoplus;
import com.google.gson.Gson;
import com.google.gson.internal.StringMap;
import com.restfb.DefaultFacebookClient;
import com.restfb.FacebookClient;
import com.restfb.Parameter;
import com.sun.jna.platform.win32.Secur32;
import com.sun.jna.platform.win32.Secur32Util;

@Path("/auth")
public class UserResource
{
	String output = "";
	private final HttpTransport TRANSPORT = new NetHttpTransport();
	private final JacksonFactory JSON_FACTORY = new JacksonFactory();
	private final Gson GSON = new Gson();
	private UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
	private static AccessToken twitToken = null;
	private static AccessToken googAppToken = null; 
	
	private static Properties socialData = null;
	
	private final String GOOGLE_CLIENT_SECRET = "VNIxZUbsMj-wV5CNDwF5gXcV";	
	
	private final String FACEBOOK_APP_SECRET = "d19e07178689958a9f773c5f6f1c5d45";
	private final String FACEBOOK_ACCESS_TOKEN_NEW = "https://graph.facebook.com/oauth/access_token?code=%s&client_id=%s&redirect_uri=%s&client_secret=%s";
	
	private static ArrayList<String> userEmails = new ArrayList<String>();
	
	static {
		String whitelistPath = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER) + "/db/" + Constants.SECURITY_DB + "/" + Constants.AUTH_WHITELIST_FILE;
		try {
			File whitelistFile = new File(whitelistPath);
			if(whitelistFile.exists()) {
				Scanner whitelist = new Scanner(new FileReader(whitelistFile));
				while(whitelist.hasNext()) {
					userEmails.add(whitelist.nextLine().trim());
				}
				whitelist.close();
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		loadConnectors();
	}
	
	public static void loadConnectors()
	{
		try {
			if(socialData == null)
			{
				socialData = new Properties();
				socialData.load(new FileInputStream(new File(DIHelper.getInstance().getProperty("SOCIAL"))));
				
				loginTwitterApp();
				loginGoogleApp();
				// also make the twit token and such
				AppTokens.getInstance().setAccessToken(twitToken);
				AppTokens.getInstance().setAccessToken(googAppToken);	
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Logs user in through Google+.
	 */
	@POST
	@Produces("application/json")
	@Path("/login/google")
	public Response loginGoogle(@Context HttpServletRequest request) throws IOException {
		Hashtable<String, String> ret = new Hashtable<String, String>();

		// Grab the request's payload
		StringBuilder buffer = new StringBuilder();
		BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream()));
		String line;
		while ((line = reader.readLine()) != null) {
			buffer.append(line);
		}

		HashMap<String, String> h = GSON.fromJson(buffer.toString(), HashMap.class);

		String clientId = h.get("clientId");
		request.getSession().setAttribute("clientId", clientId);
		String code = h.get("code");
		String redirectUri = h.get("redirectUri");

		// Only connect a user that is not already connected.
		String tokenData = (String) request.getSession().getAttribute("token");
		if (tokenData != null) {
			ret.put("error", "User is already connected.");
//			return Response.status(200).entity(WebUtility.getSO(ret)).build();
			return WebUtility.getResponse(ret, 200);
		}
		// Ensure that this is no request forgery going on, and that the user
		// sending us this connect request is the user that was supposed to.
		if (request.getParameter("state") != null && request.getSession().getAttribute("state") != null && 
				!request.getParameter("state").equals(request.getSession().getAttribute("state"))) {
			ret.put("error", "Invalid state parameter.");
//			return Response.status(400).entity(WebUtility.getSO(ret)).build();
			return WebUtility.getResponse(ret, 400);
		}

		GoogleTokenResponse tokenResponse = new GoogleTokenResponse();
		try {
			// Upgrade the authorization code into an access and refresh token.
			tokenResponse =	new GoogleAuthorizationCodeTokenRequest(TRANSPORT, JSON_FACTORY, clientId, GOOGLE_CLIENT_SECRET, code, redirectUri).execute();

			// Grab the Google user info
			GoogleCredential credential = new GoogleCredential.Builder()
			.setTransport(this.TRANSPORT)
			.setJsonFactory(this.JSON_FACTORY)
			.setClientSecrets(clientId, GOOGLE_CLIENT_SECRET).build()
			.setFromTokenResponse(tokenResponse);
			Oauth2 oauth2 = new Oauth2.Builder(TRANSPORT, JSON_FACTORY, credential).setApplicationName("SEMOSS").build();
			Userinfoplus userinfo = oauth2.userinfo().get().execute();
			
			// Parse out information from payload as needed and add it to User object and session
			GoogleIdToken idToken = tokenResponse.parseIdToken();
			String gplusId = idToken.getPayload().getSubject();
			String email = idToken.getPayload().getEmail();
			String name = userinfo.getGivenName() + " " + userinfo.getFamilyName();
			String picture = userinfo.getPicture();
			
			if(!checkWhitelistForEmail(email)) {
//				return Response.status(401).entity(WebUtility.getSO("Not permitted to log in. Please contact administrator.")).build();
				return WebUtility.getResponse("Not permitted to log in. Please contact administrator", 401);
			}
			
			ret.put("token", tokenResponse.getAccessToken());
			ret.put("id", gplusId);
			ret.put("name", name);
			ret.put("email", email);
			User newUser;
			if(picture != null && !picture.isEmpty()) {
				ret.put("picture", picture);
				newUser = new User(gplusId, name, User.LOGIN_TYPES.GOOGLE, email, picture);
			} else {
				newUser = new User(gplusId, name, User.LOGIN_TYPES.GOOGLE, email);
			}
			// also set the google id token
			newUser.setAdditionalData("googleCredential", credential);
			addUser(newUser);
			
			if(permissions.isUserAdmin(gplusId)) {
				newUser.setAdmin(true);
			}
			
			request.getSession().setAttribute(Constants.SESSION_USER, newUser);
			
			// Store the token in the session for later use.
			request.getSession().setAttribute("token", tokenResponse.toString());
		} catch (TokenResponseException e) {
			ret.put("success", "false");
			ret.put("error", "Failed to upgrade the auth code: " + e.getMessage());
//			return Response.status(400).entity(WebUtility.getSO(ret)).build();
			return WebUtility.getResponse(ret, 400);
		} catch (IOException e) {
			ret.put("success", "false");
			ret.put("error", "Failed to read the token data from google: " + e.getMessage());
//			return Response.status(404).entity(WebUtility.getSO(ret)).build();
			return WebUtility.getResponse(ret, 404);
		}
		
		ret.put("success", "true");
//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Logs user in through Facebook.
	 */
	@POST
	@Produces("application/json")
	@Path("/login/facebook")
	public Response loginFacebook(@Context HttpServletRequest request) throws IOException {
		Hashtable<String, String> ret = new Hashtable<String, String>();
		
		StringBuilder buffer = new StringBuilder();
		BufferedReader reader = null;
		try {
			reader = new BufferedReader(new InputStreamReader(request.getInputStream()));
			String line;
			while ((line = reader.readLine()) != null) {
				buffer.append(line);
			}
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if(reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

		HashMap<String, String> h = GSON.fromJson(buffer.toString(), HashMap.class);

		String clientId = h.get("clientId");
		request.getSession().setAttribute("clientId", clientId);
		String code = h.get("code");
		String redirectUri = h.get("redirectUri");
		String accessToken = "";
		
		CloseableHttpClient httpclient = HttpClients.createDefault();
		String url = String.format(this.FACEBOOK_ACCESS_TOKEN_NEW, code, clientId, URLEncoder.encode(redirectUri, "UTF-8"), this.FACEBOOK_APP_SECRET);
		HttpGet http = new HttpGet(url);
		CloseableHttpResponse response = httpclient.execute(http);
		
		// Retrieve Facebook OAuth access token
		try {
		    HttpEntity entity = response.getEntity();
		    if (entity != null) {
		    	InputStream is = entity.getContent();
		        if (is != null) {
		            String resp = EntityUtils.toString(entity);
		            
		            if(resp.contains("access_token")) {
		            	accessToken = resp.substring(resp.indexOf("=")+1, resp.indexOf("&expires"));
		            	request.getSession().setAttribute("token", accessToken);
		            } else {
			            Gson gson = new Gson();
			    		HashMap<String, StringMap<String>> k = gson.fromJson(resp, HashMap.class);
			    		
			    		if(k.get("error") != null) {
			    			ret.put("success", "false");
			    			ret.put("error", h.get("error").toString());
//			    			return Response.status(400).entity(WebUtility.getSO(ret)).build();
			    			return WebUtility.getResponse(ret, 400);
			    		}
		            }
		        }
		    }
		} finally {
		    response.close();
		    httpclient.close();
		}
		
		// Retrieve Facebook account User object using OAuth access token
		FacebookClient fb = new DefaultFacebookClient(accessToken, this.FACEBOOK_APP_SECRET);
		com.restfb.types.User me = fb.fetchObject("me", com.restfb.types.User.class, Parameter.with("fields", "id, name, email, picture"));
		
		// Parse out information from return data as needed and add it to User object and session
		String id = me.getId();
		String email = me.getEmail();
		String name = me.getName();
		
		if(!checkWhitelistForEmail(email)) {
//			return Response.status(401).entity(WebUtility.getSO("Not permitted to log in. Please contact administrator.")).build();
			return WebUtility.getResponse("Not permitted to log in.  Please contact administrator.", 401);
		}
		
		User newUser;
		if(me.getPicture() != null) {
			String picture = me.getPicture().getUrl();
			ret.put("picture", picture);
			newUser = new User(id, name, User.LOGIN_TYPES.FACEBOOK, email, picture);
		} else {
			newUser = new User(id, name, User.LOGIN_TYPES.FACEBOOK, email);
		}
		
		ret.put("token", accessToken);
		ret.put("id", id);
		ret.put("name", name);
		ret.put("email", email);
		
		addUser(newUser);
		
		if(permissions.isUserAdmin(id)) {
			newUser.setAdmin(true);
		}
		
		request.getSession().setAttribute(Constants.SESSION_USER, newUser);
		
		// Store the token in the session for later use.
		request.getSession().setAttribute("token", accessToken);
		
		ret.put("success", "true");
//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Logs user out when authenticated through Facebook.
	 */
	@GET
	@Produces("application/json")
	@Path("/logout/facebook")
	public Response logoutFacebook(@Context HttpServletRequest request) throws IOException {
		Hashtable<String, String> ret = new Hashtable<String, String>();
		
		// Only disconnect a connected user.
		String tokenData = (String) request.getSession().getAttribute("token");
		if (tokenData == null) {
			ret.put("success", "false");
			ret.put("error", "User is not connected.");
//			return Response.status(400).entity(WebUtility.getSO(ret)).build();
			return WebUtility.getResponse(ret, 400);
		}
		
		request.getSession().invalidate();
		
		ret.put("success", "true");
//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
		return WebUtility.getResponse(ret, 200);
	}
	
	private boolean checkWhitelistForEmail(String email) {
		if(userEmails.isEmpty() || userEmails.contains(email)) {
			return true;
		} else {
			return false;
		}
	}
	
	@POST
	@Produces("application/json")
	@Path("login/cac")
	public StreamingOutput loginCAC(@Context HttpServletRequest request, @QueryParam("pin") String pin) {
		CACReader reader = new CACReader();
    	return WebUtility.getSO(reader.getCACName(pin));
	}
	
	/**
	 * Adds user to Local Master DB upon sign-in
	 * 
	 * @param userId	User ID of user retrieved from Identity Provider
	 * @param EMAIL		Email address of user retrieved from Identity Provider
	 */
	private void addUser(User newUser) {
		permissions.addUser(newUser);
	}
	
	@GET
	@Path("isLoggedIn")
	public Response isUserLoggedIn(@Context HttpServletRequest request) {
		if(request.getSession().getAttribute(Constants.SESSION_USER) != null) {
			User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
			if(!user.getId().equals(Constants.ANONYMOUS_USER_ID)) {
//				return Response.status(200).entity(WebUtility.getSO(true)).build();
				return WebUtility.getResponse(true, 200);
			}
		}
		
//		return Response.status(200).entity(WebUtility.getSO(false)).build();
		return WebUtility.getResponse(false, 200);
	}
	
	@GET
	@Produces("application/json")
	@Path("userInfo")
	public Response getLoggedInUserInfo(@Context HttpServletRequest request) {
		Hashtable<String, String> ret = new Hashtable<String, String>();
		
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		if(user.getPicture() != null) {
			String picture = user.getPicture();
			ret.put("picture", picture);
		}
		
		ret.put("id", user.getId());
		ret.put("name", user.getName());
		ret.put("email", user.getEmail());
		
//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
		return WebUtility.getResponse(ret, 200);
	}
	
	/**
	 * Gets user info for GoogleDrive
	 */
	@GET
	@Produces("application/json")
	@Path("/userinfo/google")
	public Response userinfoGoogle(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException 
	{
		String queryString = request.getQueryString();
		Object userObj = request.getSession().getAttribute("semoss_user");
		Hashtable<String, String> ret = new Hashtable<String, String>();

		String objectName = "prerna.auth.AccessToken"; // it will fill this object and return the data
		String [] beanProps = {"name", "profile"}; // add is done when you have a list
		String jsonPattern = "[name, picture]";

		userObj = request.getSession().getAttribute("semoss_user");
		User2 user = (User2)userObj;
		String accessString=null;
		try{
			if(user==null){
				ret.put("ERROR", "Log into your Google account");
				return WebUtility.getResponse(ret, 200);
				}
			else if (user != null) {
				AccessToken googleToken = user.getAccessToken(AuthProvider.GOOGLE.name());
				accessString=googleToken.getAccess_token();
			}
		}
		catch (Exception e) {
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
		if(accessToken2.getProfile() != null) {
			ret.put("picture", accessToken2.getProfile());
		}
			ret.put("name", accessToken2.getName());
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return WebUtility.getResponse(ret, 200);

	}
	
	/**
	 * Gets user info for OneDrive
	 */
	@GET
	@Produces("application/json")
	@Path("/userinfo/onedrive")
	public Response userinfoOneDrive(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException 
	{
		String queryString = request.getQueryString();
		Object userObj = request.getSession().getAttribute("semoss_user");
		Hashtable<String, String> ret = new Hashtable<String, String>();

		String objectName = "prerna.auth.AccessToken"; // it will fill this object and return the data
		String [] beanProps = {"name"}; // add is done when you have a list
		String jsonPattern = "[displayName]";

		userObj = request.getSession().getAttribute("semoss_user");
		User2 user = (User2)userObj;
		String accessString=null;
		try{
			if(user==null){
				ret.put("ERROR", "Log into your Microsoft account");
				return WebUtility.getResponse(ret, 200);
			}
			else if (user != null) {
				AccessToken msToken = user.getAccessToken(AuthProvider.AZURE_GRAPH.name());
				accessString=msToken.getAccess_token();
			}
		}
		catch (Exception e) {
			ret.put("ERROR", "Log into your Microsoft account");
			return WebUtility.getResponse(ret, 200);
		}
		String url = "https://graph.microsoft.com/v1.0/me/";


		String output = AbstractHttpHelper.makeGetCall(url, accessString, null, true);
		AccessToken accessToken2 = (AccessToken)BeanFiller.fillFromJson(output, jsonPattern, beanProps, new AccessToken());
		try {
			ret.put("name", accessToken2.getName());
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return WebUtility.getResponse(ret, 200);

	}
	
	/**
	 * Gets user info for DropBox
	 */
	@GET
	@Produces("application/json")
	@Path("/userinfo/dropbox")
	public Response userinfoDropbox(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException 
	{
		String queryString = request.getQueryString();
		Object userObj = request.getSession().getAttribute("semoss_user");
		Hashtable<String, String> ret = new Hashtable<String, String>();

		String objectName = "prerna.auth.AccessToken"; // it will fill this object and return the data
		String [] beanProps = {"name", "profile"}; // add is done when you have a list
		String jsonPattern = "[name.display_name, profile_photo_url]";

		userObj = request.getSession().getAttribute("semoss_user");
		User2 user = (User2)userObj;
		String accessString=null;
		try{
			if(user==null){
				ret.put("ERROR", "Log into your DropBox account");
				return WebUtility.getResponse(ret, 200);
			}
			else if (user != null) {
				AccessToken dropToken = user.getAccessToken(AuthProvider.DROPBOX.name());
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
			if(accessToken2.getProfile() != null) {
				ret.put("picture", accessToken2.getProfile());
			}
				ret.put("name", accessToken2.getName());
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			return WebUtility.getResponse(ret, 200);

	}

	@GET
	@Produces("text/plain")
	@Path("/whoami")
	public StreamingOutput show(@Context HttpServletRequest request, @Context HttpServletResponse response) {
		HttpSession session = request.getSession();
		System.err.println(" came into user resource");
		Principal principal = request.getUserPrincipal();
		output = "<html> <body> <pre>";
		output = request.getRemoteUser() + "\n" + request.getUserPrincipal().getName() + "\n";
		output = output + "Session " + request.getSession().getId() + "\n";
		output = output + "Impersonation " + Secur32Util.getUserNameEx(Secur32.EXTENDED_NAME_FORMAT.NameSamCompatible);
		if (principal instanceof WindowsPrincipal) {
			WindowsPrincipal windowsPrincipal = (WindowsPrincipal) principal;
			for(waffle.windows.auth.WindowsAccount account : windowsPrincipal.getGroups().values()) {
				output = output + account.getFqn() + account.getSidString() + "\n";
			}
		}
		output = output + "</pre></body></html>";
		return new StreamingOutput() {
			public void write(OutputStream outputStream) throws IOException, WebApplicationException {
				PrintStream ps = new PrintStream(outputStream);
				ps.println(output);
			}
		};
	}
	
	@GET
	@Produces("text/plain")
	@Path("/getCert")
	public StreamingOutput getCert(@Context HttpServletRequest request, @Context HttpServletResponse response) throws InvalidNameException {
		X509Certificate[] certs = (X509Certificate[]) request.getAttribute("javax.servlet.request.X509Certificate");
		if(certs == null || certs.length == 0) {
			output = "WE GOT NOTHING!!! :(";
		} else {
			for(int i = 0; i < certs.length; i++) {
				X509Certificate cert = certs[i];
				
				String dn = cert.getSubjectX500Principal().getName();
				LdapName ldapDN = new LdapName(dn);
				for(Rdn rdn: ldapDN.getRdns()) {
					if(rdn.getType().equals("CN")) {
						output += "You are = " + rdn.getValue().toString() + "\n";
					}
				}
			}
		}
		return new StreamingOutput() {
			public void write(OutputStream outputStream) throws IOException, WebApplicationException {
				PrintStream ps = new PrintStream(outputStream);
				ps.println(output);
			}
		};
	}
	
	/**
	 * Logs user in through SalesForce
	 */
	@GET
	@Produces("application/json")
	@Path("/login/sf")
	public Response loginSF(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException 
	{
		// redirect if query string not there
		
		Object userObj = request.getSession().getAttribute("semoss_user");
		Hashtable<String, String> ret = new Hashtable<String, String>();
		

		String queryString = request.getQueryString();
		
		if(queryString != null && queryString.contains("code="))
		{
			if(userObj == null || ((User2)userObj).getAccessToken(AuthProvider.GOOGLE.name()) == null)
			{

			String [] outputs = AbstractHttpHelper.getCodes(queryString);
			
			String prefix = "sf_";
			
			String clientId = (String)socialData.getProperty(prefix+"client_id");
			String clientSecret = (String)socialData.getProperty(prefix+"secret_key");
			String redirectUri = (String)socialData.getProperty(prefix+"redirect_uri");
			
			System.out.println(">> " + request.getQueryString());
			
			Hashtable params = new Hashtable();
			params.put("client_id", clientId);
			params.put("grant_type", "authorization_code");
			params.put("redirect_uri", redirectUri);
			params.put("code", URLDecoder.decode(outputs[0]));
			params.put("client_secret", clientSecret);
	
			String url = "https://login.salesforce.com/services/oauth2/token";
			
			AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, true, true);
			
			accessToken.setProvider(AuthProvider.SALESFORCE.name());
			addAccessToken(accessToken, request);
			
			System.out.println("Access Token is.. " + accessToken.getAccess_token());
			
			ret.put("success", "true");
	//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
			return WebUtility.getResponse(ret, 200);
			}
			else
			{
				ret.put("success", "true");
				ret.put("Already_Authenticated", "true");
				//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
				return WebUtility.getResponse(ret, 200);

			}
				
		}
		else if(userObj == null || ((User2)userObj).getAccessToken(AuthProvider.SALESFORCE.name()) == null)
		{
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getSFRedirect(request));
		}
		return null;
	}
	
	private String getSFRedirect(HttpServletRequest request)
			throws UnsupportedEncodingException {

		String prefix = "sf_";
		
		String clientId = (String)socialData.getProperty(prefix+"client_id");
		String clientSecret = (String)socialData.getProperty(prefix+"secret_key");
		String redirectUri = (String)socialData.getProperty(prefix+"redirect_uri");

		
		String redirectUrl = "https://login.salesforce.com/services/oauth2/authorize?" +
		"client_id=" + clientId +
		"&response_type=code" +
		"&redirect_uri=" + redirectUri +
		"&scope=" + URLEncoder.encode("api", "UTF-8");

		System.out.println("Sending redirect.. " + redirectUrl);

		return redirectUrl;
	}


	/**
	 * Logs user in through GIT
	 */
	@GET
	@Produces("application/json")
	@Path("/login/git")
	public Response loginGit(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException 
	{
		// redirect if query string not there
		Object userObj = request.getSession().getAttribute("semoss_user");
		Hashtable<String, String> ret = new Hashtable<String, String>();
		
		//https://developer.github.com/apps/building-oauth-apps/authorization-options-for-oauth-apps/
		String queryString = request.getQueryString();
		
		if(queryString != null && queryString.contains("code="))
		{
			if(userObj == null || ((User2)userObj).getAccessToken(AuthProvider.GIT.name()) == null)
			{

			String [] outputs = AbstractHttpHelper.getCodes(queryString);
			
			String prefix = "git_";
			
			String clientId = (String)socialData.getProperty(prefix+"client_id");
			String clientSecret = (String)socialData.getProperty(prefix+"secret_key");
			String redirectUri = (String)socialData.getProperty(prefix+"redirect_uri");
			
			System.out.println(">> " + request.getQueryString());
			
			Hashtable params = new Hashtable();
			params.put("client_id", clientId);
			params.put("redirect_uri", redirectUri);
			params.put("code", outputs[0]);
			params.put("state", outputs[1]);
			params.put("client_secret", clientSecret);

			String url = "https://github.com/login/oauth/access_token";
				
			AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, false, true);
			accessToken.setProvider(AuthProvider.GIT.name());
			addAccessToken(accessToken, request);
			
			System.out.println("Access Token is.. " + accessToken.getAccess_token());
			
			ret.put("success", "true");
	//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
			return WebUtility.getResponse(ret, 200);
			}
			else
			{
				ret.put("success", "true");
				ret.put("Already_Authenticated", "true");
				//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
				return WebUtility.getResponse(ret, 200);

			}
		}
		else if(userObj == null || ((User2)userObj).getAccessToken(AuthProvider.GIT.name()) == null)
		{
			// not authenticated

			response.setStatus(302);
			response.sendRedirect(getGitRedirect(request));
		}
		return null;
	}
	
	private String getGitRedirect(HttpServletRequest request)
			throws UnsupportedEncodingException {

		String prefix = "git_";
		
		String clientId = (String)socialData.getProperty(prefix+"client_id");
		String clientSecret = (String)socialData.getProperty(prefix+"secret_key");
		String redirectUri = (String)socialData.getProperty(prefix+"redirect_uri");
		String scope = (String)socialData.getProperty(prefix+"scope");

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
	 * Logs user in through SalesForce
	 */
	@GET
	@Produces("application/json")
	@Path("/login/ms")
	public Response loginMS(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException 
	{
		// redirect if query string not there
		
		Object userObj = request.getSession().getAttribute("semoss_user");
		Hashtable<String, String> ret = new Hashtable<String, String>();
		
		
		String queryString = request.getQueryString();
		
		if(queryString != null && queryString.contains("code="))
		{
			if(userObj == null || ((User2)userObj).getAccessToken(AuthProvider.AZURE_GRAPH.name()) == null)
			{
			String [] outputs = AbstractHttpHelper.getCodes(queryString);
			
			String prefix = "ms_";
			
			String clientId = (String)socialData.getProperty(prefix+"client_id");
			String clientSecret = (String)socialData.getProperty(prefix+"secret_key");
			String redirectUri = (String)socialData.getProperty(prefix+"redirect_uri");
			String tenant = (String)socialData.getProperty(prefix+"tenant");
			
			System.out.println(">> " + request.getQueryString());
			
			Hashtable params = new Hashtable();

			params.put("client_id", clientId);
			params.put("scope", "User.Read.All");
			params.put("redirect_uri", redirectUri);
			params.put("code", outputs[0]);
			params.put("grant_type", "authorization_code");
			params.put("client_secret", clientSecret);

			String url = "https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/token";
	
			
			AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, true, true);
			accessToken.setProvider(AuthProvider.AZURE_GRAPH.name());
			addAccessToken(accessToken, request);

			System.out.println("Access Token is.. " + accessToken.getAccess_token());
			
			ret.put("success", "true");
	//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
			return WebUtility.getResponse(ret, 200);
			}
			else
			{
				ret.put("success", "true");
				ret.put("Already_Authenticated", "true");
				//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
				return WebUtility.getResponse(ret, 200);

			}
		}
		else if(userObj == null || ((User2)userObj).getAccessToken(AuthProvider.AZURE_GRAPH.name()) == null)
		{
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getMSRedirect(request));
		}
		return null;
	}
	
	private String getMSRedirect(HttpServletRequest request)
			throws UnsupportedEncodingException {

		String prefix = "ms_";
		
		String clientId = (String)socialData.getProperty(prefix+"client_id");
		String clientSecret = (String)socialData.getProperty(prefix+"secret_key");
		String redirectUri = (String)socialData.getProperty(prefix+"redirect_uri");
		String tenant = (String)socialData.getProperty(prefix+"tenant");
		String scope = (String)socialData.getProperty(prefix+"scope"); // need to set this up and reuse

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
	 * Logs user in through SalesForce
	 */
	@GET
	@Produces("application/json")
	@Path("/login/dropbox")
	public Response loginDropBox(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException 
	{
		// redirect if query string not there
		
		Object userObj = request.getSession().getAttribute("semoss_user");
		Hashtable<String, String> ret = new Hashtable<String, String>();
		
		
		String queryString = request.getQueryString();
		
		if(queryString != null && queryString.contains("code="))
		{
			if(userObj == null || ((User2)userObj).getAccessToken(AuthProvider.DROPBOX.name()) == null)
			{
				String [] outputs = AbstractHttpHelper.getCodes(queryString);
				
				String prefix = "dropbox_";
				
				String clientId = (String)socialData.getProperty(prefix+"client_id");
				String clientSecret = (String)socialData.getProperty(prefix+"secret_key");
				String redirectUri = (String)socialData.getProperty(prefix+"redirect_uri");
				
				System.out.println(">> " + request.getQueryString());
				
				Hashtable params = new Hashtable();
	
				params.put("client_id", clientId);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("grant_type", "authorization_code");
				params.put("client_secret", clientSecret);
	
				String url = "https://www.dropbox.com/oauth2/token";
		
				
				AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, true, true);
				accessToken.setProvider(AuthProvider.DROPBOX.name());
				addAccessToken(accessToken, request);
	
				System.out.println("Access Token is.. " + accessToken.getAccess_token());			
			ret.put("success", "true");
	//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
			return WebUtility.getResponse(ret, 200);
			}
			else
			{
				ret.put("success", "true");
				ret.put("Already_Authenticated", "true");
				//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
				return WebUtility.getResponse(ret, 200);

			}

		}
		else if(userObj == null || ((User2)userObj).getAccessToken(AuthProvider.DROPBOX.name()) == null)
		{
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getDBRedirect(request));
		}
		return null;
	}
	
	private String getDBRedirect(HttpServletRequest request)
			throws UnsupportedEncodingException {

		String prefix = "dropbox_";
		
		String clientId = (String)socialData.getProperty(prefix+"client_id");
		String clientSecret = (String)socialData.getProperty(prefix+"secret_key");
		String redirectUri = (String)socialData.getProperty(prefix+"redirect_uri");
		String scope = (String)socialData.getProperty(prefix+"scope"); // need to set this up and reuse
		String role = (String)socialData.getProperty(prefix+"role"); // need to set this up and reuse

		String state = UUID.randomUUID().toString();
	
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
	 * Logs user in through SalesForce
	 */
	@GET
	@Produces("application/json")
	@Path("/login/google2")
	public Response loginGoogle2(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException 
	{
		// redirect if query string not there
		
		String queryString = request.getQueryString();
		Object userObj = request.getSession().getAttribute("semoss_user");
		Hashtable<String, String> ret = new Hashtable<String, String>();
		
		// if the code is there
		if(queryString != null && queryString.contains("code="))
		{
			// if the user is not logged in
			if(userObj == null || ((User2)userObj).getAccessToken(AuthProvider.GOOGLE.name()) == null)
			{
				String [] outputs = AbstractHttpHelper.getCodes(queryString);
				
				String prefix = "google_";
				
				String clientId = (String)socialData.getProperty(prefix+"client_id");
				String clientSecret = (String)socialData.getProperty(prefix+"secret_key");
				String redirectUri = (String)socialData.getProperty(prefix+"redirect_uri");
				
				System.out.println(">> " + request.getQueryString());
				
				Hashtable params = new Hashtable();
	
				params.put("client_id", clientId);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("grant_type", "authorization_code");
				params.put("client_secret", clientSecret);
	
				String url = "https://www.googleapis.com/oauth2/v4/token";
				
				System.out.println("Temp.. ");
		
				
				AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, true, true);
				//https://developers.google.com/api-client-library/java/google-api-java-client/oauth2
				// Shows how to make a google credential from an access token
				System.out.println("Access Token is.. " + accessToken.getAccess_token());
				accessToken.setProvider(AuthProvider.GOOGLE.name());
				addAccessToken(accessToken, request);
			}
			

			userObj = request.getSession().getAttribute("semoss_user");
			User2 user = (User2)userObj;
			AccessToken accessToken = user.getAccessToken(AuthProvider.GOOGLE.name());
			
			//performGoogleOps(request, ret);
			ret.put("success", "true");
			try {
				//ret.put("user", BeanFiller.getJson(accessToken));
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			ret.put("success", "true");
	//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
			return WebUtility.getResponse(ret, 200);
		}
		// else if the user object is there, but there is no google
		else if(userObj == null || ((User2)userObj).getAccessToken(AuthProvider.GOOGLE.name()) == null)
		{
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getGoogleRedirect(request));
		}
		// else if user object is there and google is there
		else if(userObj != null && ((User2)userObj).getAccessToken(AuthProvider.GOOGLE.name()) != null)
		{
			ret.put("success", "true");
			User2 user = (User2)userObj;
			AccessToken accessToken = user.getAccessToken(AuthProvider.GOOGLE.name());
			
			// performGoogleOps(request, ret);
			ret.put("success", "true");
			try {
				//ret.put("user", BeanFiller.getJson(accessToken));
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			return WebUtility.getResponse(ret, 200);
			
		}
		return null;
	}
	
	private void performGoogleOps(HttpServletRequest request, Hashtable ret)
	{
		// get the user details
		IConnectorIOp prof = new GoogleProfile();
		prof.execute((User2)request.getSession().getAttribute("semoss_user"), null);
		
		IConnectorIOp lister = new GoogleListFiles();
		List fileList = (List)lister.execute((User2)request.getSession().getAttribute("semoss_user"), null);

		// get the file
		IConnectorIOp getter = new GoogleFileRetriever();
		Hashtable params2 = new Hashtable();
		params2.put("exportFormat", "csv");
		params2.put("id", "1it40jNFcRo1ur2dHIYUk18XmXdd37j4gmJm_Sg7KLjI");
		params2.put("target", "c:\\users\\pkapaleeswaran\\workspacej3\\datasets\\googlefile.csv");

		getter.execute((User2)request.getSession().getAttribute("semoss_user"), params2);

		IConnectorIOp ner = new GoogleEntityResolver();

		NLPDocumentInput docInput = new NLPDocumentInput();
		docInput.setContent("Obama is staying in the whitehouse !!");
		params2 = new Hashtable();
		
		Hashtable docInputShell = new Hashtable();
		docInputShell.put("encodingType", "UTF8");
		docInputShell.put("document", docInput);
		
		params2.put("input", docInputShell);
		ner.execute((User2)request.getSession().getAttribute("semoss_user"), params2);
		
		try {
			ret.put("files", BeanFiller.getJson(fileList));
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		IConnectorIOp lat = new GoogleLatLongGetter();
		params2 = new Hashtable();
		params2.put("address", "1919 N Lynn Street, Arlington, VA");
		
		lat.execute((User2)request.getSession().getAttribute("semoss_user"), params2);

		IConnectorIOp ts = new TwitterSearcher();
		params2 = new Hashtable();
		params2.put("q", "Anlaytics");
		params2.put("lang", "en");
		params2.put("count", "10");
		
		Object vp = ts.execute((User2)request.getSession().getAttribute("semoss_user"), params2);

		
	}
	
	private String getGoogleRedirect(HttpServletRequest request)
			throws UnsupportedEncodingException {

		String prefix = "google_";
		
		String clientId = (String)socialData.getProperty(prefix+"client_id");
		String clientSecret = (String)socialData.getProperty(prefix+"secret_key");
		String redirectUri = (String)socialData.getProperty(prefix+"redirect_uri");
		String scope = (String)socialData.getProperty(prefix+"scope"); // need to set this up and reuse
		String accessType = (String)socialData.getProperty(prefix+"access_type"); // need to set this up and reuse

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
	
	// add this user to session
	private void addAccessToken(AccessToken token, HttpServletRequest request)
	{
		User2 semossUser = null;
		Object user = request.getSession().getAttribute("semoss_user");
		if(user != null)
			semossUser = (User2)user;
		else
		{
			semossUser = new User2();
			if(twitToken != null)
				semossUser.setAccessToken(twitToken);
			if(googAppToken != null)
				semossUser.setAccessToken(googAppToken);

		}
		
		semossUser.setAccessToken(token);
		
		request.getSession().setAttribute("semoss_user",semossUser);
	}
	
	@GET
	@Produces("application/json")
	@Path("/login/producthunt")
	public Response loginProducthunt(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException 
	{
		// redirect if query string not there
		
		Object userObj = request.getSession().getAttribute("semoss_user");
		Hashtable<String, String> ret = new Hashtable<String, String>();
		
		
		String queryString = request.getQueryString();
		
		if(queryString != null && queryString.contains("code="))
		{
			if(userObj == null || ((User2)userObj).getAccessToken(AuthProvider.PRODUCT_HUNT.name()) == null)
			{
				String [] outputs = AbstractHttpHelper.getCodes(queryString);
				
				String prefix = "producthunt_";
				
				String clientId = (String)socialData.getProperty(prefix+"client_id");
				String clientSecret = (String)socialData.getProperty(prefix+"secret_key");
				String redirectUri = (String)socialData.getProperty(prefix+"redirect_uri");
				
				System.out.println(">> " + request.getQueryString());
				
				Hashtable params = new Hashtable();
	
				params.put("client_id", clientId);
				params.put("redirect_uri", redirectUri);
				params.put("code", outputs[0]);
				params.put("grant_type", "authorization_code");
				params.put("client_secret", clientSecret);
	
				String url = "https://api.producthunt.com/v1/oauth/token";
		
				
				AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, true, true);
				accessToken.setProvider(AuthProvider.PRODUCT_HUNT.name());
				addAccessToken(accessToken, request);
	
				System.out.println("Access Token is.. " + accessToken.getAccess_token());			
			ret.put("success", "true");
	//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
			return WebUtility.getResponse(ret, 200);
			}
			else
			{
				ret.put("success", "true");
				ret.put("Already_Authenticated", "true");
				//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
				return WebUtility.getResponse(ret, 200);

			}

		}
		else if(userObj == null || ((User2)userObj).getAccessToken(AuthProvider.PRODUCT_HUNT.name()) == null)
		{
			// not authenticated
			response.setStatus(302);
			response.sendRedirect(getProducthuntRedirect(request));
		}
		return null;
	}
	
	private String getProducthuntRedirect(HttpServletRequest request)
			throws UnsupportedEncodingException {

		String prefix = "producthunt_";
		
		String clientId = (String)socialData.getProperty(prefix+"client_id");
		String clientSecret = (String)socialData.getProperty(prefix+"secret_key");
		String redirectUri = (String)socialData.getProperty(prefix+"redirect_uri");
		String scope = (String)socialData.getProperty(prefix+"scope");

	
		String redirectUrl = "https://api.producthunt.com/v1/oauth/authorize?" + 
				"client_id=" + clientId+
				"&response_type=code" + 
				"&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8")+ 
				"&scope=" + URLEncoder.encode(scope, "UTF-8") ;

		System.out.println("Sending redirect.. " + redirectUrl);

		return redirectUrl;
	}
	
	
	public static void loginTwitterApp() throws IOException 
	{
		// redirect if query string not there
		Hashtable<String, String> ret = new Hashtable<String, String>();
		
		// getting the bearer token on twitter for app authentication is a lot simpler
		// need to just combine the id and secret
		// base 64 and send as authorization

		if(twitToken == null)
		{
			try {
				String prefix = "twitter_";
				
				
				String clientId = "I09t8iaHy4UogHfDVFppZiUpo";
				String clientSecret = "MGKH5Pm6ChWDmrLK7IaJsLhSn57ckmHiMghtim8qZ46wnXrxJY";
				
				clientId = (String)socialData.getProperty(prefix+"client_id");
				clientSecret = (String)socialData.getProperty(prefix+"secret_key");

				
				// make a joint string
				String jointString = clientId + ":" + clientSecret;
				
				// encde this base 64
				String encodedJointString = new String(Base64.getEncoder().encode(jointString.getBytes()));
				CloseableHttpClient httpclient = HttpClients.createDefault();
				HttpPost httppost = new HttpPost("https://api.twitter.com/oauth2/token");
				
				httppost.addHeader("Authorization", "Basic " + encodedJointString);
				//httppost.addHeader("Content-Type","application/json; charset=utf-8");

				List<NameValuePair> paramList = new ArrayList<NameValuePair>();
					
				paramList.add(new BasicNameValuePair("grant_type", "client_credentials"));
					
				httppost.setEntity(new UrlEncodedFormEntity(paramList));
								
				ResponseHandler<String> handler = new BasicResponseHandler();
				CloseableHttpResponse authResp = httpclient.execute(httppost);
				
				System.out.println("Response Code " + authResp.getStatusLine().getStatusCode());
				
				int status = authResp.getStatusLine().getStatusCode();
				
				BufferedReader rd = new BufferedReader(
				        new InputStreamReader(authResp.getEntity().getContent()));
	
				StringBuffer result = new StringBuffer();
				String line = "";
				while ((line = rd.readLine()) != null) {
					result.append(line);
				}
				
				twitToken = AbstractHttpHelper.getJAccessToken(result.toString());
				twitToken.setProvider(AuthProvider.TWITTER.name());
				
				//addAccessToken(twitToken, request);
				
			}catch(Exception ex)
			{
				ex.printStackTrace();
			}
		System.out.println("Access Token is.. " + twitToken.getAccess_token());
		}		
	}
	
	private static void loginGoogleApp()
	{
		// nothing big here
		// set the name on accesstoken
		if(googAppToken == null)
		{
			googAppToken = new AccessToken();
			googAppToken.setAccess_token(socialData.getProperty("google_maps_api"));
			googAppToken.setProvider(AuthProvider.GOOGLE_MAP.name());
		}
	}


	/**
	 * Logs user in through GIT
	 */
	@GET
	@Produces("application/json")
	@Path("/login/twitter")
	public Response loginTwitter(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException 
	{
		// redirect if query string not there
		Object userObj = request.getSession().getAttribute("semoss_user");
		Hashtable<String, String> ret = new Hashtable<String, String>();
		
		// getting the bearer token on twitter for app authentication is a lot simpler
		// need to just combine the id and secret
		// base 64 and send as authorization
		
		
		//https://developer.github.com/apps/building-oauth-apps/authorization-options-for-oauth-apps/
		String queryString = request.getQueryString();
		
		if(queryString != null && queryString.contains("code="))
		{
			if(userObj == null || ((User2)userObj).getAccessToken(AuthProvider.GIT.name()) == null)
			{

			String [] outputs = AbstractHttpHelper.getCodes(queryString);
			
			String prefix = "git_";
			
			String clientId = (String)socialData.getProperty(prefix+"client_id");
			String clientSecret = (String)socialData.getProperty(prefix+"secret_key");
			String redirectUri = (String)socialData.getProperty(prefix+"redirect_uri");
			
			System.out.println(">> " + request.getQueryString());
			
			Hashtable params = new Hashtable();
			params.put("client_id", clientId);
			params.put("redirect_uri", redirectUri);
			params.put("code", outputs[0]);
			params.put("state", outputs[1]);
			params.put("client_secret", clientSecret);

			String url = "https://github.com/login/oauth/access_token";
				
			AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, false, true);
			accessToken.setProvider(AuthProvider.GIT.name());
			addAccessToken(accessToken, request);
			
			System.out.println("Access Token is.. " + accessToken.getAccess_token());
			
			ret.put("success", "true");
	//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
			return WebUtility.getResponse(ret, 200);
			}
			else
			{
				ret.put("success", "true");
				ret.put("Already_Authenticated", "true");
				//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
				return WebUtility.getResponse(ret, 200);

			}
		}
		else if(userObj == null || ((User2)userObj).getAccessToken(AuthProvider.GIT.name()) == null)
		{
			// not authenticated

			response.setStatus(302);
			response.sendRedirect(getGitRedirect(request));
		}
		return null;
	}
	
	private String getTwitterRedirect(HttpServletRequest request)
			throws UnsupportedEncodingException {

		String prefix = "twitter_";
		
		String clientId = (String)socialData.getProperty(prefix+"client_id");
		String clientSecret = (String)socialData.getProperty(prefix+"secret_key");
		String redirectUri = (String)socialData.getProperty(prefix+"redirect_uri");
		String scope = (String)socialData.getProperty(prefix+"scope");
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


	/**
	 * Logs user in through GIT
	 */
	@GET
	@Produces("application/json")
	@Path("/login/linkedin")
	public Response loginIn(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException 
	{
		// redirect if query string not there
		Object userObj = request.getSession().getAttribute("semoss_user");
		Hashtable<String, String> ret = new Hashtable<String, String>();
		
		//https://developer.github.com/apps/building-oauth-apps/authorization-options-for-oauth-apps/
		String queryString = request.getQueryString();
		
		if(queryString != null && queryString.contains("code="))
		{
			if(userObj == null || ((User2)userObj).getAccessToken(AuthProvider.IN.name()) == null)
			{

			String [] outputs = AbstractHttpHelper.getCodes(queryString);
			
			String prefix = "in_";
			
			String clientId = (String)socialData.getProperty(prefix+"client_id");
			String clientSecret = (String)socialData.getProperty(prefix+"secret_key");
			String redirectUri = (String)socialData.getProperty(prefix+"redirect_uri");
			
			System.out.println(">> " + request.getQueryString());
			
			Hashtable params = new Hashtable();
			params.put("client_id", clientId);
			params.put("redirect_uri", redirectUri);
			params.put("code", outputs[0]);
			params.put("grant_type", "authorization_code");
			params.put("client_secret", clientSecret);

			String url = "https://www.linkedin.com/oauth/v2/accessToken";
				
			AccessToken accessToken = AbstractHttpHelper.getAccessToken(url, params, true, true);
			accessToken.setProvider(AuthProvider.IN.name());
			addAccessToken(accessToken, request);
			
			System.out.println("Access Token is.. " + accessToken.getAccess_token());
			
			ret.put("success", "true");
	//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
			return WebUtility.getResponse(ret, 200);
			}
			else
			{
				ret.put("success", "true");
				ret.put("Already_Authenticated", "true");
				//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
				return WebUtility.getResponse(ret, 200);

			}
		}
		else if(userObj == null || ((User2)userObj).getAccessToken(AuthProvider.IN.name()) == null)
		{
			// not authenticated

			response.setStatus(302);
			response.sendRedirect(getInRedirect(request));
		}
		return null;
	}
	
	private String getInRedirect(HttpServletRequest request)
			throws UnsupportedEncodingException {

		String prefix = "in_";
		
		String clientId = (String)socialData.getProperty(prefix+"client_id");
		String clientSecret = (String)socialData.getProperty(prefix+"secret_key");
		String redirectUri = (String)socialData.getProperty(prefix+"redirect_uri");
		String scope = (String)socialData.getProperty(prefix+"scope");

		String redirectUrl = "https://www.linkedin.com/oauth/v2/authorization?" +
				"client_id=" + clientId +
				"&redirect_uri=" + redirectUri +
				"&state=" + UUID.randomUUID().toString() +
				"&response_type=code" +
				"&scope=" + URLEncoder.encode(scope, "UTF-8");

		System.out.println("Sending redirect.. " + redirectUrl);

		return redirectUrl;
	}

	
	public static String calculateRFC2104HMAC(String data, String key)
			throws SignatureException, NoSuchAlgorithmException, InvalidKeyException
		{
			SecretKeySpec signingKey = new SecretKeySpec(key.getBytes(), "HmacSHA1");
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(signingKey);
			return toHexString(mac.doFinal(data.getBytes()));
		}
	
	private static String toHexString(byte[] bytes) {
		Formatter formatter = new Formatter();
		
		for (byte b : bytes) {
			formatter.format("%02x", b);
		}

		return formatter.toString();
	}
	
	@GET
	@Produces("application/json")
	@Path("/logout/{provider}")
	public Response logout(@PathParam("provider") String provider, @Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException 
	{
		if(provider.equalsIgnoreCase("ALL"))
		{
			// remove the user from session call it a day
			request.getSession().removeAttribute("semoss_user");
		}
		else
		{
			User2 thisUser = (User2)request.getSession().getAttribute("semoss_user");
			thisUser.dropAccessToken(provider.toUpperCase());
			request.getSession().setAttribute("semoss_user",thisUser);
		}
		
		Hashtable ret = new Hashtable();
		ret.put("logout", "true");
		//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
		return WebUtility.getResponse(ret, 200);		
	}

	
//	/**
//	 * Logs user in through Google+.
//	 */
//	@POST
//	@Produces("application/json")
//	@Path("/login/github")
//	public Response loginGithub(@Context HttpServletRequest request) throws IOException {
//		final String YOUR_API_KEY = "0d45001167302b65c68f"; // client id
//		final String YOUR_API_SECRET = "1c34c70b7b37dfc93539218f4b94b387365f5e33"; // client secret
//		
//		OAuthService service = new ServiceBuilder(YOUR_API_KEY)
//                .apiSecret(YOUR_API_SECRET)
//                .build(GitHubApi.instance());
//		
//		System.out.println(service);
//		
//		return WebUtility.getResponse(true, 200);
//	}
	
}
