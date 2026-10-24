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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.microsoft.playwright.BrowserContext;

import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import prerna.auth.SyncUserAssetsThread;
import prerna.auth.User;
import prerna.cluster.util.ClusterUtil;
import prerna.engine.impl.model.Room;
import prerna.engine.impl.model.RoomMessageStore;
import prerna.engine.impl.model.RoomUtils;
import prerna.engine.impl.r.IRUserConnection;
import prerna.om.ClientProcessWrapper;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.LocalUserStore;
import prerna.reactor.playwright.PlaywrightSession;
import prerna.semoss.web.services.local.MCPResource;
import prerna.usertracking.UserAuditTrailUtils;
import prerna.usertracking.UserTrackingUtils;
import prerna.util.Constants;
import prerna.util.FileSystemUtil;
import prerna.util.SymlinkHelper;
import prerna.util.Utility;
import prerna.util.insight.InsightUtility;
import prerna.util.openshell.OpenShellUserSandboxes;

@WebListener
public class UserSessionLoader implements HttpSessionListener {

	/** How far short of its timeout an idle session still counts as timed out. */
	private static final long SESSION_EXPIRY_SLACK_MS = 5000L;

	public static final String IS_USER_LOGOUT = "IS_USER_LOGOUT";
	public static final String USER_LOGOUT_IP_ADDR = "USER_LOGOUT_IP_ADDR";

	private static final Logger classLogger = LogManager.getLogger(UserSessionLoader.class);
	private static final String DIR_SEPARATOR = java.nio.file.FileSystems.getDefault().getSeparator();

	/**
	 * No-op session creation hook.
	 *
	 * @param sessionEvent the servlet session event
	 */
	@Override
	public void sessionCreated(HttpSessionEvent sessionEvent) {
		// nothing to do
	}

