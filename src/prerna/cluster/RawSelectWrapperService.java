// package prerna.cluster;

// import java.util.Hashtable;
// import java.util.Map;
// import java.util.concurrent.ConcurrentHashMap;

// import javax.servlet.http.HttpServletRequest;
// import javax.ws.rs.POST;
// import javax.ws.rs.Path;
// import javax.ws.rs.Produces;
// import javax.ws.rs.core.Context;
// import javax.ws.rs.core.Response;

// import org.apache.logging.log4j.LogManager;
// import org.apache.logging.log4j.Logger;

// import prerna.algorithm.api.SemossDataType;
// import prerna.engine.api.IEngine;
// import prerna.engine.api.IHeadersDataRow;
// import prerna.engine.api.IRawSelectWrapper;
// import prerna.rdf.engine.wrappers.WrapperManager;
// import prerna.sablecc2.om.execptions.SemossPixelException;
// import prerna.util.Constants;
// import prerna.util.Utility;
// import prerna.web.services.util.WebUtility;

// @Path("/cluster")
// public class RawSelectWrapperService implements IRawSelectWrapper {

// 	private static final Logger logger = LogManager.getLogger(RawSelectWrapperService.class); 

// 	public static final String APP_ID = "appId";
// 	public static final String WRAPPER_ID = "wrapperId";
// 	public static final String QUERY = "query";

// 	//////////////////////////////////////////////////////////////////////////////////////////
// 	////////////////////////////// Code for managing active wrappers /////////////////////////

// 	/**
// 	 * Make this a concurrent hash map, since it can be accessed concurrently by incoming requests.
// 	 */
// 	private Map<String, IRawSelectWrapper> activeWrappers = new ConcurrentHashMap<>();

// 	/**
// 	 * Gets the wrapper when the query is not applicable.
// 	 * 
// 	 * @param appId
// 	 * @param wrapperId
// 	 * @return
// 	 * @throws Exception 
// 	 */
// 	private IRawSelectWrapper getRawSelectWrapper(String appId, String wrapperId) throws Exception {
// 		return getRawSelectWrapper(appId, wrapperId, null);
// 	}

// 	/**
// 	 * If the wrapper has already been activated, then return the existing
// 	 * wrapper. Else create it and add it to the active wrappers map.
// 	 * 
// 	 * @param appId
// 	 * @param wrapperId
// 	 * @param query
// 	 * @return
// 	 * @throws Exception 
// 	 */
// 	private IRawSelectWrapper getRawSelectWrapper(String appId, String wrapperId, String query) throws Exception {
		
// 		if(appId ==null) {
// 			throw new SemossPixelException("App is currently disabled by owner");
// 		}
		
// 		String wrapperKey = wrapperId + "@" + appId;
// 		if (activeWrappers.containsKey(wrapperKey)) {
// 			return activeWrappers.get(wrapperKey);
// 		} else {
// 			IEngine engine = getEngine(appId);
// 			IRawSelectWrapper wrapper = WrapperManager.getInstance().getRawWrapper(engine, query);
// 			activeWrappers.put(wrapperKey, wrapper);
// 			return wrapper;
// 		}
// 	}

// 	/**
// 	 * Removes the wrapper and returns true if it was actually removed, false if
// 	 * it was not present.
// 	 * 
// 	 * @param appId
// 	 * @param wrapperId
// 	 * @return
// 	 */
// 	private boolean removeRawSelectWrapper(String appId, String wrapperId) {
// 		String wrapperKey = wrapperId + "@" + appId;
// 		if (activeWrappers.containsKey(wrapperKey)) {
// 			activeWrappers.remove(wrapperKey);
// 			return true;
// 		} else {
// 			return false;
// 		}
// 	}

// 	//////////////////////////////////////////////////////////////////////////////////////////
// 	/////////////////////////////////// Util methods /////////////////////////////////////////

// 	private IEngine getEngine(String appId) {
// 		IEngine engine = null;
// //		if (appId.endsWith("_InsightsRDBMS")) {
// //			String parentAppId = appId.replace("_InsightsRDBMS", "");
// //			IEngine parentEngine = Utility.getEngine(parentAppId);
// //			engine = parentEngine.getInsightDatabase();
// //		} else {
// 			engine = Utility.getEngine(appId);
// //		}
// 		return engine;
// 	}

// 	@POST
// 	@Produces("application/json")
// 	@Path("/alive")
// 	public Response alive(@Context HttpServletRequest request) {
// 		return WebUtility.getResponse(true, 200);
// 	}

