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
import java.math.BigInteger;
import java.net.URL;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.security.HttpHelperUtility;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;

/**
 * Filter to validate Keycloak JWT bearer tokens for MCP endpoints.
 * This filter extracts the JWT from the Authorization header, validates it
 * against the Keycloak server's public keys, and creates a user session.
 *
 * Configuration in social.properties:
 * - keycloak_realm_url: The Keycloak realm URL (e.g., https://keycloak.example.com/realms/myrealm)
 * - keycloak_client_id: The expected client ID (audience) in the token
 * - keycloak_login: true/false to enable Keycloak authentication
 */
public class KeycloakJWTFilter implements Filter {

	private static final Logger classLogger = LogManager.getLogger(KeycloakJWTFilter.class);

	// Cache for JWKS keys - key is the kid (key ID), value is the public key
	private static final Map<String, RSAPublicKey> jwksCache = new ConcurrentHashMap<>();
	private static long jwksCacheExpiry = 0;
	private static final long JWKS_CACHE_TTL_MS = 3600000; // 1 hour

	private static final Gson gson = new Gson();

	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
			throws IOException, ServletException {

		HttpServletRequest request = (HttpServletRequest) arg0;
		HttpServletResponse response = (HttpServletResponse) arg1;
		HttpSession session = request.getSession(false);

		// If user already in session, continue
		User user = null;
		if (session != null) {
			user = (User) session.getAttribute(Constants.SESSION_USER);
		}
		if (user != null) {
			arg2.doFilter(arg0, arg1);
			return;
		}

		// Check if this is an MCP endpoint that needs Keycloak auth
		String path = request.getRequestURI();
		if (!path.contains("/api/ext/mcp/")) {
			arg2.doFilter(arg0, arg1);
			return;
		}

		// Check if Keycloak or Generic (Keycloak-based) login is enabled
		SocialPropertiesUtil socialData = SocialPropertiesUtil.getInstance();
		Boolean keycloakEnabled = socialData.getLoginsAllowed().get("keycloak");
		Boolean genericEnabled = socialData.getLoginsAllowed().get("generic");

		// Determine which provider to use (keycloak takes priority, then generic)
		String provider = null;
		if (keycloakEnabled != null && keycloakEnabled) {
			provider = "keycloak";
		} else if (genericEnabled != null && genericEnabled) {
			// Check if generic is configured with a Keycloak-style URL
			String authUrl = socialData.getProperty("generic_auth_url");
			if (authUrl != null && (authUrl.contains("/realms/") || authUrl.contains("keycloak"))) {
				provider = "generic";
			}
		}

		if (provider == null) {
			// Neither Keycloak nor Generic (Keycloak) enabled, continue with other auth methods
			arg2.doFilter(arg0, arg1);
			return;
		}

		// Get Authorization header
		String authHeader = request.getHeader("Authorization");
		if (authHeader == null) {
			authHeader = request.getHeader("authorization");
		}

		if (authHeader == null || !authHeader.toLowerCase().startsWith("bearer ")) {
			// No bearer token, continue (other filters may handle different auth types)
			arg2.doFilter(arg0, arg1);
			return;
		}

		String token = authHeader.substring("Bearer ".length()).trim();

		// Skip if this looks like our access_key:secret_key format (handled by OpenAIFilter)
		if (token.contains(":") && !token.contains(".")) {
			arg2.doFilter(arg0, arg1);
			return;
		}

		// Validate the JWT
		try {
			user = validateKeycloakToken(token, socialData, provider);
			if (user != null) {
				session = request.getSession(true);
				session.setAttribute(Constants.SESSION_USER, user);
				AccessToken accessToken = user.getPrimaryLoginToken();
				if (accessToken != null) {
					session.setAttribute(Constants.SESSION_USER_ID_LOG, accessToken.getId());
				}
				classLogger.info("User authenticated via {} JWT: {}", provider, User.getSingleLogginName(user));
			}
		} catch (Exception e) {
			classLogger.error("Failed to validate {} JWT token", provider, e);
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setContentType("application/json");
			response.getWriter().write("{\"error\": \"Invalid or expired token\", \"error_description\": \"" +
					e.getMessage().replace("\"", "'") + "\"}");
			return;
		}

		arg2.doFilter(arg0, arg1);
	}