	/**
	 * Performs best-effort cleanup when an HTTP session is destroyed.
	 * <p>
	 * Cleanup is intentionally defensive; each step is wrapped in try/catch so
	 * remaining cleanup actions can continue even if one step fails.
	 *
	 * @param sessionEvent the servlet session event
	 */
	@Override
	public void sessionDestroyed(HttpSessionEvent sessionEvent) {
		classLogger.info("Starting logout");
		HttpSession session = sessionEvent.getSession();
		String sessionId = session.getId();

		User thisUser = (User) session.getAttribute(Constants.SESSION_USER);
		// A user sandbox outlives a server stop, which also destroys every session: only a
		// logout or a real timeout ends the user's claim on it (see OpenShellUserSandboxes).
		boolean sessionOver = !OpenShellUserSandboxes.appliesTo(thisUser) || sessionIsOver(session);
		if (thisUser == null) {
			// no need to log a new session that is auto dropped
			// this just keeps writing to the log
			if (!session.isNew()) {
				classLogger.info("Unknown user ending session");
			}
		} else {
			boolean isUserLogout = Boolean.parseBoolean(session.getAttribute(UserSessionLoader.IS_USER_LOGOUT) + "");
			if (isUserLogout) {
				classLogger.info("User {} has logged out to end session", User.getSingleLogginName(thisUser));
			} else {
				classLogger.info("User {} is ending session from non-logout event", User.getSingleLogginName(thisUser));
			}
			// remove the user memory
			thisUser.removeUserMemory();
		}
		// back up the user asset apps
		try {
			SyncUserAssetsThread.execute(session);
		} catch (Exception e) {
			classLogger.error("Failed to back up user apps during session cleanup", e);
		}

		// clear up insight store
		InsightStore inStore = InsightStore.getInstance();
		Set<String> insightIDs = inStore.getInsightIDsForSession(sessionId);
		if (insightIDs != null) {
			Set<String> copy = new HashSet<String>(insightIDs);
			for (String insightId : copy) {
				Insight insight = InsightStore.getInstance().get(insightId);
				if (insight == null) {
					MCPResource.clearInsightLock(insightId);
					continue;
				}
				classLogger.info("Trying to drop insight {}", insightId);
				try {
					if (!sessionOver) {
						// the user's python outlives this server; keep the insight's variables
						insight.setDeletePythonGlobalsOnDropInsight(false);
					}
					InsightUtility.dropInsight(insight);
					classLogger.info("Dropped insight {}", insightId);
				} catch (Exception e) {
					classLogger.error("Error dropping insight {}", insightId, e);
				} finally {
					MCPResource.clearInsightLock(insightId);
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
			classLogger.error("Failed to delete user session cache folder", e);
		}

		// clear from the mcp thread
		Object mcpKeysObj = session.getAttribute(MCPResource.MCP_AUTH_KEY);
		if (mcpKeysObj instanceof Set) {
			Set<String> mcpKeys = (Set<String>) mcpKeysObj;
			if (mcpKeys != null) {
				for (String authKey : mcpKeys) {
					MCPResource.clearInsight(authKey);
				}
			}
		}
		// also attempt to clear via just the sessionId
		MCPResource.clearSessionState(sessionId);

		// clear temporal user values and identify any agent user for cleanup
		User subAgent = removeAgentUserFromTemporalAccessKey(thisUser);

		// drop the r thread if not netty
		try {
			if (thisUser != null) {
				IRUserConnection rserve = thisUser.getRcon();
				if (rserve != null && !rserve.isStopped()) {
					classLogger.info("Dropping user r serve");
					try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
						executor.submit(new Callable<Void>() {
							@Override
							public Void call() throws Exception {
								try {
									rserve.stopR();
									classLogger.info("Successfully dropped user r serve");
								} catch (Exception e) {
									classLogger.warn("Unable to drop user r serve during session user cleanup", e);
								}
								return null;
							}
						});
					}
				}
			}
		} catch (Exception e) {
			classLogger.error("Failed to stop R connection during session user cleanup", e);
		}

		// stop netty/python wrappers and chroot mounts for session user
		cleanupUserProcessAndChroot(thisUser, sessionId, sessionOver, "session user");

		// remove agent users
		if (subAgent != thisUser) {
			cleanupUserProcessAndChroot(subAgent, sessionId, sessionOver, "agent user");
		}

		// if cloud sync enabled, push and clear the rooms
		cleanupUserRooms(thisUser, "session user");
		if (subAgent != thisUser) {
			cleanupUserRooms(subAgent, "agent user");
		}

		// drop cached room projections from Redis; they reload from the DB on next open
		evictRedisRooms(thisUser);
		if (subAgent != thisUser) {
			evictRedisRooms(subAgent);
		}

		cleanupPlaywrightSessions(thisUser);

		// register the successful logout
		UserTrackingUtils.registerLogout(sessionId);
		if (thisUser != null) {
			String logoutIpAddr = (String) session.getAttribute(UserSessionLoader.USER_LOGOUT_IP_ADDR);
			UserAuditTrailUtils.recordLogout(thisUser, sessionId,
					Boolean.parseBoolean(session.getAttribute(UserSessionLoader.IS_USER_LOGOUT) + "") ? "USER_LOGOUT"
							: "SESSION_ENDED",
					logoutIpAddr);
		}
		classLogger.info("Finished logout");
	}

	/**
	 * Cleans up user-owned client process and optional chroot resources.
	 *
	 * @param user      the user to clean up
	 * @param sessionId the session that ended
	 * @param over      true for a logout or timeout, false when the server is stopping
	 * @param userType  descriptive label used in logs (for example, session user)
	 */
	private void cleanupUserProcessAndChroot(User user, String sessionId, boolean over, String userType) {
		if (user == null) {
			return;
		}

		boolean openshellUserSandbox = OpenShellUserSandboxes.appliesTo(user);
		try {
			if (openshellUserSandbox) {
				// The python worker and chroot belong to the user, not this session: they
				// go when the user's last session here ends, and the sandbox when their
				// last session anywhere does.
				OpenShellUserSandboxes.sessionEnded(user, sessionId, over);
			} else {
				// stop the netty thread if used for either r or python
				ClientProcessWrapper cpw = user.getPythonClientProcessWrapper();
				if (cpw != null) {
					cpw.shutdown(true);
				}
			}
		} catch (Exception e) {
			classLogger.error("Failed to shut down client process wrapper during {} cleanup", userType, e);
		}

		try {
			// stop the node.js agent worker if one was spawned
			ClientProcessWrapper nodeCpw = user.getNodeClientProcessWrapper();
			if (nodeCpw != null) {
				nodeCpw.shutdown(true);
			}
		} catch (Exception e) {
			classLogger.error("Failed to shut down node client process wrapper during {} cleanup", userType, e);
		}

		// remove mounts if chroot is enabled
		try {
			if (!openshellUserSandbox && Boolean.parseBoolean(Utility.getDIHelperProperty(Constants.CHROOT_ENABLE))) {
				SymlinkHelper chrootHelper = user.getUserSymlinkHelper();
				if (chrootHelper != null) {
					chrootHelper.removeChrootFolder();
				}
			}
		} catch (Exception e) {
			classLogger.error("Failed to remove chroot folder during {} cleanup", userType, e);
		}
	}

	/**
	 * Pushes user rooms to cloud and removes local room folders when cluster mode
	 * is enabled.
	 *
	 * @param user     the user whose room map should be processed
	 * @param userType descriptive label used in logs (for example, session user)
	 */
	private void evictRedisRooms(User user) {
		if (user == null || user.getRoomHash() == null) {
			return;
		}
		try {
			RoomMessageStore.evictRedisProjections(user.getRoomHash().keySet());
		} catch (Exception e) {
			classLogger.warn("Failed to evict Redis room projections during session cleanup", e);
		}
	}

	private void cleanupUserRooms(User user, String userType) {
		if (!ClusterUtil.IS_CLUSTER || user == null || user.getRoomHash() == null) {
			return;
		}

		Map<String, Room> roomHash = user.getRoomHash();
		for (Map.Entry<String, Room> entry : roomHash.entrySet()) {
			String roomId = entry.getKey();
			Room room = entry.getValue();
			try {
				String roomFolderPath = null;
				if (room != null) {
					try {
						roomFolderPath = room.getRoomFolderPath();
					} catch (Exception e) {
						classLogger.warn("Could not get room folder path for room {} during {} cleanup", roomId,
								userType, e);
					}
				}
				if (roomFolderPath != null) {
					java.io.File roomFolder = new java.io.File(roomFolderPath);
					if (roomFolder.exists() && roomFolder.isDirectory() && RoomUtils.hasFiles(room)) {
						try {
							ClusterUtil.pushRoom(roomId);
							classLogger.info("Pushed room {} to cloud during {} cleanup", roomId, userType);
						} catch (Exception e) {
							classLogger.error("Failed to push room {} to cloud during {} cleanup", roomId, userType, e);
						}
					}
					try {
						FileSystemUtil.deleteFolderIfExists(roomFolderPath);
						classLogger.info("Deleted local room folder for room {} during {} cleanup", roomId, userType);
					} catch (Exception e) {
						classLogger.error("Failed to delete local room folder for room {} during {} cleanup", roomId,
								userType, e);
					}
				}
			} catch (Exception e) {
				classLogger.error("Error processing room {} during {} cleanup", roomId, userType, e);
			}
		}
	}

	/**
	 * Removes any agent user associated with the session user's temporal access
	 * key.
	 *
	 * @param user the session user
	 * @return the removed agent user, or {@code null} when none exists or cleanup
	 *         fails
	 */
	private User removeAgentUserFromTemporalAccessKey(User user) {
		if (user == null) {
			return null;
		}

		try {
			String accessKey = user.getCachedTemporalAccessKey();
			if (accessKey == null || accessKey.isEmpty()) {
				return null;
			}
			return LocalUserStore.getInstance().remove(accessKey);
		} catch (Exception e) {
			classLogger.error("Failed to clear temporal access key during session user cleanup", e);
			return null;
		}
	}

	private void cleanupPlaywrightSessions(User thisUser) {
		if (thisUser != null) {
			Set<String> playwrightSessionIds = thisUser.getPlaywrightSessionIds();
			for (String sessionId : playwrightSessionIds) {
				try {
					PlaywrightSession thisSession = thisUser.getPlaywrightSession(sessionId);
					if (thisSession != null) {
						thisSession.close();
					}
				} catch (Exception e) {
					classLogger.error("Error occurred closing the playwright session {}", sessionId, e);
				}
			}
			BrowserContext sharedContext = thisUser.getSharedPlaywrightContext();
			if (sharedContext != null) {
				try {
					sharedContext.close();
				} catch (Exception e) {
					classLogger.error("Error occurred closing the playwright shared context", e);
				}
			}
		}
	}

	/**
	 * Whether the user has really left: an explicit logout, or idle past the session
	 * timeout. Anything else that destroys a session -- a server stop above all -- is not
	 * the user leaving.
	 *
	 * @param session the session being destroyed
	 * @return true for a logout or a timeout
	 */
	private static boolean sessionIsOver(HttpSession session) {
		try {
			if (Boolean.parseBoolean(String.valueOf(session.getAttribute(UserSessionLoader.IS_USER_LOGOUT)))) {
				return true;
			}
			int maxInactive = session.getMaxInactiveInterval();
			long idleMs = System.currentTimeMillis() - session.getLastAccessedTime();
			// the container expires a session a little after its interval, never before
			return maxInactive > 0 && idleMs >= maxInactive * 1000L - SESSION_EXPIRY_SLACK_MS;
		} catch (IllegalStateException e) {
			// unreadable: keep the lease; the sweeper deletes it once it lapses
			return false;
		}
	}
}
