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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import prerna.util.Utility;

/**
 * Turns the client certificate an AWS Application Load Balancer passes in a header back into the
 * request attribute {@code CACFilter}, {@code PIVFilter} and {@code CACRequiredFilter} read.
 * <p>
 * Behind an ALB the TLS connection ends at the load balancer, so Tomcat never sees the card. With
 * mutual TLS in <em>verify</em> mode the ALB checks the certificate chain against its trust store
 * and then forwards the leaf certificate in the {@code X-Amzn-Mtls-Clientcert-Leaf} header, as URL
 * encoded PEM in which {@code + = /} are left as they are. This filter decodes that header and
 * presents the certificate to the rest of the chain exactly as the connector would have.
 * <p>
 * Because a header is only as trustworthy as whoever sent it, the filter is strict:
 * <ul>
 * <li>It does nothing unless the {@value #ENABLED_PROPERTY} system property is {@code true}, so a
 * deployment that has no ALB in front never honors the header.</li>
 * <li>It honors the header only when the TCP peer of the request is inside one of the CIDR ranges
 * in the {@value #TRUSTED_PEERS_PROPERTY} system property (the ALB's subnets). With the filter
 * enabled and no usable range, nothing is trusted. The peer is the socket's address, never
 * {@code X-Forwarded-For}, which a client can set.</li>
 * <li>A certificate the connector already supplied (a direct TLS connection) is never replaced.</li>
 * <li>A header that is malformed, too large, or holds an expired or not yet valid certificate is
 * ignored, so the request continues without a certificate and is refused further down.</li>
 * </ul>
 * The ALB's own verification (trust store and, if configured, revocation lists) is what makes the
 * certificate trustworthy; this filter does not validate a chain. The tasks must therefore accept
 * connections only from the ALB.
 */
public class AlbClientCertFilter implements Filter {

	private static final Logger classLogger = LogManager.getLogger(AlbClientCertFilter.class);

	/** Turns the filter on; set from ALB_MTLS_ENABLED. */
	public static final String ENABLED_PROPERTY = "alb.mtls.enabled";
	/** Comma separated CIDR ranges (or single addresses) the ALB's requests come from. */
	public static final String TRUSTED_PEERS_PROPERTY = "alb.mtls.trusted.cidrs";
	/** Overrides the header name; defaults to {@value #DEFAULT_HEADER}. */
	public static final String HEADER_PROPERTY = "alb.mtls.cert.header";

	static final String DEFAULT_HEADER = "X-Amzn-Mtls-Clientcert-Leaf";
	static final String CERT_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

	private static final int MAX_HEADER_CHARS = 16 * 1024;
	private static final long WARN_INTERVAL_MS = 60_000L;
	private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

	private final AtomicLong lastWarning = new AtomicLong();
	private volatile Clock clock = Clock.systemUTC();

