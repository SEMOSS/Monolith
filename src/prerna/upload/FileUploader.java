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
package prerna.upload;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.annotation.security.PermitAll;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.io.FilenameUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.mime.MimeTypes;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.engine.api.IEngine;
import prerna.io.connector.antivirus.VirusScannerUtils;
import prerna.io.connector.antivirus.VirusScanningException;
import prerna.om.HeadersException;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.ThreadStore;
import prerna.project.api.IProject;
import prerna.util.AssetUtility;
import prerna.util.Constants;
import prerna.util.EngineUtility;
import prerna.util.Utility;
import prerna.util.git.GitRepoUtils;
import prerna.web.services.util.WebUtility;

@Path("/uploadFile")
@PermitAll
public class FileUploader extends Uploader {

	private static final long serialVersionUID = 1L;

	private static final Logger classLogger = LogManager.getLogger(FileUploader.class);

	/*
	 * Moving a file onto the BE cannot be performed through pixel Thus, we still
	 * expose "drag and drop" of a file through a rest call However, this is only
	 * used to push the file to the BE server, the actual processing of the file to
	 * create/add to a data frame occurs through pixel
	 */

	/**
	 * Checks user-defined headers for illegal characters, reserved words, and
	 * duplicates.
	 * 
	 * @param form The form data containing the upload type and headers to check.
	 * @return A response containing a map of invalid headers and the reasons why
	 *         they are invalid.
	 */
	@POST
	@Path("/headerCheck")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response checkUserDefinedHeaders(MultivaluedMap<String, String> form) {
		Gson gson = new Gson();

		// this will let me know if I should expect the csv data type map or the excel
		// data type map
		// this is really annoying...
		// TODO: really should consider consolidating the formats it will make csv
		// format look dumb
		// since its a useless additional key but then I wouldn't need to have the
		// bifurcation in
		// formats here and bifurcation in formats in the ImportOptions object as well
		String type = WebUtility.inputSQLSanitizer(form.getFirst("uploadType").toUpperCase());
		String headersToCheckString = WebUtility.inputSQLSanitizer(form.getFirst("userHeaders"));
		// grab the checker
		HeadersException headerChecker = HeadersException.getInstance();
		if (type.equalsIgnoreCase("EXCEL")) {
			List<Map<String, Map<String, String>>> invalidHeadersList = new ArrayList<>();

			// each entry (outer map object) in the list if a workbook
			// each key in that map object is the sheetName for that given workbook
			// the list are the headers inside that sheet
			List<Map<String, String[]>> userDefinedHeadersMap = null;
			try {
				userDefinedHeadersMap = gson.fromJson(headersToCheckString,
						new TypeToken<List<Map<String, String[]>>>() {
						}.getType());
			} catch (Exception e) {
				classLogger.error("Error parsing user defined headers", e);
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE,
						"Invalid format passed for user defined headers: " + headersToCheckString);
				return WebUtility.getResponse(errorMap, 400);
			}

			// iterate through each workbook
			for (Map<String, String[]> excelWorkbook : userDefinedHeadersMap) {
				Map<String, Map<String, String>> invalidHeadersMap = new HashMap<>();

				for (String sheetName : excelWorkbook.keySet()) {
					// grab all the headers for the given sheet
					String[] userHeaders = excelWorkbook.get(sheetName);

					// now we need to check all of these headers
					for (int colIdx = 0; colIdx < userHeaders.length; colIdx++) {
						String userHeader = userHeaders[colIdx];
						Map<String, String> badHeaderMap = new HashMap<>();
						if (headerChecker.isIllegalHeader(userHeader)) {
							badHeaderMap.put(userHeader, "This header name is a reserved word");
						} else if (headerChecker.containsIllegalCharacter(userHeader)) {
							badHeaderMap.put(userHeader, "Header names cannot contain +%@;");
						} else if (headerChecker.isDuplicated(userHeader, userHeaders, colIdx)) {
							badHeaderMap.put(userHeader, "Cannot have duplicate header names");
						}

						// map is filled in only if the header is bad
						if (!badHeaderMap.isEmpty()) {
							// need to make sure we do not override existing bad headers stored
							// within the map
							Map<String, String> invalidHeadersForSheet = null;
							if (invalidHeadersMap.containsKey(sheetName)) {
								invalidHeadersForSheet = invalidHeadersMap.get(sheetName);
							} else {
								invalidHeadersForSheet = new HashMap<>();
							}

							// now add in the bad header for the file map
							invalidHeadersForSheet.putAll(badHeaderMap);
							// now store it in the overall object
							invalidHeadersMap.put(sheetName, invalidHeadersForSheet);
						}
					}
				}

				// now store the invalid headers map inside the list
				// even if it is empty, we need to store it since the FE does this based on
				// indices
				invalidHeadersList.add(invalidHeadersMap);
			}
			return WebUtility.getResponse(invalidHeadersList, 200);
		} else {
			Map<String, Map<String, String>> invalidHeadersMap = new HashMap<>();

			// the key is for each file name
			// the list are the headers inside that file
			Map<String, String[]> userDefinedHeadersMap = null;
			try {
				userDefinedHeadersMap = gson.fromJson(headersToCheckString, new TypeToken<Map<String, String[]>>() {
				}.getType());
			} catch (Exception e) {
				classLogger.error("Error parsing user defined headers", e);
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE,
						"Invalid format passed for user defined headers: " + headersToCheckString);
				return WebUtility.getResponse(errorMap, 400);
			}

