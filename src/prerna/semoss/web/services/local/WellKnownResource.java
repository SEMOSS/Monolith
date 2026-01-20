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
package prerna.semoss.web.services.local;

import javax.annotation.security.PermitAll;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import prerna.util.SocialPropertiesUtil;

/**
 * Well-Known URIs Resource (RFC 5785)
 * Provides OAuth Protected Resource Metadata (RFC 9728) for MCP OAuth integration.
 */
@Singleton
@Path("/.well-known")
@PermitAll
public class WellKnownResource {

	private static final Logger classLogger = LogManager.getLogger(WellKnownResource.class);
	private static final SocialPropertiesUtil socialData = SocialPropertiesUtil.getInstance();

	/**
	 * OAuth Protected Resource Metadata (RFC 9728) - required for ChatGPT MCP OAuth.
	 * This endpoint must be at /.well-known/oauth-protected-resource per the spec.
	 */
	@GET
	@Path("/oauth-protected-resource")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getOAuthProtectedResourceMetadata(@Context HttpServletRequest request) {

		String baseUrl = getExternalBaseUrl(request);
		String authServerUrl = getAuthServerUrl();

		JSONObject metadata = new JSONObject();
		metadata.put("resource", baseUrl + "/api");
		metadata.put("authorization_servers", new JSONArray().put(authServerUrl));
		metadata.put("scopes_supported", new JSONArray()
				.put("openid").put("profile").put("email").put("offline_access"));

		classLogger.info("Served OAuth Protected Resource Metadata: " + metadata);
		return Response.ok(metadata.toString()).type(MediaType.APPLICATION_JSON).build();
	}

	/**
	 * Get the external base URL for this server.
	 */
	private String getExternalBaseUrl(HttpServletRequest request) {
		String externalUrl = socialData.getProperty("EXTERNAL_URL");
		if (externalUrl != null && !externalUrl.isEmpty()) {
			return externalUrl.replaceAll("/+$", ""); // Remove trailing slashes
		}

		String scheme = request.getScheme();
		String serverName = request.getServerName();
		int serverPort = request.getServerPort();
		String contextPath = request.getContextPath();

		StringBuilder url = new StringBuilder();
		url.append(scheme).append("://").append(serverName);

		if ((scheme.equals("http") && serverPort != 80) || (scheme.equals("https") && serverPort != 443)) {
			url.append(":").append(serverPort);
		}

		url.append(contextPath);
		return url.toString();
	}

	/**
	 * Get the authorization server URL from configuration.
	 */
	private String getAuthServerUrl() {
		String authUrl = socialData.getProperty("KEYCLOAK_URL");
		if (authUrl == null || authUrl.isEmpty()) {
			authUrl = socialData.getProperty("EXTERNAL_URL");
			if (authUrl != null && !authUrl.isEmpty()) {
				return authUrl.replaceAll("/+$", "") + "/auth/realms/semoss";
			}
			return "http://localhost:9090/auth/realms/semoss";
		}
		return authUrl;
	}
}

