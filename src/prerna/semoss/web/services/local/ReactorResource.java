package prerna.semoss.web.services.local;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map; // still used in test endpoint
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.security.PermitAll;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
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
 * Returns each reactor's name, description, required keys, optional keys, and
 * usage.
 */
@Path("/engine/reactors")
@PermitAll
public class ReactorResource {

	private static final Logger log = LogManager.getLogger(ReactorResource.class);


	private static IReactor getReactorByName(String name) {
		try {
			return ReactorFactory.getReactor(null, name, null, null);
		} catch (Exception e) {
			log.warn("Failed to load reactor: {}", name, e);
			return null;
		}
	}

	private Set<String> getAllReactorNames() {
		return ReactorFactory.reactorHash.keySet();
	}

	@GET
	@Path("usageOnly")
	@Produces(MediaType.APPLICATION_JSON)
	public Response listReactorsWithUsageOnly() {

		List<ReactorDTO> reactorList = getAllReactorNames().stream().map(r -> {
				return getReactorByName(r);
		}).filter(Objects::nonNull)
				.filter(reactor -> reactor.getUsage() != null && !reactor.getUsage().isBlank())
				.map(reactor -> {
					String reactorName = reactor.getName();
					String description = null;
					List<String> requiredKeys = new ArrayList<>();
					Set<String> allKeys = new HashSet<>();
					String usage = reactor.getUsage();
					List<String> optionalKeys = new ArrayList<>();
					try {

						JSONObject tool = reactor.asMcpTool();
						if (tool != null) {
							description = tool.optString("description", null);
							if (tool.has("inputSchema")) {
								JSONObject inputSchema = tool.getJSONObject("inputSchema");
								if (inputSchema.has("required")) {
									for (Object o : inputSchema.getJSONArray("required")) {
										requiredKeys.add(String.valueOf(o));
									}
								}
								if (inputSchema.has("properties")) {
									JSONObject props = inputSchema.getJSONObject("properties");
									for (String key : props.keySet()) {
										allKeys.add(key);
									}
								}
							}
							 usage = reactor.getUsage();
						} else {
							log.debug("Null tool metadata for reactor {}", reactorName);
						}
					} catch (Exception ee) {
						log.debug("Could not derive MCP metadata for reactor {}", reactorName, ee);
					}
					optionalKeys = allKeys.stream()
							.filter(Objects::nonNull)
							.map(String::trim)
							.filter(k -> !k.isBlank() && !requiredKeys.contains(k))
							.collect(Collectors.toCollection(ArrayList::new));

							return new ReactorDTO(reactorName, description, requiredKeys, optionalKeys, usage);

				}).toList();
		//return WebUtility.getResponse(Map.of("reactors", reactorList), 200);
		return WebUtility.getResponse( reactorList, 200);
	}





	/**
	 * GET /engine/reactors
	 * Returns JSON array (field "reactors") of reactor metadata.
	 * Each reactor object: {
	 * name: String,
	 * description: String?,
	 * requiredKeys: [String],
	 * optionalKeys: [String],
	 * usage: String?
	 * }
	 */

	@GET
	@Path("all")
	@Produces(MediaType.APPLICATION_JSON)
	public Response listReactors(@QueryParam("usageOnly") @DefaultValue("false") boolean usageOnly) {
		List<ReactorDTO> reactorList = new ArrayList<>();
		Map<String, Object> result = new HashMap<>();
		try {
			for (String reactorName : ReactorFactory.reactorHash.keySet()) {
				String description = null;
				List<String> requiredKeys = new ArrayList<>();
				Set<String> allKeys = new HashSet<>();
				String usage = null;
				try {
					IReactor reactor = ReactorFactory.getReactor(null, reactorName, null, null);
					if (reactor != null) {
						try {
							JSONObject tool = reactor.asMcpTool();
							if (tool != null) {
								description = tool.optString("description", null);
								if (tool.has("inputSchema")) {
									JSONObject inputSchema = tool.getJSONObject("inputSchema");
									if (inputSchema.has("required")) {
										for (Object o : inputSchema.getJSONArray("required")) {
											requiredKeys.add(String.valueOf(o));
										}
									}
									if (inputSchema.has("properties")) {
										JSONObject props = inputSchema.getJSONObject("properties");
										for (String key : props.keySet()) {
											allKeys.add(key);
										}
									}
								}
								String reactorUsage = reactor.getUsage();
								if (reactorUsage != null && !reactorUsage.isBlank()) {
									usage = reactorUsage;
								}
							} else {
								log.debug("Null tool metadata for reactor {}", reactorName);
							}
						} catch (Exception ee) {
							log.debug("Could not derive MCP metadata for reactor {}", reactorName, ee);
						}
					}
				} catch (Exception inner) {
					log.warn("Failed to load metadata for reactor: {}", reactorName, inner);
				}
				List<String> optionalKeys = new ArrayList<>();
				for (String k : allKeys) {
					if (!requiredKeys.contains(k)) {
						optionalKeys.add(k);
					}
				}
				reactorList.add(new ReactorDTO(reactorName, description, requiredKeys, optionalKeys, usage));
			}
		} catch (Exception e) {
			log.error("Unexpected error building reactor list", e);
			result.put("error", "Failed to build reactor list: " + e.getMessage());
		}
		result.put("reactors", reactorList);
		log.info("Found {} reactors", reactorList.size());

		return WebUtility.getResponse(result, 200);
	}

	class ReactorDTO {
		public String name;
		public String description;
		public List<String> requiredKeys;
		public List<String> optionalKeys;
		public String usage;

		public ReactorDTO(String name, String description, List<String> requiredKeys, List<String> optionalKeys,
				String usage) {
			this.name = name;
			this.description = description;
			this.requiredKeys = requiredKeys;
			this.optionalKeys = optionalKeys;
			this.usage = usage;
		}
	}


		
	

}
