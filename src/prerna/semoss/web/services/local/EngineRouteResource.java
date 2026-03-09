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

import java.io.File;
import java.io.FileFilter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.engine.api.IEngine;
import prerna.engine.impl.CaseInsensitiveProperties;
import prerna.engine.impl.SmssUtilities;
import prerna.io.connector.couch.CouchException;
import prerna.io.connector.couch.CouchUtil;
import prerna.io.connector.secrets.ISecrets;
import prerna.io.connector.secrets.SecretsFactory;
import prerna.notifications.NotificationDbUtils;
import prerna.util.Constants;
import prerna.util.DefaultImageGeneratorUtil;
import prerna.util.EmailUtility;
import prerna.util.EngineUtility;
import prerna.util.NotificationConstants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/e-{engineId}")
@PermitAll
public class EngineRouteResource {

	private static final Logger classLogger = LogManager.getLogger(ModelEngineResource.class);

	private boolean canAccessOrDiscoverableEngine(User user, String engineId) throws IllegalAccessException {
		engineId = SecurityQueryUtils.testUserEngineIdForAlias(user, engineId);
		if (!SecurityEngineUtils.userCanViewEngine(user, engineId)
				&& !SecurityEngineUtils.engineIsDiscoverable(engineId)) {
			throw new IllegalAccessException("Engine " + engineId + " does not exist or user does not have access");
		}

		return true;
	}

	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////

	@POST
	@Path("/updateSmssFile")
	@Produces("application/json;charset=utf-8")
	public Response updateSmssFile(@Context HttpServletRequest request, @PathParam("engineId") String engineId) {
		engineId = WebUtility.inputSanitizer(engineId);

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		try {
			boolean isAdmin = SecurityAdminUtils.userIsAdmin(user);
			if (!isAdmin) {
				boolean isOwner = SecurityEngineUtils.userIsOwner(user, engineId);
				if (!isOwner) {
					throw new IllegalAccessException("Engine " + engineId
							+ " does not exist or user does not have permissions to update the smss. User must be the owner to perform this function.");
				}
			}
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		IEngine engine = Utility.getEngine(engineId);
		String currentSmssFileLocation = engine.getSmssFilePath();
		File currentSmssFile = new File(currentSmssFileLocation);
		if (!currentSmssFile.exists() || !currentSmssFile.isFile()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Could not find current engie smss file");
			return WebUtility.getResponse(errorMap, 400);
		}

		Properties currentSmssProperties = engine.getSmssProp();
		String newSmssContent = request.getParameter("smss");

		// validate the new SMSS
		// that the user is not doing something they cannot do
		// like update the engine id / alias
		{
			Properties newProp = new CaseInsensitiveProperties(Utility.loadPropertiesString(newSmssContent));
			if (!newProp.get(Constants.ENGINE).equals(currentSmssProperties.get(Constants.ENGINE))
					|| !newProp.get(Constants.ENGINE_ALIAS).equals(currentSmssProperties.get(Constants.ENGINE_ALIAS))
					|| !newProp.get(Constants.ENGINE_TYPE).equals(currentSmssProperties.get(Constants.ENGINE_TYPE))) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "The engine id, engine name, and engine type cannot be changed");
				return WebUtility.getResponse(errorMap, 400);
			}
		}

		// this section is for secrets
		ISecrets secretStore = SecretsFactory.getSecretConnector();
		if (secretStore != null) {
			try {
				Map<String, Object> currentSecrets = secretStore.getEngineSecrets(engine.getCatalogType(),
						engine.getEngineId(), engine.getEngineName());

				String unconcealedNewSmssContent = SmssUtilities.unconcealSmssSensitiveInfo(newSmssContent,
						currentSmssProperties, currentSecrets);
				Properties newProp = new CaseInsensitiveProperties(
						Utility.loadPropertiesString(unconcealedNewSmssContent));

				secretStore.writeEngineSecrets(engine.getCatalogType(), engine.getEngineId(), engine.getEngineName(),
						newProp.stringPropertyNames().stream().collect(Collectors.toMap(key -> key, newProp::get)));

			} catch (Exception e) {
				classLogger.error("An error occurred saving the engine details in the secret store", e);
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE,
						"An error occurred initializing the new engine details. Detailed message = " + e.getMessage());
				return WebUtility.getResponse(errorMap, 400);
			}

