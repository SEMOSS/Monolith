package prerna.semoss.web.services.config;

import java.io.IOException;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.cache.CacheBuilder;

import prerna.auth.utils.SecurityAPIUserUtils;
import prerna.auth.utils.SecurityTokenUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.date.SemossDate;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/")
@PermitAll
public class TrustedTokenService {
	
	private static final Logger logger = LogManager.getLogger(TrustedTokenService.class);
	
	private static long expirationMinutes = 120L;
	private static ConcurrentMap<String, Object[]> tokenStorage = null;
	static {
		TrustedTokenService.tokenStorage = CacheBuilder.newBuilder().maximumSize(1000L)
				.expireAfterWrite(expirationMinutes, TimeUnit.MINUTES)
				.<String, Object[]>build().asMap();
	}
	
	@GET
	@Path("/getToken")
	public Response getTokenGet(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException {
		if(SecurityAPIUserUtils.getApplicationAPIUserTokenCheck()) {
			Map<String, Object> ret = new Hashtable<>();
			ret.put("success", false);
			ret.put(Constants.ERROR_MESSAGE, "Must use POST request to send client/secret keys");
			return WebUtility.getResponse(ret, 401);
		}
		String clientId =WebUtility.inputSanitizer( request.getParameter("client_id"));
		String ip = ResourceUtility.getClientIp(request);
		Object[] tokenDetails = null;
		if(ClusterUtil.IS_CLUSTER) {
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
	public Response getTokenPost(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException {
		String clientId = WebUtility.inputSanitizer(request.getParameter("client_id"));
		if(SecurityAPIUserUtils.getApplicationAPIUserTokenCheck()) {
			String secretKey = request.getParameter("secret_key");
			
			if(!SecurityAPIUserUtils.validCredentials(clientId, secretKey)) {
				Map<String, Object> ret = new Hashtable<>();
				ret.put("success", false);
				ret.put(Constants.ERROR_MESSAGE, "Invalid client/secret key combination");
				return WebUtility.getResponse(ret, 401);
			}
		}
		String ip = ResourceUtility.getClientIp(request);
		Object[] tokenDetails = null;
		if(ClusterUtil.IS_CLUSTER) {
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
	 * @param ip
	 * @return
	 */
	private static Object[] getClusterToken(String ip, String clientId) {
		SecurityTokenUtils.clearExpiredTokens(TrustedTokenService.expirationMinutes);
		Object[] tokenDetails = SecurityTokenUtils.getToken(ip);
		if(tokenDetails == null) {
			logger.info(Utility.cleanLogString("IP = " + ip + ", generating new token id"));
			tokenDetails = SecurityTokenUtils.generateToken(ip, clientId);
			return tokenDetails;
		}

		logger.info(Utility.cleanLogString("IP = " + ip + ", requesting existing token id"));
		return tokenDetails;
	}
	
	/**
	 * Store and get the ip locally on the pod
	 * @param ip
	 * @return
	 */
	private static Object[] getLocalToken(String ip, String clientId) {
		Object[] tokenDetails = null;
		
		if(tokenStorage.containsKey(ip)) {
			tokenDetails = tokenStorage.get(ip);
			logger.info(Utility.cleanLogString("IP = " + ip + ", requesting existing token id"));
		} else {
			String token = UUID.randomUUID().toString();
			tokenDetails = new Object[] {token, new SemossDate(Utility.getCurrentZonedDateTimeUTC()), clientId};
			tokenStorage.put(ip, tokenDetails);
			logger.info(Utility.cleanLogString("IP = " + ip + ", generating new token id"));
		}
		
		return tokenDetails;
	}
	
	/**
	 * Get the token for a specific IP address
	 * @param ip
	 * @return
	 */
	public static Object[] getTokenForIp(String ip) {
		if(ClusterUtil.IS_CLUSTER) {
			SecurityTokenUtils.clearExpiredTokens(TrustedTokenService.expirationMinutes);
			return SecurityTokenUtils.getToken(ip);
		}
		
		return tokenStorage.get(ip);
	}

}
