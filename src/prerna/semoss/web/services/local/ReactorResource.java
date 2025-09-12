package prerna.semoss.web.services.local;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.security.PermitAll;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import prerna.reactor.IReactor;
import prerna.reactor.ReactorFactory;
import prerna.web.services.util.WebUtility;

/**
 * REST resource to expose metadata about available Reactors.
 * Returns each reactor's name, description, required keys, optional keys, and usage.
 */
@Path("/engine/reactors")
@PermitAll
public class ReactorResource {

	private static final Logger log = LogManager.getLogger(ReactorResource.class);

	/**
	 * GET /engine/reactors
	 * Returns JSON array (field "reactors") of reactor metadata.
	 * Each reactor object: {
	 *   name: String,
	 *   description: String?,
	 *   requiredKeys: [String],
	 *   optionalKeys: [String],
	 *   usage: String?
	 * }
	 */
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public Response listReactors() {
		List<Map<String, Object>> reactorList = new ArrayList<>();
		try {
			// Attempt to retrieve all reactor names from the factory if method exists.
			// Since we do not have the code for ReactorFactory here, use reflection defensively.
			List<String> reactorNames = new ArrayList<>();
			try {
				// Expecting a static method like ReactorFactory.getAllReactorNames()
				java.lang.reflect.Method m = ReactorFactory.class.getMethod("getAllReactorNames");
				Object ret = m.invoke(null);
				if(ret instanceof List<?>) {
					for(Object o : (List<?>)ret) {
						if(o != null) reactorNames.add(o.toString());
					}
				}
			} catch (NoSuchMethodException nsme) {
				log.warn("ReactorFactory.getAllReactorNames() not found; returning empty list");
			} catch (Exception e) {
				log.error("Error invoking ReactorFactory.getAllReactorNames", e);
			}

			for(String reactorName : reactorNames) {
				Map<String, Object> info = new HashMap<>();
				info.put("name", reactorName);
				try {
					// We only need metadata; pass nulls / dummies as appropriate
					IReactor reactor = ReactorFactory.getReactor(null, reactorName, null, null);
					if(reactor != null) {
						// Try to use MCP tool schema if available (as seen in NameServer usage)
						try {
							JSONObject tool = reactor.asMcpTool();
							if(tool != null) {
								info.put("description", tool.optString("description", null));
								if(tool.has("inputSchema")) {
									JSONObject inputSchema = tool.getJSONObject("inputSchema");
									// required keys
									List<String> requiredKeys = new ArrayList<>();
									if(inputSchema.has("required")) {
										for(Object o : inputSchema.getJSONArray("required")) {
											requiredKeys.add(String.valueOf(o));
										}
									}
									info.put("requiredKeys", requiredKeys);
									// optional keys = all properties - required
									List<String> optionalKeys = new ArrayList<>();
									if(inputSchema.has("properties")) {
										JSONObject props = inputSchema.getJSONObject("properties");
										for(String key : props.keySet()) {
											if(!requiredKeys.contains(key)) {
												optionalKeys.add(key);
											}
										}
									}
									info.put("optionalKeys", optionalKeys);
								} else {
									info.put("requiredKeys", new ArrayList<>());
									info.put("optionalKeys", new ArrayList<>());
								}
								// usage: attempt to get a usage/help field if present
								String usage = null;
								if(tool.has("usage")) {
									usage = tool.optString("usage", null);
								} else if(tool.has("examples")) {
									usage = tool.get("examples").toString();
								}
								info.put("usage", usage);
							}
						} catch(Exception ee) {
							log.debug("Could not derive MCP metadata for reactor " + reactorName, ee);
						}
					}
				} catch (Exception inner) {
					log.warn("Failed to load metadata for reactor: " + reactorName, inner);
				}
				reactorList.add(info);
			}
		} catch (Exception e) {
			log.error("Unexpected error building reactor list", e);
			Map<String, Object> error = new HashMap<>();
			error.put("error", "Failed to build reactor list: " + e.getMessage());
			return WebUtility.getResponse(error, 500);
		}

		Map<String, Object> wrapper = new HashMap<>();
		wrapper.put("reactors", reactorList);
		return WebUtility.getResponse(wrapper, 200);
	}
}
