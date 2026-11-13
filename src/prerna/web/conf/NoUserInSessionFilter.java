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

import org.owasp.encoder.Encode;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import prerna.auth.User;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;
import prerna.web.services.util.WebUtility;

public class NoUserInSessionFilter implements Filter {

	private static final String NO_USER_HTML = "/noUserFail/";

	// programs, not browsers, call these APIs, so a missing or bad key is answered
	// with a 401 in each API's own error shape instead of a login redirect
	private static final String UNAUTHORIZED_MESSAGE = "Invalid or missing API key. "
			+ "Send your access key and secret key as accessKey:secretKey.";
	private static final String OPENAI_UNAUTHORIZED = "{\"error\":{\"message\":\"" + UNAUTHORIZED_MESSAGE
			+ "\",\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}";
	private static final String ANTHROPIC_UNAUTHORIZED = "{\"type\":\"error\",\"error\":{\"type\":"
			+ "\"authentication_error\",\"message\":\"" + UNAUTHORIZED_MESSAGE + "\"}}";
	private static final String OLLAMA_UNAUTHORIZED = "{\"error\":\"" + UNAUTHORIZED_MESSAGE + "\"}";

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
			throws IOException, ServletException {
		ServletContext context = arg0.getServletContext();

		if (!ResourceUtility.canAccessWithoutLogin((HttpServletRequest) arg0)) {
			// due to FE being annoying
			// we need to push a response for this one end point
			// since security is embedded w/ normal semoss and not standalone

			HttpSession session = ((HttpServletRequest) arg0).getSession(false);
			User user = null;
			if (session != null) {
				// System.out.println("Session ID >> " + session.getId());
				user = (User) session.getAttribute(Constants.SESSION_USER);
			}

			if (user == null || user.getLogins().isEmpty()) {
				String apiError = apiClientUnauthorizedBody((HttpServletRequest) arg0);
				if (apiError != null) {
					HttpServletResponse response = (HttpServletResponse) arg1;
					response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
					response.setHeader("WWW-Authenticate", "Bearer");
					response.setContentType("application/json");
					response.setCharacterEncoding("UTF-8");
					response.getWriter().write(apiError);
					return;
				}
				// don't know what i am redirecting
				// send back an error and have the client remake the request
				setInvalidEntryRedirect(context, arg0, arg1);
				return;
			}
		}

		arg2.doFilter(arg0, arg1);
	}

	/**
	 * Returns the 401 body for a call to one of the APIs programs use, in that API's
	 * error shape, or null for any other request.
	 *
	 * @param request the request
	 * @return the error body, or null when the request should be redirected
	 */
	private static String apiClientUnauthorizedBody(HttpServletRequest request) {
		String path = ResourceUtility.servletRelativePath(request);
		if (isUnder(path, "model/anthropic")) {
			return ANTHROPIC_UNAUTHORIZED;
		}
		if (isUnder(path, "model/ollama")) {
			return OLLAMA_UNAUTHORIZED;
		}
		if (isUnder(path, "model/openai") || isUnder(path, "ext")) {
			return OPENAI_UNAUTHORIZED;
		}
		return null;
	}

	private static boolean isUnder(String path, String prefix) {
		return path.equals(prefix) || path.startsWith(prefix + "/");
	}

	/**
	 * Method to determine where to redirect the user
	 * 
	 * @param context
	 * @param arg0
	 * @param arg1
	 * @throws IOException
	 */
	private void setInvalidEntryRedirect(ServletContext context, ServletRequest arg0, ServletResponse arg1)
			throws IOException {
		String fullUrl = WebUtility.cleanHttpResponse(((HttpServletRequest) arg0).getRequestURL().toString());
		((HttpServletResponse) arg1).setStatus(302);
		String redirectUrl = ((HttpServletRequest) arg0).getHeader("referer");
		// if no referrer
		// then a person hit the endpoint directly
		if (redirectUrl == null) {
			((HttpServletRequest) arg0).getSession(true).setAttribute(Constants.ENDPOINT_REDIRECT_KEY, fullUrl);
			// this will be the deployment name of the app
			String loginRedirect = SocialPropertiesUtil.getInstance().getLoginRedirect();
			((HttpServletResponse) arg1).setStatus(302);
			if (loginRedirect != null) {
				((HttpServletResponse) arg1).sendRedirect(loginRedirect);
			} else {
				String contextPath = context.getContextPath();
				redirectUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length()) + NO_USER_HTML;
				((HttpServletResponse) arg1).sendRedirect(redirectUrl);
			}
		} else {
			// are we in public home - if no, we dont include ! in the redirect
			redirectUrl = redirectUrl + WebUtility.determineLoginExtension((HttpServletRequest) arg0);
			String encodedRedirectUrl = Encode.forHtml(redirectUrl);
			((HttpServletResponse) arg1).setHeader("redirect", encodedRedirectUrl);
			((HttpServletResponse) arg1).sendError(302, "Need to redirect to " + encodedRedirectUrl);

			// invalidate the session if necessary
			WebUtility.dropSessionWithoutUser((HttpServletRequest) arg0, null);
		}
	}

	@Override
	public void destroy() {
		// destroy
	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		// initialize
	}

}
