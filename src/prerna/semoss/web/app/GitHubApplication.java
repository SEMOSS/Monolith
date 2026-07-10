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

import prerna.semoss.web.github.AdminGitHubService;
import prerna.semoss.web.github.GitHubService;

/**
 * JAX-RS application that exposes the GitHub endpoints under {@code /github}.
 * <p>
 * Registered by a dedicated RESTEasy dispatcher mapped to {@code /github/*}
 * (see web.xml), so the whole feature lives off {@code /api} and outside that
 * auth filter chain - the GitHub-driven endpoints (webhook, OAuth/install
 * callbacks) must be reachable without an API session, and the rest carry their
 * own access checks. {@link AdminGitHubService} holds the admin-only
 * app-manifest lifecycle; {@link GitHubService} holds the install/linking flow
 * and the inbound webhook.
 */
@ApplicationPath("/github")
public class GitHubApplication extends Application {

	private Set<Object> singletons = new HashSet<Object>();

	public GitHubApplication() {
		singletons.add(new GitHubService());
		singletons.add(new AdminGitHubService());
	}

	@Override
	public Set<Object> getSingletons() {
		return singletons;
	}
}
