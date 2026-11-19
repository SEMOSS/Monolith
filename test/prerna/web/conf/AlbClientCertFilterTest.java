package prerna.web.conf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class AlbClientCertFilterTest {

	/** A throwaway self-signed test certificate, valid for 100 years. Its private key was discarded. */
	private static final String LONG_LIVED = """
-----BEGIN CERTIFICATE-----
MIIDaDCCAlACCQCmB2hCb31HHDANBgkqhkiG9w0BAQsFADB1MQswCQYDVQQGEwJV
UzEYMBYGA1UECgwPVS5TLiBHb3Zlcm5tZW50MQwwCgYDVQQLDANEb0QxDDAKBgNV
BAsMA1BLSTEMMAoGA1UECwwDVVNBMSIwIAYDVQQDDBlET0UuSk9ITi5BTExFTi4x
MjM0NTY3ODkwMCAXDTI2MTExOTAxMjUyMloYDzIxMjYxMDI2MDEyNTIyWjB1MQsw
CQYDVQQGEwJVUzEYMBYGA1UECgwPVS5TLiBHb3Zlcm5tZW50MQwwCgYDVQQLDANE
b0QxDDAKBgNVBAsMA1BLSTEMMAoGA1UECwwDVVNBMSIwIAYDVQQDDBlET0UuSk9I
Ti5BTExFTi4xMjM0NTY3ODkwMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKC
AQEApmwVu6/WDAlheglow4LBbSpo2opqjoUifJvV0FLkaNlVzd9AUGiCG3RjNCq6
fw44/TXU7aQHv37lLvfl7b+M4A8mWxsM5dPg3RPqi/4O1oLLpJ4NEoD28QjjAC33
rSNsP10uhY4t3Z+L72uMs1eGpIAwveTUAx5ERKAk1hJ/hCrksnCQte6nOoeli/ce
Lx8jXfM74nbTdv7eLR/2o6u6nSG1dZydu4tUmuB4/Yh191QFxqrv52FFisptRvcx
vro9plyGGp1wvwZkWiuLt1ELD2PIDMXmzAYnx5iHBbcCQK3ZifXJ+URCl0nhkKNq
VuoPNAhPJyybvLXDXPsuCGpnhwIDAQABMA0GCSqGSIb3DQEBCwUAA4IBAQBmoG9q
aNeKBn54xxQJwazEP3OQ6pSuJoMRz+dO3zRNjSgdig6ZR26KJOd8VGSq/ZF7Qm85
3j76973W9jF8rBH7G8Pqelv07ddQYI9153+L+n9CD6RCvIHZ2enKQyf+6Q0MtEPh
loPdqfEQJd3jP5860wqfE80scMRBI9F2Zxo1B/A/F+MtpX/EJqh/4wYqXEws6K3f
qsdW5W2hVW0SohFaGg0DNcyhQFj/DsP3hzCNYwIYoejGTMKcwID+XXBcVYm7VO5Q
UZvlPIj8mSIDa9C67d4Pf5xaM0OJbKSWiS4etbG7WI4Lt2W6vn4V6aACOjYDOUU1
LZZHOVS3NDsGl3V/
-----END CERTIFICATE-----
""";
	/** A throwaway test certificate that was valid for one day from 2026-11-19. */
	private static final String ONE_DAY = """
-----BEGIN CERTIFICATE-----
MIIDZjCCAk4CCQCldpZx+tuZ1jANBgkqhkiG9w0BAQsFADB1MQswCQYDVQQGEwJV
UzEYMBYGA1UECgwPVS5TLiBHb3Zlcm5tZW50MQwwCgYDVQQLDANEb0QxDDAKBgNV
BAsMA1BLSTEMMAoGA1UECwwDVVNBMSIwIAYDVQQDDBlET0UuSk9ITi5BTExFTi4x
MjM0NTY3ODkwMB4XDTI2MTExOTAxMjUyM1oXDTI2MTEyMDAxMjUyM1owdTELMAkG
A1UEBhMCVVMxGDAWBgNVBAoMD1UuUy4gR292ZXJubWVudDEMMAoGA1UECwwDRG9E
MQwwCgYDVQQLDANQS0kxDDAKBgNVBAsMA1VTQTEiMCAGA1UEAwwZRE9FLkpPSE4u
QUxMRU4uMTIzNDU2Nzg5MDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEB
APMXgYW4baSOW54nzE+HSGjpd9kb9ax3xU6pD6HbhOTqLevPz4X+XN18OaziPXyi
T5dlm9jdO1Fk9nrKNVULZCySP984A/0qL/hMYxMCvEbgolinxgvq6YyPZNHMPHal
c9LqVf2fJINtfH3NApRuS0DZZ9ncI1Ub76fy3WcEjQO25LjLiZTi9ZLQLJKXy6yG
5i38vv4lwelt9zYzwaOO2Sk/cMQ4zX05TOWG4Dlop5WFGUpi+PLb0F+1VIP1yuzE
SxbZmtWaXq1KTQBfvUeqV8zgIgkTbxqDqVIJO8Z7yBZ+M62JQoDT3jnXJ/D+64j9
IiMR51EKtlDbVMXvYMrhwsECAwEAATANBgkqhkiG9w0BAQsFAAOCAQEAJcKdninK
rAUuQ86cZPkInjtIC7eDKl3eycIwwUqUxl/eK/kxJ2z03sLN9D7CJkd02H2QD2Fo
iq4HHbdKQvMipElX0OikNBrXiNRv+rQBKpmqHOWUgedOy5AuyGktFVdCS2YW37ce
YPsL/hVat9hNSECrtPRR+wTZo8P81UsJ2s2RCPFwZviw1ZMoPZdYvKqKVMP2Nzuy
jKG2s35l6qKI593I58kHiHUln8GxLYpmKqiVEWK+Uj+W/v27qG/k04oMPfllbAfH
nFiY79YYcFDxR/RaqQvAK4euA7phx05ZrAsRfdXgLlhFZq80iHEuZA5rMzwRqXbb
d1lp5XCuNJ5O1g==
-----END CERTIFICATE-----
""";
	private static final String ALB_PEER = "192.168.200.9";

	@BeforeEach
	void configure() {
		System.setProperty(AlbClientCertFilter.ENABLED_PROPERTY, "true");
		System.setProperty(AlbClientCertFilter.TRUSTED_PEERS_PROPERTY, "192.168.200.0/24");
		System.clearProperty(AlbClientCertFilter.HEADER_PROPERTY);
	}

	@AfterEach
	void clean() {
		System.clearProperty(AlbClientCertFilter.ENABLED_PROPERTY);
		System.clearProperty(AlbClientCertFilter.TRUSTED_PEERS_PROPERTY);
		System.clearProperty(AlbClientCertFilter.HEADER_PROPERTY);
	}

	/** Encodes the way the ALB does: percent escapes everywhere except + = / and the unreserved set. */
	private static String encodeLikeAlb(String pem) {
		StringBuilder out = new StringBuilder();
		for (byte b : pem.getBytes(StandardCharsets.US_ASCII)) {
			char c = (char) (b & 0xFF);
			if (Character.isLetterOrDigit(c) || "-._~+=/".indexOf(c) >= 0) {
				out.append(c);
			} else {
				out.append(String.format("%%%02X", b & 0xFF));
			}
		}
		return out.toString();
	}

	private static HttpServletRequest request(String peer, String headerName, String headerValue,
			Map<String, Object> attributes) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRemoteAddr()).thenReturn(peer);
		when(request.getHeader(headerName)).thenReturn(headerValue);
		when(request.getAttribute(any())).thenAnswer(call -> attributes.get(call.<String>getArgument(0)));
		org.mockito.Mockito.doAnswer(call -> attributes.put(call.getArgument(0), call.getArgument(1)))
				.when(request).setAttribute(anyString(), any());
		return request;
	}

	private static void run(AlbClientCertFilter filter, HttpServletRequest request) throws Exception {
		FilterChain chain = mock(FilterChain.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		filter.doFilter(request, response, chain);
		verify(chain).doFilter(request, response); // the request always continues down the chain
	}

	private static X509Certificate certificateOf(Map<String, Object> attributes) {
		Object value = attributes.get(AlbClientCertFilter.CERT_ATTRIBUTE);
		return value == null ? null : ((X509Certificate[]) value)[0];
	}

	@Test
	void aTrustedLoadBalancerHeaderBecomesTheClientCertificate() throws Exception {
		Map<String, Object> attributes = new HashMap<>();

		run(new AlbClientCertFilter(), request(ALB_PEER, AlbClientCertFilter.DEFAULT_HEADER,
				encodeLikeAlb(LONG_LIVED), attributes));

		X509Certificate certificate = certificateOf(attributes);
		assertNotNull(certificate);
		assertTrue(certificate.getSubjectX500Principal().getName().contains("CN=DOE.JOHN.ALLEN.1234567890"));
		assertEquals(1, ((X509Certificate[]) attributes.get(AlbClientCertFilter.CERT_ATTRIBUTE)).length);
	}

	@Test
	void plusAndSlashInTheBase64AreNotCorrupted() {
		assertTrue(LONG_LIVED.contains("+") && LONG_LIVED.contains("/"), "the test certificate must exercise + and /");
		String encoded = encodeLikeAlb(LONG_LIVED);
		assertTrue(encoded.contains("+") && encoded.contains("/"));
		assertEquals(LONG_LIVED, AlbClientCertFilter.percentDecode(encoded));
		assertNotNull(AlbClientCertFilter.parseCertificate(encoded));
	}

	@Test
	void theFilterDoesNothingUnlessItIsEnabled() throws Exception {
		System.clearProperty(AlbClientCertFilter.ENABLED_PROPERTY);
		Map<String, Object> attributes = new HashMap<>();

		run(new AlbClientCertFilter(), request(ALB_PEER, AlbClientCertFilter.DEFAULT_HEADER,
				encodeLikeAlb(LONG_LIVED), attributes));

		assertNull(certificateOf(attributes));
	}

	@Test
	void aPeerOutsideTheLoadBalancerRangesCannotSendTheHeader() throws Exception {
		for (String peer : new String[] { "203.0.113.50", "192.168.201.9", "10.0.0.1", "127.0.0.1", "::1", null }) {
			Map<String, Object> attributes = new HashMap<>();

			run(new AlbClientCertFilter(), request(peer, AlbClientCertFilter.DEFAULT_HEADER,
					encodeLikeAlb(LONG_LIVED), attributes));

			assertNull(certificateOf(attributes), "peer " + peer);
		}
	}

	@Test
	void withNoUsableRangeNothingIsTrusted() throws Exception {
		for (String ranges : new String[] { null, "", " , ", "not-an-address", "192.168.200.0/99", "192.168.200.0/x",
				"999.1.1.1/8" }) {
			if (ranges == null) {
				System.clearProperty(AlbClientCertFilter.TRUSTED_PEERS_PROPERTY);
			} else {
				System.setProperty(AlbClientCertFilter.TRUSTED_PEERS_PROPERTY, ranges);
			}
			Map<String, Object> attributes = new HashMap<>();

			run(new AlbClientCertFilter(), request(ALB_PEER, AlbClientCertFilter.DEFAULT_HEADER,
					encodeLikeAlb(LONG_LIVED), attributes));

			assertNull(certificateOf(attributes), "ranges " + ranges);
		}
	}

	@Test
	void aCertificateFromTheTlsConnectionIsNeverReplaced() throws Exception {
		X509Certificate fromTls = AlbClientCertFilter.parseCertificate(encodeLikeAlb(ONE_DAY));
		Map<String, Object> attributes = new HashMap<>();
		attributes.put(AlbClientCertFilter.CERT_ATTRIBUTE, new X509Certificate[] { fromTls });

		run(new AlbClientCertFilter(), request(ALB_PEER, AlbClientCertFilter.DEFAULT_HEADER,
				encodeLikeAlb(LONG_LIVED), attributes));

		assertSame(fromTls, certificateOf(attributes));
	}

	@Test
	void aMissingOrBlankHeaderLeavesTheRequestWithoutACertificate() throws Exception {
		for (String value : new String[] { null, "", "   " }) {
			Map<String, Object> attributes = new HashMap<>();

			run(new AlbClientCertFilter(), request(ALB_PEER, AlbClientCertFilter.DEFAULT_HEADER, value, attributes));

			assertNull(certificateOf(attributes));
		}
	}

	@Test
	void aMalformedHeaderIsIgnoredNotFatal() throws Exception {
		String[] values = { "garbage", "%", "%4", "%ZZ",
				"-----BEGIN%20CERTIFICATE-----%0AAAAA%0A-----END%20CERTIFICATE-----", "café",
				encodeLikeAlb(LONG_LIVED).substring(0, 200) };
		for (String value : values) {
			Map<String, Object> attributes = new HashMap<>();

			run(new AlbClientCertFilter(), request(ALB_PEER, AlbClientCertFilter.DEFAULT_HEADER, value, attributes));

			assertNull(certificateOf(attributes), value);
		}
	}

	@Test
	void anOversizedHeaderIsIgnored() throws Exception {
		Map<String, Object> attributes = new HashMap<>();
		String huge = encodeLikeAlb(LONG_LIVED) + "A".repeat(17 * 1024);

		run(new AlbClientCertFilter(), request(ALB_PEER, AlbClientCertFilter.DEFAULT_HEADER, huge, attributes));

		assertNull(certificateOf(attributes));
	}

	@Test
	void anExpiredOrNotYetValidCertificateIsIgnored() throws Exception {
		AlbClientCertFilter filter = new AlbClientCertFilter();
		filter.setClock(Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC)); // after the one day cert
		Map<String, Object> attributes = new HashMap<>();
		run(filter, request(ALB_PEER, AlbClientCertFilter.DEFAULT_HEADER, encodeLikeAlb(ONE_DAY), attributes));
		assertNull(certificateOf(attributes));

		filter.setClock(Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC)); // before it existed
		attributes = new HashMap<>();
		run(filter, request(ALB_PEER, AlbClientCertFilter.DEFAULT_HEADER, encodeLikeAlb(LONG_LIVED), attributes));
		assertNull(certificateOf(attributes));
	}

	@Test
	void theHeaderNameCanBeChanged() throws Exception {
		System.setProperty(AlbClientCertFilter.HEADER_PROPERTY, "X-Test-Cert");
		Map<String, Object> attributes = new HashMap<>();

		run(new AlbClientCertFilter(), request(ALB_PEER, "X-Test-Cert", encodeLikeAlb(LONG_LIVED), attributes));

		assertNotNull(certificateOf(attributes));
	}

	@Test
	void addressRangesMatchOnThePrefixLength() {
		System.setProperty(AlbClientCertFilter.TRUSTED_PEERS_PROPERTY, "192.168.200.0/28, 10.1.2.3, 2001:db8::/32");
		assertTrue(AlbClientCertFilter.isTrustedPeer("192.168.200.15"));
		assertFalse(AlbClientCertFilter.isTrustedPeer("192.168.200.16"));
		assertTrue(AlbClientCertFilter.isTrustedPeer("10.1.2.3"));
		assertFalse(AlbClientCertFilter.isTrustedPeer("10.1.2.4"));
		assertTrue(AlbClientCertFilter.isTrustedPeer("2001:db8:1::5"));
		assertFalse(AlbClientCertFilter.isTrustedPeer("2001:db9::1"));
		// an IPv4 peer reported in IPv6 form
		assertTrue(AlbClientCertFilter.isTrustedPeer("::ffff:192.168.200.3"));
		// a host name is never resolved
		assertFalse(AlbClientCertFilter.isTrustedPeer("localhost"));
	}

	// ---- together with CACRequiredFilter, in the order web.xml puts them ----

	private static HttpServletResponse sinkResponse(ByteArrayOutputStream sink) throws Exception {
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

	/** Runs AlbClientCertFilter, then CACRequiredFilter, then the application; returns whether the application ran. */
	private static boolean reachesTheApplication(String peer, String headerValue, HttpServletResponse response)
			throws Exception {
		System.setProperty(CACRequiredFilter.REQUIRED_PROPERTY, "true");
		try {
			Map<String, Object> attributes = new HashMap<>();
			HttpServletRequest request = request(peer, AlbClientCertFilter.DEFAULT_HEADER, headerValue, attributes);
			when(request.getMethod()).thenReturn("GET");
			when(request.getServletPath()).thenReturn("/api/engine/runPixel");
			when(request.getHeader("Accept")).thenReturn("application/json");
			when(request.getContextPath()).thenReturn("/Monolith");
			CACRequiredFilter required = new CACRequiredFilter();
			required.init(mock(FilterConfig.class));
			boolean[] reached = { false };
			FilterChain application = (req, res) -> reached[0] = true;
			new AlbClientCertFilter().doFilter(request, response, (req, res) -> required.doFilter(req, res, application));
			return reached[0];
		} finally {
			System.clearProperty(CACRequiredFilter.REQUIRED_PROPERTY);
		}
	}

	@Test
	void behindTheLoadBalancerACardRequestReachesTheApplication() throws Exception {
		assertTrue(reachesTheApplication(ALB_PEER, encodeLikeAlb(LONG_LIVED), sinkResponse(new ByteArrayOutputStream())));
	}

	@Test
	void behindTheLoadBalancerARequestWithoutACardIsStillRefused() throws Exception {
		HttpServletResponse response = sinkResponse(new ByteArrayOutputStream());

		assertFalse(reachesTheApplication(ALB_PEER, null, response));
		verify(response).setStatus(403);
	}

	@Test
	void aForgedHeaderFromAnUntrustedAddressIsStillRefused() throws Exception {
		HttpServletResponse response = sinkResponse(new ByteArrayOutputStream());

		assertFalse(reachesTheApplication("203.0.113.50", encodeLikeAlb(LONG_LIVED), response));
		verify(response).setStatus(403);
	}
}
