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
package prerna.web.conf;

import java.util.Map;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.cluster.util.ZKClient;

public class UserDBLoader implements ServletContextListener {

	private static final Logger logger = LogManager.getLogger(UserDBLoader.class); 

	@Override
	public void contextDestroyed(ServletContextEvent arg0) {
		// TODO Auto-generated method stub
		// unpublish
		unpublish();
	}

	@Override
	public void contextInitialized(ServletContextEvent arg0) {
		// TODO Auto-generated method stub
		// given a particular user this would load the databases specific to this user
		logger.info("Initializing the context 2");
		publish();
		// need to think through this later
		// this would add the user specific databases
		// picks the users id from the session

	}
	
	// TODO >>>timb: need to pull dbs in here for now until we get to lazy load
	// TODO >>>timb: or in user resource pull on login
	private void publish()
	{
		Map envMap = System.getenv();
		
		if(envMap.containsKey(ZKClient.ZK_SERVER) || envMap.containsKey(ZKClient.ZK_SERVER.toUpperCase()))
		{
			// we are in business
			ZKClient client = ZKClient.getInstance();
			// TODO >>>timb: this needs to be done with check on the env var
			// client.publishContainer(System.getenv("hostname") + "@" + client.host);
			client.publishContainer(client.host);
		}
		// else
		// nothing to do proceed
		
	}
	
	private void unpublish()
	{
		Map envMap = System.getenv();
		
		if(envMap.containsKey(ZKClient.ZK_SERVER) || envMap.containsKey(ZKClient.ZK_SERVER.toUpperCase()))
		{
			// we are in business
			
			ZKClient client = ZKClient.getInstance();
			client.zkClient = null;
			client = ZKClient.getInstance();
			client.deleteContainer(client.host);
		}
		// else
		// nothing to do proceed
		
	}

	
	
}
