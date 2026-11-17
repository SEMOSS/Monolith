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
package prerna.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;

class SocketSessionHandlerTest {

	private static final class BlockingStreamer implements FileStreamer {
		private final CountDownLatch started = new CountDownLatch(1);
		private final CountDownLatch finished = new CountDownLatch(1);
		private final AtomicInteger starts = new AtomicInteger();
		private volatile boolean running;

		@Override
		public void start() {
			running = true;
			starts.incrementAndGet();
			started.countDown();
			try {
				while (running) {
					Thread.sleep(5);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				running = false;
				finished.countDown();
			}
		}

		@Override
		public void stop() {
			running = false;
		}

		@Override
		public boolean isRunning() {
			return running;
		}
	}

	private static Session socket(RemoteEndpoint.Basic remote) {
		Session session = mock(Session.class);
		when(session.getBasicRemote()).thenReturn(remote);
		return session;
	}

	@Test
	void sendsEachRecipeUpdateToEverySession() throws Exception {
		SocketSessionHandler handler = new SocketSessionHandler();
		RemoteEndpoint.Basic first = mock(RemoteEndpoint.Basic.class);
		RemoteEndpoint.Basic second = mock(RemoteEndpoint.Basic.class);
		handler.addSession(socket(first));
		handler.addSession(socket(second));

		handler.updateRecipe("{\"x\":1}");

		verify(first).sendText("{\"x\":1}");
		verify(second).sendText("{\"x\":1}");
	}

	@Test
	void dropsASessionWhoseSendFails() throws Exception {
		SocketSessionHandler handler = new SocketSessionHandler();
		RemoteEndpoint.Basic broken = mock(RemoteEndpoint.Basic.class);
		RemoteEndpoint.Basic healthy = mock(RemoteEndpoint.Basic.class);
		doThrow(new IOException("gone")).when(broken).sendText("one");
		handler.addSession(socket(broken));
		handler.addSession(socket(healthy));

		handler.updateRecipe("one");
		handler.updateRecipe("two");

		verify(broken, times(1)).sendText(anyString());
		verify(healthy).sendText("one");
		verify(healthy).sendText("two");
		assertFalse(handler.isEmpty());
	}

	@Test
	void reportsWhetherAnySessionRemains() {
		SocketSessionHandler handler = new SocketSessionHandler();
		Session session = mock(Session.class);
		assertTrue(handler.isEmpty());

		handler.addSession(session);
		assertFalse(handler.isEmpty());

		handler.removeSession(session);
		assertTrue(handler.isEmpty());
	}

	@Test
	void startsOneStreamerPerKey() throws Exception {
		SocketSessionHandler handler = new SocketSessionHandler();
		BlockingStreamer streamer = new BlockingStreamer();
		BlockingStreamer duplicate = new BlockingStreamer();

		handler.startStreamer("claude_code:r1", streamer);
		handler.startStreamer("claude_code:r1", duplicate);

		assertTrue(streamer.started.await(5, TimeUnit.SECONDS));
		assertEquals(0, duplicate.starts.get());
		handler.stopStreamer("claude_code:r1");
		assertTrue(streamer.finished.await(5, TimeUnit.SECONDS));
	}

	@Test
	void stopStreamerEndsTheStreamerThread() throws Exception {
		SocketSessionHandler handler = new SocketSessionHandler();
		BlockingStreamer streamer = new BlockingStreamer();
		handler.startStreamer("k", streamer);
		assertTrue(streamer.started.await(5, TimeUnit.SECONDS));

		handler.stopStreamer("k");
		handler.stopStreamer("unknown");

		assertTrue(streamer.finished.await(5, TimeUnit.SECONDS));
		assertFalse(streamer.isRunning());
	}

	@Test
	void aStoppedKeyCanBeStartedAgain() throws Exception {
		SocketSessionHandler handler = new SocketSessionHandler();
		BlockingStreamer first = new BlockingStreamer();
		handler.startStreamer("k", first);
		assertTrue(first.started.await(5, TimeUnit.SECONDS));
		handler.stopStreamer("k");

		BlockingStreamer second = new BlockingStreamer();
		handler.startStreamer("k", second);

		assertTrue(second.started.await(5, TimeUnit.SECONDS));
		handler.stopAllStreamers();
		assertTrue(second.finished.await(5, TimeUnit.SECONDS));
	}

	@Test
	void stopsEveryStreamerWhenTheLastSessionLeaves() throws Exception {
		SocketSessionHandler handler = new SocketSessionHandler();
		Session first = mock(Session.class);
		Session second = mock(Session.class);
		handler.addSession(first);
		handler.addSession(second);
		BlockingStreamer a = new BlockingStreamer();
		BlockingStreamer b = new BlockingStreamer();
		handler.startStreamer("a", a);
		handler.startStreamer("b", b);
		assertTrue(a.started.await(5, TimeUnit.SECONDS));
		assertTrue(b.started.await(5, TimeUnit.SECONDS));

		handler.removeSession(first);
		assertTrue(a.isRunning());

		handler.removeSession(second);
		assertTrue(a.finished.await(5, TimeUnit.SECONDS));
		assertTrue(b.finished.await(5, TimeUnit.SECONDS));
	}
}
