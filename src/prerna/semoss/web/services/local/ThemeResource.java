package prerna.semoss.web.services.local;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;

import prerna.auth.User;
import prerna.theme.AbstractThemeUtils;
import prerna.theme.AdminThemeUtils;
import prerna.web.services.util.WebUtility;

@Path("/themes")
@PermitAll
@SecurityRequirement(name = "basicAuth")
@SecurityScheme(name = "basicAuth", type = SecuritySchemeType.HTTP, scheme = "basic")
@Tag(name = "Themes", description = "Endpoints for managing administrative UI themes")
public class ThemeResource {

	/**
	 * Get the user
	 * @param request
	 * @return
	 * @throws IllegalAccessException 
	 * @throws IOException
	 */
	private static void checkInit() throws IllegalAccessException {
		if(!AbstractThemeUtils.isInitalized()) {
			throw new IllegalAccessException("Theming database was not found to perform these operations");
		}
	}
	
	@GET
	@Path("/getActiveAdminTheme")
	@Produces("application/json")
	@Operation(
		summary = "Get active admin theme",
		description = "Returns the currently active administrative theme if theming is initialized.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Active theme returned", content = @Content(mediaType = "application/json")),
		@ApiResponse(responseCode = "400", description = "Theming not initialized", content = @Content(mediaType = "application/json"))
	})
	public Response getActiveAdminTheme(@Context HttpServletRequest request) {
		try {
			checkInit();
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		
		Object activeTheme = AdminThemeUtils.getActiveAdminTheme();
		return WebUtility.getResponse(activeTheme, 200);
	}
	
	@GET
	@Path("/getAdminThemes")
	@Produces("application/json")
	@Operation(
		summary = "List admin themes",
		description = "Returns a paginated list of admin themes (admin only).",
		parameters = {
			@Parameter(name = "limit", description = "Max themes to return"),
			@Parameter(name = "offset", description = "Offset for pagination")
		}
	)
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Themes list returned", content = @Content(mediaType = "application/json")),
		@ApiResponse(responseCode = "400", description = "Theming not initialized", content = @Content),
		@ApiResponse(responseCode = "401", description = "Not authorized / not admin", content = @Content)
	})
	public Response getAdminThemes(@Context HttpServletRequest request,
			@QueryParam("limit") Integer limit,
			@QueryParam("offset") Integer offset) {
		try {
			checkInit();
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		AdminThemeUtils instance = AdminThemeUtils.getInstance(user);
		if(instance == null) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User is not an admin");
			return WebUtility.getResponse(errorMap, 401);
		}
		List<Map<String, Object>> themes = instance.getAdminThemes(limit, offset);
		return WebUtility.getResponse(themes, 200);
	}
	
	@POST
	@Path("/createAdminTheme")
	@Produces("application/json")
	@Operation(
		summary = "Create admin theme",
		description = "Creates a new admin theme (admin only).",
		requestBody = @RequestBody(required = true, description = "Form data with name, json, isActive",
			content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(description = "Theme create form")))
	)
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Theme created", content = @Content(mediaType = "application/json")),
		@ApiResponse(responseCode = "400", description = "Validation or init error", content = @Content),
		@ApiResponse(responseCode = "401", description = "Not authorized / not admin", content = @Content)
	})
	public Response createAdminTheme(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		try {
			checkInit();
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		AdminThemeUtils instance = AdminThemeUtils.getInstance(user);
		if(instance == null) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User is not an admin");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String themeName = WebUtility.inputSanitizer(form.getFirst("name"));
		String themeMap = WebUtility.inputSQLSanitizer(form.getFirst("json"));
		boolean isActive = Boolean.parseBoolean(form.getFirst("isActive"));
		String themeId = instance.createAdminTheme(themeName, themeMap, isActive);
		if (themeId != null) {
			return WebUtility.getResponse(true, 200);
		} else {
			return WebUtility.getResponse(false, 400);
		}
	}
	
	@POST
	@Path("/editAdminTheme")
	@Produces("application/json")
	@Operation(
		summary = "Edit admin theme",
		description = "Edits an existing admin theme (admin only).",
		requestBody = @RequestBody(required = true, description = "Form data with id, name, json, isActive",
			content = @Content(mediaType = "application/x-www-form-urlencoded"))
	)
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Theme updated", content = @Content(mediaType = "application/json")),
		@ApiResponse(responseCode = "400", description = "Update or init error", content = @Content),
		@ApiResponse(responseCode = "401", description = "Not authorized / not admin", content = @Content)
	})
	public Response editAdminTheme(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		try {
			checkInit();
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		AdminThemeUtils instance = AdminThemeUtils.getInstance(user);
		if(instance == null) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User is not an admin");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String themeId = WebUtility.inputSanitizer(form.getFirst("id"));
		String themeName = WebUtility.inputSanitizer(form.getFirst("name"));
		String themeMap = WebUtility.inputSQLSanitizer(form.getFirst("json"));
		boolean isActive = Boolean.parseBoolean(form.getFirst("isActive"));
		boolean success = instance.editAdminTheme(themeId, themeName, themeMap, isActive);
		if (success) {
			return WebUtility.getResponse(success, 200);
		} else {
			return WebUtility.getResponse(success, 400);
		}
	}
	
	@POST
	@Path("/deleteAdminTheme")
	@Produces("application/json")
	@Operation(
		summary = "Delete admin theme",
		description = "Deletes an admin theme (admin only).",
		requestBody = @RequestBody(required = true, description = "Form data with id",
			content = @Content(mediaType = "application/x-www-form-urlencoded"))
	)
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Theme deleted", content = @Content(mediaType = "application/json")),
		@ApiResponse(responseCode = "400", description = "Deletion or init error", content = @Content),
		@ApiResponse(responseCode = "401", description = "Not authorized / not admin", content = @Content)
	})
	public Response deleteAdminTheme(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		try {
			checkInit();
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		AdminThemeUtils instance = AdminThemeUtils.getInstance(user);
		if(instance == null) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User is not an admin");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String themeId = WebUtility.inputSanitizer(form.getFirst("id"));
		boolean success = instance.deleteAdminTheme(themeId);
		if (success) {
			return WebUtility.getResponse(success, 200);
		} else {
			return WebUtility.getResponse(success, 400);
		}
	}
	
	@POST
	@Path("/setActiveAdminTheme")
	@Produces("application/json")
	@Operation(
		summary = "Set active admin theme",
		description = "Sets a specific theme as the active admin theme (admin only).",
		requestBody = @RequestBody(required = true, description = "Form data with id",
			content = @Content(mediaType = "application/x-www-form-urlencoded"))
	)
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Theme set active", content = @Content(mediaType = "application/json")),
		@ApiResponse(responseCode = "400", description = "Activation or init error", content = @Content),
		@ApiResponse(responseCode = "401", description = "Not authorized / not admin", content = @Content)
	})
	public Response setActiveAdminTheme(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		try {
			checkInit();
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		AdminThemeUtils instance = AdminThemeUtils.getInstance(user);
		if(instance == null) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User is not an admin");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String themeId = WebUtility.inputSanitizer(form.getFirst("id"));
		boolean success = instance.setActiveTheme(themeId);
		if (success) {
			return WebUtility.getResponse(success, 200);
		} else {
			return WebUtility.getResponse(success, 400);
		}
	}
	
	@POST
	@Path("/setAllAdminThemesInactive")
	@Produces("application/json")
	@Operation(
		summary = "Set all admin themes inactive",
		description = "Marks all admin themes as inactive (admin only).")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "All themes set inactive", content = @Content(mediaType = "application/json")),
		@ApiResponse(responseCode = "400", description = "Operation or init error", content = @Content),
		@ApiResponse(responseCode = "401", description = "Not authorized / not admin", content = @Content)
	})
	public Response setAllAdminThemesInactive(@Context HttpServletRequest request) {
		try {
			checkInit();
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		AdminThemeUtils instance = AdminThemeUtils.getInstance(user);
		if(instance == null) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User is not an admin");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		boolean success = instance.setAllThemesInactive();
		if (success) {
			return WebUtility.getResponse(success, 200);
		} else {
			return WebUtility.getResponse(success, 400);
		}
	}


}
