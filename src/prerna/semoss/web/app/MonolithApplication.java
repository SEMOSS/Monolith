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

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import prerna.remoteviewer.api.RemoteBrowserSessionController;
import prerna.semoss.web.form.FormResource;
import prerna.semoss.web.services.config.ServerConfigurationResource;
import prerna.semoss.web.services.local.A2AResource;
import prerna.semoss.web.services.local.AdminThemeResource;
import prerna.semoss.web.services.local.AnthropicEndpoints;
import prerna.semoss.web.services.local.AuthorizationResource;
import prerna.semoss.web.services.local.DatabaseEngineResource;
import prerna.semoss.web.services.local.EngineRouteResource;
import prerna.semoss.web.services.local.ExecuteInsightResource;
import prerna.semoss.web.services.local.FunctionEngineResource;
import prerna.semoss.web.services.local.MCPResource;
import prerna.semoss.web.services.local.ModelEngineResource;
import prerna.semoss.web.services.local.NameServer;
import prerna.semoss.web.services.local.OllamaEndpoints;
import prerna.semoss.web.services.local.OpenAIEndpoints;
import prerna.semoss.web.services.local.ProjectResource;
import prerna.semoss.web.services.local.SchedulerResource;
import prerna.semoss.web.services.local.SessionResource;
import prerna.semoss.web.services.local.ShareInsightResource;
import prerna.semoss.web.services.local.StorageEngineResource;
import prerna.semoss.web.services.local.UserResource;
import prerna.semoss.web.services.local.VectorEngineResource;
import prerna.semoss.web.services.local.auth.AdminEngineAuthorizationResource;
import prerna.semoss.web.services.local.auth.AdminGroupAuthorizationResource;
import prerna.semoss.web.services.local.auth.AdminInsightAuthorizationResource;
import prerna.semoss.web.services.local.auth.AdminModelUsageResource;
import prerna.semoss.web.services.local.auth.AdminPermissionBatchResource;
import prerna.semoss.web.services.local.auth.AdminProjectAuthorizationResource;
import prerna.semoss.web.services.local.auth.AdminUserAuthorizationResource;
import prerna.semoss.web.services.local.auth.EngineAuthorizationResource;
import prerna.semoss.web.services.local.auth.GroupAuthorizationResource;
import prerna.semoss.web.services.local.auth.GroupEngineAuthorizationResource;
import prerna.semoss.web.services.local.auth.GroupInsightAuthorizationResource;
import prerna.semoss.web.services.local.auth.GroupProjectAuthorizationResource;
import prerna.semoss.web.services.local.auth.InsightAuthorizationResource;
import prerna.semoss.web.services.local.auth.PermissionBatchResource;
import prerna.semoss.web.services.local.auth.ProjectAuthorizationResource;
import prerna.semoss.web.services.local.auth.UserAuthorizationResource;
import prerna.upload.FileUploader;
import prerna.upload.ImageUploader;

@ApplicationPath("/api")
public class MonolithApplication extends Application {

	private Set<Class<?>> classes = new HashSet<>();

	public MonolithApplication() {
		// core
		classes.add(UserResource.class);
		classes.add(NameServer.class);
		classes.add(FileUploader.class);
		classes.add(ImageUploader.class);
		classes.add(SessionResource.class);
		// engine interfaces
		classes.add(EngineRouteResource.class);
		classes.add(DatabaseEngineResource.class);
		classes.add(StorageEngineResource.class);
		classes.add(ModelEngineResource.class);
		classes.add(VectorEngineResource.class);
		classes.add(FunctionEngineResource.class);
		classes.add(ProjectResource.class);
		// authorization resources
		classes.add(AuthorizationResource.class);
		classes.add(EngineAuthorizationResource.class);
		classes.add(ProjectAuthorizationResource.class);
		classes.add(InsightAuthorizationResource.class);
		classes.add(UserAuthorizationResource.class);
		// admin authorization
		classes.add(AdminEngineAuthorizationResource.class);
		classes.add(AdminProjectAuthorizationResource.class);
		classes.add(AdminInsightAuthorizationResource.class);
		classes.add(AdminUserAuthorizationResource.class);
		// group authorization
		classes.add(GroupEngineAuthorizationResource.class);
		classes.add(GroupProjectAuthorizationResource.class);
		classes.add(GroupInsightAuthorizationResource.class);
		// group managers maintain the members of their custom groups
		classes.add(GroupAuthorizationResource.class);
		// Bulk Access Management validates and saves many access changes together
		classes.add(PermissionBatchResource.class);
		classes.add(AdminPermissionBatchResource.class);
		// admins set the platform default model usage limit and team totals
		classes.add(AdminModelUsageResource.class);
		// group admin authorization
		classes.add(AdminGroupAuthorizationResource.class);
		// insight execution
		classes.add(ExecuteInsightResource.class);
		classes.add(ShareInsightResource.class);
		classes.add(SchedulerResource.class);
		// other
		classes.add(AdminThemeResource.class);
		classes.add(ServerConfigurationResource.class);
		// legacy forms - still used in production - RDF specific
		classes.add(FormResource.class);

		// openai endpoints for CFG AI Model Inference
		classes.add(OpenAIEndpoints.class);
		classes.add(AnthropicEndpoints.class);
		classes.add(OllamaEndpoints.class);
		// MCP
		classes.add(MCPResource.class);
		// A2A
		classes.add(A2AResource.class);
		// Remote Browser Viewer
		classes.add(RemoteBrowserSessionController.class);
	}

	@Override
	public Set<Class<?>> getClasses() {
		return classes;
	}
}
