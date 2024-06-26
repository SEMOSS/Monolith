package prerna.semoss.web.services.local;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ResourceContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.engine.api.IEngine;
import prerna.engine.impl.CaseInsensitiveProperties;
import prerna.engine.impl.SmssUtilities;
import prerna.io.connector.couch.CouchException;
import prerna.io.connector.couch.CouchUtil;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.ThreadStore;
import prerna.project.api.IProject;
import prerna.reactor.IReactor;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.PixelStreamUtility;
import prerna.sablecc2.om.NounStore;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.util.AssetUtility;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.EngineUtility;
import prerna.util.Utility;
import prerna.util.insight.TextToGraphic;
import prerna.web.requests.OverrideParametersServletRequest;
import prerna.web.services.util.WebUtility;

@Path("/project-{projectId}")
public class ProjectResource {

	private static final Logger classLogger = LogManager.getLogger(ProjectResource.class);

	private static final String DIR_SEPARATOR = java.nio.file.FileSystems.getDefault().getSeparator();
	private static String defaultEmbedLogo = null;
	private static boolean noLogo = false;
	
	private boolean canAccessProject(User user, String projectId) throws IllegalAccessException {
		projectId = SecurityProjectUtils.testUserProjectIdForAlias(user, projectId);
		if(!SecurityProjectUtils.userCanViewProject(user, projectId)) {
			throw new IllegalAccessException("Project " + projectId + " does not exist or user does not have access");
		}
		
		return true;
	}
	
