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
package prerna.insights.admin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.log4j.Logger;

import prerna.rpa.RPAProps;
import prerna.web.services.util.WebUtility;

public class DBAdminResource {

	private static final Logger LOGGER = Logger.getLogger(DBAdminResource.class.getName());
	private static final int MAX_CHAR = 100;
	private boolean securityEnabled;

	public void setSecurityEnabled(boolean securityEnabled) {
		this.securityEnabled = securityEnabled;
	}

	@POST
	@Path("/scheduleJob")
	@Produces("application/json")
	public Response scheduleJob(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String json = form.getFirst("json");
		String fileName = form.getFirst("fileName");
		try {
			String jsonDirectory = RPAProps.getInstance().getProperty(RPAProps.JSON_DIRECTORY_KEY);
			String jsonFilePath = jsonDirectory + fileName + ".json";
			try (FileWriter file = new FileWriter(jsonDirectory + fileName + ".json")) {
				file.write(json);
			} catch (IOException e) {
				String errorMessage = "failed to write file to " + jsonFilePath + ": " + e.toString();
				return WebUtility.getResponse(errorMessage.substring(0,
						(errorMessage.length() < MAX_CHAR) ? errorMessage.length() : MAX_CHAR), 500);
			}
		} catch (Exception e) {
			String errorMessage = "failed to retrieve the json directory: " + e.toString();
			return WebUtility.getResponse(errorMessage.substring(0,
					(errorMessage.length() < MAX_CHAR) ? errorMessage.length() : MAX_CHAR), 500);
		}
		return WebUtility.getResponse("success", 200);
	}
	
	@POST
	@Path("/unscheduleJob")
	@Produces("application/json")
	public Response unscheduleJob(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String fileName = form.getFirst("fileName");
		try {
			String jsonDirectory = RPAProps.getInstance().getProperty(RPAProps.JSON_DIRECTORY_KEY);
			String jsonFilePath = jsonDirectory + fileName + ".json";
			File file = new File(jsonFilePath);
			try {
				file.delete();
			} catch (SecurityException e) {
				String errorMessage = "failed to delete file " + jsonFilePath + ": " + e.toString();
				return WebUtility.getResponse(errorMessage.substring(0,
						(errorMessage.length() < MAX_CHAR) ? errorMessage.length() : MAX_CHAR), 500);
			}
		} catch (Exception e) {
			String errorMessage = "failed to retrieve the json directory: " + e.toString();
			return WebUtility.getResponse(errorMessage.substring(0,
					(errorMessage.length() < MAX_CHAR) ? errorMessage.length() : MAX_CHAR), 500);
		}
		return WebUtility.getResponse("success", 200);
	}
	
	@POST
	@Path("/listJobs")
	@Produces("application/json")
	public Response listJobs(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		List<String> jobNames = new ArrayList<String>();
		try {
			File jsonDirectory = new File(RPAProps.getInstance().getProperty(RPAProps.JSON_DIRECTORY_KEY));
			File[] files = jsonDirectory.listFiles();
			for (File file : files) {
				if (file.getName().endsWith(".json")) {
					jobNames.add(file.getName().substring(0, file.getName().length() - 5));
				}
			}
		} catch (Exception e) {
			String errorMessage = "failed to retrieve the json directory: " + e.toString();
			return WebUtility.getResponse(errorMessage.substring(0,
					(errorMessage.length() < MAX_CHAR) ? errorMessage.length() : MAX_CHAR), 500);
		}
		return WebUtility.getResponse(jobNames, 200);
	}
}
