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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.microsoft.playwright.BrowserContext;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.SyncUserAssetsThread;
import prerna.auth.User;
import prerna.engine.impl.model.RoomMessageStore;
import prerna.om.ClientProcessWrapper;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.LocalUserStore;
import prerna.reactor.playwright.PlaywrightSession;
import prerna.semoss.web.services.local.A2AResource;
import prerna.semoss.web.services.local.MCPResource;
import prerna.usertracking.UserAuditTrailUtils;
import prerna.usertracking.UserTrackingUtils;
import prerna.util.Constants;
import prerna.util.FileSystemUtil;
import prerna.util.SymlinkHelper;
import prerna.util.Utility;
import prerna.util.insight.InsightUtility;

class UserSessionLoaderTest {

	private static final String SESSION_ID = "s1";

	@TempDir
	Path cacheDir;

	private HttpSession session;
	private InsightStore insights;
	private String chrootEnabled;
	private boolean syncFails;
	private String agentKey;
	private MockedStatic<UserAuditTrailUtils> audit;
	private MockedStatic<UserTrackingUtils> tracking;
	private MockedStatic<MCPResource> mcp;
	private MockedStatic<A2AResource> a2a;
	private MockedStatic<FileSystemUtil> files;
	private MockedStatic<InsightUtility> insightUtility;

	@BeforeEach
	void setUp() {
		session = mock(HttpSession.class);
		when(session.getId()).thenReturn(SESSION_ID);
		insights = mock(InsightStore.class);
		chrootEnabled = null;
		syncFails = false;
		audit = mockStatic(UserAuditTrailUtils.class);
		tracking = mockStatic(UserTrackingUtils.class);
		mcp = mockStatic(MCPResource.class);
		a2a = mockStatic(A2AResource.class);
		files = mockStatic(FileSystemUtil.class);
		insightUtility = mockStatic(InsightUtility.class);
	}

	@AfterEach
	void tearDown() {
		insightUtility.close();
		files.close();
		a2a.close();
		mcp.close();
		tracking.close();
		audit.close();
		if (agentKey != null) {
			LocalUserStore.getInstance().remove(agentKey);
		}
	}

	private void destroy() {
		try (MockedStatic<SyncUserAssetsThread> sync = mockStatic(SyncUserAssetsThread.class);
				MockedStatic<InsightStore> stores = mockStatic(InsightStore.class);
				MockedStatic<Utility> utility = mockStatic(Utility.class);
				MockedStatic<RoomMessageStore> rooms = mockStatic(RoomMessageStore.class)) {
			if (syncFails) {
				sync.when(() -> SyncUserAssetsThread.execute(session)).thenThrow(new IllegalStateException("s3 down"));
			}
			stores.when(InsightStore::getInstance).thenReturn(insights);
			utility.when(Utility::getInsightCacheDir).thenReturn(cacheDir.toString());
			utility.when(() -> Utility.getDIHelperProperty(Constants.CHROOT_ENABLE)).thenReturn(chrootEnabled);
			new UserSessionLoader().sessionDestroyed(new HttpSessionEvent(session));
		}
	}

	private static User user() {
		AccessToken token = new AccessToken();
		token.setProvider(AuthProvider.NATIVE);
		token.setId("jdoe");
		User user = new User();
		user.setAccessToken(token);
		return spy(user);
	}

	@Test
	void createsNothingForANewSession() {
		new UserSessionLoader().sessionCreated(new HttpSessionEvent(session));

		verify(session, never()).setAttribute(anyString(), any());
	}

	@Test
	void dropsTheSessionInsightsAndRecordsTheLogout() {
		Insight open = mock(Insight.class);
		Set<String> ids = new HashSet<>(List.of("open", "gone"));
		when(insights.getInsightIDsForSession(SESSION_ID)).thenReturn(ids);
		when(insights.get("open")).thenReturn(open);

		destroy();

		insightUtility.verify(() -> InsightUtility.dropInsight(open));
		mcp.verify(() -> MCPResource.clearInsightLock("open"));
		mcp.verify(() -> MCPResource.clearInsightLock("gone"));
		assertTrue(ids.isEmpty());
		verify(insights).clearSession(SESSION_ID);
		files.verify(() -> FileSystemUtil
				.deleteFolderIfExists(cacheDir.toString() + FileSystems.getDefault().getSeparator() + SESSION_ID));
		mcp.verify(() -> MCPResource.clearSessionState(SESSION_ID));
		tracking.verify(() -> UserTrackingUtils.registerLogout(SESSION_ID));
		audit.verify(() -> UserAuditTrailUtils.recordLogout(any(), anyString(), anyString(), any()), never());
	}

