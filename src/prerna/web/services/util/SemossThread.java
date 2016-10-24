package prerna.web.services.util;

import javax.ws.rs.container.AsyncResponse;

public class SemossThread extends Thread {

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
			System.out.println("Complete");
		}catch(InterruptedException ex)
		{
			// try to see who interrupted and why and then possibly kill the thread ?
			complete = true;
			System.out.println("Interrupted.. ");
		}
	}
	
	public Object getOutput()
	{
		return message;
	}
}
