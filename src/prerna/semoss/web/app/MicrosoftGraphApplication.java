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
import prerna.semoss.web.ms.MicrosoftGraphNotificationService;
import prerna.semoss.web.ms.MicrosoftGraphSubscriptionService;

/**
 * JAX-RS application that exposes the Microsoft Graph change notification
 * endpoints under {@code /msgraph}.
 * <p>
 * Registered by a dedicated RESTEasy dispatcher mapped to {@code /msgraph/*}
 * (see web.xml), so the feature lives off {@code /api} and outside that auth
 * filter chain, the same way the GitHub and Teams integrations do. Graph posts
 * its notifications with no session and no bearer token of ours, so the
 * receivers have to be reachable without one and authenticate each delivery
 * themselves.
 * <p>
 * {@link MicrosoftGraphSubscriptionService} holds the lifecycle a signed in
 * user drives, and every endpoint on it needs a session;
 * {@link MicrosoftGraphNotificationService} holds the two inbound receivers,
 * which authenticate each delivery by the client state the subscription was
 * created with.
 */
@ApplicationPath("/msgraph")
public class MicrosoftGraphApplication extends Application {

	private Set<Object> singletons = new HashSet<Object>();

	public MicrosoftGraphApplication() {
		singletons.add(new MicrosoftGraphSubscriptionService());
		singletons.add(new MicrosoftGraphNotificationService());
	}

	@Override
	public Set<Object> getSingletons() {
		return singletons;
	}
}
