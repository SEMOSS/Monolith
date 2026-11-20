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

import prerna.auth.utils.CatalogDependencyDerivation;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import prerna.auth.User;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.engine.api.IEngine;
import prerna.engine.impl.CaseInsensitiveProperties;
import prerna.engine.impl.SmssEditCopy;
import prerna.engine.impl.SmssUtilities;
import prerna.io.connector.couch.CouchException;
import prerna.io.connector.couch.CouchUtil;
import prerna.io.connector.secrets.ISecrets;
import prerna.io.connector.secrets.SecretsFactory;
import prerna.notifications.NotificationDbUtils;
import prerna.upload.CatalogImageUploader;
import prerna.util.Constants;
import prerna.util.ContentChangedException;
import prerna.util.DefaultImageGeneratorUtil;
import prerna.util.EmailUtility;
import prerna.util.EngineSyncUtility;
import prerna.util.EngineUtility;
import prerna.util.NotificationConstants;
import prerna.util.Utility;
import prerna.web.services.util.CatalogImageResponse;
import prerna.web.services.util.WebUtility;

@Path("/e-{engineId}")
@PermitAll
public class EngineRouteResource {

	private static final Logger classLogger = LogManager.getLogger(EngineRouteResource.class);

	public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	/**
	 * Append {@code key=<json value>} to a pixel argument list when the body has a
	 * non-null, non-empty value for the given key. The leading {@code ", "} is
	 * added on the assumption that the caller already wrote at least one argument
	 * before this one.
	 */
	public static void appendIfPresent(StringBuilder pixel, Map<String, Object> body, String key) {
		Object value = body.get(key);
		if (value == null) {
			return;
		}
		if (value instanceof String && ((String) value).isEmpty()) {
			return;
		}
		pixel.append(", ").append(key).append("=").append(GSON.toJson(value));
	}

	/**
	 * Build an error response with the standard error message envelope used by the
	 * engine resource endpoints.
	 */
	public static Response error(String message, int status) {
		Map<String, String> errorMap = new HashMap<>();
		errorMap.put(Constants.ERROR_MESSAGE, message);
		return WebUtility.getResponse(errorMap, status);
	}

	/**
	 * Verify that the given user is allowed to interact with this engine. The user
	 * is allowed if they either have explicit view permission or the engine is
	 * marked discoverable.
	 *
	 * @param user     the calling user
	 * @param engineId the engine id being accessed
	 * @return {@code true} when access is granted
	 * @throws IllegalAccessException if the engine does not exist or the user has
	 *                                no view/discoverable access
	 */
	private boolean canAccessOrDiscoverableEngine(User user, String engineId) throws IllegalAccessException {
		if (!SecurityEngineUtils.userCanViewEngine(user, engineId)
				&& !SecurityEngineUtils.engineIsDiscoverable(engineId)) {
			throw new IllegalAccessException("Engine " + engineId + " does not exist or user does not have access");
		}

		return true;
	}

	/**
	 * Return the {@link IEngine.CATALOG_TYPE} for this engine.
	 */
	@GET
	@Path("/type")
	@Produces("application/json;charset=utf-8")
	public Response getEngineType(@Context HttpServletRequest request, @PathParam("engineId") String engineId) {
		engineId = WebUtility.inputSanitizer(engineId);

		User user;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			return error("User session is invalid", 401);
		}
		try {
			canAccessOrDiscoverableEngine(user, engineId);
		} catch (IllegalAccessException e) {
			return error(e.getMessage(), 401);
		}

		IEngine.CATALOG_TYPE engineType;
		try {
			engineType = (IEngine.CATALOG_TYPE) SecurityEngineUtils.getEngineTypeAndSubtype(engineId)[0];
		} catch (Exception e) {
			classLogger.error("Failed to look up engine type for engine '{}'", engineId, e);
			return error("Unknown engine with id " + engineId, 400);
		}

