package prerna.semoss.web.services.local;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.security.PermitAll;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import prerna.algorithm.api.ITableDataFrame;
import prerna.om.Insight;
import prerna.reactor.IReactor;
import prerna.reactor.ReactorFactory;
import prerna.web.services.util.ReactorResourceGroups;
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

	@GET
	@Path("all")
	@Produces(MediaType.APPLICATION_JSON)
	public Response listReactors() {

		Map<String, List<ReactorDTO>> reactorList = ReactorResourceGroups.getReactorHashByGroups().entrySet().stream()
				.collect(Collectors.toMap(
						Map.Entry::getKey,
						entry -> entry.getValue().stream().map(r -> {
							return getReactorByName(r);
						}).filter(Objects::nonNull)
								.map(ReactorResource::mapReactor).collect(Collectors.toList())
				));

		return WebUtility.getResponse(reactorList, 200);
	}

	private static IReactor getReactorByName(String name) {
		Insight i = null;
		IReactor pr = null;
		ITableDataFrame tdb = null;
		try {
			return ReactorFactory.getReactor(i, name, pr, tdb);
		} catch (Exception e) {
			log.warn("Failed to load reactor: {}", name, e);
			return null;
		}
	}

	
	private static ReactorDTO mapReactor(IReactor reactor) {
		// ReactorDTO dto = new
		String reactorName = reactor.getName();
		String description = null;
		List<String> requiredKeys = new ArrayList<>();
		Set<String> allKeys = new HashSet<>();
		try {

			JSONObject tool = reactor.asMcpTool();
			if (tool != null) {
				description = tool.optString("description", null);
				if (tool.has("inputSchema")) {
					JSONObject inputSchema = tool.getJSONObject("inputSchema");
					if (inputSchema.has("required")) {
						for (Object o : inputSchema.getJSONArray("required")) {
							String key = String.valueOf(o).trim();
							if (!key.isBlank() && !requiredKeys.contains(key)) {
								requiredKeys.add(key);
							}
						}
					}
					if (inputSchema.has("properties")) {
						JSONObject props = inputSchema.getJSONObject("properties");
						for (String key : props.keySet()) {
							allKeys.add(key);
						}
					}
				}

			} else {
				log.debug("Null tool metadata for reactor {}", reactorName);
			}
		} catch (Exception ee) {
			log.debug("Could not derive MCP metadata for reactor {}", reactorName, ee);
		}
		return new ReactorDTO.Builder()
				.setName(reactorName)
				.setDescription(description)
				.setRequiredKeys(requiredKeys)
				.setOptionalKeys(allKeys.stream()
						.filter(Objects::nonNull)
						.map(String::trim)
						.filter(key -> !key.isBlank() && !requiredKeys.contains(key))
						.collect(Collectors.toList()))
				.build();
	}


	
	// DTO class to hold reactor metadata
	static class ReactorDTO {
		public String name;
		public String description;
		public List<String> requiredKeys;
		public List<String> optionalKeys;

		public ReactorDTO(String name, String description, List<String> requiredKeys, List<String> optionalKeys
				) {
			this.name = name;
			this.description = description;
			this.requiredKeys = requiredKeys;
			this.optionalKeys = optionalKeys;
		}

		// Generate builder pattern
		public static class Builder {
			private String name;
			private String description;
			private List<String> requiredKeys = new ArrayList<>();
			private List<String> optionalKeys = new ArrayList<>();

			public Builder setName(String name) {
				this.name = name;
				return this;
			}

			public Builder setDescription(String description) {
				this.description = description;
				return this;
			}

			public Builder setRequiredKeys(List<String> requiredKeys) {
				this.requiredKeys = requiredKeys;
				return this;
			}

			public Builder setOptionalKeys(List<String> optionalKeys) {
				this.optionalKeys = optionalKeys;
				return this;
			}


			public ReactorDTO build() {
				return new ReactorDTO(name, description, requiredKeys, optionalKeys);
			}
		}
	}

}
