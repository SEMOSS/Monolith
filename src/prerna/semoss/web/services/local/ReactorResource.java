package prerna.semoss.web.services.local;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

		@GET
	@Path("usageOnly")
	@Produces(MediaType.APPLICATION_JSON)
	public Response listReactorsWithUsageOnly() {

		List<ReactorDTO> reactorList = getAllReactorNames().stream().map(r -> {
			return getReactorByName(r);
		}).filter(Objects::nonNull)
				.filter(reactor -> reactor.getUsage() != null && !reactor.getUsage().isBlank())
				.map(ReactorResource::mapReactor).toList();
		return WebUtility.getResponse(reactorList, 200);
	}



	@GET
	@Path("all")
	@Produces(MediaType.APPLICATION_JSON)
	public Response listReactors() {
	
		List<ReactorDTO> reactorList = getAllReactorNames().stream().map(r -> {
			return getReactorByName(r);
		}).filter(Objects::nonNull)
				.map(ReactorResource::mapReactor).toList();
		return WebUtility.getResponse(reactorList, 200);
	}

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

	private static ReactorDTO mapReactor(IReactor reactor) {
		// ReactorDTO dto = new
		String reactorName = reactor.getName();
		String description = null;
		List<String> requiredKeys = new ArrayList<>();
		Set<String> allKeys = new HashSet<>();
		String usage = reactor.getUsage();
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
		return new ReactorDTO.Builder()
				.setName(reactorName)
				.setDescription(description)
				.setRequiredKeys(requiredKeys)
				.setOptionalKeys(allKeys.stream()
						.filter(Objects::nonNull)
						.map(String::trim)
						.filter(key -> !key.isBlank() && !requiredKeys.contains(key))
						.collect(Collectors.toList()))
				.setUsage(usage)
				.build();
	}


	// DTO class to hold reactor metadata
	static class ReactorDTO {
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

		// Generate builder pattern
		public static class Builder {
			private String name;
			private String description;
			private List<String> requiredKeys = new ArrayList<>();
			private List<String> optionalKeys = new ArrayList<>();
			private String usage;

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

			public Builder setUsage(String usage) {
				this.usage = usage;
				return this;
			}

			public ReactorDTO build() {
				return new ReactorDTO(name, description, requiredKeys, optionalKeys, usage);
			}
		}
	}

}