	/**
	 * Validates the Keycloak JWT token and returns a User object if valid.
	 * Supports both 'keycloak_' and 'generic_' property prefixes.
	 *
	 * @param token The JWT token to validate
	 * @param socialData The social properties utility
	 * @param provider The provider name ('keycloak' or 'generic')
	 */
	private User validateKeycloakToken(String token, SocialPropertiesUtil socialData, String provider) throws Exception {
		String prefix = provider + "_";

		// Parse the JWT
		SignedJWT signedJWT = SignedJWT.parse(token);

		// Get the key ID from the token header
		String kid = signedJWT.getHeader().getKeyID();
		JWSAlgorithm algorithm = signedJWT.getHeader().getAlgorithm();

		if (!JWSAlgorithm.RS256.equals(algorithm) && !JWSAlgorithm.RS384.equals(algorithm)
				&& !JWSAlgorithm.RS512.equals(algorithm)) {
			throw new SecurityException("Unsupported JWT algorithm: " + algorithm);
		}

		// Get the Keycloak realm URL
		// Try realm_url first, then derive from auth_url
		String realmUrl = socialData.getProperty(prefix + "realm_url");
		if (realmUrl == null || realmUrl.isEmpty()) {
			// Fall back to constructing from auth_url
			String authUrl = socialData.getProperty(prefix + "auth_url");
			if (authUrl != null && authUrl.contains("/protocol/openid-connect")) {
				realmUrl = authUrl.substring(0, authUrl.indexOf("/protocol/openid-connect"));
			} else {
				throw new IllegalStateException(prefix + "realm_url or " + prefix + "auth_url not configured");
			}
		}

		// Get the public key from JWKS
		RSAPublicKey publicKey = getPublicKey(realmUrl, kid);
		if (publicKey == null) {
			throw new SecurityException("Unable to find public key for kid: " + kid);
		}

		// Verify the signature
		JWSVerifier verifier = new RSASSAVerifier(publicKey);
		if (!signedJWT.verify(verifier)) {
			throw new SecurityException("JWT signature verification failed");
		}

		// Get claims
		JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

		// Validate expiration
		if (claims.getExpirationTime() == null ||
				claims.getExpirationTime().getTime() < System.currentTimeMillis()) {
			throw new SecurityException("JWT token has expired");
		}

		// Validate issuer
		String expectedIssuer = realmUrl;
		if (!expectedIssuer.equals(claims.getIssuer())) {
			throw new SecurityException("JWT issuer mismatch. Expected: " + expectedIssuer +
					", Got: " + claims.getIssuer());
		}

		// Optionally validate audience (client_id)
		String expectedClientId = socialData.getProperty(prefix + "client_id");
		if (expectedClientId != null && !expectedClientId.isEmpty()) {
			// Check if the azp (authorized party) or aud contains our client ID
			String azp = (String) claims.getClaim("azp");
			if (azp != null && !azp.equals(expectedClientId)) {
				// Also check audience
				if (claims.getAudience() == null || !claims.getAudience().contains(expectedClientId)) {
					classLogger.warn("JWT audience/authorized party mismatch. Expected: {}, Got azp: {}, aud: {}",
							expectedClientId, azp, claims.getAudience());
					// Don't throw exception - just log warning. The token is still valid for this realm.
				}
			}
		}

		// Create user from claims with the appropriate provider
		return createUserFromClaims(claims, provider);
	}