	private boolean canAccessInsight(User user, String projectId, String insightId) throws IllegalAccessException {
		projectId = SecurityProjectUtils.testUserProjectIdForAlias(user, projectId);
		if(!SecurityInsightUtils.userCanViewInsight(user, projectId, insightId)) {
			throw new IllegalAccessException("Insight does not exist or user does not have access to view");
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
	public Response updateSmssFile(@Context HttpServletRequest request, @PathParam("projectId") String projectId) {
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
			if(!isAdmin) {
				boolean isOwner = SecurityProjectUtils.userIsOwner(user, projectId);
				if(!isOwner) {
					throw new IllegalAccessException("Project " + projectId + " does not exist or user does not have permissions to update the smss of the project. User must be the owner to perform this function.");
				}
			}
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		IProject project = Utility.getProject(projectId);
		String currentSmssFileLocation = project.getSmssFilePath();
		File currentSmssFile = new File(currentSmssFileLocation);
		if(!currentSmssFile.exists() || !currentSmssFile.isFile()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Could not find current project smss file");
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// using the current smss properties
		// and the new file contents
		// unconceal any hidden values that have not been altered
		Properties currentSmssProperties = project.getSmssProp();
		String newSmssContent = request.getParameter("smss");
		String unconcealedNewSmssContent = SmssUtilities.unconcealSmssSensitiveInfo(newSmssContent, currentSmssProperties);
		
		// validate the new SMSS
		// that the user is not doing something they cannot do
		// like update the engine id / alias
		{
			Properties newProp = new CaseInsensitiveProperties(Utility.loadPropertiesString(newSmssContent));
			if(!newProp.get(Constants.PROJECT).equals(currentSmssProperties.get(Constants.PROJECT))
					|| !newProp.get(Constants.PROJECT_ALIAS).equals(currentSmssProperties.get(Constants.PROJECT_ALIAS))
					|| !newProp.get(Constants.PROJECT_TYPE).equals(currentSmssProperties.get(Constants.PROJECT_TYPE))
					) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "The project id, project name, and project type cannot be changed");
				return WebUtility.getResponse(errorMap, 400);
			}
		}
		
		// read the current smss as text in case of an error
		String currentSmssContent = null;
		try {
			currentSmssContent = new String(Files.readAllBytes(Paths.get(currentSmssFile.toURI())));
		} catch (IOException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "An error occurred reading the current project smss details. Detailed message = " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		try {
			project.close();
			try (FileWriter fw = new FileWriter(currentSmssFile, false)){
				fw.write(unconcealedNewSmssContent);
			}
			project.open(currentSmssFileLocation);
		} catch(Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			// reset the values
			try {
				project.close();
			} catch (IOException e1) {
				// will ignore this and try to reopen the project
				classLogger.error(Constants.STACKTRACE, e1);
			}
			currentSmssFile.delete();
			try (FileWriter fw = new FileWriter(currentSmssFile, false)){
				fw.write(currentSmssContent);
				project.open(currentSmssFileLocation);
			} catch(Exception e2) {
				classLogger.error(Constants.STACKTRACE, e2);
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "A fatal error occurred and could not revert the project to an operational state. Detailed message = " + e2.getMessage());
				return WebUtility.getResponse(errorMap, 400);
			}
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "An error occurred initializing the new project details. Detailed message = " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		// push to cloud
		ClusterUtil.pushProjectSmss(projectId);
		
		Map<String, Object> success = new HashMap<>();
		success.put("success", true);
		return WebUtility.getResponse(success, 200);
	}
	
	@POST
	@Path("/runReactor/{reactorName}")
	@Produces("application/json;charset=utf-8")
	public Response runReactor(@Context HttpServletRequest request, 
			@PathParam("projectId") String projectId, 
			@PathParam("reactorName") String reactorName) {
		User user = null;
		String sessionId = null;
		try {
			HttpSession session = request.getSession(false);
			if(session == null){
				throw new IllegalAccessException("User session is invalid");
			}
			sessionId = session.getId();
			
			user = (User) session.getAttribute(Constants.SESSION_USER);
			if(user == null) {
				throw new IllegalAccessException("User session is invalid");
			}
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		try {
			boolean isAdmin = SecurityAdminUtils.userIsAdmin(user);
			if(!isAdmin) {
				boolean isOwner = SecurityProjectUtils.userIsOwner(user, projectId);
				if(!isOwner) {
					throw new IllegalAccessException("Project " + projectId + " does not exist or user does not have permissions to update the smss of the project. User must be the owner to perform this function.");
				}
			}
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		IProject project = Utility.getProject(projectId);
		String insightId = "TempInsight_" + UUID.randomUUID().toString();
		Insight insight = new Insight();
		insight.setInsightId(insightId);
		insight.setUser(user);
		// assume we have a json in the body being passed
		try {
			JsonObject jsonObject = JsonParser.parseReader(request.getReader()).getAsJsonObject();
			NounStore nounStore = NounStore.flushJsonToNounStore(jsonObject);
			IReactor reactor = project.getReactor(reactorName, null);
			if(reactor == null) {
				throw new IllegalArgumentException("Could not find custom reactor " + reactor + " in project " + projectId);
			}
			reactor.setNounStore(nounStore);
			reactor.setInsight(insight);
			
			// set in thread
			ThreadStore.setInsightId(insightId);
			ThreadStore.setSessionId(sessionId);
			ThreadStore.setUser(user);
			
			// run the reactor
			NounMetadata retNoun = reactor.execute();
			
			// generate the pixel runner output structure
			PixelRunner pixelRunner = new PixelRunner();
			pixelRunner.setInsight(insight);
			pixelRunner.addResult("", retNoun, false);
			return Response.status(200).entity(PixelStreamUtility.collectPixelData(pixelRunner, null))
					.header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0, post-check=0, pre-check=0")
					.header("Pragma", "no-cache")
					.build();
		} catch (Exception e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
	}
	
	/*
	* Code below is around retrieving app assets
	*/

	@GET
	@Path("/landing")
	@Produces(MediaType.TEXT_HTML)
	public Response getProjectLandingPage(@Context final Request coreRequest, @Context HttpServletRequest request, @PathParam("projectId") String projectId) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		try {
			canAccessProject(user, projectId);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String propFileLoc = (String) DIHelper.getInstance().getProjectProperty(projectId + "_" + Constants.STORE);
		Properties prop = Utility.loadProperties(propFileLoc);
		String projectName = prop.getProperty(Constants.PROJECT_ALIAS);
		
		String fileLocation = EngineUtility.getSpecificEngineBaseFolder(IEngine.CATALOG_TYPE.PROJECT, projectId, projectName)
								+ DIR_SEPARATOR + "app_root/version/assets/landing.html";
		File file = new File(Utility.normalizePath(fileLocation));
		if(file != null && file.exists()) {
		    try {
		    	String html = FileUtils.readFileToString(file, "UTF-8");
				
				// want to cache this on browser if user has access
//				CacheControl cc = new CacheControl();
//				cc.setMaxAge(1);
//				cc.setPrivate(true);
			    EntityTag etag = new EntityTag(Integer.toString(html.hashCode()));
			    ResponseBuilder builder = coreRequest.evaluatePreconditions(etag);

			    // cached resource did not change
			    if(builder != null) {
			        return builder.build();
			    }
				
				return Response.status(200).entity(html)
//						.cacheControl(cc)
						.tag(etag)
//						.lastModified(new Date(file.lastModified()))
						.build();
			} catch (IOException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Unable to load landing html file");
				return WebUtility.getResponse(errorMap, 404);
			}
		} else {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "No custom landing page found");
			return WebUtility.getResponse(errorMap, 404);
		}
	} 
	
	@GET
	@Path("/downloadProjectAsset/{relPath}")
	@Produces({MediaType.TEXT_HTML, MediaType.APPLICATION_OCTET_STREAM})
	public Response downloadProjectAsset(@Context final Request coreRequest, @Context HttpServletRequest request, @PathParam("projectId") String projectId, @PathParam("relPath") String relPath) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		try {
			canAccessProject(user, projectId);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String propFileLoc = (String) DIHelper.getInstance().getProjectProperty(projectId + "_" + Constants.STORE);
		Properties prop = Utility.loadProperties(propFileLoc);
		String projectName = prop.getProperty(Constants.PROJECT_ALIAS);
		
		String fileLocation = EngineUtility.getSpecificEngineBaseFolder(IEngine.CATALOG_TYPE.PROJECT, projectId, projectName) 
								+ DIR_SEPARATOR + "app_root/version/assets/" + relPath;
		File file = new File(Utility.normalizePath(fileLocation));
		if(file != null && file.exists()) {
		    try {
				String contents = FileUtils.readFileToString(file, "UTF-8");
				
				// want to cache this on browser if user has access
//				CacheControl cc = new CacheControl();
//				cc.setMaxAge(1);
//				cc.setPrivate(true);
			    EntityTag etag = new EntityTag(Integer.toString(contents.hashCode()));
			    ResponseBuilder builder = coreRequest.evaluatePreconditions(etag);

			    // cached resource did not change
			    if(builder != null) {
			        return builder.build();
			    }
				
				return Response.status(200).entity(contents)
//						.cacheControl(cc)
						.tag(etag)
//						.lastModified(new Date(file.lastModified()))
						.build();
			} catch (IOException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Unable to load file");
				return WebUtility.getResponse(errorMap, 404);
			}
		} else {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "No file found");
			return WebUtility.getResponse(errorMap, 404);
		}
	}
	
	@GET
	@Path("/embedLogo")
	@Produces({MediaType.TEXT_HTML, MediaType.APPLICATION_OCTET_STREAM})
	public Response getEmbedUrl(@Context final Request coreRequest, @Context HttpServletRequest request, @PathParam("projectId") String projectId, @QueryParam("insightId") String insightId) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		try {
			canAccessProject(user, projectId);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		//TODO: ALLOW APP SPECIFIC EMBED IMAGES 
		//TODO: ALLOW INSIGHT SPECIFIC EMBED IMAGES
		
		String embedLogo = getEmbedLogo();
		if(ProjectResource.noLogo) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", "no defualt logo");
			return WebUtility.getResponse(errorMap, 404);
		}
			
		File file = new File(embedLogo);
		if(file != null && file.exists()) {
		    try {
				String contents = new String(Files.readAllBytes(Paths.get(file.getAbsolutePath())));
				
				// want to cache this on browser if user has access
//				CacheControl cc = new CacheControl();
//				cc.setMaxAge(1);
//				cc.setPrivate(true);
			    EntityTag etag = new EntityTag(Integer.toString(contents.hashCode()));
			    ResponseBuilder builder = coreRequest.evaluatePreconditions(etag);

			    // cached resource did not change
			    if(builder != null) {
			        return builder.build();
			    }
				
			    String mimeType = Files.probeContentType(file.toPath());
				return Response.status(200).entity(contents).type(mimeType)
//						.cacheControl(cc)
						.tag(etag)
//						.lastModified(new Date(file.lastModified()))
						.build();
			} catch (IOException e) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Unable to load file");
				return WebUtility.getResponse(errorMap, 400);
			}
		} else {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Default logo file not found");
			return WebUtility.getResponse(errorMap, 404);
		}
	}
	
	private static String getEmbedLogo() {
		if(ProjectResource.defaultEmbedLogo == null) {
			String embedFileName = Utility.getDIHelperProperty(Constants.EMBED_URL_LOGO);
			if(embedFileName == null || embedFileName.equalsIgnoreCase("NONE")) {
				ProjectResource.noLogo = true;
				ProjectResource.defaultEmbedLogo = "NONE";
			} else {
				String baseFolder = Utility.getBaseFolder();
				String embedFolder = baseFolder + DIR_SEPARATOR + "images" + DIR_SEPARATOR + "embed";
				ProjectResource.defaultEmbedLogo = embedFolder + DIR_SEPARATOR + embedFileName;
				ProjectResource.noLogo = false;
			}
		}
		return ProjectResource.defaultEmbedLogo;
	}
	
	
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////////////////

	/*
	 * Code below is around app images and insight images
	 */
	
	@GET
	@Path("/projectImage/download")
	@Produces({MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_SVG_XML})
	public Response downloadProjectImage(@Context final Request coreRequest, @Context HttpServletRequest request, @PathParam("projectId") String projectId) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		try {
			canAccessProject(user, projectId);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(CouchUtil.COUCH_ENABLED) {
			try {
				Map<String, String> selectors = new HashMap<>();
				selectors.put(CouchUtil.PROJECT, projectId);
				return CouchUtil.download(CouchUtil.PROJECT, selectors);
			} catch (CouchException e) {
				classLogger.error(Constants.STACKTRACE, e);
			}
		}
		
		File exportFile = null;
		try {
			exportFile = getProjectImageFile(projectId);
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
		}
		if(exportFile != null && exportFile.exists()) {
			String exportName = projectId + "_Image." + FilenameUtils.getExtension(exportFile.getAbsolutePath());
			// want to cache this on browser if user has access
//			CacheControl cc = new CacheControl();
//			cc.setMaxAge(86400);
//			cc.setPrivate(true);
//			cc.setMustRevalidate(true);
		    EntityTag etag = new EntityTag(Long.toString(exportFile.lastModified()));
		    ResponseBuilder builder = coreRequest.evaluatePreconditions(etag);

		    // cached resource did not change
		    if(builder != null) {
		        return builder.build();
		    }
		    
			return Response.status(200).entity(exportFile).header("Content-Disposition", "attachment; filename=" + exportName)
//					.cacheControl(cc)
					.tag(etag)
//					.lastModified(new Date(exportFile.lastModified()))
					.build();
		} else {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "error sending image file");
			return WebUtility.getResponse(errorMap, 400);
		}
	}
	
