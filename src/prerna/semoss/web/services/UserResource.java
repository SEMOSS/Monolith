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
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import prerna.auth.SEMOSSPrincipal;
import prerna.web.services.util.WebUtility;
import waffle.servlet.WindowsPrincipal;

import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson.JacksonFactory;
import com.google.api.services.oauth2.Oauth2;
import com.google.api.services.oauth2.model.Userinfoplus;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.internal.StringMap;
import com.restfb.DefaultFacebookClient;
import com.restfb.FacebookClient;
import com.restfb.Parameter;
import com.restfb.types.User;
import com.sun.jna.platform.win32.Secur32;
import com.sun.jna.platform.win32.Secur32Util;

@Path("/auth")
public class UserResource
{

	String output = "";
	private final HttpTransport TRANSPORT = new NetHttpTransport();
	private final JacksonFactory JSON_FACTORY = new JacksonFactory();
	private final Gson GSON = new Gson();
	
	public static enum LOGIN_TYPES {google, facebook};
	
	private final String GOOGLE_CLIENT_SECRET = "VNIxZUbsMj-wV5CNDwF5gXcV";	
	private final String GOOGLE_VALID_TOKEN_CHECK = "https://www.googleapis.com/oauth2/v1/tokeninfo?access_token=";
	private final String GOOGLE_ACCESS_TOKEN_REVOKE = "https://accounts.google.com/o/oauth2/revoke?token=";
	
	private final String FACEBOOK_APP_SECRET = "aaab566fd8f0b9c48d3a44c2241fb25b";
	private final String FACEBOOK_VALID_TOKEN_CHECK = "https://graph.facebook.com/me?fields=id&access_token=";
	private final String FACEBOOK_ACCESS_TOKEN_NEW = "https://graph.facebook.com/oauth/access_token?code=%s&client_id=%s&redirect_uri=%s&client_secret=%s";
	private final String FACEBOOK_ACCESS_TOKEN_REVOKE = "https://graph.facebook.com/v2.1/me/permissions?access_token=";

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

			GoogleIdToken idToken = tokenResponse.parseIdToken();
			String gplusId = idToken.getPayload().getSubject();
			String email = idToken.getPayload().getEmail();
			String name = userinfo.getGivenName() + " " + userinfo.getFamilyName();
			String picture = userinfo.getPicture();
			
			ret.put("token", tokenResponse.getAccessToken());
			ret.put("id", gplusId);
			ret.put("name", name);
			ret.put("email", email);
			if(picture != null && !picture.isEmpty()) {
				ret.put("picture", picture);
			}

			//TODO: Look for the authenticated user and grab permissions, or add if not found
			SEMOSSPrincipal me = new SEMOSSPrincipal(gplusId, name, SEMOSSPrincipal.LOGIN_TYPES.Google, email, picture);

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

		String clientId = request.getSession().getAttribute("clientId").toString();

		try {
			// Build credential from stored token data.
			GoogleCredential credential = new GoogleCredential.Builder()
			.setJsonFactory(JSON_FACTORY)
			.setTransport(TRANSPORT)
			.setClientSecrets(clientId, GOOGLE_CLIENT_SECRET).build()
			.setFromTokenResponse(JSON_FACTORY.fromString(tokenData, GoogleTokenResponse.class));

			// Execute HTTP GET request to revoke current token.
			HttpResponse revokeResponse = TRANSPORT.createRequestFactory()
					.buildGetRequest(new GenericUrl(
							String.format(this.GOOGLE_ACCESS_TOKEN_REVOKE + "%s", credential.getAccessToken()))).execute();
			
			if(revokeResponse.getStatusCode() == 200) {
				// Reset the user's session.
				request.getSession().removeAttribute("clientId");
				request.getSession().removeAttribute("token");
			}
		} catch (IOException e) {
			// For whatever reason, the given token was invalid.
			ret.put("success", "false");
			ret.put("error", "Failed to revoke token.");
			return Response.status(400).entity(WebUtility.getSO(ret)).build();
		}

		ret.put("success", "true");
		return Response.status(200).entity(WebUtility.getSO(ret)).build();
	}
	
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
			    			return Response.status(200).entity(WebUtility.getSO(ret)).build();
			    		}
		            }
		        }
		    }
		} finally {
		    response.close();
		    httpclient.close();
		}
		
		FacebookClient fb = new DefaultFacebookClient(accessToken, this.FACEBOOK_APP_SECRET);
		
		User me = fb.fetchObject("me", User.class, Parameter.with("fields", "id, name, email, picture"));
		String id = me.getId();
		String email = me.getEmail();
		String name = me.getName();
		if(me.getPicture() != null) {
			String picture = me.getPicture().getUrl();
			ret.put("picture", picture);
		}
		
		ret.put("token", accessToken);
		ret.put("id", id);
		ret.put("name", name);
		ret.put("email", email);
		
		ret.put("success", "true");
		return Response.status(200).entity(WebUtility.getSO(ret)).build();
	}
	
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

		String clientId = request.getSession().getAttribute("clientId").toString();
		
		CloseableHttpClient httpclient = HttpClients.createDefault();
		HttpDelete delete = new HttpDelete(this.FACEBOOK_ACCESS_TOKEN_REVOKE + tokenData);
		CloseableHttpResponse response = httpclient.execute(delete);
		
		try {
		    HttpEntity entity = response.getEntity();
		    if (entity != null) {
		    	long len = entity.getContentLength();
		        if (len != -1) {
		            String resp = EntityUtils.toString(entity);
		            
		            Gson gson = new Gson();
		    		HashMap<String, StringMap<String>> h = gson.fromJson(resp, HashMap.class);
		    		
		    		if(h.get("error") != null) {
		    			ret.put("success", "false");
		    			ret.put("error", h.get("error").toString());
		    			return Response.status(200).entity(WebUtility.getSO(ret)).build();
		    		}
		        }
		    }
		} finally {
		    response.close();
		    httpclient.close();
		}
		
		ret.put("success", "true");
		return Response.status(200).entity(WebUtility.getSO(ret)).build();
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

	public boolean isTokenValid(String provider, String accessToken) {
		boolean tokenValidity = false;
		String tokenValidUrl = "";
		
		if(provider == null || provider.isEmpty() || accessToken == null || accessToken.isEmpty()) {
			return tokenValidity;
		}
		
		if(provider.equals(LOGIN_TYPES.google.toString())) {
			tokenValidUrl = this.GOOGLE_VALID_TOKEN_CHECK;
		} else if(provider.equals(LOGIN_TYPES.facebook.toString())) {
			tokenValidUrl = this.FACEBOOK_VALID_TOKEN_CHECK;
		}
		
		// Execute HTTP GET request to revoke current token.
		try {
			HttpResponse tokenValidResponse = TRANSPORT.createRequestFactory()
					.buildGetRequest(
							new GenericUrl(String.format(tokenValidUrl + "%s", accessToken))).execute();

			HashMap<String, String> h = GSON.fromJson(tokenValidResponse.parseAsString(), HashMap.class);
			
			if(provider.equals(LOGIN_TYPES.google.toString())) {
				if(h.get("error") == null && h.get("issued_to") != null) {
					tokenValidity = true;
				}
			} else if(provider.equals(LOGIN_TYPES.facebook.toString())) {
				if(h.get("error") == null && h.get("id") != null) {
					tokenValidity = true;
				}
			}

		} catch (IOException e) {
			tokenValidity = false;
		}
		
		return tokenValidity;
	}
}
