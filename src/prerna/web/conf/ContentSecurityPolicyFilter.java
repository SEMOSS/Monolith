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

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Adds a Content-Security-Policy header to every response - Tomcat's own
 * {@code org.apache.catalina.filters.HttpHeaderSecurityFilter} (see
 * HeaderSecurityFilter in web.xml) covers HSTS, anti-clickjacking and
 * content-type-sniffing, but does not support CSP.
 *
 * <p>
 * This exists for DoD mobile-code control SC-18 (acquisition, use and
 * execution of active content such as JavaScript): SEMOSS is an app-building
 * platform - users publish their own small apps (see the platform__* project
 * templates) that get served under this origin, not just one fixed admin UI.
 * A survey of every shipped template found no third-party script references
 * anywhere, but user-customized published apps are outside what this filter's
 * default policy was verified against, which is exactly why this defaults to
 * report-only rather than blocking: it is deliberately not container-build
 * work to decide this is enforceable everywhere without a site actually
 * reviewing what it would have blocked. See docker/il4/README.md.
 * </p>
 *
 * <p>
 * Default policy intent: {@code object-src 'none'} and no {@code unsafe-eval}
 * directly target Category 1/2 mobile code (plugins, Flash, Java applets) -
 * historically what SC-18 was written against. {@code script-src 'self'}
 * restricts first-party JavaScript (Category 3, DoDI 8552.01 - generally
 * permitted) to this origin only, so an XSS payload or a published app
 * modified to load a remote script would be reported. {@code style-src}
 * allows {@code 'unsafe-inline'} because inline CSS-in-JS is common across
 * the shipped templates and is a materially lower execution risk than inline
 * script.
 * </p>
 */
public class ContentSecurityPolicyFilter implements Filter {

	private static final String REPORT_ONLY = "reportOnly";
	private static final String POLICY = "policy";

	private static final String DEFAULT_POLICY = String.join("; ",
			"default-src 'self'",
			"script-src 'self'",
			"style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
			"font-src 'self' data: https://fonts.gstatic.com",
			"img-src 'self' data: https:",
			"object-src 'none'",
			"base-uri 'self'",
			"frame-ancestors 'self'");

	private boolean reportOnly = true;
	private String policy = DEFAULT_POLICY;

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		String reportOnlyParam = filterConfig.getInitParameter(REPORT_ONLY);
		if (reportOnlyParam != null) {
			this.reportOnly = Boolean.parseBoolean(reportOnlyParam);
		}
		String policyParam = filterConfig.getInitParameter(POLICY);
		if (policyParam != null && !policyParam.isBlank()) {
			this.policy = policyParam;
		}
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (response instanceof HttpServletResponse httpResponse) {
			String headerName = this.reportOnly ? "Content-Security-Policy-Report-Only" : "Content-Security-Policy";
			httpResponse.setHeader(headerName, this.policy);
		}
		chain.doFilter(request, response);
	}

	@Override
	public void destroy() {
		// nothing to clean up
	}

}
