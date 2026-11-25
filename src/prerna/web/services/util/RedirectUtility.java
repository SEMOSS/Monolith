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
package prerna.web.services.util;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;
import prerna.util.Utility;

public final class RedirectUtility {

	private static final Logger classLogger = LogManager.getLogger(RedirectUtility.class);

	private RedirectUtility() {
	}

	// returns the target when a browser may be sent there after login, otherwise null (and logs the denial)
	// allowed: relative paths, this request's origin, the application url / social.properties "redirect" origin,
	// social.properties "redirect_allowed_origins" (comma separated origins), or a WHITE_LIST_DOMAINS domain
	public static String allowedReturnUrl(HttpServletRequest request, String target) {
		if (target == null || target.isEmpty()) {
			return null;
		}
		if (!isAllowed(request, target)) {
			classLogger.warn("Refusing redirect to a destination that is not allowed: {}", Utility.cleanLogString(target));
			return null;
		}
		return target;
	}

	// redirect to a path on the origin the browser used, never to a host taken from the request
	public static void sendLocalRedirect(HttpServletResponse response, String pathAndQuery, int status)
			throws IOException {
		// browsers drop tabs and other control characters from urls, so strip all of them first
		String target = pathAndQuery == null ? "" : pathAndQuery.replaceAll("[\\x00-\\x1f\\x7f]", "");
		// a leading // or /\ would be a network-path reference to another host
		target = "/" + target.replaceFirst("^[/\\\\]+", "");
		// set Location directly so the container never makes it absolute from the request host
		response.setStatus(status);
		response.setHeader("Location", target);
	}

	private static boolean isAllowed(HttpServletRequest request, String target) {
		if (target.indexOf('\\') >= 0 || target.chars().anyMatch(c -> c <= 0x20 || c == 0x7f)) {
			return false;
		}
		URI uri;
		try {
			uri = URI.create(target);
		} catch (IllegalArgumentException e) {
			return false;
		}
		if (!uri.isAbsolute()) {
			// relative reference, but not a network-path one (//host)
			return uri.getRawAuthority() == null && !target.startsWith("//");
		}
		String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
		if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null || uri.getRawUserInfo() != null) {
			return false;
		}
		// same origin as this request, or the configured public application url (proxies)
		if (request != null && request.getServerName() != null && origin(uri).equals(requestOrigin(request))) {
			return true;
		}
		if (matchesOrigin(uri, applicationUrl()) || matchesOrigin(uri, socialProperty("redirect"))) {
			return true;
		}
		String extra = socialProperty("redirect_allowed_origins");
		if (extra != null) {
			for (String origin : extra.split(",")) {
				if (matchesOrigin(uri, origin)) {
					return true;
				}
			}
		}
		// an explicit domain whitelist stays a valid way to allow other hosts
		String whiteList = Utility.getDIHelperProperty(Constants.WHITE_LIST_DOMAINS);
		if (whiteList != null && !whiteList.trim().isEmpty()) {
			try {
				WebUtility.checkIfValidDomain(target);
				return true;
			} catch (IllegalArgumentException | IllegalStateException e) {
				return false;
			}
		}
		return false;
	}

	// social.properties may not be loaded (minimal installs, tests); treat that as not configured
	private static String socialProperty(String key) {
		try {
			return SocialPropertiesUtil.getInstance().getProperty(key);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String applicationUrl() {
		try {
			return Utility.getApplicationUrl();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String requestOrigin(HttpServletRequest request) {
		String scheme = request.getScheme() == null ? "" : request.getScheme().toLowerCase(Locale.ROOT);
		int port = request.getServerPort() > 0 ? request.getServerPort() : ("https".equals(scheme) ? 443 : 80);
		return scheme + "://" + request.getServerName().toLowerCase(Locale.ROOT) + ":" + port;
	}

	private static boolean matchesOrigin(URI uri, String configured) {
		if (configured == null || configured.isBlank()) {
			return false;
		}
		try {
			URI other = URI.create(configured.trim());
			return other.getHost() != null && origin(uri).equals(origin(other));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static String origin(URI uri) {
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		int port = uri.getPort() != -1 ? uri.getPort() : ("https".equals(scheme) ? 443 : 80);
		return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
	}
}
