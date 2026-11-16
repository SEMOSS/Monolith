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
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import prerna.logging.SemossLogUtils;
import prerna.web.services.util.WebUtility;

/**
 * Requires a validated CAC/PIV client certificate on every request.
 * <p>
 * The HTTPS connector asks for a client certificate but does not insist on one
 * ({@code clientAuth="want"}), because a connector that insists ends the TLS
 * handshake and the browser then shows its own error with no way to tell the
 * user what to do. This filter makes the requirement at the application layer
 * instead: a request with no client certificate never reaches the login page,
 * the session filters or any endpoint. A browser navigation is redirected to a
 * static explanation page ({@value #ERROR_PAGE}) that links back to the site so
 * the user can try again once the card is in the reader; any other request gets
 * a 403 with a short JSON body.
 * <p>
 * The certificate is only read from the request attribute the connector sets
 * after it has validated the chain against its CAC trust store; this filter
 * does not validate a chain itself. A deployment must therefore give the
 * connector a trust store of the DoD PKI CAs it accepts.
 * <p>
 * The filter is off unless the {@value #REQUIRED_PROPERTY} system property is
 * {@code true}, so other deployments of this web.xml are not affected.
 * <p>
 * Init parameters:
 * <ul>
 * <li>{@code exemptLoopback} (default {@code true}): requests whose TCP peer is
 * the container itself, such as the scheduler or a local worker calling back
 * into the platform, carry no certificate and are let through. This looks only
 * at the socket's peer address, never at {@code X-Forwarded-For}, which a
 * client can set.</li>
 * </ul>
 * The health endpoints and the error page are always reachable.
 */
public class CACRequiredFilter implements Filter {

	/** The system property that turns the requirement on; set from CAC_PIV_REQUIRED. */
	public static final String REQUIRED_PROPERTY = "cac.piv.required";

	/** The request attribute the connector fills with the validated client chain. */
	static final String CERT_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

	/** Where a browser is sent when it presented no certificate. */
	static final String ERROR_PAGE = "/cacRequired/";

	private static final String ERROR_PAGE_PATH = "/cacRequired";
	private static final String EXEMPT_LOOPBACK = "exemptLoopback";
	private static final String HEALTH_PATH = "/health";

	/** One audit entry per client per interval, so a client cannot flood the audit log. */
	private static final long AUDIT_INTERVAL_MS = 60_000L;
	private static final int MAX_TRACKED_CLIENTS = 10_000;
	private static final ConcurrentHashMap<String, Long> LAST_AUDITED = new ConcurrentHashMap<>();

	private boolean exemptLoopback = true;

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		String value = filterConfig == null ? null : filterConfig.getInitParameter(EXEMPT_LOOPBACK);
		if (value != null) {
			this.exemptLoopback = Boolean.parseBoolean(value.trim());
		}
	}

	@Override
	public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
			throws IOException, ServletException {
		if (!isRequired() || !(req instanceof HttpServletRequest) || !(res instanceof HttpServletResponse)) {
			chain.doFilter(req, res);
			return;
		}
		HttpServletRequest request = (HttpServletRequest) req;
		HttpServletResponse response = (HttpServletResponse) res;

		if (isExemptPath(request.getServletPath()) || (this.exemptLoopback && isLoopback(request.getRemoteAddr()))
				|| hasClientCertificate(request)) {
			chain.doFilter(req, res);
			return;
		}

		audit(request);
		deny(request, response);
	}

	@Override
	public void destroy() {
		// nothing to release
	}

	private static boolean isRequired() {
		return Boolean.parseBoolean(System.getProperty(REQUIRED_PROPERTY, "false").trim());
	}

	static boolean isExemptPath(String servletPath) {
		if (servletPath == null) {
			return false;
		}
		return servletPath.equals(HEALTH_PATH) || servletPath.startsWith(HEALTH_PATH + "/")
				|| servletPath.equals(ERROR_PAGE_PATH) || servletPath.startsWith(ERROR_PAGE_PATH + "/");
	}

	/**
	 * Whether the socket's peer is this host. Reads the peer address only; the
	 * forwarded-for header is client controlled and is never consulted.
	 */
	static boolean isLoopback(String remoteAddr) {
		if (remoteAddr == null) {
			return false;
		}
		String addr = remoteAddr.trim();
		return addr.startsWith("127.") || addr.equals("::1") || addr.equals("0:0:0:0:0:0:0:1");
	}

	private static boolean hasClientCertificate(HttpServletRequest request) {
		Object certs = request.getAttribute(CERT_ATTRIBUTE);
		return certs instanceof X509Certificate[] && ((X509Certificate[]) certs).length > 0;
	}

	private static void deny(HttpServletRequest request, HttpServletResponse response) throws IOException {
		// every denial must stay out of caches and close the connection, so the
		// browser's next attempt - after the card is in the reader - makes a new
		// TLS handshake and offers the certificate prompt again
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("Pragma", "no-cache");
		response.setHeader("Connection", "close");

		if (isBrowserNavigation(request)) {
			response.sendRedirect(request.getContextPath() + ERROR_PAGE);
			return;
		}

		byte[] body = ("{\"errorType\":\"CAC_REQUIRED\",\"errorMessage\":\"A CAC or PIV client certificate is "
				+ "required to use this site.\"}").getBytes(StandardCharsets.UTF_8);
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.setContentLength(body.length);
		response.getOutputStream().write(body);
	}

	/** A GET or HEAD for a page, as opposed to an API call or an asset fetch. */
	static boolean isBrowserNavigation(HttpServletRequest request) {
		String method = request.getMethod();
		if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
			return false;
		}
		String servletPath = request.getServletPath();
		if (servletPath != null && servletPath.startsWith("/api/")) {
			return false;
		}
		String accept = request.getHeader("Accept");
		return accept != null && accept.toLowerCase(Locale.ROOT).contains("text/html");
	}

	private static void audit(HttpServletRequest request) {
		String clientIp = WebUtility.getClientIp(request);
		long now = System.currentTimeMillis();
		if (LAST_AUDITED.size() >= MAX_TRACKED_CLIENTS) {
			LAST_AUDITED.clear();
		}
		Long last = LAST_AUDITED.get(clientIp);
		if (last != null && now - last < AUDIT_INTERVAL_MS) {
			return;
		}
		LAST_AUDITED.put(clientIp, now);
		SemossLogUtils.logSecurityAuditEvent("LOGIN_FAILURE", null, null, clientIp, null, null, null, false,
				"a CAC/PIV client certificate is required and none was presented", null);
	}
}
