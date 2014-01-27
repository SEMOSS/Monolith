package com.restfully.shop.services;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

import prerna.semoss.web.services.NameServer;
import prerna.semoss.web.services.UserResource;

import java.util.HashSet;
import java.util.Set;

//@ApplicationPath("/services")
public class ShoppingApplication extends Application {
   private Set<Object> singletons = new HashSet<Object>();

   public ShoppingApplication() {
	   System.out.println("Invoked this >>>>>>. ");
      singletons.add(new CustomerDatabaseResource());
      singletons.add(new UserResource());
      singletons.add(new NameServer());
      
   }

   @Override
   public Set<Object> getSingletons() {
      return singletons;
   }
}
