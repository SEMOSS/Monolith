package prerna.semoss.web.services.local;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;

import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("share")
public class ShareInsightResource {

	@Path("/i-{insightId}")
	public Object validInsight(@Context HttpServletRequest request, @PathParam("insightId") String insightId) {
		HttpSession session = request.getSession(false);
		if(session == null) {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put(Constants.ERROR_MESSAGE, "Invalid session to retrieve insight data");
			return WebUtility.getResponse(errorHash, 400);
		}
		insightId = WebUtility.inputSanitizer(insightId);
		Insight in = InsightStore.getInstance().get(insightId);
		if(in == null) {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put(Constants.ERROR_MESSAGE, "Invalid insight id");
			return WebUtility.getResponse(errorHash, 400);
		}
		
		String sessionId = session.getId();
		Set<String> sessionStore = InsightStore.getInstance().getInsightIDsForSession(sessionId);
		if(sessionStore == null || !sessionStore.contains(insightId)) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Invaid session to retrieve insight data");
			return WebUtility.getResponse(errorMap, 400);
		}
		
		RunInsight runner = new RunInsight(in);
		return runner;
	}
	
}
