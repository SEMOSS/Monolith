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
package prerna.semoss.web.services;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.io.FilenameUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;

import com.google.gson.Gson;
import com.google.gson.internal.StringMap;
import com.google.gson.reflect.TypeToken;

import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.engine.api.IEngine;
import prerna.engine.impl.rdf.RemoteSemossSesameEngine;
import prerna.insights.admin.DBAdminResource;
import prerna.nameserver.utility.MasterDatabaseUtility;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.sablecc.PKQLRunner;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.PixelStreamUtility;
import prerna.sablecc2.comm.JobManager;
import prerna.sablecc2.comm.JobThread;
import prerna.upload.DatabaseUploader;
import prerna.upload.FileUploader;
import prerna.upload.ImageUploader;
import prerna.upload.Uploader;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.PlaySheetRDFMapBasedEnum;
import prerna.util.Utility;
import prerna.web.services.util.ResponseHashSingleton;
import prerna.web.services.util.SemossExecutorSingleton;
import prerna.web.services.util.SemossThread;
import prerna.web.services.util.WebUtility;

@Path("/engine")
public class NameServer {

	@Context
	protected ServletContext context;

	@GET
	@Path("/sessionTimeout")
	@Produces("application/json")
	public Response getTimeoutValue(@Context HttpServletRequest request) {
		return WebUtility.getResponse((double) request.getSession().getMaxInactiveInterval() / 60, 200);
	}
	
	// uploader functionality
	@Path("/uploadDatabase")
	public Uploader uploadDatabase(@Context HttpServletRequest request) {
		Uploader upload = new DatabaseUploader();
		String filePath = context.getInitParameter("file-upload");
		upload.setFilePath(filePath);
		String tempFilePath = context.getInitParameter("temp-file-upload");
		upload.setTempFilePath(tempFilePath);
		upload.setSecurityEnabled(Boolean.parseBoolean(context.getInitParameter(Constants.SECURITY_ENABLED)));
		return upload;
	}

	@Path("/uploadFile")
	public Uploader uploadFile(@Context HttpServletRequest request) {
		Uploader upload = new FileUploader();
		String filePath = DIHelper.getInstance().getProperty(Constants.INSIGHT_CACHE_DIR) + "\\" + DIHelper.getInstance().getProperty(Constants.CSV_INSIGHT_CACHE_FOLDER);
		upload.setFilePath(filePath);
		String tempFilePath = context.getInitParameter("temp-file-upload");
		upload.setTempFilePath(tempFilePath);
		upload.setSecurityEnabled(Boolean.parseBoolean(context.getInitParameter(Constants.SECURITY_ENABLED)));
		return upload;
	}

