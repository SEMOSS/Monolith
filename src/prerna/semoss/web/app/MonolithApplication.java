/*******************************************************************************
 * Copyright 2015 Defense Health Agency (DHA)
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
package prerna.semoss.web.app;

import java.util.HashSet;
import java.util.Set;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

// import prerna.cluster.RawSelectWrapperService;
import prerna.semoss.web.form.FormResource;
import prerna.semoss.web.services.config.ServerConfigurationResource;
import prerna.semoss.web.services.local.AuthorizationResource;
import prerna.semoss.web.services.local.DatabaseResource;
import prerna.semoss.web.services.local.ExecuteInsightResource;
import prerna.semoss.web.services.local.NameServer;
import prerna.semoss.web.services.local.ProjectResource;
import prerna.semoss.web.services.local.SchedulerResource;
import prerna.semoss.web.services.local.SessionResource;
import prerna.semoss.web.services.local.ShareInsightResource;
import prerna.semoss.web.services.local.ThemeResource;
import prerna.semoss.web.services.local.UserResource;
import prerna.semoss.web.services.local.auth.AdminDatabaseAuthorizationResource;
import prerna.semoss.web.services.local.auth.AdminDatabaseAuthorizationResource2;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResource;
import prerna.semoss.web.services.local.auth.AdminInsightAuthorizationResource;
import prerna.semoss.web.services.local.auth.AdminProjectAuthorizationResource;
import prerna.semoss.web.services.local.auth.AdminUserAuthorizationResource;
import prerna.semoss.web.services.local.auth.DatabaseAuthorizationResource;
import prerna.semoss.web.services.local.auth.DatabaseAuthorizationResource2;
import prerna.semoss.web.services.local.auth.GroupDatabaseAuthorizationResource;
import prerna.semoss.web.services.local.auth.GroupInsightAuthorizationResource;
import prerna.semoss.web.services.local.auth.GroupProjectAuthorizationResource;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResource;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResource;
import prerna.semoss.web.services.local.auth.UserAuthorizationResource;
import prerna.upload.FileUploader;
import prerna.upload.ImageUploader;

@ApplicationPath("/api")
public class MonolithApplication extends Application {
	
   private Set<Object> singletons = new HashSet<Object>();

   public MonolithApplication() {
	   // core
      singletons.add(new UserResource());
      singletons.add(new NameServer());
      singletons.add(new DatabaseResource());
      singletons.add(new ProjectResource());
      singletons.add(new FileUploader());
      singletons.add(new ImageUploader());
      singletons.add(new SessionResource());
      // authorization resources
      singletons.add(new AuthorizationResource());
      singletons.add(new DatabaseAuthorizationResource());
      singletons.add(new DatabaseAuthorizationResource2());
      singletons.add(new ProjectAuthorizationResource());
      singletons.add(new InsightAuthorizationResource());
      singletons.add(new UserAuthorizationResource());
      // admin authorization
      singletons.add(new AdminDatabaseAuthorizationResource());
      singletons.add(new AdminDatabaseAuthorizationResource2());
      singletons.add(new AdminProjectAuthorizationResource());
      singletons.add(new AdminInsightAuthorizationResource());
      singletons.add(new AdminUserAuthorizationResource());
      // group authorization
      singletons.add(new GroupDatabaseAuthorizationResource());
      singletons.add(new GroupProjectAuthorizationResource());
      singletons.add(new GroupInsightAuthorizationResource());
      // group admin authorization
      singletons.add(new AdminGroupAuthorizationResource());
      // insight execution
      singletons.add(new ExecuteInsightResource());
      singletons.add(new ShareInsightResource());
      singletons.add(new SchedulerResource());
      // other
      singletons.add(new ThemeResource());
      singletons.add(new ServerConfigurationResource());
      // singletons.add(new RawSelectWrapperService());
      // legacy forms - still used in production - RDF specific
      singletons.add(new FormResource());
   }

   @Override
   public Set<Object> getSingletons() {
      return singletons;
   }
}
