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
import prerna.auth.utils.SecurityInsightUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.project.api.IProject;
import prerna.util.AssetUtility;
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
			@PathParam("appId") String projectId, 
			@PathParam("insightId") String rdbmsId) {
		projectId = WebUtility.inputSanitizer(projectId);
		rdbmsId = WebUtility.inputSanitizer(rdbmsId);
		User user = null;
		HttpSession session = request.getSession(false);
		if(session == null) {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put(Constants.ERROR_MESSAGE, "Invalid session to retrieve insight data");
			return WebUtility.getResponse(errorHash, 400);
		}
		
		user = ((User) session.getAttribute(Constants.SESSION_USER));
		if(user == null) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		projectId = SecurityQueryUtils.testUserEngineIdForAlias(user, projectId);
		if(!SecurityInsightUtils.userCanViewInsight(user, projectId, rdbmsId)) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User does not have access to this insight");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		IProject project = Utility.getProject(projectId);
		if(project == null) {
			throw new IllegalArgumentException("Cannot find project = " + projectId);
		}
		
		Insight newInsight = null;
		try {
			newInsight = SecurityInsightUtils.getInsight(projectId, rdbmsId);
		} catch (Exception e) {
			ClusterUtil.pullInsightsDB(projectId);
			// this is needed for the pipeline json
			ClusterUtil.pullProjectFolder(project, AssetUtility.getProjectVersionFolder(project.getProjectName(), projectId));
			try {
				List<Insight> in = project.getInsight(rdbmsId + "");
				newInsight = in.get(0);
			} catch(IllegalArgumentException e2) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, "Error occured creating the insight. Detailed message = " + e2.getMessage());
				return WebUtility.getResponse(errorMap, 401);
			} catch (ArrayIndexOutOfBoundsException e2) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, "Insight does not exist");
				return WebUtility.getResponse(errorMap, 401);
			}
		}
		
		InsightStore.getInstance().put(newInsight);
		newInsight.reRunPixelInsight(false);
		RunInsight runner = new RunInsight(newInsight);
		runner.dropInsight(true);
		return runner;
	}
	
}
