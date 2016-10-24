package prerna.web.services.util;

import java.util.HashMap;

import javax.ws.rs.container.AsyncResponse;


public class ResponseHashSingleton {
	
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
		System.out.println("Dropping in job id " + jobId);
		respoHash.put(jobId, response);
	}

	public static SemossThread getThread(String jobId)
	{
		return threadHash.get(jobId);
	}
	
	public static void setThread(String jobId, SemossThread thread)
	{
		System.out.println("Dropping in job id " + jobId);
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