// 	//////////////////////////////////////////////////////////////////////////////////////////
// 	///////////////////////// Code for mirroring wrapper functionality ///////////////////////

// 	@Override
// 	public void execute() {
// 		throw new IllegalStateException("Only overriding this method for reference.");	
// 	}

// 	/**
// 	 * Execute requires appId, wrapperId, and query
// 	 * @param request
// 	 * @return
// 	 */
// 	@POST
// 	@Produces("application/json")
// 	@Path("/execute")
// 	public Response execute(@Context HttpServletRequest request) {

// 		// Grab parameters
// 		String appId = request.getParameter(APP_ID);
// 		String wrapperId = request.getParameter(WRAPPER_ID);
// 		String query = request.getParameter(QUERY);

// 		Hashtable<String, Object> ret = new Hashtable<>();

// 		// Perform action
// 		IRawSelectWrapper wrapper = null;
// 		try {
// 			wrapper = getRawSelectWrapper(appId, wrapperId, query);
// 			wrapper.execute();
// 		} catch (Exception e) {
// 			logger.error(Constants.STACKTRACE,e);
// 			ret.put("errorMessage", e.getMessage());
// 			return WebUtility.getResponse(ret, 400);
// 		}

// 		// Set return values
// 		ret.put("success", true);
// 		ret.put("appId", appId);
// 		ret.put("wrapperId", wrapperId);
// 		ret.put("query", query);
// 		ret.put("action", "execute");
// 		return WebUtility.getResponse(ret, 200);
// 	}

// 	@Override
// 	public void setQuery(String query) {
// 		throw new IllegalStateException("Only overriding this method for reference.");	
// 	}

// 	@Override
// 	public String getQuery() {
// 		throw new IllegalStateException("Only overriding this method for reference.");	
// 	}

// 	/**
// 	 * Set query requires appId, wrapperId, and query
// 	 * @param request
// 	 * @return
// 	 */
// 	@POST
// 	@Produces("application/json")
// 	@Path("/setQuery")
// 	public Response setQuery(@Context HttpServletRequest request) {

// 		// Grab parameters
// 		String appId = request.getParameter(APP_ID);
// 		String wrapperId = request.getParameter(WRAPPER_ID);
// 		String query = request.getParameter(QUERY);

// 		// Perform action
// 		IRawSelectWrapper wrapper;
// 		try {
// 			wrapper = getRawSelectWrapper(appId, wrapperId, query);
// 			wrapper.setQuery(query);
// 		} catch (Exception e) {
// 			logger.error(Constants.STACKTRACE,e);
// 		}

// 		// Set return values
// 		Hashtable<String, Object> ret = new Hashtable<>();
// 		ret.put("success", true);
// 		ret.put("appId", appId);
// 		ret.put("wrapperId", wrapperId);
// 		ret.put("query", query);
// 		ret.put("action", "setQuery");
// 		return WebUtility.getResponse(ret, 200);
// 	}

// 	@Override
// 	public void cleanUp() {
// 		throw new IllegalStateException("Only overriding this method for reference.");
// 	}

// 	/**
// 	 * Clean up requires appId and wrapperId
// 	 * @param request
// 	 * @return
// 	 */
// 	@POST
// 	@Produces("application/json")
// 	@Path("/cleanUp")
// 	public Response cleanUp(@Context HttpServletRequest request) {

// 		// Grab parameters
// 		String appId = request.getParameter(APP_ID);
// 		String wrapperId = request.getParameter(WRAPPER_ID);

// 		if (appId == null) {
// 			throw new IllegalArgumentException("App id cannot be null here, must define app id");
// 		}

// 		// Perform action
// 		IRawSelectWrapper wrapper = null;
// 		try {
// 			wrapper = getRawSelectWrapper(appId, wrapperId);
// 		} catch (Exception e) {
// 			logger.error(Constants.STACKTRACE,e);
// 		} finally {
// 			if(wrapper != null) {
// 				wrapper.cleanUp();
// 			}
// 		}
// 		boolean removed = removeRawSelectWrapper(appId, wrapperId); // Also remove from active list

// 		// Set return values
// 		Hashtable<String, Object> ret = new Hashtable<>();
// 		ret.put("success", true);
// 		ret.put("appId", appId);
// 		ret.put("wrapperId", wrapperId);
// 		ret.put("action", "cleanUp");
// 		ret.put("removed", removed);
// 		return WebUtility.getResponse(ret, 200);
// 	}

// 	@Override
// 	public void setEngine(IEngine engine) {
// 		throw new IllegalStateException("Only overriding this method for reference.");			
// 	}