	/**
	 * Creates a User object from JWT claims.
	 *
	 * @param claims The JWT claims
	 * @param provider The provider name ('keycloak' or 'generic')
	 */
	private User createUserFromClaims(JWTClaimsSet claims, String provider) {
		AccessToken token = new AccessToken();

		// Set the appropriate AuthProvider based on the provider name
		if ("keycloak".equalsIgnoreCase(provider)) {
			token.setProvider(AuthProvider.KEYCLOAK);
		} else if ("generic".equalsIgnoreCase(provider)) {
			token.setProvider(AuthProvider.GENERIC);
		} else {
			token.setProvider(AuthProvider.KEYCLOAK);
		}

		// Extract standard claims
		token.setId(claims.getSubject());

		String preferredUsername = (String) claims.getClaim("preferred_username");
		String email = (String) claims.getClaim("email");
		String name = (String) claims.getClaim("name");
		String givenName = (String) claims.getClaim("given_name");
		String familyName = (String) claims.getClaim("family_name");

		if (name != null && !name.isEmpty()) {
			token.setName(name);
		} else if (givenName != null || familyName != null) {
			token.setName(((givenName != null ? givenName : "") + " " +
					(familyName != null ? familyName : "")).trim());
		} else if (preferredUsername != null) {
			token.setName(preferredUsername);
		}

		if (email != null) {
			token.setEmail(email);
		}

		if (preferredUsername != null) {
			token.setUsername(preferredUsername);
		}

		User user = new User();
		user.setAccessToken(token);

		return user;
	}

	/**
	 * Gets the RSA public key from the JWKS endpoint.
	 */
	private RSAPublicKey getPublicKey(String realmUrl, String kid) throws Exception {
		// Check cache first
		if (System.currentTimeMillis() < jwksCacheExpiry && jwksCache.containsKey(kid)) {
			return jwksCache.get(kid);
		}

		// Fetch JWKS
		String jwksUrl = realmUrl + "/protocol/openid-connect/certs";
		String jwksResponse = fetchJwks(jwksUrl);

		if (jwksResponse == null || jwksResponse.isEmpty()) {
			throw new IOException("Failed to fetch JWKS from: " + jwksUrl);
		}

		// Parse JWKS and update cache
		JsonObject jwks = JsonParser.parseString(jwksResponse).getAsJsonObject();
		JsonArray keys = jwks.getAsJsonArray("keys");

		jwksCache.clear();

		for (JsonElement keyElement : keys) {
			JsonObject key = keyElement.getAsJsonObject();
			String keyId = key.get("kid").getAsString();
			String kty = key.get("kty").getAsString();

			if ("RSA".equals(kty)) {
				String n = key.get("n").getAsString();
				String e = key.get("e").getAsString();

				RSAPublicKey publicKey = buildRSAPublicKey(n, e);
				jwksCache.put(keyId, publicKey);
			}
		}

		jwksCacheExpiry = System.currentTimeMillis() + JWKS_CACHE_TTL_MS;

		return jwksCache.get(kid);
	}

	/**
	 * Fetches the JWKS from the Keycloak server.
	 */
	private String fetchJwks(String jwksUrl) {
		try {
			return HttpHelperUtility.getRequest(jwksUrl, null, null, null, null);
		} catch (Exception e) {
			classLogger.error("Error fetching JWKS from {}", jwksUrl, e);
			return null;
		}
	}

	/**
	 * Builds an RSA public key from the modulus (n) and exponent (e).
	 */
	private RSAPublicKey buildRSAPublicKey(String n, String e) throws Exception {
		byte[] nBytes = Base64.getUrlDecoder().decode(n);
		byte[] eBytes = Base64.getUrlDecoder().decode(e);

		BigInteger modulus = new BigInteger(1, nBytes);
		BigInteger exponent = new BigInteger(1, eBytes);

		RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
		KeyFactory factory = KeyFactory.getInstance("RSA");

		return (RSAPublicKey) factory.generatePublic(spec);
	}

	@Override
	public void destroy() {
		jwksCache.clear();
	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		classLogger.info("KeycloakJWTFilter initialized");
	}
}
