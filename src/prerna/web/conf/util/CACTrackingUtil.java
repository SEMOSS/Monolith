package prerna.web.conf.util;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.engine.api.IDatabase;
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

	public CACTrackingUtil(IDatabase trackingEngine) {
		queue = new ArrayBlockingQueue<LocalDate>(50);
		updater = new CountUpdater(trackingEngine, queue);

		new Thread(updater).start();
	}

	public static CACTrackingUtil getInstance(String trackingEngineId) {
		if(!singletonStore.containsKey(trackingEngineId)) {
			IDatabase engine = Utility.getDatabase(trackingEngineId);
			if(engine == null) {
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

	private static final String TABLE = "DAILY_USER_COUNT";
	private static final String COUNT_COL = "USER_COUNT";
	private static final String DATE_COL = "DATE_RECORDED";
	private static final String GET_LATEST_DATE_QUERY = "SELECT MAX(" + DATE_COL + ") FROM " + TABLE;
	private static final Logger logger = LogManager.getLogger(CountUpdater.class);

	// this is how we will keep the last date so we 
	// do not need to query if this date exists every time
	private String lastDateExists = "xxxx-xx-xx";
	// no need to recreate this query every time either
	private String updateQuery = null;
	protected IDatabase engine;

	protected BlockingQueue<LocalDate> queue = null;
	
	public CountUpdater(IDatabase engine, BlockingQueue<LocalDate> queue) {
		this.engine = engine;
		this.queue = queue;
		
		// since the server could go down
		// need to get the last "lastDateExists"
		// so we do not have duplicates
		IRawSelectWrapper wrapper = null;
		try {
			wrapper = WrapperManager.getInstance().getRawWrapper(engine, GET_LATEST_DATE_QUERY);
			if(wrapper.hasNext()) {
				Object value = wrapper.next().getValues()[0];
				if(value != null) {
					this.lastDateExists = value.toString();
					// we need to update our query + lastDateExists
					this.updateQuery = "UPDATE " + TABLE + 
							" SET " + COUNT_COL + " = " + COUNT_COL + " + 1 "
							+ "WHERE " + DATE_COL + "='" + lastDateExists + "'";

				}
			}
		} catch (Exception e) {
			logger.error("STACKTRACE: ",e);
		} finally {
			if(wrapper != null) {
				wrapper.cleanUp();
			}
		}
	}

	public void run() {
		try {
			LocalDate localDate = null;
			while( (localDate = queue.take()) != null) {
				String todaysDate = java.sql.Date.valueOf(localDate).toString();
				
				if(!lastDateExists.equals(todaysDate)) {
					// we will insert for the first one of the day
					String insertQuery = "INSERT INTO " + TABLE + 
							"(" + COUNT_COL + ", " + DATE_COL + ") "
							+ "VALUES (1,'" + todaysDate + "')";
					try {
						engine.insertData(insertQuery);
					} catch (Exception e) {
						logger.error("STACKTRACE: ",e);
					}
					
					// and we will set the query up for the rest of the 
					// updates that happen today
					
					this.lastDateExists = todaysDate;
					// we need to update our query + lastDateExists
					this.updateQuery = "UPDATE " + TABLE + 
							" SET " + COUNT_COL + " = " + COUNT_COL + " + 1 "
							+ "WHERE " + DATE_COL + "='" + lastDateExists + "'";
				} else {
					try {
						engine.insertData(this.updateQuery);
					} catch (Exception e) {

						logger.error("STACKTRACE: ",e);
					}
				}
				
				engine.commit();
			}
		} catch (InterruptedException e) {
			logger.error("STACKTRACE: ",e);
		}
	}
	
}