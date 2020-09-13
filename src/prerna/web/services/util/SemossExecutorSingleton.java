package prerna.web.services.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class SemossExecutorSingleton {
	
	private static final Logger logger = LogManager.getLogger(SemossExecutorSingleton.class);

	static SemossExecutorSingleton singleton = null;
	ExecutorService service = null;
	int count = 0;
	
	
	private SemossExecutorSingleton()
	{
		
	}
	
	public static SemossExecutorSingleton getInstance()
	{
		if(singleton == null)
		{
			singleton = new SemossExecutorSingleton();
			singleton.init();
		}
		return singleton;
	}
	
	public void init()
	{
		if(service == null)
			service = Executors.newFixedThreadPool(20);
	}
	
	public String execute(Runnable t)
	{
		//service.execute(t);
		count++;
		String jobId = "j" + count;
		((SemossThread)t).setJobId(jobId);
		service.execute(t);
		logger.info("Serving thread" + "j" + count);
		return jobId;
	}
}
