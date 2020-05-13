package prerna.web.services.util;

import javax.ws.rs.container.AsyncResponse;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

public class SemossThread extends Thread {
	
	private static final Logger logger = LogManager.getLogger(SemossThread.class); 


	String jobId;
	boolean complete = false;
	String message = "Hello";
	
	
	public void setJobId(String jobId)
	{
		this.jobId = jobId;
	}
	
	
	public void setComplete(boolean complete)
	{
		this.complete = complete;
	}
	
	
	@Override
	public void run() {
		// TODO Auto-generated method stub
		try
		{
			int count = 0;
			while(!complete)
			{
				message = "At.. " + count;
				//System.out.println("Messaging.. " + message);
				count++;
				Thread.sleep(2000);
			}
			logger.info("Complete");
		}catch(InterruptedException ex)
		{
			// try to see who interrupted and why and then possibly kill the thread ?
			complete = true;
			logger.info("Interrupted.. ");
		}
	}
	
	public Object getOutput()
	{
		return message;
	}
}