			for (String fileName : userDefinedHeadersMap.keySet()) {
				String[] userHeaders = userDefinedHeadersMap.get(fileName);

				// now we need to check all of these headers
				// now we need to check all of these headers
				for (int colIdx = 0; colIdx < userHeaders.length; colIdx++) {
					String userHeader = userHeaders[colIdx];
					Map<String, String> badHeaderMap = new HashMap<>();
					if (headerChecker.isIllegalHeader(userHeader)) {
						badHeaderMap.put(userHeader, "This header name is a reserved word");
					} else if (headerChecker.containsIllegalCharacter(userHeader)) {
						badHeaderMap.put(userHeader, "Header names cannot contain +%@;");
					} else if (headerChecker.isDuplicated(userHeader, userHeaders, colIdx)) {
						badHeaderMap.put(userHeader, "Cannot have duplicate header names");
					}

					// map is filled in only if the header is bad
					if (!badHeaderMap.isEmpty()) {
						// need to make sure we do not override existing bad headers stored
						// within the map
						Map<String, String> invalidHeadersForFile = null;
						if (invalidHeadersMap.containsKey(fileName)) {
							invalidHeadersForFile = invalidHeadersMap.get(fileName);
						} else {
							invalidHeadersForFile = new HashMap<>();
						}

						// now add in the bad header for the file map
						invalidHeadersForFile.putAll(badHeaderMap);
						// now store it in the overall object
						invalidHeadersMap.put(fileName, invalidHeadersForFile);
					}
				}
			}
			return WebUtility.getResponse(invalidHeadersMap, 200);
		}
	}

	/**
	 * Uploads a file to the server.
	 * 
	 * @param context      The servlet context.
	 * @param request      The HTTP servlet request.
	 * @param insightId    The ID of the insight to upload the file to.
	 * @param relativePath The relative path to upload the file to.
	 * @param projectId    The ID of the project to upload the file to.
	 * @param engineId     The ID of the engine to upload the file to.
	 * @return A response containing a list of maps with the file name and location.
	 */
	@POST
	@Path("baseUpload")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	public Response baseUpload(@Context ServletContext context, @Context HttpServletRequest request,
			@QueryParam("insightId") String insightId, @QueryParam("path") String relativePath,
			@QueryParam("projectId") String projectId, @QueryParam("engineId") String engineId,
			@QueryParam("userSpace") boolean userSpace) {

		insightId = WebUtility.inputSanitizer(insightId);
		relativePath = WebUtility.inputSanitizer(relativePath);
		projectId = WebUtility.inputSanitizer(projectId);
		engineId = WebUtility.inputSanitizer(engineId);

		Insight in = getValidInsight(insightId);
		if (in == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Session could not be validated in order to upload files");
			return WebUtility.getResponse(errorMap, 400);
		}
		User user = in.getUser();

		Response permResponse = checkGeneralUserPermissions(user);
		if (permResponse != null) {
			return permResponse;
		}

		if (user.isAnonymous() && in.isSavedInsight()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must be logged in to upload files to a saved insight");
			return WebUtility.getResponse(errorMap, 400);
		}

		if (in.isSavedInsight() && !SecurityInsightUtils.userCanEditInsight(user, in.getProjectId(), in.getRdbmsId())) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User does not edit access for this insight");
			return WebUtility.getResponse(errorMap, 400);
		}

		if (projectId != null && !projectId.equalsIgnoreCase("user")) {
			permResponse = checkProjectEditPermission(user, projectId);
			if (permResponse != null) {
				return permResponse;
			}
		}

		if (engineId != null) {
			permResponse = checkEngineEditPermission(user, engineId);
			if (permResponse != null) {
				return permResponse;
			}
		}

		if (projectId != null && engineId != null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Cannot provide both a projectId and engineId in the same request");
			return WebUtility.getResponse(errorMap, 422);
		}

		ThreadStore.setSessionId(request.getSession().getId());
		try {
			List<FileItem> fileItems = processRequest(context, request, insightId);
			// collect all of the data input on the form
			List<Map<String, String>> inputData = getBaseUploadData(fileItems, in, relativePath, projectId, engineId,
					userSpace, user);
			return WebUtility.getResponse(inputData, 200);
		} catch (VirusScanningException e) {
			classLogger.error("Virus scan failed during upload", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Error during file upload", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error uploading file. Error = " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} finally {
			ThreadStore.remove();
		}
	}

	/**
	 * Method to parse just files and move to the server
	 * 
	 * @param fileItems    a list of maps containing the file name and file location
	 * @param in           The insight to upload the file to.
	 * @param relativePath The relative path to upload the file to.
	 * @param projectId    The ID of the project to upload the file to.
	 * @param engineId     The ID of the engine to upload the file to.
	 * @param userSpace    Boolean true to write to the user assets project.
	 * @param user         The user uploading the file.
	 * @return A list of maps containing the file name and file location.
	 * @throws VirusScanningException if a virus is detected in the file.
	 * @throws IOException            if an error occurs while writing the file.
	 */
	private List<Map<String, String>> getBaseUploadData(List<FileItem> fileItems, Insight in, String relativePath,
			String projectId, String engineId, boolean userSpace, User user)
			throws VirusScanningException, IOException {
		boolean pushEngine = false;
		boolean pushRoom = false;
		boolean pushUser = false;
		IEngine engine = null;

		// get base asset folder
		String assetFolder = null;
		String fePath = DIR_SEPARATOR;
		if (projectId != null) {
			if (projectId.equals("user")) {
				AuthProvider provider = user.getPrimaryLogin();
				projectId = user.getAssetProjectId(provider);
				String projectName = "Asset";
				assetFolder = AssetUtility.getUserAssetAppRootFolder(projectName, projectId);
				pushUser = true;
			} else {
				engine = Utility.getProject(projectId);
				assetFolder = EngineUtility.getSpecificEngineAppRootFolder(IEngine.CATALOG_TYPE.PROJECT,
						engine.getEngineId(), engine.getEngineName());
				pushEngine = true;
			}
		} else if (engineId != null) {
			engine = Utility.getEngine(engineId);
			assetFolder = EngineUtility.getSpecificEngineBaseFolder(engine.getCatalogType(), engine.getEngineId(),
					engine.getEngineName());
			pushEngine = true;
		} else if (userSpace) {
			engine = user.getAssetProject();
			assetFolder = AssetUtility.getUserAssetFolder(engine.getEngineName(), engine.getEngineId());
		} else {
			assetFolder = in.getInsightFolder();
			if (in.getRoomId() != null) {
				pushRoom = true;
			}
		}
		String filePath = assetFolder;
		// add relative path
		if (relativePath != null) {
			filePath = assetFolder + DIR_SEPARATOR + WebUtility.normalizePath(relativePath);
			fePath += relativePath;
		}
		File fileDir = new File(WebUtility.normalizePath(filePath));
		if (!fileDir.exists()) {
			Boolean success = fileDir.mkdirs();
			if (!success) {
				classLogger.warn("Unable to make directory at location: {}", Utility.cleanLogString(filePath));
			}
		}

		List<Map<String, String>> retData = processFileItems(fileItems, filePath, fePath);
		if (pushEngine) {
			if (engine instanceof IProject) {
				if (userSpace) {
					ClusterUtil.pushUserAsset(engine.getEngineId());
				} else {
					ClusterUtil.pushProjectFolder((IProject) engine, filePath);
				}
			} else {
				ClusterUtil.pushEngineFolder(engine, filePath);
			}
		} else if (pushRoom) {
			ClusterUtil.pushRoom(in.getRoomId());
		} else if (pushUser) {
			ClusterUtil.pushUserAsset(projectId);
		}
		return retData;
	}

	/**
	 * Uploads a file to the user assets project.
	 *
	 * @param context      The servlet context.
	 * @param request      The HTTP servlet request.
	 * @param insightId    The ID of the insight to upload the file to.
	 * @param relativePath The relative path to upload the file to.
	 * @return A response containing a list of maps with the file name and location.
	 */
	@POST
	@Path("userAssetsUpload")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	public Response userAssetsUpload(@Context ServletContext context, @Context HttpServletRequest request,
			@QueryParam("insightId") String insightId, @QueryParam("path") String relativePath) {

		insightId = WebUtility.inputSanitizer(insightId);
		relativePath = WebUtility.inputSanitizer(relativePath);

		Insight in = getValidInsight(insightId);
		if (in == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Session could not be validated in order to upload files");
			return WebUtility.getResponse(errorMap, 400);
		}
		User user = in.getUser();

		Response permResponse = checkGeneralUserPermissions(user);
		if (permResponse != null) {
			return permResponse;
		}

		if (user.isAnonymous()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must be logged in to upload files to user assets");
			return WebUtility.getResponse(errorMap, 400);
		}

		ThreadStore.setSessionId(request.getSession().getId());
		try {
			List<FileItem> fileItems = processRequest(context, request, insightId);
			List<Map<String, String>> inputData = getBaseUploadData(fileItems, in, relativePath, null, null, true,
					user);
			return WebUtility.getResponse(inputData, 200);
		} catch (VirusScanningException e) {
			classLogger.error("Virus scan failed during upload", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Error during file upload", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error uploading file. Error = " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} finally {
			ThreadStore.remove();
		}
	}

	/**
	 * Uploads a file to the project assets.
	 *
	 * @param context      The servlet context.
	 * @param request      The HTTP servlet request.
	 * @param insightId    The ID of the insight to upload the file to.
	 * @param relativePath The relative path to upload the file to.
	 * @param projectId    The ID of the project to upload the file to.
	 * @return A response containing a list of maps with the file name and location.
	 */
	@POST
	@Path("projectAssetsUpload")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	public Response projectAssetsUpload(@Context ServletContext context, @Context HttpServletRequest request,
			@QueryParam("insightId") String insightId, @QueryParam("path") String relativePath,
			@QueryParam("projectId") String projectId) {

		insightId = WebUtility.inputSanitizer(insightId);
		relativePath = WebUtility.inputSanitizer(relativePath);
		projectId = WebUtility.inputSanitizer(projectId);

		Insight in = getValidInsight(insightId);
		if (in == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Session could not be validated in order to upload files");
			return WebUtility.getResponse(errorMap, 400);
		}
		User user = in.getUser();

		Response permResponse = checkGeneralUserPermissions(user);
		if (permResponse != null) {
			return permResponse;
		}

		if (projectId == null || (projectId = projectId.trim()).isEmpty()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must provide a project id.");
			return WebUtility.getResponse(errorMap, 400);
		}

		permResponse = checkProjectEditPermission(user, projectId);
		if (permResponse != null) {
			return permResponse;
		}

		ThreadStore.setSessionId(request.getSession().getId());
		try {
			List<FileItem> fileItems = processRequest(context, request, insightId);
			// collect all of the data input on the form
			IProject project = Utility.getProject(projectId);
			List<Map<String, String>> inputData = uploadEngineAssets(fileItems, in, relativePath, project, user);
			return WebUtility.getResponse(inputData, 200);
		} catch (VirusScanningException e) {
			classLogger.error("Virus scan failed during upload", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Error during file upload", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error uploading file. Error = " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} finally {
			ThreadStore.remove();
		}
	}

	/**
	 * Uploads a file to the engine assets.
	 * 
	 * @param context      The servlet context.
	 * @param request      The HTTP servlet request.
	 * @param insightId    The ID of the insight to upload the file to.
	 * @param relativePath The relative path to upload the file to.
	 * @param engineId     The ID of the engine to upload the file to.
	 * @return A response containing a list of maps with the file name and location.
	 */
	@POST
	@Path("engineAssetsUpload")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	public Response engineAssetsUpload(@Context ServletContext context, @Context HttpServletRequest request,
			@QueryParam("insightId") String insightId, @QueryParam("path") String relativePath,
			@QueryParam("engineId") String engineId) {

		insightId = WebUtility.inputSanitizer(insightId);
		relativePath = WebUtility.inputSanitizer(relativePath);
		engineId = WebUtility.inputSanitizer(engineId);

		Insight in = getValidInsight(insightId);
		if (in == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Session could not be validated in order to upload files");
			return WebUtility.getResponse(errorMap, 400);
		}
		User user = in.getUser();

		Response permResponse = checkGeneralUserPermissions(user);
		if (permResponse != null) {
			return permResponse;
		}

		if (engineId == null || (engineId = engineId.trim()).isEmpty()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must provide an engine id.");
			return WebUtility.getResponse(errorMap, 400);
		}

		permResponse = checkEngineEditPermission(user, engineId);
		if (permResponse != null) {
			return permResponse;
		}

		ThreadStore.setSessionId(request.getSession().getId());
		try {
			List<FileItem> fileItems = processRequest(context, request, insightId);
			// collect all of the data input on the form
			IEngine engine = Utility.getEngine(engineId);
			List<Map<String, String>> inputData = uploadEngineAssets(fileItems, in, relativePath, engine, user);
			return WebUtility.getResponse(inputData, 200);
		} catch (VirusScanningException e) {
			classLogger.error("Virus scan failed during upload", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} catch (Exception e) {
			classLogger.error("Error during file upload", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error uploading file. Error = " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		} finally {
			ThreadStore.remove();
		}
	}

	/**
	 * Validates that the insight and user are valid.
	 * 
	 * @param insightId The insight ID to validate.
	 * @return The Insight object if valid, otherwise null.
	 */
	private Insight getValidInsight(String insightId) {
		Insight in = InsightStore.getInstance().get(insightId);
		if (in == null || in.getUser() == null) {
			return null;
		}
		return in;
	}

	/**
	 * Validates general user permissions for uploading.
	 * 
	 * @param user The user to validate.
	 * @return A Response object if permissions are denied, otherwise null.
	 */
	private Response checkGeneralUserPermissions(User user) {
		if (user.isAnonymous() && !AbstractSecurityUtils.anonymousUserUploadData()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must be logged in to upload files");
			return WebUtility.getResponse(errorMap, 400);
		}

		if (AbstractSecurityUtils.adminSetPublisher() && !SecurityQueryUtils.userIsPublisher(user)) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE,
					"User does not have permission to publish data. Please reach out to the admin to get proper access");
			return WebUtility.getResponse(errorMap, 400);
		}
		return null;
	}

	/**
	 * Validates if the user has edit permission for a project.
	 * 
	 * @param user      The user to validate.
	 * @param projectId The project ID to check.
	 * @return A Response object if permissions are denied, otherwise null.
	 */
	private Response checkProjectEditPermission(User user, String projectId) {
		if (!SecurityProjectUtils.userCanEditProject(user, projectId)) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User does not have permission for this project.");
			return WebUtility.getResponse(errorMap, 400);
		}
		return null;
	}

	/**
	 * Validates if the user has edit permission for an engine.
	 * 
	 * @param user     The user to validate.
	 * @param engineId The engine ID to check.
	 * @return A Response object if permissions are denied, otherwise null.
	 */
	private Response checkEngineEditPermission(User user, String engineId) {
		if (!SecurityEngineUtils.userCanEditEngine(user, engineId)) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User does not have permission for this engine.");
			return WebUtility.getResponse(errorMap, 400);
		}
		return null;
	}

	/**
	 * Uploads engine assets.
	 * 
	 * @param fileItems    The file items to upload.
	 * @param in           The insight to upload the file to.
	 * @param relativePath The relative path to upload the file to.
	 * @param engine       The engine to upload the file to.
	 * @param user         The user uploading the file.
	 * @return A list of maps containing the file name and file location.
	 * @throws VirusScanningException if a virus is detected in the file.
	 * @throws IOException            if an error occurs while writing the file.
	 */
	private List<Map<String, String>> uploadEngineAssets(List<FileItem> fileItems, Insight in, String relativePath,
			IEngine engine, User user) throws VirusScanningException, IOException {

		String assetFolder = EngineUtility.getSpecificEngineAssetsFolder(engine.getCatalogType(), engine.getEngineId(),
				engine.getEngineName());
		String fePath = DIR_SEPARATOR;

		String filePath = assetFolder;
		// add relative path
		if (relativePath != null) {
			filePath = assetFolder + DIR_SEPARATOR + WebUtility.normalizePath(relativePath);
			fePath += relativePath;
		}
		File fileDir = new File(WebUtility.normalizePath(filePath));
		if (!fileDir.exists()) {
			Boolean success = fileDir.mkdirs();
			if (!success) {
				classLogger.info("Unable to make direction at location: {}", Utility.cleanLogString(filePath));
			}
		}

		List<Map<String, String>> retData = processFileItems(fileItems, filePath, fePath);

		// Track uploaded files in git for all engine types
		try {
			String gitFolder = EngineUtility.getSpecificEngineVersionFolder(engine.getCatalogType(),
					engine.getEngineId(), engine.getEngineName());
			List<String> gitRelativeFilePaths = new ArrayList<>();
			for (Map<String, String> fileMap : retData) {
				String fileName = fileMap.get("fileName");
				String gitRelPath;
				if (relativePath != null && !relativePath.isEmpty() && !relativePath.equals("/")) {
					String cleanRelPath = relativePath.replaceAll("^/+|/+$", "");
					gitRelPath = Constants.ASSETS_FOLDER + DIR_SEPARATOR + cleanRelPath + DIR_SEPARATOR + fileName;
				} else {
					gitRelPath = Constants.ASSETS_FOLDER + DIR_SEPARATOR + fileName;
				}
				gitRelativeFilePaths.add(gitRelPath);
			}
			if (!gitRelativeFilePaths.isEmpty()) {
				AccessToken accessToken = user.getAccessToken(user.getPrimaryLogin());
				String author = accessToken.getUsername();
				String email = accessToken.getEmail();
				String fileNames = String.join(", ", gitRelativeFilePaths);
				GitRepoUtils.addSpecificFiles(gitFolder, gitRelativeFilePaths);
				GitRepoUtils.commitAddedFiles(gitFolder, "add: uploaded " + fileNames, author, email);
			}
		} catch (Exception e) {
			classLogger.error("Error committing uploaded files to git for engine {}", engine.getEngineId(), e);
		}

		if (engine instanceof IProject) {
			ClusterUtil.pushProjectFolder((IProject) engine, filePath);
		} else {
			ClusterUtil.pushEngineFolder(engine, filePath);
		}
		return retData;
	}

	/**
	 * Processes the file items and writes them to the server.
	 * 
	 * @param fileItems The file items to process.
	 * @param filePath  The path to write the files to.
	 * @param fePath    The front-end path to the files.
	 * @return A list of maps containing the file name and file location.
	 * @throws VirusScanningException if a virus is detected in the file.
	 * @throws IOException            if an error occurs while writing the file.
	 */
	private List<Map<String, String>> processFileItems(List<FileItem> fileItems, String filePath, String fePath)
			throws VirusScanningException, IOException {
		Iterator<FileItem> iteratorFileItems = fileItems.iterator();
		// collect all of the data input on the form
		List<Map<String, String>> retData = new ArrayList<Map<String, String>>();

		while (iteratorFileItems.hasNext()) {
			FileItem fi = iteratorFileItems.next();
			if (!fi.isFormField()) {
				// Get the uploaded file parameters
				String fieldName = fi.getFieldName();
				String name = WebUtility.inputSanitizer(fi.getName());
				String fileExtension = FilenameUtils.getExtension(name);
				String contentType = fi.getContentType();
				MimeType type = null;
				if (fileExtension == null || fileExtension.isEmpty()) {
					try {
						type = MimeTypes.getDefaultMimeTypes().forName(contentType);
						name += type.getExtension();
					} catch (MimeTypeException e) {
						classLogger.error("Error determining mime type from content type", e);
					}
				}

				// we need the key to be file
				if (!fieldName.equals("file")) {
					// delete the field
					fi.delete();
					continue;
				}

				// Check for viruses on upload
				checkForViruses(fi);

				String fileLocation = Utility.getUniqueFilePath(filePath, name);
				File file = new File(WebUtility.normalizePath(fileLocation));

				// instead of adding unique
				// we will do what a normal OS system does
				writeFile(fi, file);

				String savedName = FilenameUtils.getName(fileLocation);
				Map<String, String> fileMap = new HashMap<String, String>();
				fileMap.put("fileName", savedName);
				if (fePath.endsWith(DIR_SEPARATOR)) {
					fileMap.put("fileLocation", fePath + savedName);
				} else {
					fileMap.put("fileLocation", fePath + DIR_SEPARATOR + savedName);
				}
				retData.add(fileMap);
			} else if (fi.getFieldName().equals("file")) {
				// its a file, but not in a form
				// i.e. this is a person copy/pasting
				// the values directly
				classLogger.info("Writing Input To File");
				// Check for viruses on upload
				checkForViruses(fi);

				String fileLocation = Utility.getUniqueFilePath(filePath, "AutoGeneratedFile.txt");

				File file = new File(WebUtility.normalizePath(fileLocation));
				writeFile(fi, file);
				classLogger.info("Saved Pasted Data To {}", Utility.cleanLogString(file.toString()));

				String savedName = FilenameUtils.getName(fileLocation);
				Map<String, String> fileMap = new HashMap<String, String>();
				fileMap.put("fileName", savedName);
				if (fePath.endsWith(DIR_SEPARATOR)) {
					fileMap.put("fileLocation", fePath + savedName);
				} else {
					fileMap.put("fileLocation", fePath + DIR_SEPARATOR + savedName);
				}
				retData.add(fileMap);
			}
			// delete the field
			fi.delete();
		}

		return retData;
	}

	/**
	 * Checks a file for viruses.
	 * 
	 * @param fi The file item to check.
	 * @throws VirusScanningException if a virus is detected in the file.
	 * @throws IOException            if an error occurs while reading the file.
	 */
	private void checkForViruses(FileItem fi) throws VirusScanningException, IOException {
		if (Utility.isVirusScanningEnabled()) {
			try {
				Map<String, Collection<String>> viruses = VirusScannerUtils.getViruses(fi.getName(),
						fi.getInputStream());

				if (!viruses.isEmpty()) {
					classLogger.warn("Virus scanner errors map for {} : {}", Utility.cleanLogString(fi.getName()),
							Utility.cleanLogString(String.valueOf(viruses)));
					String error = "Detected " + viruses.size() + " virus";

					if (viruses.size() > 1) {
						error = error + "es";
					}

					error += ". If you believe this is an error, please contact an administrator.";

					throw new VirusScanningException(error);
				}
			} catch (VirusScanningException e) {
				throw e;
			} catch (IOException e) {
				classLogger.error("Could not read file item for virus scanning", e);
				throw new IllegalArgumentException("Could not read file item for virus scanning.");
			}
		}
	}
}
