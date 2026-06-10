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

import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.engine.api.IDatabaseEngine;
import prerna.engine.api.IRawSelectWrapper;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.Utility;

public class CACTrackingUtil {

	private static Map<String, CACTrackingUtil> singletonStore = new HashMap<String, CACTrackingUtil>();

	/*
	 * Creating a class to manage updating the user count in a synchronized manner
	 */

	private BlockingQueue<LocalDate> queue;
	private CountUpdater updater;

	public CACTrackingUtil(IDatabaseEngine trackingEngine) {
		queue = new ArrayBlockingQueue<LocalDate>(50);
		updater = new CountUpdater(trackingEngine, queue);

		new Thread(updater).start();
	}

	public static CACTrackingUtil getInstance(String trackingEngineId) {
		if (!singletonStore.containsKey(trackingEngineId)) {
			IDatabaseEngine engine = Utility.getDatabase(trackingEngineId);
			if (engine == null) {
				throw new IllegalArgumentException("Could not find tracking engine");
			}
			CACTrackingUtil trackingUtil = new CACTrackingUtil(engine);
			singletonStore.put(trackingEngineId, trackingUtil);
		}
		return singletonStore.get(trackingEngineId);
	}

	public void addToQueue(LocalDate d) {
		queue.add(d);
	}

}

class CountUpdater implements Runnable {

	private static final Logger classLogger = LogManager.getLogger(CountUpdater.class);

	private static final String TABLE = "DAILY_USER_COUNT";
	private static final String COUNT_COL = "USER_COUNT";
	private static final String DATE_COL = "DATE_RECORDED";
	private static final String GET_LATEST_DATE_QUERY = "SELECT MAX(" + DATE_COL + ") FROM " + TABLE;

	// this is how we will keep the last date so we
	// do not need to query if this date exists every time
	private String lastDateExists = "xxxx-xx-xx";
	// no need to recreate this query every time either
	private String updateQuery = null;
	protected IDatabaseEngine engine;

	protected BlockingQueue<LocalDate> queue = null;

	public CountUpdater(IDatabaseEngine engine, BlockingQueue<LocalDate> queue) {
		this.engine = engine;
		this.queue = queue;

		// since the server could go down
		// need to get the last "lastDateExists"
		// so we do not have duplicates
		IRawSelectWrapper wrapper = null;
		try {
			wrapper = WrapperManager.getInstance().getRawWrapper(engine, GET_LATEST_DATE_QUERY);
			if (wrapper.hasNext()) {
				Object value = wrapper.next().getValues()[0];
				if (value != null) {
					this.lastDateExists = value.toString();
					// we need to update our query + lastDateExists
					this.updateQuery = "UPDATE " + TABLE + " SET " + COUNT_COL + " = " + COUNT_COL + " + 1 " + "WHERE "
							+ DATE_COL + "='" + lastDateExists + "'";

				}
			}
		} catch (Exception e) {
			classLogger.error("Failed to query the latest recorded user count date from the tracking engine", e);
		} finally {
			if (wrapper != null) {
				try {
					wrapper.close();
				} catch (IOException e) {
					classLogger.error("Failed to close the result wrapper for the latest user count date query", e);
				}
			}
		}
	}

	@Override
	public void run() {
		try {
			LocalDate localDate = null;
			while ((localDate = queue.take()) != null) {
				String todaysDate = java.sql.Date.valueOf(localDate).toString();

				if (!lastDateExists.equals(todaysDate)) {
					// we will insert for the first one of the day
					String insertQuery = "INSERT INTO " + TABLE + "(" + COUNT_COL + ", " + DATE_COL + ") "
							+ "VALUES (1,'" + todaysDate + "')";
					try {
						engine.insertData(insertQuery);
					} catch (Exception e) {
						classLogger.error("Failed to insert the new daily user count record for date {}", todaysDate,
								e);
					}

					// and we will set the query up for the rest of the
					// updates that happen today

					this.lastDateExists = todaysDate;
					// we need to update our query + lastDateExists
					this.updateQuery = "UPDATE " + TABLE + " SET " + COUNT_COL + " = " + COUNT_COL + " + 1 " + "WHERE "
							+ DATE_COL + "='" + lastDateExists + "'";
				} else {
					try {
						engine.insertData(this.updateQuery);
					} catch (Exception e) {

						classLogger.error("Failed to increment the daily user count for date {}", lastDateExists, e);
					}
				}

				engine.commit();
			}
		} catch (InterruptedException e) {
			classLogger.error("The user count updater thread was interrupted while waiting for new dates to process",
					e);
		}
	}

}