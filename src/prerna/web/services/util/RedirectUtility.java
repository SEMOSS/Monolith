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

import jakarta.servlet.http.HttpServletResponse;
import prerna.util.SocialPropertiesUtil;

/** Destination authorization for browser redirects, separate from header encoding. */
public final class RedirectUtility {
    private RedirectUtility() { }

    /**
     * The configured frontend origin is trusted by default. Additional return
     * origins may be listed in social.properties as redirect_allowed_origins.
     * Relative URLs retain their existing browser resolution behavior.
     */
    public static String samlReturnUrl(String target) {
        SocialPropertiesUtil properties = SocialPropertiesUtil.getInstance();
        return allowedReturnUrl(target, properties.getProperty("redirect"),
                properties.getProperty("redirect_allowed_origins"));
    }

    static String allowedReturnUrl(String target, String loginUrl, String allowedOrigins) {
        URI uri = parseTarget(target);
        if (!uri.isAbsolute()) {
            if (uri.getRawAuthority() != null || target.startsWith("//")) {
                throw new IllegalArgumentException("Network-path redirects are not allowed");
            }
            return target;
        }
        String origin = httpOrigin(uri);
        if (loginUrl != null && !loginUrl.isBlank()) {
            URI login = parseTarget(loginUrl.trim());
            if (login.isAbsolute() && origin.equals(httpOrigin(login))) {
                return target;
            }
        }
        if (allowedOrigins != null) {
            for (String allowed : allowedOrigins.split(",")) {
                if (!allowed.isBlank() && origin.equals(configuredOrigin(allowed.trim()))) {
                    return target;
                }
            }
        }
        throw new IllegalArgumentException("Redirect origin is not allowed");
    }

    /**
     * Preserve absolute API redirects and their status codes. A configured public
     * API origin replaces the request-derived authority; otherwise that authority
     * must match the frontend origin or redirect_allowed_origins.
     */
    public static void sendRequestRedirect(HttpServletResponse response, String target, int status)
            throws IOException {
        String safe;
        try {
            SocialPropertiesUtil properties = SocialPropertiesUtil.getInstance();
            safe = requestReturnUrl(target, properties.getProperty("redirect"),
                    properties.getProperty("redirect_allowed_origins"),
                    properties.getProperty("redirect_api_origin"));
        } catch (IllegalArgumentException e) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Redirect origin is not allowed");
            return;
        }
        if (status == HttpServletResponse.SC_MOVED_TEMPORARILY) {
            response.sendRedirect(safe);
        } else {
            response.setStatus(status);
            response.setHeader("Location", WebUtility.responseHeaderValue(safe));
        }
    }

    static String requestReturnUrl(String target, String loginUrl, String allowedOrigins, String apiOrigin) {
        URI uri = parseTarget(target);
        httpOrigin(uri); // Request return URLs must be absolute HTTP(S) URLs.
        if (apiOrigin != null && !apiOrigin.isBlank()) {
            String configured = apiOrigin.trim();
            configuredOrigin(configured); // Require an origin, not a path or query.
            URI origin = URI.create(configured);
            return origin.getScheme() + "://" + origin.getRawAuthority() + uri.getRawPath()
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
        }
        return allowedReturnUrl(target, loginUrl, allowedOrigins);
    }

    private static URI parseTarget(String value) {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0
                || value.chars().anyMatch(c -> c <= 0x20 || c == 0x7f)) {
            throw new IllegalArgumentException("Invalid redirect URL");
        }
        return URI.create(value);
    }

    private static String configuredOrigin(String value) {
        URI uri = parseTarget(value);
        if ((uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Redirect allowlist entries must be origins");
        }
        return httpOrigin(uri);
    }

    private static String httpOrigin(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Redirect must use an HTTP(S) origin without user information");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        int port = uri.getPort() == -1 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }
}
