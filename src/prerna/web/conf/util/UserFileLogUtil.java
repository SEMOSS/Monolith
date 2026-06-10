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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.util.Utility;

public class UserFileLogUtil {

	private static Map<String, UserFileLogUtil> singletonStore = new HashMap<>();

	/*
	 * Creating a class to write to a file the users who are signing in
	 */

	private String filePath;
	private String sep;
	private BlockingQueue<String[]> queue;
	private FileAppender fileLogger;

	public UserFileLogUtil(String filePath, String sep) throws IOException {
		this.filePath = filePath;
		this.sep = sep;
		this.queue = new ArrayBlockingQueue<>(50);
		this.fileLogger = new FileAppender(this.filePath, this.sep, this.queue);

		new Thread(this.fileLogger).start();
	}

	public static UserFileLogUtil getInstance(String filePath, String sep) throws IOException {
		if (filePath == null || filePath.trim().isEmpty()) {
			throw new IOException("Must pass in a valid filePath");
		}
		synchronized (UserFileLogUtil.class) {
			if (!singletonStore.containsKey(filePath)) {
				UserFileLogUtil trackingUtil = new UserFileLogUtil(filePath, sep);
				singletonStore.put(filePath, trackingUtil);
			}
		}
		return singletonStore.get(filePath);
	}

	public void addToQueue(String[] row) {
		queue.add(row);
	}

}

class FileAppender implements Runnable {

	private static final Logger classLogger = LogManager.getLogger(FileAppender.class);

	private File f = null;
	private FileWriter fw = null;
	private String filePath = null;
	private String sep = null;
	private BlockingQueue<String[]> queue = null;

	public FileAppender(String filePath, String sep, BlockingQueue<String[]> queue) throws IOException {
		this.filePath = filePath;
		this.sep = sep;
		this.queue = queue;

		f = new File(this.filePath);
		if (!f.getParentFile().exists()) {
			Boolean success = f.getParentFile().mkdirs();
			if (!success) {
				classLogger.info("Unable to create file appender at :{}", Utility.cleanLogString(f.getAbsolutePath()));
			}
		}

		// set to append mode
		// to account for restarts of the service
		this.fw = new FileWriter(f, true);
	}

	@Override
	public void run() {
		try {
			String[] row = null;
			while ((row = queue.take()) != null) {
				if (fw == null) {
					this.fw = new FileWriter(f, true);
				}
				if (row == null || row.length == 0) {
					continue;
				}
				int size = row.length;
				StringBuilder builder = new StringBuilder();
				builder.append(row[0] + "");
				for (int i = 1; i < size; i++) {
					builder.append(this.sep).append(row[i] + "");
				}
				builder.append("\n");
				try {
					fw.write(builder.toString());
					fw.flush();
				} catch (IOException e) {
					classLogger.error("Failed to write the user log row to the file at {}",
							Utility.cleanLogString(filePath), e);
				}
			}
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			classLogger.error("Interrupted while waiting to take the next user log row from the queue", ie);
		} catch (IOException ioe) {
			classLogger.error("Failed to write the user log to the file at {}", Utility.cleanLogString(filePath), ioe);
		} finally {
			if (fw != null) {
				try {
					fw.close();
				} catch (IOException ioe) {
					classLogger.error("Failed to close the file writer for the user log file at {}",
							Utility.cleanLogString(filePath), ioe);
				}
			}
			fw = null;
		}
	}

}