			try {
				engine.close();
				engine.open(currentSmssFileLocation);
			} catch (Exception e) {
				classLogger.error(Constants.STACKTRACE, e);
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE,
						"An error occurred re-connecting to the engine with the new credentials. Detailed message = "
								+ e.getMessage());
				return WebUtility.getResponse(errorMap, 400);
			}

			Map<String, Object> success = new HashMap<>();
			success.put("success", true);
			return WebUtility.getResponse(success, 200);
		}

		// using the current smss properties
		// and the new file contents
		// unconceal any hidden values that have not been altered
		String unconcealedNewSmssContent = SmssUtilities.unconcealSmssSensitiveInfo(newSmssContent,
				currentSmssProperties);

		// read the current smss as text in case of an error
		String currentSmssContent = null;
		try {
			currentSmssContent = new String(Files.readAllBytes(Paths.get(currentSmssFile.toURI())));
		} catch (IOException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE,
					"An error occurred reading the current engine smss details. Detailed message = " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		try {
			engine.close();
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE,
					"An error occurred closing the engine. Detailed message = " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		try {
			try (FileWriter fw = new FileWriter(currentSmssFile, false)) {
				fw.write(unconcealedNewSmssContent);
			}
			engine.open(currentSmssFileLocation);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			// reset the values
			try {
				// close the engine again
				engine.close();
			} catch (IOException e1) {
				classLogger.error(Constants.STACKTRACE, e1);
			}
			currentSmssFile.delete();
			try (FileWriter fw = new FileWriter(currentSmssFile, false)) {
				fw.write(currentSmssContent);
				engine.open(currentSmssFileLocation);
			} catch (Exception e2) {
				classLogger.error(Constants.STACKTRACE, e2);
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE,
						"A fatal error occurred and could not revert the engine to an operational state. Detailed message = "
								+ e2.getMessage());
				return WebUtility.getResponse(errorMap, 400);
			}
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE,
					"An error occurred initializing the new engine details. Detailed message = " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		// push to cloud
		ClusterUtil.pushEngineSmss(engineId, engine.getCatalogType());
		if (Utility.isNotificationDatabaseEnabled()) {
			// Adding notification
			String engineType = String.valueOf(SecurityEngineUtils.getEngineType(engineId)).toLowerCase();
			NotificationDbUtils.createNotification(user, null, null, engineId, NotificationConstants.Type.SMSS_UPDATE,
					engineType, NotificationConstants.Priority.MEDIUM, null, null);

			// Adding email notification
			EmailUtility.sendSmssUpdateEmailNotification(user, engineId, EmailUtility.RESOURCE_TYPE.ENGINE);
		}

		Map<String, Object> success = new HashMap<>();
		success.put("success", true);
		return WebUtility.getResponse(success, 200);
	}

	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////

	/*
	 * Code below is around engine images
	 */

	@GET
	@Path("/image/download")
	@Produces({ MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_SVG_XML })
	public Response imageDownload(@Context final Request coreRequest, @Context HttpServletRequest request,
			@PathParam("engineId") String engineId) {
		engineId = WebUtility.inputSanitizer(engineId);

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		IEngine.CATALOG_TYPE engineType = null;
		Object[] typeAndSubtype = null;
		try {
			typeAndSubtype = SecurityEngineUtils.getEngineTypeAndSubtype(engineId);
			engineType = (IEngine.CATALOG_TYPE) typeAndSubtype[0];
		} catch (Exception e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", "Unknown engine with id " + engineId);
			return WebUtility.getResponse(errorMap, 400);
		}
		try {
			canAccessOrDiscoverableEngine(user, engineId);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		String engineName = SecurityEngineUtils.getEngineAliasForId(engineId);
		String engineNameAndId = SmssUtilities.getUniqueName(engineName, engineId);

		// will define these here up front
		String couchSelector = null;
		String engineVersionPath = null;
		try {
			couchSelector = EngineUtility.getCouchSelector(engineType);
			engineVersionPath = EngineUtility.getSpecificEngineVersionFolder(engineType, engineNameAndId);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> returnMap = new HashMap<>();
			returnMap.put(Constants.ERROR_MESSAGE,
					"Unknown engine type '" + engineType + "' for engine " + engineNameAndId);
			return WebUtility.getResponse(returnMap, 400);
		}

		File exportFile = null;
		// is the image in couch db
		if (CouchUtil.COUCH_ENABLED) {
			try {
				Map<String, String> selectors = new HashMap<>();
				selectors.put(couchSelector, engineId);
				return CouchUtil.download(couchSelector, selectors);
			} catch (CouchException e) {
				classLogger.error(Constants.STACKTRACE, e);
			}
		}
		// is the image in cloud storage
		else if (ClusterUtil.IS_CLUSTER) {
			try {
				exportFile = ClusterUtil.getEngineAndProjectImage(engineId, engineType);
			} catch (Exception e) {
				classLogger.error(Constants.STACKTRACE, e);
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Error sending image file");
				return WebUtility.getResponse(errorMap, 400);
			}
			// is the image local in engine folder
		} else {
			exportFile = findImageFile(engineVersionPath);
			if (exportFile == null) {
				// make the image
				String fileLocation = engineVersionPath + "/" + "image.png";
				exportFile = DefaultImageGeneratorUtil.pickRandomImage(fileLocation);
			}
		}

		if (exportFile != null && exportFile.exists()) {
			String exportName = engineId + "_Image." + FilenameUtils.getExtension(exportFile.getAbsolutePath());
			// want to cache this on browser if user has access
//			CacheControl cc = new CacheControl();
//			cc.setMaxAge(86400);
//			cc.setPrivate(true);
//			cc.setMustRevalidate(true);
			EntityTag etag = new EntityTag(Long.toString(exportFile.lastModified()));
			ResponseBuilder builder = coreRequest.evaluatePreconditions(etag);

			// cached resource did not change
			if (builder != null) {
				return builder.build();
			}

			return Response.status(200).entity(exportFile)
					.header("Content-Disposition", "attachment; filename=" + exportName)
//					.cacheControl(cc)
					.tag(etag)
//					.lastModified(new Date(exportFile.lastModified()))
					.build();
		} else {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error sending image file");
			return WebUtility.getResponse(errorMap, 400);
		}
	}

	/**
	 * Find an image in the directory
	 * 
	 * @param folderDirectory
	 * @return
	 */
	private File findImageFile(String folderDirectory) {
		List<String> extensions = new ArrayList<>();
		extensions.add("image.png");
		extensions.add("image.jpeg");
		extensions.add("image.jpg");
		extensions.add("image.gif");
		extensions.add("image.svg");
		FileFilter imageExtensionFilter = new WildcardFileFilter(extensions);
		File baseFolder = new File(WebUtility.normalizePath(folderDirectory));
		File[] imageFiles = baseFolder.listFiles(imageExtensionFilter);
		if (imageFiles != null && imageFiles.length > 0) {
			return imageFiles[0];
		}
		return null;
	}

}