// 	@Override
// 	public IEngine getEngine() {
// 		throw new IllegalStateException("Only overriding this method for reference.");			
// 	}

// 	/**
// 	 * Set engine requires appId and wrapperId
// 	 * @param request
// 	 * @return
// 	 */
// 	@POST
// 	@Produces("application/json")
// 	@Path("/setEngine")
// 	public Response setEngine(@Context HttpServletRequest request) {

// 		// Grab parameters
// 		String appId = request.getParameter(APP_ID);
// 		String wrapperId = request.getParameter(WRAPPER_ID);

// 		// Perform action
// 		IRawSelectWrapper wrapper;
// 		try {
// 			wrapper = getRawSelectWrapper(appId, wrapperId);
// 			IEngine engine = getEngine(appId);
// 			wrapper.setEngine(engine);
// 		} catch (Exception e) {
// 			logger.error(Constants.STACKTRACE,e);
// 		}

// 		// Set return values
// 		Hashtable<String, Object> ret = new Hashtable<>();
// 		ret.put("success", true);
// 		ret.put("appId", appId);
// 		ret.put("wrapperId", wrapperId);
// 		ret.put("action", "setEngine");
// 		return WebUtility.getResponse(ret, 200);
// 	}

// 	@Override
// 	public boolean hasNext() {
// 		throw new IllegalStateException("Only overriding this method for reference.");	
// 	}

// 	/**
// 	 * Has next requires appId and wrapperId
// 	 * @param request
// 	 * @return
// 	 */
// 	@POST
// 	@Produces("application/json")
// 	@Path("/hasNext")
// 	public Response hasNext(@Context HttpServletRequest request) {

// 		// Grab parameters
// 		String appId = request.getParameter(APP_ID);
// 		String wrapperId = request.getParameter(WRAPPER_ID);

// 		// Perform action
// 		boolean hasNext = false;
// 		IRawSelectWrapper wrapper;
// 		try {
// 			wrapper = getRawSelectWrapper(appId, wrapperId);
// 			hasNext = wrapper.hasNext();
// 		} catch (Exception e) {
// 			logger.error(Constants.STACKTRACE,e);
// 		}

// 		// Set return values
// 		return WebUtility.getResponse(hasNext, 200);
// 	}

// 	@Override
// 	public IHeadersDataRow next() {
// 		throw new IllegalStateException("Only overriding this method for reference.");	
// 	}

// 	/**
// 	 * Next requires appId and wrapperId
// 	 * @param request
// 	 * @return
// 	 */
// 	@POST
// 	@Produces("application/json")
// 	@Path("/next")
// 	public Response next(@Context HttpServletRequest request) {

// 		// Grab parameters
// 		String appId = request.getParameter(APP_ID);
// 		String wrapperId = request.getParameter(WRAPPER_ID);

// 		// Perform action
// 		IHeadersDataRow nextRow = null;
// 		IRawSelectWrapper wrapper = null;
// 		try {
// 			wrapper = getRawSelectWrapper(appId, wrapperId);
// 			nextRow = wrapper.next();
// 		} catch (Exception e) {
// 			logger.error(Constants.STACKTRACE,e);
// 		}

// 		// Set return values
// 		return WebUtility.getResponse(nextRow, 200);
// 	}

// 	@Override
// 	public String[] getHeaders() {
// 		throw new IllegalStateException("Only overriding this method for reference.");	
// 	}

// 	/**
// 	 * Get headers requires appId and wrapperId
// 	 * @param request
// 	 * @return
// 	 */
// 	@POST
// 	@Produces("application/json")
// 	@Path("/getHeaders")
// 	public Response getHeaders(@Context HttpServletRequest request) {

// 		// Grab parameters
// 		String appId = request.getParameter(APP_ID);
// 		String wrapperId = request.getParameter(WRAPPER_ID);

// 		// Perform action
// 		String[] headers = null;
// 		IRawSelectWrapper wrapper;
// 		try {
// 			wrapper = getRawSelectWrapper(appId, wrapperId);
// 			headers = wrapper.getHeaders();
// 		} catch (Exception e) {
// 			logger.error(Constants.STACKTRACE,e);
// 		}

// 		// Set return values
// 		return WebUtility.getResponse(headers, 200);
// 	}

// 	@Override
// 	public SemossDataType[] getTypes() {
// 		throw new IllegalStateException("Only overriding this method for reference.");	
// 	}

