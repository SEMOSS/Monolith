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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.servlet.annotation.WebListener;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.SyncUserAppsThread;
import prerna.auth.User;
import prerna.cluster.util.ClusterUtil;
import prerna.engine.impl.model.Room;
import prerna.engine.impl.model.RoomUtils;
import prerna.engine.impl.r.IRUserConnection;
import prerna.om.ClientProcessWrapper;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.semoss.web.services.local.MCPResource;
import prerna.semoss.web.services.local.StandardMCPResource;
import prerna.usertracking.UserTrackingUtils;
import prerna.util.Constants;
import prerna.util.FileSystemUtil;
import prerna.util.SymlinkHelper;
import prerna.util.Utility;
import prerna.util.insight.InsightUtility;

@WebListener
public class UserSessionLoader implements HttpSessionListener {

	public static final String IS_USER_LOGOUT = "IS_USER_LOGOUT";

	private static final Logger classLogger = LogManager.getLogger(UserSessionLoader.class);
	private static final String DIR_SEPARATOR = java.nio.file.FileSystems.getDefault().getSeparator();

	@Override
	public void sessionCreated(HttpSessionEvent sessionEvent) {
		// nothing to do
	}

	@Override
	public void sessionDestroyed(HttpSessionEvent sessionEvent) {
		classLogger.info("Starting logout");
		HttpSession session = sessionEvent.getSession();
		String sessionId = session.getId();

		User thisUser = (User) session.getAttribute(Constants.SESSION_USER);
		if (thisUser == null) {
			// no need to log a new session that is auto dropped
			// this just keeps writing to the log
			if (!session.isNew()) {
				classLogger.info("Unknown user ending session");
			}
		} else {
			boolean isUserLogout = Boolean.parseBoolean(session.getAttribute(UserSessionLoader.IS_USER_LOGOUT) + "");
			if (isUserLogout) {
				classLogger.info("User " + User.getSingleLogginName(thisUser) + " has logged out to end session");
			} else {
				classLogger.info(
						"User " + User.getSingleLogginName(thisUser) + " is ending session from non-logout event");
			}
			// remove the user memory
			thisUser.removeUserMemory();
		}
		// back up the workspace and asset apps
		try {
			SyncUserAppsThread.execute(session);
		} catch (Exception e) {
			classLogger.error("Error during session cleanup while backing up user apps", e);
		}

		// clear up insight store
		InsightStore inStore = InsightStore.getInstance();
		Set<String> insightIDs = inStore.getInsightIDsForSession(sessionId);
		if (insightIDs != null) {
			Set<String> copy = new HashSet<String>(insightIDs);
			for (String insightId : copy) {
				Insight insight = InsightStore.getInstance().get(insightId);
				if (insight == null) {
					continue;
				}
				classLogger.info("Trying to drop insight {}", insightId);
				try {
					InsightUtility.dropInsight(insight);
					classLogger.info("Dropped insight {}", insightId);
				} catch (Exception e) {
					classLogger.error("Error dropping insight {}", insightId, e);
				}
			}
			classLogger.info("Successfully removed insight information from session");

			// clear the current session store
			insightIDs.removeAll(copy);
		}
		// remove the key as well
		inStore.clearSession(sessionId);

		try {
			String sessionStorage = Utility.getInsightCacheDir() + DIR_SEPARATOR + sessionId;
			FileSystemUtil.deleteFolderIfExists(sessionStorage);
		} catch (Exception e) {
			classLogger.error("Error deleting user session cache folder", e);
		}

		// clear from the mcp thread
		Object mcpKeysObj = session.getAttribute(MCPResource.MCP_AUTH_KEY);
		if (mcpKeysObj instanceof Set) {
			Set<String> mcpKeys = (Set<String>) mcpKeysObj;
			if (mcpKeys != null) {
				for (String authKey : mcpKeys) {
					MCPResource.clearInsight(authKey);
					StandardMCPResource.clearInsightsByAuthorization(authKey);
				}
			}
		}

		// drop the r thread if not netty
		try {
			if (thisUser != null) {
				IRUserConnection rserve = thisUser.getRcon();
				if (rserve != null && !rserve.isStopped()) {
					classLogger.info("Dropping user r serve");
					ExecutorService executor = Executors.newSingleThreadExecutor();
					try {
						executor.submit(new Callable<Void>() {
							@Override
							public Void call() throws Exception {
								try {
									rserve.stopR();
									classLogger.info("Successfully dropped user r serve");
								} catch (Exception e) {
									classLogger.warn("Unable to drop user r serve");
								}
								return null;
							}
						});
					} finally {
						executor.shutdown();
					}
				}
			}
		} catch (Exception e) {
			classLogger.error("Error during session cleanup while dropping R connection", e);
		}

		try {
			if (thisUser != null) {
				// stop the netty thread if used for either r or python
				ClientProcessWrapper cpw = thisUser.getPythonClientProcessWrapper();
				if (cpw != null) {
					cpw.shutdown(true);
				}
			}
		} catch (Exception e) {
			classLogger.error("Error during session cleanup while shutting down client process wrapper", e);
		}

		// remove the mounts if chroot enabled
		try {
			if (Boolean.parseBoolean(Utility.getDIHelperProperty(Constants.CHROOT_ENABLE))) {
				if (thisUser != null) {
					SymlinkHelper chrootHelper = thisUser.getUserSymlinkHelper();
					if (chrootHelper != null) {
						chrootHelper.removeChrootFolder();
					}
				}
			}
		} catch (Exception e) {
			classLogger.error("Error during session cleanup while removing chroot folder", e);
		}

		// if cloud sync enabled, push and clear the rooms
		if (ClusterUtil.IS_CLUSTER) {
			if (thisUser != null && thisUser.roomHash != null) {
				Map<String, Object> roomHash = thisUser.roomHash;
				for (Map.Entry<String, Object> entry : roomHash.entrySet()) {
					String roomId = entry.getKey();
					Object roomObj = entry.getValue();
					try {
						// Assume roomObj has a getRoomFolderPath() method or similar
						String roomFolderPath = null;
						Room room = null;
						if (roomObj != null) {
							try {
								room = (Room) roomObj;
								roomFolderPath = room.getRoomFolderPath();
							} catch (Exception e) {
								classLogger.warn("Could not get room folder path for room {}", roomId, e);
							}
						}
						if (roomFolderPath != null) {
							java.io.File roomFolder = new java.io.File(roomFolderPath);
							if (roomFolder.exists() && roomFolder.isDirectory() && RoomUtils.hasFiles(room)) {
								// Push to cloud (placeholder, implement as needed)
								try {
									ClusterUtil.pushRoom(roomId);
									classLogger.info("Pushed room {} to cloud", roomId);
								} catch (Exception e) {
									classLogger.error("Failed to push room {} to cloud", roomId, e);
								}
							}
							// Remove local folder
							try {
								FileSystemUtil.deleteFolderIfExists(roomFolderPath);
								classLogger.info("Deleted local room folder for room {}", roomId);
							} catch (Exception e) {
								classLogger.error("Failed to delete local room folder for room {}", roomId, e);
							}

						}
					} catch (Exception e) {
						classLogger.error("Error processing room {} on logout", roomId, e);
					}
				}
			}
		}

		// register the successful logout
		UserTrackingUtils.registerLogout(sessionId);
		classLogger.info("Finished logout");
	}

}