	/** For tests: the time used to judge a certificate's validity. */
	void setClock(Clock clock) {
		this.clock = clock;
	}

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		if (isEnabled() && parseCidrs(System.getProperty(TRUSTED_PEERS_PROPERTY)).isEmpty()) {
			classLogger.error("{} is true but {} has no usable range, so the load balancer's certificate "
					+ "header will not be trusted", ENABLED_PROPERTY, TRUSTED_PEERS_PROPERTY);
		}
	}

	@Override
	public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
			throws IOException, ServletException {
		if (isEnabled() && req instanceof HttpServletRequest) {
			applyHeader((HttpServletRequest) req);
		}
		chain.doFilter(req, res);
	}

	@Override
	public void destroy() {
		// nothing to release
	}

	private static boolean isEnabled() {
		return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false").trim());
	}

	private void applyHeader(HttpServletRequest request) {
		if (request.getAttribute(CERT_ATTRIBUTE) != null) {
			return; // the connector already has the client's certificate
		}
		String headerName = System.getProperty(HEADER_PROPERTY, DEFAULT_HEADER).trim();
		String value = request.getHeader(headerName);
		if (value == null || value.isBlank()) {
			return;
		}
		if (!isTrustedPeer(request.getRemoteAddr())) {
			warn("A request from {} sent the {} header but is not from a trusted load balancer address; ignoring it",
					Utility.cleanLogString(request.getRemoteAddr()), headerName);
			return;
		}
		if (value.length() > MAX_HEADER_CHARS) {
			warn("The {} header is larger than {} characters; ignoring it", headerName, MAX_HEADER_CHARS);
			return;
		}
		X509Certificate certificate = parseCertificate(value);
		if (certificate == null) {
			warn("The {} header did not hold a readable certificate; ignoring it", headerName);
			return;
		}
		try {
			certificate.checkValidity(Date.from(this.clock.instant()));
		} catch (CertificateException e) {
			warn("The certificate in the {} header is not valid now ({}); ignoring it", headerName,
					e.getClass().getSimpleName());
			return;
		}
		request.setAttribute(CERT_ATTRIBUTE, new X509Certificate[] { certificate });
	}

	// ---------------------------------------------------------------------------------------------
	// the certificate
	// ---------------------------------------------------------------------------------------------

	/** The leaf certificate in the ALB's header form, or null when it cannot be read. */
	static X509Certificate parseCertificate(String headerValue) {
		try {
			byte[] pem = percentDecode(headerValue).getBytes(StandardCharsets.US_ASCII);
			CertificateFactory factory = CertificateFactory.getInstance("X.509");
			return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(pem));
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Decodes %XX escapes only. The ALB leaves {@code + = /} unescaped because they are base64, so
	 * a form decoder, which turns {@code +} into a space, would corrupt the certificate.
	 */
	static String percentDecode(String value) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '%') {
				if (i + 2 >= value.length()) {
					throw new IllegalArgumentException("truncated escape");
				}
				int high = Character.digit(value.charAt(i + 1), 16);
				int low = Character.digit(value.charAt(i + 2), 16);
				if (high < 0 || low < 0) {
					throw new IllegalArgumentException("bad escape");
				}
				out.write((high << 4) | low);
				i += 2;
			} else if (c < 0x80) {
				out.write(c);
			} else {
				throw new IllegalArgumentException("non ASCII character");
			}
		}
		return out.toString(StandardCharsets.US_ASCII);
	}

	// ---------------------------------------------------------------------------------------------
	// who may send the header
	// ---------------------------------------------------------------------------------------------

	static boolean isTrustedPeer(String peer) {
		List<byte[][]> ranges = parseCidrs(System.getProperty(TRUSTED_PEERS_PROPERTY));
		if (ranges.isEmpty() || peer == null) {
			return false;
		}
		byte[] address = literal(peer.startsWith("::ffff:") ? peer.substring(7) : peer);
		if (address == null) {
			return false;
		}
		for (byte[][] range : ranges) {
			byte[] network = range[0];
			int bits = range[1][0] & 0xFF;
			if (network.length == address.length && matches(network, address, bits)) {
				return true;
			}
		}
		return false;
	}

	private static boolean matches(byte[] network, byte[] address, int bits) {
		int whole = bits / 8;
		for (int i = 0; i < whole; i++) {
			if (network[i] != address[i]) {
				return false;
			}
		}
		int rest = bits % 8;
		if (rest == 0) {
			return true;
		}
		int mask = (0xFF << (8 - rest)) & 0xFF;
		return (network[whole] & mask) == (address[whole] & mask);
	}

	/** Each entry is {network address, {prefix length}}; unusable entries are skipped. */
	static List<byte[][]> parseCidrs(String text) {
		List<byte[][]> ranges = new ArrayList<>();
		if (text == null) {
			return ranges;
		}
		for (String part : text.split(",")) {
			String entry = part.trim();
			if (entry.isEmpty()) {
				continue;
			}
			String host = entry;
			Integer prefix = null;
			int slash = entry.indexOf('/');
			if (slash >= 0) {
				host = entry.substring(0, slash);
				try {
					prefix = Integer.valueOf(entry.substring(slash + 1).trim());
				} catch (NumberFormatException e) {
					classLogger.warn("Ignoring the unusable trusted range {}", Utility.cleanLogString(entry));
					continue;
				}
			}
			byte[] network = literal(host);
			if (network == null) {
				classLogger.warn("Ignoring the unusable trusted range {}", Utility.cleanLogString(entry));
				continue;
			}
			int max = network.length * 8;
			int bits = prefix == null ? max : prefix;
			if (bits < 0 || bits > max) {
				classLogger.warn("Ignoring the unusable trusted range {}", Utility.cleanLogString(entry));
				continue;
			}
			ranges.add(new byte[][] { network, { (byte) bits } });
		}
		return ranges;
	}

	/** The bytes of an IPv4 or IPv6 literal, or null. Never does a DNS lookup. */
	private static byte[] literal(String text) {
		String value = text.trim();
		if (IPV4.matcher(value).matches()) {
			String[] octets = value.split("\\.");
			byte[] out = new byte[4];
			for (int i = 0; i < 4; i++) {
				int octet = Integer.parseInt(octets[i]);
				if (octet > 255) {
					return null;
				}
				out[i] = (byte) octet;
			}
			return out;
		}
		if (value.indexOf(':') >= 0 && value.matches("[0-9A-Fa-f:.]+")) {
			try {
				return InetAddress.getByName(value).getAddress();
			} catch (UnknownHostException e) {
				return null;
			}
		}
		return null;
	}

	private void warn(String message, Object... arguments) {
		long now = System.currentTimeMillis();
		long last = this.lastWarning.get();
		if (now - last >= WARN_INTERVAL_MS && this.lastWarning.compareAndSet(last, now)) {
			classLogger.warn(message, arguments);
		}
	}
}
