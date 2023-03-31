package prerna.semoss.web.services.config;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.cache.CacheBuilder;

import prerna.auth.utils.SecurityAPIUserUtils;
import prerna.auth.utils.SecurityTokenUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/")
public class TrustedTokenService {
	
	private static final Logger logger = LogManager.getLogger(TrustedTokenService.class);
	
	private static long expirationMinutes = 120L;
	private static ConcurrentMap<String, String> tokenStorage = null;
	static {
		TrustedTokenService.tokenStorage = CacheBuilder.newBuilder().maximumSize(1000L)
				.expireAfterWrite(expirationMinutes, TimeUnit.MINUTES)
				.<String, String>build().asMap();
	}
	
	@GET
	@Path("/getToken")
	public Response getToken(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException {
		if(Utility.getApplicationAPIUserTokenCheck()) {
			String clientId = request.getParameter("client_id");
			String secretKey = request.getParameter("secret_key");
			
			if(!SecurityAPIUserUtils.validCredentials(clientId, secretKey)) {
				Map<String, Object> ret = new Hashtable<>();
				ret.put("success", false);
				ret.put(Constants.ERROR_MESSAGE, "Invalid client/secret key combination");
				return WebUtility.getResponse(ret, 401);
			}
		}
		String ip = ResourceUtility.getClientIp(request);
		String token = null;
		String dateAdded = null;
		if(ClusterUtil.IS_CLUSTER) {
			String[] val = getClusterToken(ip);
			token = val[0];
			dateAdded = val[1];
		} else {
			token = getLocalToken(ip);
		}
		
		Map<String, String> retMap = new HashMap<>();
		retMap.put("token", token);
		retMap.put("dateAdded", dateAdded);
		return WebUtility.getResponse(retMap, 200);
	}
	
	/**
	 * Store and get the ip in a clustered location
	 * @param ip
	 * @return
	 */
	private static String[] getClusterToken(String ip) {
		SecurityTokenUtils.clearExpiredTokens(TrustedTokenService.expirationMinutes);
		String token = SecurityTokenUtils.getToken(ip);
		if(token == null) {
			logger.info("IP = " + ip + ", generating new token id");
			Object[] genRet = SecurityTokenUtils.generateToken(ip);
			token = (String) genRet[0];
			
			LocalDateTime dateAdded = (LocalDateTime) genRet[1];
			Calendar cal = (Calendar) genRet[2];
			DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
			
			String formattedDate = dateAdded.atZone(cal.getTimeZone().toZoneId()).format(formatter);
			return new String[] {token, formattedDate};
		}

		logger.info("IP = " + ip + ", requesting existing token id");
		return new String[] {token, null};
	}
	
	/**
	 * Store and get the ip locally on the pod
	 * @param ip
	 * @return
	 */
	private static String getLocalToken(String ip) {
		String token = null;
		
		if(tokenStorage.containsKey(ip)) {
			token = tokenStorage.get(ip);
			logger.info("IP = " + ip + ", requesting existing token id");
		} else {
			token = UUID.randomUUID().toString();
			tokenStorage.put(ip, token);
			logger.info("IP = " + ip + ", generating new token id");
		}
		
		return token;
	}
	
	/**
	 * Get the token for a specific IP address
	 * @param ip
	 * @return
	 */
	public static String getTokenForIp(String ip) {
		if(ClusterUtil.IS_CLUSTER) {
			SecurityTokenUtils.clearExpiredTokens(TrustedTokenService.expirationMinutes);
			return SecurityTokenUtils.getToken(ip);
		}
		
		return tokenStorage.get(ip);
	}

}
