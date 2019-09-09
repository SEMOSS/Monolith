package prerna.semoss.web.services.local;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;

import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.engine.api.IEngine;
import prerna.nameserver.utility.MasterDatabaseUtility;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("exec")
public class ExecuteInsightResource {

	@Path("/test")
	public Object test() {
		Map ret = new HashMap<String, String>();
		return WebUtility.getSO(ret);
	}
	
	@Path("/a-{appId}/i-{insightId}")
	public Object generateInsight(@Context HttpServletRequest request, 
			@PathParam("appId") String appId, 
			@PathParam("insightId") String rdbmsId) {
		boolean securityEnabled = AbstractSecurityUtils.securityEnabled();
		User user = null;

		HttpSession session = null;
		if(securityEnabled){
			session = request.getSession(false);
			if(session == null) {
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "Invalid session to retrieve insight data");
				return WebUtility.getResponse(errorHash, 400);
			}
			
			user = ((User) session.getAttribute(Constants.SESSION_USER));
			if(user == null) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("error", "User session is invalid");
				return WebUtility.getResponse(errorMap, 401);
			}
		} else {
			session = request.getSession(true);
			user = ((User) session.getAttribute(Constants.SESSION_USER));
		}
		
		if(securityEnabled) {
			appId = SecurityQueryUtils.testUserEngineIdForAlias(user, appId);
			if(!SecurityInsightUtils.userCanViewInsight(user, appId, rdbmsId)) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("error", "User does not have access to this insight");
				return WebUtility.getResponse(errorMap, 401);
			}
		} else {
			appId = MasterDatabaseUtility.testEngineIdIfAlias(appId);
		}
		
		IEngine engine = Utility.getEngine(appId);
		if(engine == null) {
			throw new IllegalArgumentException("Cannot find app = " + appId);
		}
		Insight newInsight = null;
		try {
			List<Insight> in = engine.getInsight(rdbmsId + "");
			newInsight = in.get(0);
		} catch (ArrayIndexOutOfBoundsException e) {
			ClusterUtil.reactorUpdateApp(appId);
			try {
				List<Insight> in = engine.getInsight(rdbmsId + "");
				newInsight = in.get(0);
			} catch (ArrayIndexOutOfBoundsException e2) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("error", "Insight does not exist");
				return WebUtility.getResponse(errorMap, 401);
			}
		}

		InsightStore.getInstance().put(newInsight);
		newInsight.reRunPixelInsight();
		RunInsight runner = new RunInsight(newInsight);
		runner.dropInsight(true);
		return runner;
	}
	
}