	@Test
	void recordsAUserLogoutAndStopsTheirProcesses() {
		User user = user();
		ClientProcessWrapper python = mock(ClientProcessWrapper.class);
		ClientProcessWrapper node = mock(ClientProcessWrapper.class);
		PlaywrightSession browser = mock(PlaywrightSession.class);
		BrowserContext shared = mock(BrowserContext.class);
		doReturn(python).when(user).getPythonClientProcessWrapper();
		doReturn(node).when(user).getNodeClientProcessWrapper();
		doReturn(Set.of("pw1")).when(user).getPlaywrightSessionIds();
		doReturn(browser).when(user).getPlaywrightSession("pw1");
		doReturn(shared).when(user).getSharedPlaywrightContext();
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		when(session.getAttribute(UserSessionLoader.IS_USER_LOGOUT)).thenReturn(true);
		when(session.getAttribute(UserSessionLoader.USER_LOGOUT_IP_ADDR)).thenReturn("10.0.0.9");

		destroy();

		verify(user).removeUserMemory();
		verify(python).shutdown(true);
		verify(node).shutdown(true);
		verify(browser).close();
		verify(shared).close();
		audit.verify(() -> UserAuditTrailUtils.recordLogout(user, SESSION_ID, "USER_LOGOUT", "10.0.0.9"));
		tracking.verify(() -> UserTrackingUtils.registerLogout(SESSION_ID));
	}

	@Test
	void cleansUpAfterTheUserLogsOutOfTheirLastLogin() {
		User user = user();
		user.getPrimaryLogin();
		user.dropAccessToken(AuthProvider.NATIVE);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		when(session.getAttribute(UserSessionLoader.IS_USER_LOGOUT)).thenReturn(true);

		destroy();

		verify(user).removeUserMemory();
		audit.verify(() -> UserAuditTrailUtils.recordLogout(user, SESSION_ID, "USER_LOGOUT", null));
		tracking.verify(() -> UserTrackingUtils.registerLogout(SESSION_ID));
	}

	@Test
	void recordsAnEndedSessionWhenTheUserDidNotLogOut() {
		User user = user();
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);

		destroy();

		audit.verify(() -> UserAuditTrailUtils.recordLogout(user, SESSION_ID, "SESSION_ENDED", null));
	}

	@Test
	void stopsTheAgentUserBehindATemporalAccessKey() {
		agentKey = "agent-" + UUID.randomUUID();
		User agent = user();
		ClientProcessWrapper agentPython = mock(ClientProcessWrapper.class);
		doReturn(agentPython).when(agent).getPythonClientProcessWrapper();
		LocalUserStore.getInstance().store(agentKey, new Object[] { "secret", null, null });
		LocalUserStore.getInstance().cacheUser(agentKey, agent);
		User user = user();
		doReturn(agentKey).when(user).getCachedTemporalAccessKey();
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);

		destroy();

		verify(agentPython).shutdown(true);
		assertNull(LocalUserStore.getInstance().getCachedUser(agentKey));
		assertFalse(LocalUserStore.getInstance().validate(agentKey, "secret"));
	}

	@Test
	void clearsEveryMcpAndA2aCacheEntryOfTheSession() {
		when(session.getAttribute(MCPResource.MCP_AUTH_KEY)).thenReturn(Set.of("k1", "k2"));

		destroy();

		mcp.verify(() -> MCPResource.clearInsight("k1"));
		mcp.verify(() -> MCPResource.clearInsight("k2"));
		mcp.verify(() -> MCPResource.clearSessionState(SESSION_ID));
		a2a.verify(() -> A2AResource.clearInsight("k1"));
		a2a.verify(() -> A2AResource.clearInsight("k2"));
		a2a.verify(() -> A2AResource.clearInsight(SESSION_ID));
	}

	@Test
	void removesTheUserChrootWhenChrootIsOn() {
		chrootEnabled = "true";
		User user = user();
		SymlinkHelper chroot = mock(SymlinkHelper.class);
		doReturn(chroot).when(user).getUserSymlinkHelper();
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);

		destroy();

		verify(chroot).removeChrootFolder();
	}

	@Test
	void keepsCleaningUpWhenAStepFails() {
		syncFails = true;
		User user = user();
		ClientProcessWrapper python = mock(ClientProcessWrapper.class);
		ClientProcessWrapper node = mock(ClientProcessWrapper.class);
		doThrow(new IllegalStateException("already gone")).when(python).shutdown(true);
		doReturn(python).when(user).getPythonClientProcessWrapper();
		doReturn(node).when(user).getNodeClientProcessWrapper();
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);

		destroy();

		verify(node).shutdown(true);
		tracking.verify(() -> UserTrackingUtils.registerLogout(SESSION_ID));
		audit.verify(() -> UserAuditTrailUtils.recordLogout(user, SESSION_ID, "SESSION_ENDED", null));
	}
}
