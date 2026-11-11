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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

class SocketSessionHandlerFactoryTest {

	@Test
	void returnsOneHandlerPerInsight() {
		String insightId = "factory-" + UUID.randomUUID();

		SocketSessionHandler first = SocketSessionHandlerFactory.getHandler(insightId);

		assertNotNull(first);
		assertSame(first, SocketSessionHandlerFactory.getHandler(insightId));
		assertNotSame(first, SocketSessionHandlerFactory.getHandler(insightId + "-other"));
	}

	@Test
	void concurrentCallersShareTheSameHandler() throws Exception {
		String insightId = "factory-" + UUID.randomUUID();
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<SocketSessionHandler>> results = new ArrayList<>();
			Callable<SocketSessionHandler> lookup = () -> SocketSessionHandlerFactory.getHandler(insightId);
			for (int i = 0; i < 32; i++) {
				results.add(pool.submit(lookup));
			}
			SocketSessionHandler expected = results.get(0).get();
			for (Future<SocketSessionHandler> result : results) {
				assertSame(expected, result.get());
			}
		} finally {
			pool.shutdownNow();
		}
	}
}