// 	/**
// 	 * Get types requires appId and wrapperId
// 	 * @param request
// 	 * @return
// 	 */
// 	@POST
// 	@Produces("application/json")
// 	@Path("/getTypes")
// 	public Response getTypes(@Context HttpServletRequest request) {

// 		// Grab parameters
// 		String appId = request.getParameter(APP_ID);
// 		String wrapperId = request.getParameter(WRAPPER_ID);

// 		// Perform action
// 		SemossDataType[] types = null;
// 		IRawSelectWrapper wrapper;
// 		try {
// 			wrapper = getRawSelectWrapper(appId, wrapperId);
// 			types = wrapper.getTypes();
// 		} catch (Exception e) {
// 			logger.error(Constants.STACKTRACE,e);
// 		}

// 		// Set return values
// 		return WebUtility.getResponse(types, 200);
// 	}

// 	@Override
// 	public long getNumRecords() {
// 		throw new IllegalStateException("Only overriding this method for reference.");	
// 	}

// 	/**
// 	 * Get num records requires appId and wrapperId
// 	 * @param request
// 	 * @return
// 	 */
// 	@POST
// 	@Produces("application/json")
// 	@Path("/getNumRecords")
// 	public Response getNumRecords(@Context HttpServletRequest request) {

// 		// Grab parameters
// 		String appId = request.getParameter(APP_ID);
// 		String wrapperId = request.getParameter(WRAPPER_ID);

// 		// Perform action
// 		long numRecords;
// 		IRawSelectWrapper wrapper = null;
// 		try {
// 			wrapper = getRawSelectWrapper(appId, wrapperId);
// 			numRecords = wrapper.getNumRecords();
// 		} catch (Exception e) {
// 			logger.error(Constants.STACKTRACE,e);
// 			Hashtable<String, Object> ret = new Hashtable<>();
// 			ret.put("errorMessage", e.getMessage());
// 			return WebUtility.getResponse(ret, 400);
// 		}

// 		// Set return values
// 		return WebUtility.getResponse(numRecords, 200);
// 	}

// 	@Override
// 	public long getNumRows() {
// 		throw new IllegalStateException("Only overriding this method for reference.");	
// 	}

// 	/**
// 	 * Get num rows requires appId and wrapperId
// 	 * @param request
// 	 * @return
// 	 */
// 	@POST
// 	@Produces("application/json")
// 	@Path("/getNumRows")
// 	public Response getNumRows(@Context HttpServletRequest request) {

// 		// Grab parameters
// 		String appId = request.getParameter(APP_ID);
// 		String wrapperId = request.getParameter(WRAPPER_ID);

// 		// Perform action
// 		long numRecords;
// 		IRawSelectWrapper wrapper = null;
// 		try {
// 			wrapper = getRawSelectWrapper(appId, wrapperId);
// 			numRecords = wrapper.getNumRows();
// 		} catch (Exception e) {
// 			Hashtable<String, Object> ret = new Hashtable<>();
// 			ret.put("errorMessage", e.getMessage());
// 			return WebUtility.getResponse(ret, 400);
// 		}

// 		// Set return values
// 		return WebUtility.getResponse(numRecords, 200);
// 	}

// 	@Override
// 	public void reset() {
// 		throw new IllegalStateException("Only overriding this method for reference.");	
// 	}

// 	/**
// 	 * Reset requires appId and wrapperId
// 	 * @param request
// 	 * @return
// 	 */
// 	@POST
// 	@Produces("application/json")
// 	@Path("/reset")
// 	public Response reset(@Context HttpServletRequest request) {

// 		// Grab parameters
// 		String appId = request.getParameter(APP_ID);
// 		String wrapperId = request.getParameter(WRAPPER_ID);

// 		Hashtable<String, Object> ret = new Hashtable<>();

// 		// Perform action
// 		IRawSelectWrapper wrapper = null;
// 		try {
// 			wrapper = getRawSelectWrapper(appId, wrapperId);
// 			wrapper.reset();
// 		} catch (Exception e) {
// 			logger.error(Constants.STACKTRACE,e);
// 			ret.put("errorMessage", e.getMessage());
// 			return WebUtility.getResponse(ret, 400);
// 		}

// 		// Set return values
// 		ret.put("success", true);
// 		ret.put("appId", appId);
// 		ret.put("wrapperId", wrapperId);
// 		ret.put("action", "reset");
// 		return WebUtility.getResponse(ret, 200);
// 	}

// 	@Override
// 	public boolean flushable() {
// 		return false;
// 	}

// 	@Override
// 	public String flush() {
// 		return null;
// 	}

// }
