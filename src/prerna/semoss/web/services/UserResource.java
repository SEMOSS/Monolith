package prerna.semoss.web.services;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.security.Principal;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import waffle.servlet.WindowsPrincipal;

import com.sun.jna.platform.win32.Secur32;
import com.sun.jna.platform.win32.Secur32Util;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
//@Path("/who")
public class UserResource
{

	String output = "";
	
   @GET
   @Produces("text/plain")
   @Path("/whoami")
   public StreamingOutput show(@Context HttpServletRequest request, @Context HttpServletResponse response) {
       /*try {
    	   System.out.println("Came here");
   		return Response.temporaryRedirect(new URI("/customers/europe-db/1")).build();
   	} catch (URISyntaxException e) {
   		// TODO Auto-generated catch block
   		e.printStackTrace();
   	}*/
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