	/**
	 * Use to find the file for the image
	 * @param projectId
	 * @return
	 * @throws Exception 
	 */
	protected File getProjectImageFile(String projectId) throws Exception {
		if(ClusterUtil.IS_CLUSTER) {
			return ClusterUtil.getEngineAndProjectImage(projectId, IEngine.CATALOG_TYPE.PROJECT);
		}
		String propFileLoc = (String) DIHelper.getInstance().getProjectProperty(projectId + "_" + Constants.STORE);
		if(propFileLoc == null && !projectId.equals("NEWSEMOSSAPP")) {
			String imageDir = Utility.getBaseFolder() + "/images/stock/";
			return new File(imageDir + "color-logo.png");
		}
		Properties prop = Utility.loadProperties(propFileLoc);
		String projectName = prop.getProperty(Constants.PROJECT_ALIAS);
		
		String fileLocation = AssetUtility.getProjectVersionFolder(projectName, projectId);
		File f = findImageFile(fileLocation);
		if(f != null) {
			return f;
		} else {
			// make the image
			f = new File(fileLocation);
			if(!f.exists()) {
			Boolean success = f.mkdirs();
			if(!success) {
				classLogger.info("Unable to make direction at location: " + Utility.cleanLogString(fileLocation));
			}
			}
			fileLocation = fileLocation + DIR_SEPARATOR + "image.png";
			if(projectName != null) {
				TextToGraphic.makeImage(projectName, fileLocation);
			} else {
				TextToGraphic.makeImage(projectId, fileLocation);
			}
			f = new File(fileLocation);
			return f;
		}
	}
	
