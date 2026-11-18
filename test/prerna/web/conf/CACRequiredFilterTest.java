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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import prerna.web.conf.util.CacAudit;

class CACRequiredFilterTest {

	private String previousProperty;

	@BeforeEach
	void requireCac() {
		this.previousProperty = System.getProperty(CACRequiredFilter.REQUIRED_PROPERTY);
		System.setProperty(CACRequiredFilter.REQUIRED_PROPERTY, "true");
	}

	@AfterEach
	void restoreProperty() {
		if (this.previousProperty == null) {
			System.clearProperty(CACRequiredFilter.REQUIRED_PROPERTY);
		} else {
			System.setProperty(CACRequiredFilter.REQUIRED_PROPERTY, this.previousProperty);
		}
	}

	private static HttpServletRequest request(String method, String servletPath, String accept, String remoteAddr,
			X509Certificate[] certs) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn(method);
		when(request.getServletPath()).thenReturn(servletPath);
		when(request.getHeader("Accept")).thenReturn(accept);
		when(request.getRemoteAddr()).thenReturn(remoteAddr);
		when(request.getContextPath()).thenReturn("/Monolith");
		when(request.getAttribute(CACRequiredFilter.CERT_ATTRIBUTE)).thenReturn(certs);
		return request;
	}

	private static CACRequiredFilter filter() throws Exception {
		CACRequiredFilter filter = new CACRequiredFilter();
		filter.init(mock(FilterConfig.class));
		return filter;
	}

	private static HttpServletResponse response(ByteArrayOutputStream sink) throws IOException {
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
			@Override
			public void write(int b) {
				sink.write(b);
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setWriteListener(WriteListener writeListener) {
				// not used
			}
		});
		return response;
	}

	@Test
	void aRequestWithAClientCertificateGoesThrough() throws Exception {
		HttpServletRequest request = request("GET", "/api/engine/runPixel", "application/json", "203.0.113.9",
				new X509Certificate[] { mock(X509Certificate.class) });
		HttpServletResponse response = response(new ByteArrayOutputStream());
		FilterChain chain = mock(FilterChain.class);

		filter().doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
	}

	@Test
	void aBrowserNavigationWithNoCertificateIsSentToTheErrorPage() throws Exception {
		HttpServletRequest request = request("GET", "/", "text/html,application/xhtml+xml", "203.0.113.9", null);
		HttpServletResponse response = response(new ByteArrayOutputStream());
		FilterChain chain = mock(FilterChain.class);

		try (MockedStatic<CacAudit> log = mockStatic(CacAudit.class)) {
			filter().doFilter(request, response, chain);
		}

		verify(chain, never()).doFilter(any(), any());
		verify(response).sendRedirect("/Monolith/cacRequired/");
		verify(response).setHeader("Cache-Control", "no-store");
		verify(response).setHeader("Connection", "close");
	}

	@Test
	void anApiCallWithNoCertificateGetsAJson403() throws Exception {
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		HttpServletRequest request = request("POST", "/api/engine/runPixel", "application/json", "198.51.100.4",
				null);
		HttpServletResponse response = response(sink);
		FilterChain chain = mock(FilterChain.class);

		try (MockedStatic<CacAudit> log = mockStatic(CacAudit.class)) {
			filter().doFilter(request, response, chain);
		}

		verify(chain, never()).doFilter(any(), any());
		verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
		verify(response, never()).sendRedirect(any());
		assertTrue(sink.toString(StandardCharsets.UTF_8).contains("CAC_REQUIRED"));
	}

	@Test
	void aGetForAnAssetWithNoCertificateIsNotRedirected() throws Exception {
		HttpServletRequest request = request("GET", "/static/app.js", "*/*", "198.51.100.4", null);
		HttpServletResponse response = response(new ByteArrayOutputStream());
		FilterChain chain = mock(FilterChain.class);

		try (MockedStatic<CacAudit> log = mockStatic(CacAudit.class)) {
			filter().doFilter(request, response, chain);
		}

		verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
		verify(response, never()).sendRedirect(any());
	}

	@Test
	void theErrorPageAndTheHealthEndpointsAreAlwaysReachable() throws Exception {
		for (String path : new String[] { "/cacRequired/", "/cacRequired/index.html", "/health", "/health/ready" }) {
			HttpServletRequest request = request("GET", path, "text/html", "203.0.113.9", null);
			HttpServletResponse response = response(new ByteArrayOutputStream());
			FilterChain chain = mock(FilterChain.class);

			filter().doFilter(request, response, chain);

			verify(chain).doFilter(request, response);
		}
	}

	@Test
	void aLookalikePathIsNotExempt() {
		assertFalse(CACRequiredFilter.isExemptPath("/cacRequiredEvil"));
		assertFalse(CACRequiredFilter.isExemptPath("/healthcheck-data"));
		assertFalse(CACRequiredFilter.isExemptPath(null));
	}

	@Test
	void theContainerItselfIsExemptButAForwardedForHeaderDoesNotMakeAClientLocal() throws Exception {
		// a callback from this host carries no certificate and must work
		HttpServletRequest local = request("POST", "/api/scheduler/run", "application/json", "127.0.0.1", null);
		FilterChain localChain = mock(FilterChain.class);
		HttpServletResponse localResponse = response(new ByteArrayOutputStream());
		filter().doFilter(local, localResponse, localChain);
		verify(localChain).doFilter(local, localResponse);

		// a remote client that claims to be local through X-Forwarded-For is not
		HttpServletRequest spoofed = request("POST", "/api/scheduler/run", "application/json", "203.0.113.9", null);
		when(spoofed.getHeader("X-FORWARDED-FOR")).thenReturn("127.0.0.1");
		FilterChain spoofedChain = mock(FilterChain.class);
		HttpServletResponse spoofedResponse = response(new ByteArrayOutputStream());
		try (MockedStatic<CacAudit> log = mockStatic(CacAudit.class)) {
			filter().doFilter(spoofed, spoofedResponse, spoofedChain);
		}
		verify(spoofedChain, never()).doFilter(any(), any());
		verify(spoofedResponse).setStatus(HttpServletResponse.SC_FORBIDDEN);
	}

	@Test
	void loopbackExemptionCanBeTurnedOff() throws Exception {
		FilterConfig config = mock(FilterConfig.class);
		when(config.getInitParameter("exemptLoopback")).thenReturn("false");
		CACRequiredFilter filter = new CACRequiredFilter();
		filter.init(config);
		HttpServletRequest request = request("POST", "/api/x", "application/json", "127.0.0.1", null);
		FilterChain chain = mock(FilterChain.class);
		HttpServletResponse response = response(new ByteArrayOutputStream());

		try (MockedStatic<CacAudit> log = mockStatic(CacAudit.class)) {
			filter.doFilter(request, response, chain);
		}

		verify(chain, never()).doFilter(any(), any());
	}

	@Test
	void theFilterDoesNothingUnlessItIsTurnedOn() throws Exception {
		System.clearProperty(CACRequiredFilter.REQUIRED_PROPERTY);
		HttpServletRequest request = request("GET", "/", "text/html", "203.0.113.9", null);
		HttpServletResponse response = response(new ByteArrayOutputStream());
		FilterChain chain = mock(FilterChain.class);

		filter().doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
	}

	@Test
	void theDenialIsWrittenToTheAuditTrailOncePerClientInterval() throws Exception {
		try (MockedStatic<CacAudit> log = mockStatic(CacAudit.class)) {
			for (int i = 0; i < 3; i++) {
				HttpServletRequest request = request("GET", "/", "text/html", "192.0.2.77", null);
				filter().doFilter(request, response(new ByteArrayOutputStream()), mock(FilterChain.class));
			}
			log.verify(() -> CacAudit.record(eq("LOGIN_FAILURE"), any(), any(), any(), eq(false), any()),
					org.mockito.Mockito.times(1));
		}
		assertEquals("/Monolith/cacRequired/", "/Monolith" + CACRequiredFilter.ERROR_PAGE);
	}
}
