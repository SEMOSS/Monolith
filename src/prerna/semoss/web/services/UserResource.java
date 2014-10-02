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

import prerna.auth.SEMOSSPrincipal;
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
import com.google.gson.GsonBuilder;
import com.sun.jna.platform.win32.Secur32;
import com.sun.jna.platform.win32.Secur32Util;

@Path("/auth")
public class UserResource
{

	String output = "";
	private static final HttpTransport TRANSPORT = new NetHttpTransport();
	private static final JacksonFactory JSON_FACTORY = new JacksonFactory();
	private static final Gson GSON = new Gson();
//	private static final String CLIENT_ID = "160506583908-41r33qtvgen71m5c7dithf7oirdqfiu5.apps.googleusercontent.com";
	private static final String CLIENT_SECRET = "VNIxZUbsMj-wV5CNDwF5gXcV";
	
	@POST
	@Produces("application/json")
	@Path("/google")
	public Response loginGoogle(@Context HttpServletRequest request) throws IOException {
		Hashtable<String, String> ret = new Hashtable<String, String>();
		
		// Grab the request's payload
		StringBuilder buffer = new StringBuilder();
	    BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream()));
	    String line;
	    while ((line = reader.readLine()) != null) {
	        buffer.append(line);
	    }
	    
	    Gson gson = new Gson();
	    HashMap<String, String> h = gson.fromJson(buffer.toString(), HashMap.class);
	    
	    String clientId = h.get("clientId");
	    String code = h.get("code");
		
		// Only connect a user that is not already connected.
		String tokenData = (String) request.getSession().getAttribute("token");
		if (tokenData != null) {
			ret.put("error", "User is already connected.");
			return Response.status(204).entity(getSO(ret)).build();
		}
		// Ensure that this is no request forgery going on, and that the user
		// sending us this connect request is the user that was supposed to.
		if (request.getParameter("state") != null && request.getSession().getAttribute("state") != null && 
				!request.getParameter("state").equals(request.getSession().getAttribute("state"))) {
			ret.put("error", "Invalid state parameter.");
			return Response.status(401).entity(getSO(ret)).build();
		}
		
		GoogleTokenResponse tokenResponse = new GoogleTokenResponse();
		try {
			// Upgrade the authorization code into an access and refresh token.
			tokenResponse =	new GoogleAuthorizationCodeTokenRequest(TRANSPORT, JSON_FACTORY, clientId, CLIENT_SECRET, code, "https://localhost").execute();
			
			// Grab the Google user info
			GoogleCredential credential = new GoogleCredential.Builder()
				.setTransport(this.TRANSPORT)
				.setJsonFactory(this.JSON_FACTORY)
				.setClientSecrets(clientId, CLIENT_SECRET).build()
				.setFromTokenResponse(tokenResponse);
			Oauth2 oauth2 = new Oauth2.Builder(TRANSPORT, JSON_FACTORY, credential).setApplicationName("SEMOSS").build();
			Userinfoplus userinfo = oauth2.userinfo().get().execute();

			GoogleIdToken idToken = tokenResponse.parseIdToken();
			String gplusId = idToken.getPayload().getSubject();
			String email = idToken.getPayload().getEmail();
			String name = userinfo.getGivenName() + userinfo.getFamilyName();
			String picture = userinfo.getPicture();
			
			ret.put("token", tokenResponse.toString());
			ret.put("id", gplusId);
			ret.put("name", name);
			ret.put("email", email);
			ret.put("picture", picture);
			
			//TODO: Look for the authenticated user and grab permissions, or add if not found
			SEMOSSPrincipal me = new SEMOSSPrincipal(gplusId, name, SEMOSSPrincipal.LOGIN_TYPES.Google, email, picture);

			// Store the token in the session for later use.
			request.getSession().setAttribute("token", tokenResponse.toString());
		} catch (TokenResponseException e) {
			ret.put("error", "Failed to upgrade the auth code: " + e.getMessage());
			return Response.status(400).entity(getSO(ret)).build();
		} catch (IOException e) {
			ret.put("error", "Failed to read the token data from google: " + e.getMessage());
			return Response.status(404).entity(getSO(ret)).build();
		}

		return Response.status(200).entity(getSO(ret)).build();
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
   
   private StreamingOutput getSO(Object vec)
   {
		if(vec != null)
		{
			Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
			output = gson.toJson(vec);
			return new StreamingOutput() {
				public void write(OutputStream outputStream) throws IOException, WebApplicationException {
					PrintStream ps = new PrintStream(outputStream);
					ps.println(output);
				}};		
		}
		return null;
   }
}
