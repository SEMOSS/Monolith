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
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import prerna.auth.User;
import prerna.theme.AbstractThemeUtils;
import prerna.theme.AdminThemeUtils;
import prerna.web.services.util.WebUtility;

@Path("/themes")
@PermitAll
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
	public Response getAdminThemes(@Context HttpServletRequest request) {
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
		List<Map<String, Object>> themes = instance.getAdminThemes();
		return WebUtility.getResponse(themes, 200);
	}
	
	@POST
	@Path("/createAdminTheme")
	@Produces("application/json")
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
		String themeMap = WebUtility.inputSanitizer(form.getFirst("json"));
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
		String themeMap = WebUtility.inputSanitizer(form.getFirst("json"));
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
