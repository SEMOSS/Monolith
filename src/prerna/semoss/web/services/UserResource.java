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
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.security.Principal;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Scanner;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

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

import prerna.auth.CACReader;
import prerna.auth.User;
import prerna.auth.UserPermissionsMasterDB;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.web.services.util.WebUtility;
import waffle.servlet.WindowsPrincipal;

@Path("/auth")
public class UserResource
{
	String output = "";
	private final HttpTransport TRANSPORT = new NetHttpTransport();
	private final JacksonFactory JSON_FACTORY = new JacksonFactory();
	private final Gson GSON = new Gson();
	private UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
	
	private final String GOOGLE_CLIENT_SECRET = "***REMOVED***";	
	
	private final String FACEBOOK_APP_SECRET = "***REMOVED***";
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
				newUser = new User(gplusId, name, User.LOGIN_TYPES.google, email, picture);
			} else {
				newUser = new User(gplusId, name, User.LOGIN_TYPES.google, email);
			}
			
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
	 * Logs user out when authenticated through Google+.
	 */
	@GET
	@Produces("application/json")
	@Path("/logout/google")
	public Response logoutGoogle(@Context HttpServletRequest request) throws IOException {
		Hashtable<String, String> ret = new Hashtable<String, String>();

		// Only disconnect a connected user.
		String tokenData = (String) request.getSession().getAttribute("token");
		if (tokenData == null) {
			ret.put("success", "false");
			ret.put("error", "User is not connected.");
//			return Response.status(200).entity(WebUtility.getSO(ret)).build();
			return WebUtility.getResponse(ret, 200);
		}

		request.getSession().invalidate();

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
			newUser = new User(id, name, User.LOGIN_TYPES.facebook, email, picture);
		} else {
			newUser = new User(id, name, User.LOGIN_TYPES.facebook, email);
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
	 * @param email		Email address of user retrieved from Identity Provider
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
}
