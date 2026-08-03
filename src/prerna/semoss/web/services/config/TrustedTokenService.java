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
package prerna.semoss.web.services.config;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.cache.CacheBuilder;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import prerna.auth.utils.SecurityAPIUserUtils;
import prerna.auth.utils.SecurityTokenUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.date.SemossDate;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/")
@PermitAll
public class TrustedTokenService {

	private static final Logger classLogger = LogManager.getLogger(TrustedTokenService.class);

	private static long expirationMinutes = 120L;
	private static ConcurrentMap<String, Object[]> tokenStorage = null;
	static {
		TrustedTokenService.tokenStorage = CacheBuilder.newBuilder().maximumSize(1000L)
				.expireAfterWrite(expirationMinutes, TimeUnit.MINUTES).<String, Object[]>build().asMap();
	}

	@GET
	@Path("/getToken")
	public Response getTokenGet(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws IOException {
		if (SecurityAPIUserUtils.getApplicationAPIUserTokenCheck()) {
			Map<String, Object> ret = new HashMap<>();
			ret.put("success", false);
			ret.put(Constants.ERROR_MESSAGE, "Must use POST request to send client/secret keys");
			return WebUtility.getResponse(ret, 401);
		}
		String clientId = WebUtility.inputSanitizer(request.getParameter("client_id"));
		String ip = WebUtility.getClientIp(request);
		Object[] tokenDetails = null;
		if (ClusterUtil.IS_CLUSTER) {
			tokenDetails = getClusterToken(ip, clientId);
		} else {
			tokenDetails = getLocalToken(ip, clientId);
		}

		Map<String, Object> retMap = new HashMap<>();
		retMap.put("token", tokenDetails[0]);
		retMap.put("dateAdded", tokenDetails[1]);
		retMap.put("clientId", tokenDetails[2]);
		return WebUtility.getResponse(retMap, 200);
	}

	@POST
	@Path("/getToken")
	public Response getTokenPost(@Context HttpServletRequest request, @Context HttpServletResponse response)
			throws IOException {
		String clientId = WebUtility.inputSanitizer(request.getParameter("client_id"));
		if (SecurityAPIUserUtils.getApplicationAPIUserTokenCheck()) {
			String secretKey = request.getParameter("secret_key");

			if (!SecurityAPIUserUtils.validCredentials(clientId, secretKey)) {
				Map<String, Object> ret = new HashMap<>();
				ret.put("success", false);
				ret.put(Constants.ERROR_MESSAGE, "Invalid client/secret key combination");
				return WebUtility.getResponse(ret, 401);
			}
		}
		String ip = WebUtility.getClientIp(request);
		Object[] tokenDetails = null;
		if (ClusterUtil.IS_CLUSTER) {
			tokenDetails = getClusterToken(ip, clientId);
		} else {
			tokenDetails = getLocalToken(ip, clientId);
		}

		Map<String, Object> retMap = new HashMap<>();
		retMap.put("token", tokenDetails[0]);
		retMap.put("dateAdded", tokenDetails[1]);
		retMap.put("clientId", tokenDetails[2]);
		return WebUtility.getResponse(retMap, 200);
	}

	/**
	 * Store and get the ip in a clustered location
	 * 
	 * @param ip
	 * @return
	 */
	private static Object[] getClusterToken(String ip, String clientId) {
		SecurityTokenUtils.clearExpiredTokens(TrustedTokenService.expirationMinutes);
		Object[] tokenDetails = SecurityTokenUtils.getToken(ip);
		if (tokenDetails == null) {
			classLogger.info("IP = {}, generating new token id", Utility.cleanLogString(ip));
			tokenDetails = SecurityTokenUtils.generateToken(ip, clientId);
			return tokenDetails;
		}

		classLogger.info("IP = {}, requesting existing token id", Utility.cleanLogString(ip));
		return tokenDetails;
	}

	/**
	 * Store and get the ip locally on the pod
	 * 
	 * @param ip
	 * @return
	 */
	private static Object[] getLocalToken(String ip, String clientId) {
		Object[] tokenDetails = null;

		if (tokenStorage.containsKey(ip)) {
			tokenDetails = tokenStorage.get(ip);
			classLogger.info("IP = {}, requesting existing token id", Utility.cleanLogString(ip));
		} else {
			String token = UUID.randomUUID().toString();
			tokenDetails = new Object[] { token, new SemossDate(Utility.getCurrentZonedDateTimeUTC()), clientId };
			tokenStorage.put(ip, tokenDetails);
			classLogger.info("IP = {}, generating new token id", Utility.cleanLogString(ip));
		}

		return tokenDetails;
	}

	/**
	 * Get the token for a specific IP address
	 * 
	 * @param ip
	 * @return
	 */
	public static Object[] getTokenForIp(String ip) {
		if (ClusterUtil.IS_CLUSTER) {
			SecurityTokenUtils.clearExpiredTokens(TrustedTokenService.expirationMinutes);
			return SecurityTokenUtils.getToken(ip);
		}

		return tokenStorage.get(ip);
	}

}