	@Path("/uploadImage")
	public Uploader uploadImage(@Context HttpServletRequest request) {
		Uploader upload = new ImageUploader();
		String filePath = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER) + "\\db";
		upload.setFilePath(filePath);
		String tempFilePath = context.getInitParameter("temp-file-upload");
		upload.setTempFilePath(tempFilePath);
		upload.setSecurityEnabled(Boolean.parseBoolean(context.getInitParameter(Constants.SECURITY_ENABLED)));
		return upload;
	}

	@GET
	@Path("playsheets")
	@Produces("application/json")
	public StreamingOutput getPlaySheets(@Context HttpServletRequest request) {
		Hashtable<String, String> hashTable = new Hashtable<String, String>();

		List<String> sheetNames = PlaySheetRDFMapBasedEnum.getAllSheetNames();
		for (int i = 0; i < sheetNames.size(); i++) {
			hashTable.put(sheetNames.get(i), PlaySheetRDFMapBasedEnum.getClassFromName(sheetNames.get(i)));
		}
		return WebUtility.getSO(hashTable);
	}

	@Path("/dbAdmin")
	public Object modifyInsight(@Context HttpServletRequest request) {
		DBAdminResource questionAdmin = new DBAdminResource();
		questionAdmin.setSecurityEnabled(Boolean.parseBoolean(context.getInitParameter(Constants.SECURITY_ENABLED)));
		return questionAdmin;
	}

	@Path("i-{insightID}")
	public Object getInsightDataFrame(@PathParam("insightID") String insightID, @QueryParam("dataFrameType") String dataFrameType, @Context HttpServletRequest request) {
		// eventually I want to pick this from session
		// but for now let us pick it from the insight store
		System.out.println("Came into this point.. " + insightID);

		Insight existingInsight = null;
		if (insightID != null && !insightID.isEmpty() && !insightID.startsWith("new")) {
			existingInsight = InsightStore.getInstance().get(insightID);
			if (existingInsight == null) {				
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "Existing insight based on passed insightID is not found");
				//				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
				return WebUtility.getResponse(errorHash, 400);
			} 
			//			else if(!existingInsight.hasInstantiatedDataMaker()) {
			//				synchronized(existingInsight) {
			//					if(!existingInsight.hasInstantiatedDataMaker()) {
			////						IDataMaker dm = null;
			////						// check if the insight is from a csv
			////						if(!existingInsight.isDbInsight()) {
			////							// it better end up being created here since it must be serialized as a tinker
			////							InsightCache inCache = CacheFactory.getInsightCache(CacheFactory.CACHE_TYPE.CSV_CACHE);
			////							dm = inCache.getDMCache(existingInsight);
			////							DataMakerComponent dmc = new DataMakerComponent(inCache.getDMFilePath(existingInsight));
			////							
			////							Vector<DataMakerComponent> dmcList = new Vector<DataMakerComponent>();
			////							dmcList.add(dmc);
			////							existingInsight.setDataMakerComponents(dmcList);
			////						} else {
			//							// otherwise, grab the serialization if it is there
			//						IDataMaker dm = CacheFactory.getInsightCache(CacheFactory.CACHE_TYPE.DB_INSIGHT_CACHE).getDMCache(existingInsight);
			////						}
			//						
			//						if(dm != null) {
			//							// this means the serialization was good and pushing it into the insight object
			//							existingInsight.setDataMaker(dm);
			//						} else {
			////							 this means the serialization has never occurred
			////							 could be because hasn't happened, or could be because it is not a tinker frame
			//							InsightCreateRunner run = new InsightCreateRunner(existingInsight);
			//							Map<String, Object> webData = run.runWeb();
			//							// try to serialize
			//							// this will do nothing if not a tinker frame
			//							CacheFactory.getInsightCache(CacheFactory.CACHE_TYPE.DB_INSIGHT_CACHE).cacheInsight(existingInsight, webData);
			//						}
			//					}
			//				}
			//			}
		}
		else if(insightID.equals("new"))
		{
			// get the data frame type and set it from the FE
			if(dataFrameType == null) {
				dataFrameType = "H2Frame";
			}
			//			existingInsight = new Insight(null, dataFrameType, "Grid");
			existingInsight = new Insight();
			// set the user id into the insight
			existingInsight.setUser( ((User) request.getSession().getAttribute(Constants.SESSION_USER)) );
			InsightStore.getInstance().put(existingInsight);
		}
		//		else if(insightID.equals("newDashboard")) {
		//			// get the data frame type and set it from the FE
		////			existingInsight = new Insight(null, "Dashboard", "Dashboard");
		//			existingInsight = new Insight();
		//			// set the user id into the insight
		//			existingInsight.setUserId( ((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId() );
		//			Dashboard dashboard = new Dashboard();
		//			existingInsight.setDataMaker(dashboard);
		//			String insightid = InsightStore.getInstance().put(existingInsight);
		//			dashboard.setInsightID(insightid);
		//		}

		DataframeResource dfr = new DataframeResource();
		dfr.insight = existingInsight;

		return dfr;
	}

	@GET
	@Path("/downloadFile")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	public Response downloadFile(@QueryParam("insightId") String insightId, @QueryParam("fileKey") String fileKey) {
		// for "security"
		// require the person to have both the insight id
		// and the file id
		// in order to download the file
		Insight insight = InsightStore.getInstance().get(insightId);
		if(insight == null) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("errorMessage", "Could not find the insight id");
			return WebUtility.getResponse(errorMap, 400);
		}

		String filePath = insight.getExportFileLocation(fileKey);
		File exportFile = new File(filePath);
		if(!exportFile.exists()) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("errorMessage", "Could not find the file for given file id");
			return WebUtility.getResponse(errorMap, 400);
		}

		Date date = new Date();
		String modifiedDate = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSSS").format(date);
		String exportName = "SEMOSS_Export_" + modifiedDate + "." + FilenameUtils.getExtension(filePath);

		return Response.status(200).entity(exportFile).header("Content-Disposition", "attachment; filename=" + exportName).build();
	}

	///////////////////////////////////////////////
	///////////////////////////////////////////////
	///////////////////////////////////////////////

	/*
	 * TODO: move FE to use app resource calls
	 */

	@GET
	@Path("/appImage")
	@Produces({"image/jpeg", "image/png"})
	public Response getAppImage(@Context HttpServletRequest request, @QueryParam("app") String app) {
		AppResource r = new AppResource();
		return r.downloadAppImage(request, app);
	}

	@GET
	@Path("/insightImage")
	@Produces({"image/jpeg", "image/png"})
	public Response getInsightImage(@Context HttpServletRequest request, @QueryParam("app") String app, @QueryParam("rdbmsId") String insightId, @QueryParam("params") String params) {
		AppResource r = new AppResource();
		return r.downloadInsightImage(request, app, insightId, params);
	}

	///////////////////////////////////////////////
	///////////////////////////////////////////////
	///////////////////////////////////////////////


	@POST
	@Path("/runPixel")
	@Produces("application/json")
	public Response runPixelSync(MultivaluedMap<String, String> form, @Context HttpServletRequest request){
		// I need to do a couple of things here
		// I need to get the basic blocking queue as a singleton
		// create a thread
		// set the insight and pixels into the thread
		// and then let it lose

		// I need a couple of different statistics for this user and panel
		// is user (initially I had he, but then diversity) listening for stdout / stderr or both
		// what is the level of log the user wants and the panel wants

		// other than that - 
		// there is a jobID status Hash - this can eventually be zookeeper
		// Then there is a jobID to message if the user has turned on the stdout, then it has a stack of messages
		// once the job is done, the stack is also cleared

		HttpSession session = null;
		String sessionId = null; 
		User user = null;

		boolean securityEnabled = Boolean.parseBoolean(context.getInitParameter(Constants.SECURITY_ENABLED));
		//If security is enabled try to get an existing session.
		//Otherwise get a session with the default user.
		if(securityEnabled){
			session = request.getSession(false);
			if(session != null){
				sessionId = session.getId();
				user = ((User) session.getAttribute(Constants.SESSION_USER));
			}
			
			if(user == null) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("error", "User session is invalid");
				System.out.println("User session is invalid");
				return WebUtility.getResponse(errorMap, 401);
			}
		} else {
			session = request.getSession(true);
			user = ((User) session.getAttribute(Constants.SESSION_USER));
			sessionId = session.getId();
		}

		String jobId = "";
		final String tempInsightId = "TempInsightNotStored";

		String insightId = form.getFirst("insightId");
		String expression = form.getFirst("expression");
		Insight insight = null;

		// figure out the type of insight
		// first is temp
		if(insightId == null || insightId.toString().isEmpty() || insightId.equals("undefined")) {
			insightId = tempInsightId;
			insight = new Insight();
			insight.setInsightId(tempInsightId);
			//insight.setUser(user);
		} else if (insightId.equals("new")) { // need to make a new insight here
			insight = new Insight();
			//insight.setUser(user);
			InsightStore.getInstance().put(insight);
			insightId = insight.getInsightId();
			InsightStore.getInstance().addToSessionHash(sessionId, insightId);
		} else {// or just get it from the store
			// the session id needs to be checked
			// you better have a valid id... or else... O_O
			insight = InsightStore.getInstance().get(insightId);
			//insight.setUser(user);
			if (insight == null) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("errorMessage", "Could not find the insight id");
				return WebUtility.getResponse(errorMap, 400);
			}
			// make sure we have the correct session trying to get this id
			// #soMuchSecurity
			//			Set<String> sessionStore = InsightStore.getInstance().getInsightIDsForSession(sessionId);
			//			if(sessionStore == null || !sessionStore.contains(insightId)) {
			//				Map<String, String> errorMap = new HashMap<String, String>();
			//				errorMap.put("errorMessage", "Trying to access insight id from incorrect session");
			//				return WebUtility.getResponse(errorMap, 400);
			//			}
		}
		synchronized(insight) {
			// set the user
			insight.setUser(user);
			JobManager manager = JobManager.getManager();
			JobThread jt = null;
			if(insightId.equals(tempInsightId)) {
				jt = manager.makeJob();
			} else {
				jt = manager.makeJob(insightId);
			}
			jobId = jt.getJobId();
			session.setAttribute(jobId+"", "TRUE");
			String job = "META | Job(\"" + jobId + "\", \"" + insightId + "\", \"" + sessionId + "\");";
			expression = job + expression;

			jt.setInsight(insight);
			jt.addPixel(expression);
			jt.run();
			PixelRunner pixelRunner = jt.getRunner();

			manager.flushJob(jobId);
			return Response.status(200).entity(PixelStreamUtility.collectPixelData(pixelRunner)).build();
		}
	}

	@POST
	@Path("runPixelAsync")
	@Produces("application/json")
	public Response runPixelAsync(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		boolean securityEnabled = Boolean.parseBoolean(context.getInitParameter(Constants.SECURITY_ENABLED));
		HttpSession session = null;
		User user = null;
		if(securityEnabled){
			session = request.getSession(false);
			user = ((User) session.getAttribute(Constants.SESSION_USER));
		} else {
			session = request.getSession(true);
		}
		String sessionId = session.getId();

		String jobId = "";
		Map<String, String> dataReturn = new HashMap<String, String>();

		String insightId = form.getFirst("insightId");
		String expression = form.getFirst("expression");
		Insight insight = null;

		// figure out the type of insight
		// first is temp
		if(insightId == null || insightId.toString().isEmpty() || insightId.equals("undefined")) {
			insight = new Insight();
			insight.setInsightId("TempInsightNotStored");
		} else if(insightId.equals("new")) { // need to make a new insight here
			insight = new Insight();
			InsightStore.getInstance().put(insight);
			InsightStore.getInstance().addToSessionHash(sessionId, insight.getInsightId());
		} else {// or just get it from the store
			// the session id needs to be checked
			// you better have a valid id... or else... O_O
			insight = InsightStore.getInstance().get(insightId);
			if(insight == null) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("errorMessage", "Could not find the insight id");
				return WebUtility.getResponse(errorMap, 400);
			}
			// make sure we have the correct session trying to get this id
			// #soMuchSecurity
			//			Set<String> sessionStore = InsightStore.getInstance().getInsightIDsForSession(sessionId);
			//			if(sessionStore == null || !sessionStore.contains(insightId)) {
			//				Map<String, String> errorMap = new HashMap<String, String>();
			//				errorMap.put("errorMessage", "Trying to access insight id from incorrect session");
			//				return WebUtility.getResponse(errorMap, 400);
			//			}
		}
		if(insight != null)
		{
			synchronized(insight) {
				insight.setUser(user);
				JobManager manager = JobManager.getManager();
				JobThread jt = manager.makeJob();
				jobId = jt.getJobId();
				session.setAttribute(jobId+"", "TRUE");
				String job = "META | Job(\"" + jobId + "\", \"" + insightId + "\", \"" + sessionId + "\");";
				expression = job + expression;

				jt.setInsight(insight);
				jt.addPixel(expression);
				jt.start();
				dataReturn.put("jobId", jobId);
			}
		}		
		return WebUtility.getResponse(dataReturn, 200);
	}

	// get result of the operation
	@POST
	@Path("/result")
	@Produces("application/json")
	public StreamingOutput result(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		Object dataReturn = "NULL";
		HttpSession session = request.getSession(true);
		String jobId = form.getFirst("jobId");
		if(session.getAttribute(jobId) != null) {
			dataReturn = JobManager.getManager().getOutput(jobId);
		}
		return WebUtility.getSO(dataReturn);
	}

	// is the status of the operation
	// get result of the operation
	@POST
	@Path("/status")
	@Produces("application/json")
	public StreamingOutput status(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		Object dataReturn = "NULL";
		HttpSession session = request.getSession(true);
		String jobId = form.getFirst("jobId");
		if(session.getAttribute(jobId) != null) {
			dataReturn = JobManager.getManager().getStatus(jobId);
		}
		return WebUtility.getSO(dataReturn);
	}

	// std outputs and errors
	@POST
	@Path("/console")
	@Produces("application/json")
	public StreamingOutput console(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		Object dataReturn = "NULL";
		String jobId = form.getFirst("jobId");
		//		HttpSession session = request.getSession(true);
		//		if(session.getAttribute(jobId) != null) {
		dataReturn = JobManager.getManager().getStdOut(jobId);
		//		}
		return WebUtility.getSO(dataReturn);
	}

	@POST
	@Path("/error")
	@Produces("application/json")
	public StreamingOutput error(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		Object dataReturn = "NULL";
		String jobId = form.getFirst("jobId");
		//		HttpSession session = request.getSession(true);
		//		if(session.getAttribute(jobId) != null) {
		dataReturn = JobManager.getManager().getError(jobId);
		//		}
		return WebUtility.getSO(dataReturn);
	}

	// close / terminate job
	@POST
	@Path("/terminate")
	@Produces("application/json")
	public StreamingOutput terminate(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String jobId = form.getFirst("jobId");
		//		HttpSession session = request.getSession(true);
		//		if(session.getAttribute(jobId) != null) {
		JobManager.getManager().flushJob(jobId);
		//		}
		//		session.removeAttribute(jobId);
		return WebUtility.getSO("success");
	}


	// reset job
	@POST
	@Path("/reset")
	@Produces("application/json")
	public StreamingOutput reset(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String jobId = form.getFirst("jobId");
		//		HttpSession session = request.getSession(true);
		//		if(session.getAttribute(jobId) != null) {
		JobManager.getManager().resetJob(jobId);
		//		}
		return WebUtility.getSO("success");
	}

	@GET
	@Path("/comet")
	@Produces("text/plain")
	public String cometTry(@Context HttpServletRequest request) {
		// I need to create a job id
		// then I need to start the thread with this job id
		// I need to keep the response in the response hash with this job id.. so when I have 
		SemossExecutorSingleton threader = SemossExecutorSingleton.getInstance();
		SemossThread newThread = new SemossThread(); 
		//newThread.setResponse(response);
		String jId = threader.execute(newThread);
		//ResponseHashSingleton.setResponse(jId, response);	
		ResponseHashSingleton.setThread(jId, newThread);	
		//request.getSession(true).setAttribute("JOB_ID", jId);
		return jId; // store this in session so the user doesn't need to provide this
	}

	@GET
	@Path("/joutput")
	@Produces("text/plain")
	public String getJobOutput(@QueryParam("jobId") String jobId, @Context HttpServletRequest request){

		String output = "Job Longer Available";
		AsyncResponse myResponse = (AsyncResponse)ResponseHashSingleton.getResponseforJobId(jobId);
		//			   if(ResponseHashSingleton.getThread(jobId) != null)
		//			   {
		//				   SemossThread thread = (SemossThread)ResponseHashSingleton.getThread(jobId);
		//				   output = thread.getOutput() + "";
		//			   }			   
		if(myResponse != null ) {
			System.out.println("Respons Done ? " + myResponse.isDone());
			System.out.println("Respons suspended ? " + myResponse.isSuspended());
			System.out.println("Is the response done..  ? " + myResponse.isDone());
			myResponse.resume("Hello2222");
			myResponse.resume("Hola again");
			System.out.println("MyResponse is not null");
		}

		return output;
	}

	@GET
	@Path("/jkill")
	@Produces("application/xml")
	public void killJob(@QueryParam("jobId") String jobId, @Context HttpServletRequest request){
		//AsyncResponse myResponse = (AsyncResponse)ResponseHashSingleton.getResponseforJobId(jobId);
		SemossThread thread = (SemossThread)ResponseHashSingleton.getThread(jobId);
		thread.setComplete(true);
		ResponseHashSingleton.removeThread(jobId);

		/*			   if(myResponse != null ) {
				   System.out.println("Respons Done ? " + myResponse.isDone());
				   System.out.println("Respons suspended ? " + myResponse.isSuspended());
				   System.out.println("Is the response done..  ? " + myResponse.isDone());
				   myResponse.resume("Hello2222");
				   myResponse.resume("Hola again");
				   System.out.println("MyResponse is not null");
			   }
		 */			
		//return thread.getOutput() + "";
	}

	///////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////

	
	/*
	 * Legacy code that isn't really used anymore
	 * 
	 * 
	 */
	
	/**
	 * Executes search on MediaWiki/Wikipedia for a given search term and returns the top results.
	 * 
	 * @param searchTerm	Search term to be queried against endpoint
	 * @return	ret			Map<ProductOntology URL, Short description of entity>
	 */
	@GET
	@Path("mediawiki/tags")
	@Produces("application/json")
	public StreamingOutput getMediaWikiTagsForSearchTerm(@QueryParam("searchTerm") String searchTerm, @QueryParam("numResults") int numResults) {
		String MEDAWIKI_ENDPOINT = "https://en.wikipedia.org/w/api.php?action=query&srlimit=" + numResults + "&list=search&format=json&utf8=1&srprop=snippet&srsearch=";
		String PRODUCT_ONTOLOGY_PREFIX = "http://www.productontology.org/id/";
		StringMap<String> ret = new StringMap<String>();

		if(searchTerm != null && !searchTerm.isEmpty()) {
			try {
				CloseableHttpClient httpClient = null;
				CloseableHttpResponse response = null;
				try {
					httpClient = HttpClients.createDefault();
					HttpGet http = new HttpGet(MEDAWIKI_ENDPOINT + URLEncoder.encode(searchTerm));
					response = httpClient.execute(http);

					HttpEntity entity = response.getEntity();
					if (entity != null) {
						InputStream is = entity.getContent();
						if (is != null) {
							String resp = EntityUtils.toString(entity);
							Gson gson = new Gson();
							HashMap<String, StringMap<List<StringMap<String>>>> k = gson.fromJson(resp, HashMap.class);;
							List<StringMap<String>> mapsList = (List<StringMap<String>>)k.get("query").get("search");

							for(StringMap<String> s : mapsList) {
								ret.put(PRODUCT_ONTOLOGY_PREFIX + s.get("title"), Jsoup.parse(s.get("snippet")).text());
							}
						}
					}
				} catch (ClientProtocolException e) {
					e.printStackTrace();
				} finally {
					httpClient.close();
					response.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		return WebUtility.getSO(ret);
	}
	
	
	// gets the engine resource necessary for all engine calls
	@Path("e-{engine}")
	public Object getLocalDatabase(@Context HttpServletRequest request, @PathParam("engine") String engineId, @QueryParam("api") String api) throws IOException {
		boolean security = AbstractSecurityUtils.securityEnabled();
		if(security) {
			HttpSession session = request.getSession(false);
			if(session == null) {
				return WebUtility.getSO("Not properly authenticated");
			}
			User user = (User) session.getAttribute(Constants.SESSION_USER);
			if(user == null) {
				return WebUtility.getSO("Not properly authenticated");
			}
			engineId = SecurityQueryUtils.testUserEngineIdForAlias(user, engineId);
			if(!SecurityQueryUtils.getUserEngineIds(user).contains(engineId)) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("errorMessage", "Database " + engineId + " does not exist or user does not have access to database");
				return WebUtility.getResponse(errorMap, 400);
			}
		} else {
			engineId = MasterDatabaseUtility.testEngineIdIfAlias(engineId);
			if(!MasterDatabaseUtility.getAllEngineIds().contains(engineId)) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put("errorMessage", "Database " + engineId + " does not exist");
				return WebUtility.getResponse(errorMap, 400);
			}
		}

		IEngine engine = Utility.getEngine(engineId);
		EngineResource res = new EngineResource();
		res.setEngine(engine);
		return res;
	}

	@Path("s-{engine}")
	public Object getEngineProxy(@PathParam("engine") String db, @Context HttpServletRequest request) {
		// this is the name server
		// this needs to return stuff
		System.out.println(" Getting DB... " + db);
		HttpSession session = request.getSession();
		IEngine engine = (IEngine) session.getAttribute(db);
		EngineRemoteResource res = new EngineRemoteResource();
		res.setEngine(engine);
		return res;
	}

	// Controls all calls controlling the central name server
	@Path("centralNameServer")
	public Object getCentralNameServer(@QueryParam("centralServerUrl") String url,
			@Context HttpServletRequest request) {
		// this is the name server
		// this needs to return stuff
		System.out.println(" Going to central name server ... " + url);
		CentralNameServer cns = new CentralNameServer();
		cns.setCentralApi(url);
		return cns;
	}

	/**
	 * Get the basic information of all engines from solr.
	 * @param request
	 * @return all engines.
	 */
	@GET
	@Path("all")
	@Produces("application/json")
	public StreamingOutput printEngines(@Context HttpServletRequest request) {
		Object securityObj = DIHelper.getInstance().getLocalProp(Constants.SECURITY_ENABLED);
		boolean security =  (securityObj instanceof Boolean && ((boolean) securityObj) ) || (Boolean.parseBoolean(securityObj.toString()));

		List<Map<String, Object>> engines = null;
		if(security) {
			HttpSession session = request.getSession(false);
			if(session == null) {
				return WebUtility.getSO("Not properly authenticated");
			}
			User user = (User) session.getAttribute(Constants.SESSION_USER);
			if(user == null) {
				return WebUtility.getSO("Not properly authenticated");
			}
			engines = SecurityQueryUtils.getUserDatabaseList(user);
		} else {
			engines = SecurityQueryUtils.getAllDatabaseList();
		}

		return WebUtility.getSO(engines);
	}

	@GET
	@Path("add")
	@Produces("application/json")
	public void addEngine(@Context HttpServletRequest request, @QueryParam("api") String api, @QueryParam("database") String database) {
		// would be cool to give this as an HTML
		RemoteSemossSesameEngine newEngine = new RemoteSemossSesameEngine();
		newEngine.setAPI(api);
		newEngine.setDatabase(database);
		HttpSession session = request.getSession();
		ArrayList<Hashtable<String, String>> engines = (ArrayList<Hashtable<String, String>>) session.getAttribute(Constants.ENGINES);
		// temporal
		String remoteDbKey = api + ":" + database;
		newEngine.openDB(null);
		if (newEngine.isConnected()) {
			Hashtable<String, String> engineHash = new Hashtable<String, String>();
			engineHash.put("name", database);
			engineHash.put("api", api);
			engines.add(engineHash);
			session.setAttribute(Constants.ENGINES, engines);
			session.setAttribute(remoteDbKey, newEngine);
			DIHelper.getInstance().setLocalProperty(remoteDbKey, newEngine);
		}
	}

	@GET
	@Path("help")
	@Produces("text/html")
	public StreamingOutput printURL(@Context HttpServletRequest request, @Context HttpServletResponse response) {
		Hashtable<String, String> helpHash = null;
		// would be cool to give this as an HTML
		if (helpHash == null) {
			Hashtable<String, String> urls = new Hashtable<String, String>();
			urls.put("Help - this menu (GET)", "hostname:portname/Monolith/api/engine/help");
			urls.put("Get All the engines (GET)", "hostname:portname/Monolith/api/engine/all");
			urls.put("Perspectives in a specific engine (GET)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/perspectives");
			urls.put("All Insights in a engine (GET)", "hostname:portname/Monolith/api/engine/e-{engineName}/insights");
			urls.put("All Perspectives and Insights in a engine (GET)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/pinsights");
			urls.put("Insights for specific perspective specific engine (GET)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/insights?perspective={perspective}");
			urls.put("Insight definition for a particular insight (GET)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/insight?insight={label of insight (NOT ID)}");
			urls.put("Execute insight without parameter (GET)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/output?insight={label of insight (NOT ID)}");
			urls.put("Execute insight with parameter (GET)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/output?insight={label of insight (NOT ID)}&params=key$value~key2$value2~key3$value3");
			urls.put("Execute Custom Query Select (POST)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/querys?query={sparql query}");
			urls.put("Execute Custom Query Construct (GET)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/queryc?query={sparql query}");
			urls.put("Execute Custom Query Insert/Delete (POST)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/update?query={sparql query}");
			urls.put("Numeric properties of a given node type (GET)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/properties/node/type/numeric?nodeType={URI}");
			urls.put("Fill Values for a given parameter (You already get this in insights) (GET)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/fill?type={type}");
			urls.put("Get Neighbors of a particular node (GET)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/neighbors/instance?node={URI}");
			urls.put("Tags for an insight (Specific Engine)",
					"hostname:portname/Monolith/api/engine/e-{engineName}/tags?insight={insight label}");
			urls.put("Insights for a given tag (Tag is optional) (Specific Engine) ",
					"hostname:portname/Monolith/api/engine/e-{engineName}/insight?tag={xyz}");
			urls.put("Neighbors of across all engine", "hostname:portname/Monolith/api/engine/neighbors?node={URI}");
			urls.put("Tags for an insight", "hostname:portname/Monolith/api/engine/tags?insight={insight label}");
			urls.put("Insights for a given tag (Tag is optional)",
					"hostname:portname/Monolith/api/engine/insight?tag={xyz}");
			urls.put("Create a new engine using excel (requires form submission) (POST)",
					"hostname:portname/Monolith/api/engine/insight/upload/excel/upload");
			urls.put("Create a new engine using csv (requires form submission) (POST)",
					"hostname:portname/Monolith/api/engine/insight/upload/csv/upload");
			urls.put("Create a new engine using nlp (requires form submission) (POST)",
					"hostname:portname/Monolith/api/engine/insight/upload/nlp/upload (GET)");
			helpHash = urls;
		}
		return getSOHTML(helpHash);
	}

	private StreamingOutput getSOHTML(Hashtable<String, String> helpHash) {
		return new StreamingOutput() {
			public void write(OutputStream outputStream) throws IOException, WebApplicationException {
				PrintStream out = new PrintStream(outputStream);
				try {
					// java.io.PrintWriter out = response.getWriter();
					out.println("<html>");
					out.println("<head>");
					out.println("<title>Servlet upload</title>");
					out.println("</head>");
					out.println("<body>");

					Enumeration<String> keys = helpHash.keys();
					while (keys.hasMoreElements()) {
						String key = keys.nextElement();
						String value = (String) helpHash.get(key);
						out.println("<em>" + key + "</em>");
						out.println("<a href='#'>" + value + "</a>");
						out.println("</br>");
					}

					out.println("</body>");
					out.println("</html>");
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		};
	}

	@POST
	@Path("runPkql")
	@Produces("application/json")
	@Deprecated
	public StreamingOutput runPkql(MultivaluedMap<String, String> form ) {
		/*
		 * This is only used for calls that do not require us to hold state
		 * pkql that run in here should not touch a data farme
		 */
		String expression = form.getFirst("expression");
		PKQLRunner runner = new PKQLRunner();
		runner.runPKQL(expression);

		Map<String, Object> resultHash = new HashMap<String, Object>();

		// this is technically the only piece of information the FE needs
		// but to keep the return consistent for them
		// i am sending back the information in the same weird ordering
		Map<String, Object> pkqlDataHash = new HashMap<String, Object>();
		pkqlDataHash.put("pkqlData", runner.getResults());

		Object[] insightArr = new Object[1];
		insightArr[0] = pkqlDataHash;

		resultHash.put("insights", insightArr);

		return WebUtility.getSO(resultHash);
	}




	////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////// START SEARCH  BAR ///////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////

	/**
	 * Complete user search based on string input
	 * @return
	 */
	@GET
	@Path("central/context/getAutoCompleteResults")
	@Produces("application/json")
	public StreamingOutput getAutoCompleteResults(@QueryParam("completeTerm") String searchString, @Context HttpServletRequest request) {
		List<String> searchResults = null;
		boolean securityEnabled = Boolean.parseBoolean(context.getInitParameter(Constants.SECURITY_ENABLED));
		if(securityEnabled) {
			HttpSession session = request.getSession(false);
			User user = (User) session.getAttribute(Constants.SESSION_USER);
			searchResults = SecurityQueryUtils.predictUserInsightSearch(user, searchString, "15", "0");
		} else {
			searchResults = SecurityQueryUtils.predictInsightSearch(searchString, "15", "0");
		}
		return WebUtility.getSO(searchResults);
	}

	/**
	 * Search based on a string input 
	 * @param form - information passes in from the front end
	 * @return a string version of the results attained from the query search
	 */
	/**
	 * Search based on a string input 
	 * @param form - information passes in from the front end
	 * @return a string version of the results attained from the query search
	 */
	@POST
	@Path("central/context/getSearchInsightsResults")
	@Produces("application/json")
	public StreamingOutput getSearchInsightsResults(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		// text searched in search bar
		String searchString = form.getFirst("searchString");
		// offset for call
		String offset = form.getFirst("offset");
		// offset for call
		String limit = form.getFirst("limit");

		// If security is enabled, remove the engines in the filters that aren't
		// accessible - if none in filters, add all accessible engines to filter
		// list
		boolean securityEnabled = Boolean.parseBoolean(context.getInitParameter(Constants.SECURITY_ENABLED));
		List<Map<String, Object>> queryResults = null;
		if (securityEnabled) {
			// filter insights based on what the user has access to
			HttpSession session = request.getSession(false);
			User user = ((User) session.getAttribute(Constants.SESSION_USER));
			queryResults = SecurityQueryUtils.searchUserInsightDataByName(user, searchString, limit, offset);
		} else {
			queryResults = SecurityQueryUtils.searchAllInsightDataByName(searchString, limit, offset);
		}

		return WebUtility.getSO(queryResults);
	}


	////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////
	/////////////////////////////// END SEARCH  BAR ////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////

	@POST
	@Path("central/context/getConnectedConcepts2")
	@Produces("application/json")
	public StreamingOutput getConnectedConcepts(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		Gson gson = new Gson();
		List<String> conceptLogicalNames = gson.fromJson(form.getFirst("conceptURI"), new TypeToken<List<String>>() {}.getType());
		if(conceptLogicalNames == null || conceptLogicalNames.isEmpty()) {
			return WebUtility.getSO("");
		}
		return WebUtility.getSO(MasterDatabaseUtility.getConnectedConceptsRDBMS(conceptLogicalNames, null));
	}

	@POST
	@Path("central/context/conceptProperties")
	@Produces("application/json")
	public Response getConceptProperties(MultivaluedMap<String, String> form, @Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		List<String> conceptLogicalNames = gson.fromJson(form.getFirst("conceptURI"), new TypeToken<List<String>>() {}.getType());
		if(conceptLogicalNames == null || conceptLogicalNames.isEmpty()) {
			//				return Response.status(200).entity(WebUtility.getSO("")).build();
			return WebUtility.getResponse("", 200);
		}
		//			return Response.status(200).entity(WebUtility.getSO(DatabasePkqlService.getConceptProperties(conceptLogicalNames, null))).build();
		return WebUtility.getResponse(MasterDatabaseUtility.getConceptPropertiesRDBMS(conceptLogicalNames, null), 200);
	}

	@POST
	@Path("central/context/conceptLogicals")
	@Produces("application/json")
	public Response getAllLogicalNamesFromConceptual(MultivaluedMap<String, String> form, @Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		List<String> conceptualName = gson.fromJson(form.getFirst("conceptURI"), new TypeToken<List<String>>() {}.getType());
		if(conceptualName == null || conceptualName.isEmpty()) {
			//				return Response.status(200).entity(WebUtility.getSO("")).build();
			return WebUtility.getResponse("", 200);
		}
		int size = conceptualName.size();

		//			List<String> parentConceptualName = gson.fromJson(form.getFirst("parentConcept"), new TypeToken<List<String>>() {}.getType());
		//			if(parentConceptualName != null) {
		//				// TODO: yell at FE
		//				// ugh, FE, why do you send parent as the string "undefined"
		//				// ugh, BE, how to tell FE that the prim key that is generated for metamodel view is fake
		//				List<String> cleanParentConceptualName = new Vector<String>();
		//				for(int i = 0; i < size; i++) {
		//					String val = parentConceptualName.get(i);
		//					if(val == null) {
		//						cleanParentConceptualName.add(null);
		//					} else if(val.equals("undefined") || val.startsWith(TinkerFrame.PRIM_KEY) || val.isEmpty()) {
		//						cleanParentConceptualName.add(null);
		//					} else {
		//						cleanParentConceptualName.add(val);
		//					}
		//				}
		//				
		//				// override reference to parent conceptual name
		//				// can just keep it as null when we pass back the info to the FE
		//				parentConceptualName = cleanParentConceptualName;
		//			}
		//			return Response.status(200).entity(WebUtility.getSO(DatabasePkqlService.getAllLogicalNamesFromConceptual(conceptualName, parentConceptualName))).build();
		return WebUtility.getResponse(MasterDatabaseUtility.getAllLogicalNamesFromConceptualRDBMS(conceptualName, null), 200);
	}
}
