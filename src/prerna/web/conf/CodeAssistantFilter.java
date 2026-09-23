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
package prerna.web.conf;

import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpSession;
import prerna.auth.User;
import prerna.auth.utils.SecurityUserAccessKeyUtils;
import prerna.om.LocalUserStore;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

public class CodeAssistantFilter implements Filter {

	private static final Logger classLogger = LogManager.getLogger(CodeAssistantFilter.class);

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
			throws IOException, ServletException {
		HttpServletRequest request = (HttpServletRequest) arg0;
		HttpSession session = request.getSession(false);
		User user = null;
		if (session != null) {
			user = (User) session.getAttribute(Constants.SESSION_USER);
		}

		if (user != null) {
			arg2.doFilter(arg0, arg1);
			return;
		}

		String remoteAddr = getClientIpAddress(request);
		if (!isLocalhost(remoteAddr)) {
			arg2.doFilter(arg0, arg1);
			return;
		}

		// We pass our access key and secret key in an f string separated by a colon
		// (ex. f"{access_key}:{secret_key}") with an optional ":room-{roomId}" segment.
		// OpenAI clients send this as the "Bearer " Authorization header.
		// Anthropic clients send the same value as the x-api-key header.
		String token = null;
		String authValue = request.getHeader("Authorization");
		if (authValue == null) {
			authValue = request.getHeader("authorization");
		}
		if (authValue != null && (authValue.startsWith("Bearer") || authValue.startsWith("bearer"))) {
			token = authValue.substring("Bearer".length()).trim();
		}
		if (token == null || token.isEmpty()) {
			String apiKeyHeader = request.getHeader("x-api-key");
			if (apiKeyHeader != null) {
				token = apiKeyHeader.trim();
			}
		}

		if (token == null || token.isEmpty()) {
			// no token? just go through and other filters will validate
			arg2.doFilter(arg0, arg1);
			return;
		}

		processCredentialToken(request, token);

		// doesn't matter if we made a user or didn't
		// we will continue the filter chain because the {@link NoUserInSessionFilter}
		// will catch unauthorized access

		// wrap the request to allow subsequent reading
		HttpServletRequestWrapper requestWrapper = new HttpServletRequestWrapper(request);
		arg2.doFilter(requestWrapper, arg1);
	}

	private void processCredentialToken(HttpServletRequest request, String token) {
		String[] split = token.split(":");
		if (split == null || (split.length != 2 && split.length != 3)) {
			return;
		}

		String accessKey = split[0];
		String secretKey = split[1];
		// Optional 3rd segment: "room-{roomId}" for linking sub-conversations
		if (split.length == 3 && split[2].startsWith("room-")) {
			request.setAttribute("roomId", split[2].substring(5));
		}

		if (!LocalUserStore.getInstance().validate(accessKey, secretKey)) {
			return;
		}

		User user = null;
		try {
			user = SecurityUserAccessKeyUtils.validateLocalUserStore(accessKey, secretKey);
		} catch (IllegalAccessException e) {
			classLogger.error("Failed to validate the local user store access key for code assistant login", e);
		}

		if (user != null) {
			SecurityUserAccessKeyUtils.updateAccessTokenLastUsed(accessKey);
			HttpSession session = request.getSession(true);
			session.setAttribute(Constants.SESSION_USER, user);
			session.setAttribute(Constants.SESSION_USER_ID_LOG, user.getPrimaryLoginToken().getId());
			WebUtility.loggingContextLoginEvent(session);

			classLogger.info("User is logging in for code assistance using provider {} with user access key",
					user.getPrimaryLoginToken().getProvider());
		}
	}

	private String getClientIpAddress(HttpServletRequest request) {
		// Check for proxied requests
		String xForwardedFor = request.getHeader("X-Forwarded-For");
		if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
			return xForwardedFor.split(",")[0].trim();
		}

		String xRealIp = request.getHeader("X-Real-IP");
		if (xRealIp != null && !xRealIp.isEmpty()) {
			return xRealIp;
		}

		return request.getRemoteAddr();
	}

	private boolean isLocalhost(String ipAddress) {
		return "127.0.0.1".equals(ipAddress) || "0:0:0:0:0:0:0:1".equals(ipAddress) || "::1".equals(ipAddress)
				|| "localhost".equalsIgnoreCase(ipAddress);
	}

	@Override
	public void destroy() {
		// destroy

	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		// init
	}

}
