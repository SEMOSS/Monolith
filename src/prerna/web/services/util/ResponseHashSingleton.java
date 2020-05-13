package prerna.web.services.util;

import java.util.HashMap;

import javax.ws.rs.container.AsyncResponse;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import prerna.util.Utility;


public class ResponseHashSingleton {
	
	private static final Logger logger = LogManager.getLogger(ResponseHashSingleton.class); 

	static HashMap <String, AsyncResponse> respoHash = new HashMap<String, AsyncResponse>();
	static HashMap <String, SemossThread> threadHash = new HashMap<String, SemossThread>();
	
	private ResponseHashSingleton()
	{
		
	}
	
	public static AsyncResponse getResponseforJobId(String jobId)
	{
		return respoHash.get(jobId);
	}
	
	public static void setResponse(String jobId, AsyncResponse response)
	{
		logger.info("Dropping in job id " + Utility.cleanLogString(jobId));
		respoHash.put(jobId, response);
	}

	public static SemossThread getThread(String jobId)
	{
		return threadHash.get(jobId);
	}
	
	public static void setThread(String jobId, SemossThread thread)
	{
		logger.info("Dropping in job id " + Utility.cleanLogString(jobId));
		threadHash.put(jobId, thread);
	}

	
	public static void removeResponse(String jobId)
	{
		respoHash.remove(jobId);
	}
	
	public static void removeThread(String jobId)
	{
		threadHash.remove(jobId);
	}

}
