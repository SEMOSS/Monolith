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
package prerna.web.conf.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import prerna.engine.api.IDatabaseEngine;
import prerna.engine.api.IHeadersDataRow;
import prerna.engine.api.IRawSelectWrapper;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.Utility;

class CACTrackingUtilTest {

	private static final String LATEST_QUERY = "SELECT MAX(DATE_RECORDED) FROM DAILY_USER_COUNT";
	private static final String INSERT_NOV_10 = "INSERT INTO DAILY_USER_COUNT(USER_COUNT, DATE_RECORDED) VALUES (1,'2026-11-10')";
	private static final String UPDATE_NOV_10 = "UPDATE DAILY_USER_COUNT SET USER_COUNT = USER_COUNT + 1 WHERE DATE_RECORDED='2026-11-10'";

	/**
	 * Builds a tracker on a fresh engine id whose table already holds the given
	 * latest day, or no rows when it is null.
	 */
	private static CACTrackingUtil tracker(IDatabaseEngine engine, String latestDay) throws Exception {
		IRawSelectWrapper wrapper = mock(IRawSelectWrapper.class);
		when(wrapper.hasNext()).thenReturn(latestDay != null);
		IHeadersDataRow row = mock(IHeadersDataRow.class);
		when(row.getValues()).thenReturn(new Object[] { latestDay });
		when(wrapper.next()).thenReturn(row);
		WrapperManager manager = mock(WrapperManager.class);
		when(manager.getRawWrapper(engine, LATEST_QUERY)).thenReturn(wrapper);
		String engineId = "tracking-" + UUID.randomUUID();
		try (MockedStatic<Utility> utility = mockStatic(Utility.class);
				MockedStatic<WrapperManager> managers = mockStatic(WrapperManager.class)) {
			utility.when(() -> Utility.getDatabase(engineId)).thenReturn(engine);
			managers.when(WrapperManager::getInstance).thenReturn(manager);
			CACTrackingUtil tracker = CACTrackingUtil.getInstance(engineId);
			assertSame(tracker, CACTrackingUtil.getInstance(engineId));
			verify(wrapper).close();
			return tracker;
		}
	}

	@Test
	void refusesATrackingDatabaseThatDoesNotExist() {
		try (MockedStatic<Utility> utility = mockStatic(Utility.class)) {
			IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
					() -> CACTrackingUtil.getInstance("missing-" + UUID.randomUUID()));
			assertEquals("Could not find tracking engine", error.getMessage());
		}
	}

	@Test
	void keepsOneTrackerPerDatabase() throws Exception {
		CACTrackingUtil first = tracker(mock(IDatabaseEngine.class), null);
		CACTrackingUtil second = tracker(mock(IDatabaseEngine.class), null);

		assertNotSame(first, second);
	}

	@Test
	void insertsTheFirstLoginOfADayThenCountsTheRest() throws Exception {
		IDatabaseEngine engine = mock(IDatabaseEngine.class);
		CACTrackingUtil tracker = tracker(engine, null);

		tracker.addToQueue(LocalDate.of(2026, 11, 10));
		tracker.addToQueue(LocalDate.of(2026, 11, 10));

		verify(engine, timeout(5000)).insertData(UPDATE_NOV_10);
		InOrder order = inOrder(engine);
		order.verify(engine).insertData(INSERT_NOV_10);
		order.verify(engine).commit();
		order.verify(engine).insertData(UPDATE_NOV_10);
	}

	@Test
	void keepsCountingTheDayAlreadyRecordedBeforeARestart() throws Exception {
		IDatabaseEngine engine = mock(IDatabaseEngine.class);
		CACTrackingUtil tracker = tracker(engine, "2026-11-10");

		tracker.addToQueue(LocalDate.of(2026, 11, 10));

		verify(engine, timeout(5000)).commit();
		verify(engine).insertData(UPDATE_NOV_10);
		verify(engine, never()).insertData(startsWith("INSERT"));
	}

	@Test
	void startsANewRowWhenTheDayChanges() throws Exception {
		IDatabaseEngine engine = mock(IDatabaseEngine.class);
		CACTrackingUtil tracker = tracker(engine, "2026-11-09");

		tracker.addToQueue(LocalDate.of(2026, 11, 10));

		verify(engine, timeout(5000)).commit();
		verify(engine).insertData(INSERT_NOV_10);
		verify(engine, never()).insertData(startsWith("UPDATE"));
	}

	@Test
	void keepsCountingAfterAWriteFails() throws Exception {
		IDatabaseEngine engine = mock(IDatabaseEngine.class);
		doThrow(new IllegalStateException("table locked")).when(engine).insertData(anyString());
		CACTrackingUtil tracker = tracker(engine, "2026-11-10");

		tracker.addToQueue(LocalDate.of(2026, 11, 10));
		tracker.addToQueue(LocalDate.of(2026, 11, 10));

		verify(engine, timeout(5000).times(2)).commit();
	}
}