		Map<String, Object> ret = new HashMap<>();
		ret.put("engineId", engineId);
		ret.put("engineType", engineType.toString());
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Replace the smss file backing this engine with the contents supplied in the
	 * {@code smss} form parameter. Only the engine owner (or an admin) may call
	 * this. The engine id, alias, and type are validated to be unchanged. Any
	 * concealed sensitive values (e.g. passwords) are unconcealed against the
	 * current values before persisting.
	 * <p>
	 * If a secret store is configured the new values are written there and the
	 * engine is reopened. Otherwise the smss file on disk is rewritten in place,
	 * with a best-effort rollback to the previous contents if reopening the engine
	 * with the new file fails.
	 * <p>
	 * On success this also pushes the updated smss to cloud storage (when in a
	 * cluster), records a notification, and emails the user.
	 *
	 * @param request  the incoming HTTP request (provides the user session and the
	 *                 {@code smss} parameter)
	 * @param engineId the engine to update
	 * @return 200 with {@code {"success": true}} and the new {@code smssHash} on
	 *         success; 400 with an error envelope on validation/IO failure or a
	 *         missing {@code smssHash}; 401 if the user is not the engine owner;
	 *         409 with {@code errorCode} {@code SMSS_CHANGED} when the smss changed
	 *         since the {@code smssHash} was read
	 */
	@POST
	@Path("/updateSmssFile")
	@Produces("application/json;charset=utf-8")
	public Response updateSmssFile(@Context HttpServletRequest request, @PathParam("engineId") String engineId) {
		// not required for containment. userIsOwner below resolves engineId against
		// the ENGINE table, so a traversal value is rejected before it reaches the
		// smss file path
		engineId = WebUtility.safePathSegment(WebUtility.inputSanitizer(engineId));
		if (!WebUtility.isSafePathSegment(engineId)) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Invalid engine id");
			return WebUtility.getResponse(errorMap, 400);
		}

		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		if (!SecurityEngineUtils.userIsOwner(user, engineId)) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", "Engine " + engineId
					+ " does not exist or user does not have permissions to update the smss. "
					+ "User must be the owner to perform this function.");
			return WebUtility.getResponse(errorMap, 401);
		}

		try {
			return WebUtility.getResponse(saveSmssFile(user, engineId, request.getParameter("smss"),
					request.getParameter(SmssEditCopy.SMSS_HASH)), 200);
		} catch (ContentChangedException e) {
			return ResourceUtility.changedResponse(e);
		} catch (IllegalArgumentException e) {
			return ResourceUtility.errorResponse(e.getMessage(), 400);
		}
	}

	/**
	 * Saves new smss content for an engine and opens the engine again with it. The
	 * save is made from the copy of the smss the user read: it goes through only
	 * while the smss is still as that copy was ({@link SmssEditCopy}), and holds
	 * the engine's lock so no other save comes between the check and the write.
	 * The engine's id, name and type stay as they are, and the hidden values the
	 * new content left untouched are kept. With a secret store the values go
	 * there; otherwise the smss file is written, and put back as it was when the
	 * engine does not open with the new content. The caller has checked that the
	 * user may update the engine: its owner, or an admin through the admin
	 * endpoint.
	 *
	 * @param user           the user saving the smss, for the notification
	 * @param engineId       the engine
	 * @param newSmssContent the new content of the smss file
	 * @param smssHash       the hash that came with the copy the user edited
	 * @return the response body, which says the save went through and carries the
	 *         new {@code smssHash}
	 * @throws ContentChangedException  when the smss changed since that copy was
	 *                                  read
	 * @throws IllegalArgumentException when the content cannot be saved or no hash
	 *                                  is given, with the reason
	 */
	public static Map<String, Object> saveSmssFile(User user, String engineId, String newSmssContent,
			String smssHash) {
		ReentrantLock lock = EngineSyncUtility.getEngineLock(engineId);
		lock.lock();
		try {
			return saveSmssFileWhileLocked(user, engineId, newSmssContent, smssHash);
		} finally {
			lock.unlock();
		}
	}

	private static Map<String, Object> saveSmssFileWhileLocked(User user, String engineId, String newSmssContent,
			String smssHash) {
		IEngine engine = Utility.getEngine(engineId);
		String currentSmssFileLocation = engine.getSmssFilePath();
		File currentSmssFile = new File(currentSmssFileLocation);
		if (!currentSmssFile.exists() || !currentSmssFile.isFile()) {
			throw new IllegalArgumentException("Could not find current engie smss file");
		}
		SmssEditCopy.requireCurrent(engine, smssHash);

		Properties currentSmssProperties = engine.getSmssProp();

		// validate the new SMSS
		// that the user is not doing something they cannot do
		// like update the engine id / alias
		{
			Properties newProp = new CaseInsensitiveProperties(Utility.loadPropertiesString(newSmssContent));
			if (!newProp.get(Constants.ENGINE).equals(currentSmssProperties.get(Constants.ENGINE))
					|| !newProp.get(Constants.ENGINE_ALIAS).equals(currentSmssProperties.get(Constants.ENGINE_ALIAS))
					|| !newProp.get(Constants.ENGINE_TYPE).equals(currentSmssProperties.get(Constants.ENGINE_TYPE))) {
				throw new IllegalArgumentException("The engine id, engine name, and engine type cannot be changed");
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
				throw new IllegalArgumentException(
						"An error occurred initializing the new engine details. Detailed message = " + e.getMessage());
			}

			try {
				engine.close();
				engine.open(currentSmssFileLocation);
			} catch (Exception e) {
				classLogger.error("Failed to reopen engine '{}' after writing updated secrets to the secret store",
						engineId, e);
				throw new IllegalArgumentException(
						"An error occurred re-connecting to the engine with the new credentials. Detailed message = "
								+ e.getMessage());
			}

			// the smss file is unchanged and the new values live in the secret store, so
			// the other nodes only need to close the engine to read them on next use
			ClusterUtil.publishEngineClose(engineId);

			// the smss may name a different judge model, vector database or embedder
			CatalogDependencyDerivation.syncSmss(user, engineId, engine.getCatalogType().name(), engine.getSmssProp());

			Map<String, Object> success = new HashMap<>();
			success.put("success", true);
			success.put(SmssEditCopy.SMSS_HASH, SmssEditCopy.read(engine).getSmssHash());
			return success;
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
			throw new IllegalArgumentException(
					"An error occurred reading the current engine smss details. Detailed message = " + e.getMessage());
		}

		try {
			engine.close();
		} catch (Exception e) {
			classLogger.error("Failed to close engine '{}' before overwriting its smss file", engineId, e);
			throw new IllegalArgumentException(
					"An error occurred closing the engine. Detailed message = " + e.getMessage());
		}
		try {
			try (FileWriter fw = new FileWriter(currentSmssFile, false)) {
				fw.write(unconcealedNewSmssContent);
			}
			engine.open(currentSmssFileLocation);
		} catch (Exception e) {
			classLogger.error(
					"Failed to write new smss content or reopen engine '{}' with the new properties; attempting rollback",
					engineId, e);
			// reset the values
			try {
				// close the engine again
				engine.close();
			} catch (IOException e1) {
				classLogger.error("Failed to close engine '{}' while rolling back smss update", engineId, e1);
			}
			currentSmssFile.delete();
			try (FileWriter fw = new FileWriter(currentSmssFile, false)) {
				fw.write(currentSmssContent);
				engine.open(currentSmssFileLocation);
			} catch (Exception e2) {
				classLogger.error(
						"Failed to restore previous smss for engine '{}' during rollback - engine is left in an unrecoverable state",
						engineId, e2);
				throw new IllegalArgumentException(
						"A fatal error occurred and could not revert the engine to an operational state. "
								+ "Detailed message = " + e2.getMessage());
			}
			throw new IllegalArgumentException(
					"An error occurred initializing the new engine details. Detailed message = " + e.getMessage());
		}

		// push to cloud
		ClusterUtil.pushEngineSmss(engineId, engine.getCatalogType());
		if (Utility.isNotificationDatabaseEnabled()) {
			// Adding notification
			String engineType = String.valueOf(SecurityEngineUtils.getEngineType(engineId)).toLowerCase();
			NotificationDbUtils.createNotification(user, null, null, engineId, NotificationConstants.Type.SMSS_UPDATE,
					engineType, NotificationConstants.Priority.MEDIUM, null, null,
					NotificationConstants.DisplaySurface.BELL);

			// Adding email notification
			EmailUtility.sendSmssUpdateEmailNotification(user, engineId, EmailUtility.RESOURCE_TYPE.ENGINE);
		}

		// the smss may name a different judge model, vector database or embedder
		CatalogDependencyDerivation.syncSmss(user, engineId, engine.getCatalogType().name(), engine.getSmssProp());

		Map<String, Object> success = new HashMap<>();
		success.put("success", true);
		success.put(SmssEditCopy.SMSS_HASH, SmssEditCopy.read(engine).getSmssHash());
		return success;
	}

	/*
	 * Code below is around engine images
	 */

	/**
	 * Replace this engine's catalog image with one multipart file named
	 * {@code file}. Requires edit permission; accepts PNG, JPEG, or GIF up to 10
	 * MiB.
	 */
	@POST
	@Path("/image/upload")
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	@Produces(MediaType.APPLICATION_JSON)
	public Response uploadImage(@Context ServletContext context, @Context HttpServletRequest request,
			@PathParam("engineId") String engineId) {
		return CatalogImageUploader.upload(context, request, engineId, false);
	}

	/**
	 * Download the image associated with this engine. The lookup falls through
	 * three sources in order: CouchDB (if enabled), cloud storage (if running in
	 * cluster mode), and finally the engine's local version folder. If no image
	 * exists locally the shared stock image is served without copying it. The
	 * optional {@code theme=light|dark} query parameter selects the stock artwork
	 * theme.
	 * <p>
	 * Honors HTTP {@code If-None-Match} via an entity tag built from the file's
	 * path, last-modified timestamp, and size, so an unchanged image returns 304.
	 *
	 * @param coreRequest the JAX-RS request, used for cache precondition evaluation
	 * @param request     the underlying HTTP request (provides the user session)
	 * @param engineId    the engine whose image is being downloaded
	 * @return 200 with the image bytes (or the appropriate 3xx via
	 *         {@link Request#evaluatePreconditions}); 400 if the image cannot be
	 *         resolved; 401 if the user has no view/discoverable access
	 */
	@GET
	@Path("/image/download")
	@Produces({ "image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml",
			MediaType.APPLICATION_OCTET_STREAM })
	public Response imageDownload(@Context final Request coreRequest, @Context HttpServletRequest request,
			@PathParam("engineId") String engineId) {
		// not required for containment. getEngineTypeAndSubtype and
		// canAccessOrDiscoverableEngine below resolve engineId against the ENGINE
		// table,
		// so a traversal value is rejected before it reaches the image path
		engineId = WebUtility.safePathSegment(WebUtility.inputSanitizer(engineId));
		if (!WebUtility.isSafePathSegment(engineId)) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Invalid engine id");
			return WebUtility.getResponse(errorMap, 400);
		}

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
			classLogger.error(
					"Failed to resolve couch selector or engine version folder for engine '{}' (type {}) during image download",
					engineNameAndId, engineType, e);
			Map<String, String> returnMap = new HashMap<>();
			returnMap.put(Constants.ERROR_MESSAGE,
					"Unknown engine type '" + engineType + "' for engine " + engineNameAndId);
			return WebUtility.getResponse(returnMap, 400);
		}

		String imageTheme = request.getParameter("theme");
		File exportFile = null;
		// is the image in couch db
		if (CouchUtil.COUCH_ENABLED) {
			try {
				Map<String, String> selectors = new HashMap<>();
				selectors.put(couchSelector, engineId);
				return CatalogImageResponse.withBrowserCache(coreRequest,
						CouchUtil.download(couchSelector, selectors, imageTheme));
			} catch (CouchException e) {
				classLogger.error(
						"Failed to download engine image from CouchDB for engine '{}' using selector '{}'; falling through to other sources",
						engineId, couchSelector, e);
			}
		}
		// is the image in cloud storage
		else if (ClusterUtil.IS_CLUSTER) {
			try {
				exportFile = ClusterUtil.getEngineAndProjectImage(engineId, engineType, imageTheme);
			} catch (Exception e) {
				classLogger.error("Failed to fetch engine image from cluster storage for engine '{}' (type {})",
						engineId, engineType, e);
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Error sending image file");
				return WebUtility.getResponse(errorMap, 400);
			}
			// is the image local in engine folder
		} else {
			exportFile = findImageFile(engineVersionPath);
			if (exportFile == null) {
				// Resolve the shared stock file without creating an engine asset.
				String fileLocation = engineVersionPath + "/" + "image.png";
				exportFile = DefaultImageGeneratorUtil.getStockImageForPath(fileLocation, imageTheme);
			}
		}

		if (exportFile != null && exportFile.exists()) {
			String exportName = engineId + "_Image." + FilenameUtils.getExtension(exportFile.getAbsolutePath());
			EntityTag etag = new EntityTag(Integer.toHexString(exportFile.getAbsolutePath().hashCode()) + "-"
					+ exportFile.lastModified() + "-" + exportFile.length());
			return CatalogImageResponse.withBrowserCache(coreRequest, Response.ok(exportFile)
					.header("Content-Disposition", "attachment; filename=" + exportName).tag(etag).build());
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
		FileFilter imageExtensionFilter = WildcardFileFilter.builder().setWildcards(extensions).get();
		File baseFolder = new File(WebUtility.normalizePath(folderDirectory));
		File[] imageFiles = baseFolder.listFiles(imageExtensionFilter);
		if (imageFiles != null && imageFiles.length > 0) {
			return imageFiles[0];
		}
		return null;
	}

}
