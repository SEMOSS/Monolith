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
package prerna.semoss.web.services.local;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;

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
