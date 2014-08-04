package prerna.semoss.web.app;

import javax.ws.rs.core.Application;
import javax.ws.rs.ApplicationPath;

import prerna.semoss.web.services.NameServer;
import prerna.semoss.web.services.UserResource;

import java.util.HashSet;
import java.util.Set;

public class MonolithApplication extends Application {
   private Set<Object> singletons = new HashSet<Object>();

   public MonolithApplication() {
	  System.out.println("Invoked this >>>>>>. ");
      singletons.add(new UserResource());
      singletons.add(new NameServer());
   }

   @Override
   public Set<Object> getSingletons() {
      return singletons;
   }
}
