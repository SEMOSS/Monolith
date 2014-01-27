package prerna.web.conf;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

public class UserDBLoader implements ServletContextListener {

	@Override
	public void contextDestroyed(ServletContextEvent arg0) {
		// TODO Auto-generated method stub

	}

	@Override
	public void contextInitialized(ServletContextEvent arg0) {
		// TODO Auto-generated method stub
		// given a particular user this would load the databases specific to this user
		System.out.println("Initializing the context 2");
		// need to think through this later
		// this would add the user specific databases
		// picks the users id from the session

	}
}
