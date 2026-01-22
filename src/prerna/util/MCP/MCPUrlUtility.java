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
package prerna.util.MCP;

import javax.servlet.http.HttpServletRequest;

import prerna.util.SocialPropertiesUtil;

/**
 * Utility class for MCP URL handling.
 * Provides robust URL detection that works with various proxy configurations:
 * - Cloudflare tunnels
 * - Nginx/Apache reverse proxies
 * - Load balancers
 * - Direct access
 */
public class MCPUrlUtility {

	private static final SocialPropertiesUtil socialData = SocialPropertiesUtil.getInstance();

	/**
	 * Get the external base URL for MCP endpoints.
	 * Handles various proxy scenarios and allows configuration override.
	 *
	 * @param request The HTTP request
	 * @return The external base URL (e.g., "https://example.com/Monolith")
	 */
	public static String getExternalBaseUrl(HttpServletRequest request) {
		// 1. Check for configured URL first (highest priority)
		String configuredUrl = socialData.getProperty("mcp_external_base_url");
		if (configuredUrl != null && !configuredUrl.isEmpty() && !configuredUrl.startsWith("<")) {
			return configuredUrl.endsWith("/")
				? configuredUrl.substring(0, configuredUrl.length() - 1)
				: configuredUrl;
		}

		// 2. Detect scheme (http vs https)
		String scheme = detectScheme(request);

		// 3. Detect host
		String host = detectHost(request, scheme);

		return scheme + "://" + host + request.getContextPath();
	}

	/**
	 * Detect the scheme (http/https) from various proxy headers.
	 */
	private static String detectScheme(HttpServletRequest request) {
		// X-Forwarded-Proto - standard proxy header
		String scheme = request.getHeader("X-Forwarded-Proto");
		if (scheme != null && !scheme.isEmpty()) {
			return scheme;
		}

		// Cf-Visitor - Cloudflare specific header
		String cfVisitor = request.getHeader("Cf-Visitor");
		if (cfVisitor != null && cfVisitor.contains("https")) {
			return "https";
		}

		// X-Forwarded-Ssl
		String forwardedSsl = request.getHeader("X-Forwarded-Ssl");
		if ("on".equalsIgnoreCase(forwardedSsl)) {
			return "https";
		}

		// X-Url-Scheme
		String urlScheme = request.getHeader("X-Url-Scheme");
		if (urlScheme != null && !urlScheme.isEmpty()) {
			return urlScheme;
		}

		// Fall back to request scheme
		return request.getScheme();
	}

	/**
	 * Detect the host from various proxy headers.
	 */
	private static String detectHost(HttpServletRequest request, String scheme) {
		// X-Forwarded-Host - standard proxy header
		String host = request.getHeader("X-Forwarded-Host");
		if (host != null && !host.isEmpty()) {
			// May contain multiple hosts, take the first one
			if (host.contains(",")) {
				host = host.split(",")[0].trim();
			}
			return host;
		}

		// X-Original-Host - some proxies use this
		host = request.getHeader("X-Original-Host");
		if (host != null && !host.isEmpty()) {
			return host;
		}

		// Host header - direct or simple proxy
		host = request.getHeader("Host");
		if (host != null && !host.isEmpty()) {
			return host;
		}

		// Fall back to server name and port
		host = request.getServerName();
		int port = request.getServerPort();

		// Only append port if non-standard
		if ((scheme.equals("http") && port != 80) ||
			(scheme.equals("https") && port != 443)) {
			host += ":" + port;
		}

		return host;
	}

	/**
	 * Get the MCP endpoint URL for a specific toolbox.
	 *
	 * @param request The HTTP request
	 * @param toolboxId The toolbox ID
	 * @return The full MCP endpoint URL
	 */
	public static String getMCPEndpointUrl(HttpServletRequest request, String toolboxId) {
		return getExternalBaseUrl(request) + "/api/ext/mcp/" + toolboxId;
	}

	/**
	 * Get the OAuth authorization server URL.
	 * Supports multiple OIDC providers via configuration.
	 *
	 * @return The authorization server URL
	 */
	public static String getAuthServerUrl() {
		// Try OIDC issuer first (generic)
		String issuerUrl = socialData.getProperty("oidc_issuer_url");
		if (issuerUrl != null && !issuerUrl.isEmpty() && !issuerUrl.startsWith("<")) {
			return issuerUrl;
		}

		// Try Keycloak realm URL
		String keycloakUrl = socialData.getProperty("keycloak_realm_url");
		if (keycloakUrl != null && !keycloakUrl.isEmpty() && !keycloakUrl.startsWith("<")) {
			return keycloakUrl;
		}

		// Try to derive from generic auth URL
		String genericAuthUrl = socialData.getProperty("generic_auth_url");
		if (genericAuthUrl != null && genericAuthUrl.contains("/protocol/openid-connect")) {
			return genericAuthUrl.substring(0, genericAuthUrl.indexOf("/protocol/openid-connect"));
		}

		// Default fallback
		return "https://sso.semoss.org/realms/dev";
	}
}
