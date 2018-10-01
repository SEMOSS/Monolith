package prerna.semoss.web.services;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import prerna.auth.utils.AbstractSecurityUtils;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.web.services.util.WebUtility;

@Path("/config")
public class ServerConfigurationResource {

	private static Map<String, Object> config = null;
	
	/**
	 * Generate the configuration options for this instance
	 * Only need to make this once
	 * @param request
	 * @return
	 */
	private static Map<String, Object> getConfig(@Context HttpServletRequest request) {
		if(config == null) {
			// make thread safe
			synchronized(ServerConfigurationResource.class) {
				if(config == null) {
					config = new HashMap<String, Object>();
					// session timeout
					config.put("timeout", (double) request.getSession().getMaxInactiveInterval() / 60);
					
					// r enabled
					boolean useR = true;
					String useRStr =  DIHelper.getInstance().getProperty(Constants.USE_R);
					if(useRStr != null) {
						useR = Boolean.parseBoolean(useRStr);
					}
					config.put("r", useR);
		
					// security enabled
					config.put("security", AbstractSecurityUtils.securityEnabled());
					
					// local mode
					boolean localMode = false;
					String localModeStr =  DIHelper.getInstance().getProperty(Constants.LOCAL_DEPLOYMENT);
					if(localModeStr != null) {
						localMode = Boolean.parseBoolean(localModeStr);
					}
					config.put("localDeployment", localMode);
				}
			}
		}
		return config;
	}
	
	@GET
	@Path("/")
	@Produces("application/json")
	public Response getTimeoutValue(@Context HttpServletRequest request) {
		return WebUtility.getResponse(getConfig(request), 200);
	}
	
}
