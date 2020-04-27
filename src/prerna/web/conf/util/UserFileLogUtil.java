package prerna.web.conf.util;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

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
		if(filePath == null || filePath.trim().isEmpty()) {
			throw new IOException("Must pass in a valid filePath");
		}
		synchronized (UserFileLogUtil.class) {
			if(!singletonStore.containsKey(filePath)) {
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
	private static final Logger logger = LogManager.getLogger(FileAppender.class);
	private static final String STACKTRACE = "StackTrace: ";
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
		if(!f.getParentFile().exists()) {
			f.getParentFile().mkdirs();
		}
		
		// set to append mode
		// to account for restarts of the service
		this.fw = new FileWriter(f, true);
	}

	public void run() {
		try {
			String[] row = null;
			while( (row = queue.take()) != null) {
				if(fw == null) {
					this.fw = new FileWriter(f, true);
				}
				if(row == null || row.length == 0) {
					continue;
				}
				int size = row.length;
				StringBuilder builder = new StringBuilder();
				builder.append(row[0]);
				for(int i = 1; i < size; i++) {
					builder.append(this.sep).append(row[i]);
				}
				builder.append("\n");
				try {
					fw.write(builder.toString());
					fw.flush();
				} catch (IOException e) {
					logger.error(STACKTRACE, e);
				}
			}
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			logger.error(STACKTRACE, ie);
		} catch (IOException ioe) {
			logger.error(STACKTRACE, ioe);
		} finally {
			if(fw != null) {
				try {
					fw.close();
				} catch (IOException ioe) {
					logger.error(STACKTRACE, ioe);
				}
			}
			fw = null;
		}
	}

}