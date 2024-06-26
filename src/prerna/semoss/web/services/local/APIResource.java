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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ResourceContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.requests.OverrideParametersServletRequest;
import prerna.web.services.util.WebUtility;

@Path("/")
public class APIResource {

	private static final Logger logger = LogManager.getLogger(APIResource.class);
	@Context
	protected ServletContext context;

	@Path("/get")
	//@POST
	@GET
	//@Produces(MediaType.TEXT_PLAIN + ";charset=utf-8")
	@Produces(MediaType.APPLICATION_JSON + ";charset=utf-8")
	// gets to this point after a filter
	// no need to worry about security at this point
	public StreamingOutput getJSONOutput(
//			@QueryParam("key") String apiKey, 
//			@QueryParam("pass") String pass,
			@Context HttpServletRequest request, 
			@Context HttpServletResponse response,
			@Context ResourceContext resourceContext) 
	{
		String sql = Utility.inputSanitizer(request.getParameter("sql"));

		// get the insight from the session
		if (sql == null) 
		{
			try {
				sql =Utility.inputSanitizer( IOUtils.toString(request.getReader()));
				if (sql != null && sql.length() != 0) {
					sql = sql.replace("'", "\\\'");
					sql = sql.replace("\"", "\\\"");
					// System.err.println(sql2);
				}
			} catch (IOException e1) {
				// TODO Auto-generated catch block
				logger.error(Constants.STACKTRACE, e1);
			}
		}

		String projectId = request.getParameter("projectId");
		String insightId = request.getParameter("insightId");
		String outputFormat = request.getParameter("format");
		
		if(outputFormat == null)
			outputFormat = "json";
		
		NameServer server = resourceContext.getResource(NameServer.class);
		OverrideParametersServletRequest requestWrapper = new OverrideParametersServletRequest(request);
		Map<String, String> paramMap = new HashMap<String, String>();

		// open this insight
		String pixel = "META | OpenInsight(project=[\"" + projectId + "\"], id=[\"" + insightId
				+ "\"], additionalPixels=[\"ReadInsightTheme();\"]);";
		paramMap.put("insightId", "new");
		paramMap.put("expression", pixel);
		requestWrapper.setParameters(paramMap);
		Response resp = server.runPixelSync(requestWrapper);

		StreamingOutput utility = (StreamingOutput) resp.getEntity();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try 
		{
			utility.write(output);
			String s = new String(output.toByteArray());
			System.out.println(s);
			JSONObject obj = new JSONObject(s);
			// pixelReturn[0].output.insightData.insightId
			insightId = obj.getJSONArray("pixelReturn").getJSONObject(0).getJSONObject("output")
					.getJSONObject("insightData").getString("insightID");
			System.err.println("Insight ID is " + insightId);
		} 
		catch (WebApplicationException | IOException e) 
		{
			logger.error(Constants.STACKTRACE, e);
		}

		Insight insight = InsightStore.getInstance().get(insightId);
		if(insight!=null)
		{
			Object retOutput = null;
			if(outputFormat.equalsIgnoreCase("json"))
			{
				retOutput = insight.queryJSON(sql, null);
					return WebUtility.getSOFile(retOutput+"");					
			}
			else if(outputFormat.equalsIgnoreCase("csv"))
			{
				retOutput = insight.queryCSV(sql, null);
				if(retOutput != null)
					return WebUtility.getSOFile(retOutput+"");					
			}
		}
		else 
			return WebUtility.getSO("No such insight");

		return null;
	}
}