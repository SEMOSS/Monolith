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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import prerna.cluster.util.ZKClient;

public class UserDBLoader implements ServletContextListener {

	private static final Logger classLogger = LogManager.getLogger(UserDBLoader.class);

	@Override
	public void contextInitialized(ServletContextEvent arg0) {
		publish();
	}

	private void publish() {
		Map<String, String> envMap = System.getenv();
		if (envMap.containsKey(ZKClient.ZK_SERVER) || envMap.containsKey(ZKClient.ZK_SERVER.toUpperCase())) {
			classLogger.info("Publishing the container to ZK");
			ZKClient client = ZKClient.getInstance();
			client.publishContainer(client.host);
			classLogger.info("Published to ZK");
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent arg0) {
		unpublish();
	}

	private void unpublish() {
		Map<String, String> envMap = System.getenv();
		if (envMap.containsKey(ZKClient.ZK_SERVER) || envMap.containsKey(ZKClient.ZK_SERVER.toUpperCase())) {
			classLogger.info("Removing the container from ZK");
			ZKClient client = ZKClient.getInstance();
			client.deleteContainer(client.host);
			classLogger.info("Removed from ZK");
		}
	}

}