	@GET
	@Path("/insightImage/download")
	@Produces({MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_SVG_XML})
	public Response downloadInsightImage(@Context final Request coreRequest, @Context HttpServletRequest request, 
			@PathParam("projectId") String projectId, @QueryParam("rdbmsId") String id, @QueryParam("params") String params) {
		String sessionId = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		try {
			canAccessInsight(user, projectId, id);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		sessionId = request.getSession(false).getId();
		
		if(CouchUtil.COUCH_ENABLED) {
			try {
				Map<String, String> selectors = new HashMap<>();
				selectors.put(CouchUtil.INSIGHT, id);
				selectors.put(CouchUtil.PROJECT, projectId);
				return CouchUtil.download(CouchUtil.INSIGHT, selectors);
			} catch (CouchException e) {
				classLogger.error(Constants.STACKTRACE, e);
			}
		}
		
		File exportFile = getInsightImageFile(projectId, id, request.getHeader("Referer"), params, sessionId);
		if(exportFile != null && exportFile.exists()) {
			String exportName = projectId + "_Image." + FilenameUtils.getExtension(exportFile.getAbsolutePath());
			// want to cache this on browser if user has access
//			CacheControl cc = new CacheControl();
//			cc.setMaxAge(86400);
//			cc.setPrivate(true);
//			cc.setMustRevalidate(true);
		    EntityTag etag = new EntityTag(Long.toString(exportFile.lastModified()));
		    ResponseBuilder builder = coreRequest.evaluatePreconditions(etag);

		    // cached resource did not change
		    if(builder != null) {
		        return builder.build();
		    }
		    
			return Response.status(200).entity(exportFile).header("Content-Disposition", "attachment; filename=" + exportName)
//					.cacheControl(cc)
					.tag(etag)
//					.lastModified(new Date(exportFile.lastModified()))
					.build();
		} else {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error sending image file");
			return WebUtility.getResponse(errorMap, 404);
		}
	}
	
	/**
	 * Use to find the file for the image
	 * @param app
	 * @return
	 */
	private File getInsightImageFile(String projectId, String id, String feUrl, String params, String sessionId) {
		File f = null;
		String fileLocation = null;
		String propFileLoc = (String) DIHelper.getInstance().getProjectProperty(projectId + "_" + Constants.STORE);
		if(propFileLoc != null) {
			Properties prop = Utility.loadProperties(propFileLoc);
			String projectName = prop.getProperty(Constants.PROJECT_ALIAS);
			
			fileLocation = AssetUtility.getProjectVersionFolder(projectName, projectId);
			if(params != null && !params.isEmpty() && !params.equals("undefined")) {
				String encodedParams = Utility.encodeURIComponent(params);
				fileLocation = fileLocation + 
						DIR_SEPARATOR + id + 
						DIR_SEPARATOR + "params" + 
						DIR_SEPARATOR + encodedParams;
			} else {
				fileLocation = fileLocation + DIR_SEPARATOR + id;
			}
			f = findImageFile(fileLocation);
		}
		if(f != null && f.exists()) {
			return f;
		} else {
			// try making the image
			// JK! this is super annoying when running a bunch of 
			// insights at the same time which is what happens 
			// currently on the app home page
//			if (!ClusterUtil.IS_CLUSTER) {
//				if(feUrl != null) {
//					try {
//						ImageCaptureReactor.runImageCapture(feUrl, appId, id, params, sessionId);
//					}
//					catch(Exception | NoSuchMethodError er) {
//						//Image Capture will not run. No image exists nor will be made. The exception kills the rest.
//						// return stock image
//						er.printStackTrace();
//						f = AbstractSecurityUtils.getStockImage(appId, id);
//						return f;
//					}
//				}
//			}
			// the image capture ran
			// let us try to see if there is a file now...
			f = findImageFile(fileLocation);
			if(f != null && f.exists()) {
				return f;
			} else {
				// return stock image
				f = AbstractSecurityUtils.getStockImage(projectId, id);
				return f;
			}
		}
	}
	
	/////////////////////////////////////////////////////////////////
	
	/*
	 * Image utility methods
	 */
	
	/**
	 * Find an image in the directory
	 * @param baseDir
	 * @return
	 */
	private File findImageFile(String baseDir) {
		if(baseDir == null) {
			return null;
		}
		List<String> extensions = new Vector<>();
		extensions.add("image.png");
		extensions.add("image.jpeg");
		extensions.add("image.jpg");
		extensions.add("image.gif");
		extensions.add("image.svg");
		FileFilter imageExtensionFilter = new WildcardFileFilter(extensions);
		File baseFolder = new File(Utility.normalizePath(baseDir));
		File[] imageFiles = baseFolder.listFiles(imageExtensionFilter);
		if(imageFiles != null && imageFiles.length > 0) {
			return imageFiles[0];
		}
		return null;
	}
	
	/**
	 * Close a file stream
	 * @param fis
	 */
	protected void closeStream(FileInputStream fis) {
		if(fis != null) {
			try {
				fis.close();
			} catch (IOException e) {
	    		classLogger.error(Constants.STACKTRACE, e);
			}
		}
	}
	
	///////////////////////////////////////////////////////
	///////////////////////////////////////////////////////
	///////////////////////////////////////////////////////
	///////////////////////////////////////////////////////
	///////////////////////////////////////////////////////

	/*
	 * Currently not used below...
	 */
	
	@GET
	@Path("/projectWidget")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	public Response getAppWidget(@PathParam("projectId") String projectId, @QueryParam("widget") String widgetName, @QueryParam("file") String fileName) {

		//TODO: this is wrong structure ... needs projectName as well...
		//TODO: this is wrong structure ... needs projectName as well...
		//TODO: this is wrong structure ... needs projectName as well...
		//TODO: this is wrong structure ... needs projectName as well...
		//TODO: this is wrong structure ... needs projectName as well...

		final String basePath = Utility.getBaseFolder();
		String appWidgetDirLoc = basePath + DIR_SEPARATOR + Constants.PROJECT_FOLDER + 
				DIR_SEPARATOR + projectId +  
				DIR_SEPARATOR + "app_root" +
				DIR_SEPARATOR + "version" + 
				DIR_SEPARATOR + "widgets";

		// Get widget file
		String widgetFile = appWidgetDirLoc + DIR_SEPARATOR + widgetName + DIR_SEPARATOR + fileName;
		File f = new File(Utility.normalizePath(widgetFile));
		FileInputStream fis = null;
		if (f.exists()) {
			try {
				fis = new FileInputStream(f);
				byte[] byteArray = IOUtils.toByteArray(fis);
				// return file
				return Response.status(200).entity(byteArray).build();
			} catch (IOException e) {
	    		classLogger.error(Constants.STACKTRACE, e);
			} finally {
				closeStream(fis);
			}
		}
		// return error
		Map<String, String> errorMap = new HashMap<>();
		errorMap.put(Constants.ERROR_MESSAGE, "error sending widget file " + widgetName + "\\" + fileName);
		return WebUtility.getResponse(errorMap, 400);
	}
	
	///// JDBC pieces 
	// the project id can be a full project id
	// or it can be "session" - session basically means load the insight from this session
	// if it is an insight
	// needs to do the open insight 
	// return the insight
	// and pull data from it
	
	// this rest api will take 3 parameters
	// project id - session or the project id
	// insight id - runtime insight id or the actual insight id to open
	// sql query
	// optional frame
	@GET
	@Path("/jdbc")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	public StreamingOutput  getJDBCOutput(@PathParam("projectId") String projectId, @QueryParam("insightId") String insightId, @QueryParam("sql") String sql,  
			@QueryParam("open") String open,
			@Context HttpServletRequest request, 
			@Context ResourceContext resourceContext)  
	{
		if(projectId == null) {
			projectId = "session";
		}
		// get the insight from the session
		HttpSession session = request.getSession();
		if(session == null) {
			return WebUtility.getBinarySO("You are not authorized");
		}
		
		if(sql == null) {
			try {
				sql = IOUtils.toString(request.getReader());
				sql = sql.replace("'", "\\\'");
				sql = sql.replace("\"", "\\\"");
			} catch (IOException e) {
	    		classLogger.error(Constants.STACKTRACE, e);
			}
		}
		
		// try to clean up project
		// this will never be different between
		// new insight and exisitng insight
		sql = tryReplacements(sql, projectId);

		boolean firstTime = !projectId.equalsIgnoreCase("session") 
				&& (open != null && open.equalsIgnoreCase("true"));
		
		// first time 
		if(firstTime) {
			// replace all instances of a prefixed project or insight with 
			// what was passed in since it is the true rdbms values
			sql = tryReplacements(sql, insightId);

			NameServer server = resourceContext.getResource(NameServer.class);
			OverrideParametersServletRequest requestWrapper = new OverrideParametersServletRequest(request);
			Map<String, String> paramMap = new HashMap<String, String>();

			String pixel = "META | OpenInsight(project=[\"" + projectId + "\"], id=[\"" + insightId + "\"], additionalPixels=[\"ReadInsightTheme();\"]);" ;
			paramMap.put("insightId", "new");
			paramMap.put("expression", pixel);
			requestWrapper.setParameters(paramMap);
			classLogger.info("Executing open insight - jdbc");
			Response resp = server.runPixelSync(requestWrapper);
			classLogger.info("Done executing open insight - jdbc");

			StreamingOutput utility = (StreamingOutput) resp.getEntity();
			try (ByteArrayOutputStream output = new ByteArrayOutputStream()){
				utility.write(output);
				String s = new String(output.toByteArray());
				JSONObject obj = new JSONObject(s);
				classLogger.info("Done flushing open insight data to JSON");
				insightId = obj.getJSONArray("pixelReturn").getJSONObject(0).getJSONObject("output").getJSONObject("insightData").getString("insightID");
			} catch (WebApplicationException | IOException e) {
	    		classLogger.error(Constants.STACKTRACE, e);
			}
		}
		// now we have the insight id.. execute
		Insight insight = InsightStore.getInstance().get(insightId);
		if(insight == null) {
			return WebUtility.getBinarySO("No such insight");
		}
		if(!firstTime) {
			// replace but use the insight id of the actual insight rdbms id
			sql = tryReplacements(sql, insight.getRdbmsId());
		}
		
		// do the bifurcation here in terms of sql vs. pixel
		try {
			Object output = insight.query(sql, null);
			// add the instance so it can refer going forward
			if(output instanceof Map) {
				((Map)output).put("INSIGHT_INSTANCE", insightId);
				System.err.println(output);
			}
			
			if(output == null) {
				return WebUtility.getBinarySO("Unable to generate output from sql: " + sql);
			}
	
			return WebUtility.getBinarySO(output);
		} catch(Exception e) {
			return WebUtility.getBinarySO(e);
		}
	}
	
	/**
	 * Try many permutations of cleanup
	 * @param s
	 * @return
	 */
	private String tryReplacements(String str, String find) {
		if(str.contains(find+".")) {
			str = str.replace(find+".", "");
		} else if(str.contains(find.replace("-", "_")+".")) {
			str = str.replace(find.replace("-", "_")+".", "");
		} else if(str.contains("\""+find+"\".")) {
			str = str.replace("\""+find+"\".", "");
		} else if(str.contains("\""+find.replace("-", "_")+"\".")) {
			str = str.replace("\""+find.replace("-", "_")+"\".", "");
		} else if(str.contains("\\\""+find.replace("-", "_")+"\\\".")) {
			str = str.replace("\\\""+find.replace("-", "_")+"\\\".", "");
		}
		return str;
	}

	@GET
	@POST
	@Path("/jdbc_json")
	@Produces(MediaType.APPLICATION_JSON)
	public StreamingOutput  getJDBCJsonOutput(@PathParam("projectId") String projectId, 
			@QueryParam("insightId") String insightId, 
			@QueryParam("sql") String  sql, @Context HttpServletRequest request, 
			@Context ResourceContext resourceContext) 
	{
		if(projectId == null) {
			projectId = "session";
		}
		// get the insight from the session
		HttpSession session = request.getSession();
		if(session == null) {
			return WebUtility.getSO("You are not authorized");
		}
		
		if(sql == null) {
			try {
				sql = IOUtils.toString(request.getReader());
				sql = sql.replace("'", "\\\'");
				sql = sql.replace("\"", "\\\"");
			} catch (IOException e) {
	    		classLogger.error(Constants.STACKTRACE, e);
			}
		}

		if(!projectId.equalsIgnoreCase("session")) {
			NameServer server = resourceContext.getResource(NameServer.class);
			OverrideParametersServletRequest requestWrapper = new OverrideParametersServletRequest(request);
			Map<String, String> paramMap = new HashMap<String, String>();

			String pixel = "META | OpenInsight(project=[\"" + projectId + "\"], id=[\"" + insightId + "\"], additionalPixels=[\"ReadInsightTheme();\"]);" ;
			paramMap.put("insightId", "new");
			paramMap.put("expression", pixel);
			requestWrapper.setParameters(paramMap);
			classLogger.info("Executing open insight - jdbc_json");
			Response resp = server.runPixelSync(requestWrapper);
			classLogger.info("Done executing open insight - jdbc_json");

			StreamingOutput utility = (StreamingOutput) resp.getEntity();
			try (ByteArrayOutputStream output = new ByteArrayOutputStream()){
				utility.write(output);
				String s = new String(output.toByteArray());
				JSONObject obj = new JSONObject(s);
				classLogger.info("Done flushing open insight data to JSON");
				insightId = obj.getJSONArray("pixelReturn").getJSONObject(0).getJSONObject("output").getJSONObject("insightData").getString("insightID");
			} catch (WebApplicationException | IOException e) {
	    		classLogger.error(Constants.STACKTRACE, e);
			}
		}			
		Insight insight = InsightStore.getInstance().get(insightId);
		if(insight == null) {
			return WebUtility.getSO("No such insight");
		}
    	List<String> allFrames = insight.getVarStore().getFrameKeysCopy();
		try {
			// try determine the frame from the SQL statement, otherwise null
	        Object output = null;
			if(allFrames.size() > 1) {
		        Pattern pattern = Pattern.compile("\\bFROM\\s+([\\w\\.]+)", Pattern.CASE_INSENSITIVE);
		        Matcher matcher = pattern.matcher(sql);
		        FIND_PROPER_FRAME : while(matcher.find()) {
		        	String possibleFrame = matcher.group(1);
		        	// validate it matches a frame
		        	for(String frameName : allFrames) {
		        		if(possibleFrame.equalsIgnoreCase(frameName)) {
		        			// need to use the frame name for proper casing in varstore
				        	output = insight.query(sql, frameName);
				        	break FIND_PROPER_FRAME;
		        		}
		        	}
		        }
		        // if we didn't find a frame
		        // just try the default insight frame...
		        if(output == null) {
		        	output = insight.query(sql, null);
		        }
			} else {
				// we only have 1 frame so just pick it
				output = insight.query(sql, allFrames.get(0));
			}
			
			// add the instance so it can refer going forward
			if(output instanceof Map) {
				((Map)output).put("INSIGHT_INSTANCE", insightId);
				((Map)output).remove("types");
			}
			if(output == null) {
				return WebUtility.getSO("Unable to generate output from sql: " + sql);
			}
			return WebUtility.getSO(output);
		} catch(Exception e) {
			return WebUtility.getSO(ExceptionUtils.getStackFrames(e));
		}
	}

	@POST
	@GET
	@Path("/jdbc_csv")
	@Produces(MediaType.TEXT_PLAIN + ";charset=utf-8")
	public StreamingOutput  getJDBCCSVOutput(@PathParam("projectId") String projectId, 
			@QueryParam("insightId") String insightId, 
			@QueryParam("sql") String sql,
			@Context HttpServletRequest request, 
			@Context ResourceContext resourceContext) 
	{
		if(projectId == null) {
			projectId = "session";
		}
		// get the insight from the session
		HttpSession session = request.getSession();
		if(session == null) {
			return WebUtility.getSO("You are not authorized");
		}

		if(sql == null) {
			try {
				sql = IOUtils.toString(request.getReader());
				sql = sql.replace("'", "\\\'");
				sql = sql.replace("\"", "\\\"");
			} catch (IOException e) {
				classLogger.error(Constants.STACKTRACE, e);
			}
		}

		if(!projectId.equalsIgnoreCase("session")) // && InsightStore.getInstance().get(insightId) == null) // see if you can open the insight
		{
			NameServer server = resourceContext.getResource(NameServer.class);
			OverrideParametersServletRequest requestWrapper = new OverrideParametersServletRequest(request);
			Map<String, String> paramMap = new HashMap<String, String>();

			String pixel = "META | OpenInsight(project=[\"" + projectId + "\"], id=[\"" + insightId + "\"], additionalPixels=[\"ReadInsightTheme();\"]);" ;
			paramMap.put("insightId", "new");
			paramMap.put("expression", pixel);
			requestWrapper.setParameters(paramMap);
			classLogger.info("Executing open insight - jdbc_csv");
			Response resp = server.runPixelSync(requestWrapper);
			classLogger.info("Done executing open insight - jdbc_csv");

			StreamingOutput utility = (StreamingOutput) resp.getEntity();
			try (ByteArrayOutputStream output = new ByteArrayOutputStream()){
				utility.write(output);
				String s = new String(output.toByteArray());
				JSONObject obj = new JSONObject(s);
				classLogger.info("Done flushing open insight data to JSON");
				insightId = obj.getJSONArray("pixelReturn").getJSONObject(0).getJSONObject("output").getJSONObject("insightData").getString("insightID");
			} catch (WebApplicationException | IOException e) {
				classLogger.error(Constants.STACKTRACE, e);
			}
		}

		Insight insight = InsightStore.getInstance().get(insightId);
		if(insight == null) {
			return WebUtility.getSO("No such insight");
		}
		List<String> allFrames = insight.getVarStore().getFrameKeysCopy();
		try {
			// try determine the frame from the SQL statement, otherwise null
			Object output = null;
			if(allFrames.size() > 1) {
				Pattern pattern = Pattern.compile("\\bFROM\\s+([\\w\\.]+)", Pattern.CASE_INSENSITIVE);
				Matcher matcher = pattern.matcher(sql);
				FIND_PROPER_FRAME : while(matcher.find()) {
					String possibleFrame = matcher.group(1);
					// validate it matches a frame
					for(String frameName : allFrames) {
						if(possibleFrame.equalsIgnoreCase(frameName)) {
							// need to use the frame name for proper casing in varstore
							output = insight.queryCSV(sql, frameName);
							break FIND_PROPER_FRAME;
						}
					}
				}
				// if we didn't find a frame
				// just try the default insight frame...
				if(output == null) {
					output = insight.queryCSV(sql, null);
				}
			} else {
				// we only have 1 frame so just pick it
				output = insight.query(sql, allFrames.get(0));
			}

			if(output == null) {
				return WebUtility.getSO("Unable to generate output from sql: " + sql);
			}
			return WebUtility.getSOFile(output+"");

		} catch(Exception e) {
			return WebUtility.getSO(ExceptionUtils.getStackFrames(e));
		}
	}

}
