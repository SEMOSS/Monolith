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
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.security.Principal;
import java.util.HashMap;
import java.util.Hashtable;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
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

import prerna.auth.User;
import prerna.auth.UserPermissionsMasterDB;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;
import waffle.servlet.WindowsPrincipal;

import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson.JacksonFactory;
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
	
	private final String GOOGLE_CLIENT_SECRET = "VNIxZUbsMj-wV5CNDwF5gXcV";	
	
	private final String FACEBOOK_APP_SECRET = "aaab566fd8f0b9c48d3a44c2241fb25b";
	private final String FACEBOOK_ACCESS_TOKEN_NEW = "https://graph.facebook.com/oauth/access_token?code=%s&client_id=%s&redirect_uri=%s&client_secret=%s";
	
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

		// Only connect a user that is not already connected.
		String tokenData = (String) request.getSession().getAttribute("token");
		if (tokenData != null) {
			ret.put("error", "User is already connected.");
			return Response.status(200).entity(WebUtility.getSO(ret)).build();
		}
		// Ensure that this is no request forgery going on, and that the user
		// sending us this connect request is the user that was supposed to.
		if (request.getParameter("state") != null && request.getSession().getAttribute("state") != null && 
				!request.getParameter("state").equals(request.getSession().getAttribute("state"))) {
			ret.put("error", "Invalid state parameter.");
			return Response.status(400).entity(WebUtility.getSO(ret)).build();
		}

		GoogleTokenResponse tokenResponse = new GoogleTokenResponse();
		try {
			// Upgrade the authorization code into an access and refresh token.
			tokenResponse =	new GoogleAuthorizationCodeTokenRequest(TRANSPORT, JSON_FACTORY, clientId, GOOGLE_CLIENT_SECRET, code, "https://localhost").execute();

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
			
			ret.put("token", tokenResponse.getAccessToken());
			ret.put("id", gplusId);
			ret.put("name", name);
			ret.put("email", email);
			User newUser;
			if(picture != null && !picture.isEmpty()) {
				ret.put("picture", picture);
				newUser = new User(gplusId, name, User.LOGIN_TYPES.google, email, picture);
				request.getSession().setAttribute(Constants.SESSION_USER, newUser);
			} else {
				newUser = new User(gplusId, name, User.LOGIN_TYPES.google, email);
				request.getSession().setAttribute(Constants.SESSION_USER, newUser);
			}
			
			addUser(newUser);
			
			// Store the token in the session for later use.
			request.getSession().setAttribute("token", tokenResponse.toString());
		} catch (TokenResponseException e) {
			ret.put("success", "false");
			ret.put("error", "Failed to upgrade the auth code: " + e.getMessage());
			return Response.status(400).entity(WebUtility.getSO(ret)).build();
		} catch (IOException e) {
			ret.put("success", "false");
			ret.put("error", "Failed to read the token data from google: " + e.getMessage());
			return Response.status(404).entity(WebUtility.getSO(ret)).build();
		}
		
		ret.put("success", "true");
		return Response.status(200).entity(WebUtility.getSO(ret)).build();
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
			return Response.status(400).entity(WebUtility.getSO(ret)).build();
		}

		request.getSession().invalidate();

		ret.put("success", "true");
		return Response.status(200).entity(WebUtility.getSO(ret)).build();
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
		String accessToken = "";
		
		CloseableHttpClient httpclient = HttpClients.createDefault();
		HttpGet http = new HttpGet(
				String.format(this.FACEBOOK_ACCESS_TOKEN_NEW, code, clientId, redirectUri, this.FACEBOOK_APP_SECRET));
		CloseableHttpResponse response = httpclient.execute(http);
		
		// Retrieve Facebook OAuth access token
		try {
		    HttpEntity entity = response.getEntity();
		    if (entity != null) {
		    	long len = entity.getContentLength();
		        if (len != -1) {
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
			    			return Response.status(400).entity(WebUtility.getSO(ret)).build();
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
		User newUser;
		if(me.getPicture() != null) {
			String picture = me.getPicture().getUrl();
			ret.put("picture", picture);
			newUser = new User(id, name, User.LOGIN_TYPES.facebook, email, picture);
			request.getSession().setAttribute(Constants.SESSION_USER, newUser);
		} else {
			newUser = new User(id, name, User.LOGIN_TYPES.facebook, email);
			request.getSession().setAttribute(Constants.SESSION_USER, newUser);
		}
		
		ret.put("token", accessToken);
		ret.put("id", id);
		ret.put("name", name);
		ret.put("email", email);
		
		addUser(newUser);
		
		// Store the token in the session for later use.
		request.getSession().setAttribute("token", accessToken);
		
		ret.put("success", "true");
		return Response.status(200).entity(WebUtility.getSO(ret)).build();
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
			return Response.status(400).entity(WebUtility.getSO(ret)).build();
		}
		
		request.getSession().invalidate();
		
		ret.put("success", "true");
		return Response.status(200).entity(WebUtility.getSO(ret)).build();
	}
	
	/**
	 * Adds user to Local Master DB upon sign-in
	 * 
	 * @param userId	User ID of user retrieved from Identity Provider
	 * @param email		Email address of user retrieved from Identity Provider
	 */
	private void addUser(User newUser) {
		UserPermissionsMasterDB master = new UserPermissionsMasterDB(Constants.LOCAL_MASTER_DB_NAME);
		master.addUser(newUser);
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
}
