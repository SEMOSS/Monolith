package prerna.semoss.web.services.config;

import java.io.IOException;
import java.util.HashMap;
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

import prerna.semoss.web.services.local.ResourceUtility;
import prerna.web.services.util.WebUtility;

@Path("/")
public class TrustedTokenService {
	
	private static final Logger logger = LogManager.getLogger(TrustedTokenService.class);
	
	private static ConcurrentMap<String, String> tokenStorage = null;
	static {
		TrustedTokenService.tokenStorage = CacheBuilder.newBuilder().maximumSize(1000L)
				.expireAfterWrite(120L, TimeUnit.MINUTES)
				.<String, String>build().asMap();
	}
	
	@GET
	@Path("/getToken")
	public Response getToken(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException {
		String ip = ResourceUtility.getClientIp(request);
		String token = null;
		
		if(tokenStorage.containsKey(ip)) {
			token = tokenStorage.get(ip);
			logger.info("IP = " + ip + ", requesting existing token id");
		} else {
			token = UUID.randomUUID().toString();
			tokenStorage.put(ip, token);
			logger.info("IP = " + ip + ", generating new token id");
		}

		Map<String, String> retMap = new HashMap<>();
		retMap.put("token", token);
		return WebUtility.getResponse(retMap, 200);
	}
	
	/**
	 * Get the token for a specific IP address
	 * @param ip
	 * @return
	 */
	public static String getTokenForIp(String ip) {
		return tokenStorage.get(ip);
	}

}
