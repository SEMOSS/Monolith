package prerna.semoss.web.services.local;

import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import prerna.auth.User;
import prerna.theme.AdminThemeUtils;
import prerna.web.services.util.WebUtility;

public class ThemingService {

	@GET
	@Path("/getAdminThemes")
	@Produces("application/json")
	public Response getAdminThemes(@Context HttpServletRequest request, @PathParam("user") String app) {
		User user = null;
		List<Map<String, Object>> themes = AdminThemeUtils.getInstance(user).getAdminThemes();
		return WebUtility.getResponse(themes, 200);
	}
	
	@GET
	@Path("/getAllThemes")
	@Produces("application/json")
	public Response getAllThemes(@Context HttpServletRequest request, @PathParam("user") String app) {
		User user = null;
		List<Map<String, Object>> themes = AdminThemeUtils.getInstance(user).getAdminThemes();
		return WebUtility.getResponse(themes, 200);
	}
	
	

	@POST
	@Path("/createAdminTheme")
	@Produces("application/json")
	public Response createAdminTheme(@Context HttpServletRequest request, @PathParam("user") String app) {
		User user = null;
		String themeId = "";
		String themeName = "";
		String themeMap = "";
		boolean isActive = false;
		boolean success = AdminThemeUtils.getInstance(user).createAdminTheme(themeId, themeName, themeMap, isActive);
		if (success) {
			return WebUtility.getResponse(success, 200);
		} else {
			return WebUtility.getResponse(success, 400);

		}
	}
	
	@POST
	@Path("/editAdminTheme")
	@Produces("application/json")
	public Response editAdminTheme(@Context HttpServletRequest request, @PathParam("user") String app) {
		User user = null;
		String themeId = "";
		String themeName = "";
		String themeMap = "";
		boolean isActive = false;
		boolean success = AdminThemeUtils.getInstance(user).editAdminTheme(themeId, themeName, themeMap, isActive);
		if (success) {
			return WebUtility.getResponse(success, 200);
		} else {
			return WebUtility.getResponse(success, 400);

		}
	}
	
	@POST
	@Path("/deleteAdminTheme")
	@Produces("application/json")
	public Response deleteAdminTheme(@Context HttpServletRequest request, @PathParam("user") String app) {
		User user = null;
		String themeId = "";
		boolean success = AdminThemeUtils.getInstance(user).deleteAdminTheme(themeId);
		if (success) {
			return WebUtility.getResponse(success, 200);
		} else {
			return WebUtility.getResponse(success, 400);

		}
	}
	
	@POST
	@Path("/setActiveAdminTheme")
	@Produces("application/json")
	public Response setActiveAdminTheme(@Context HttpServletRequest request, @PathParam("user") String app) {
		User user = null;
		String themeId = "";
		boolean success = AdminThemeUtils.getInstance(user).setActiveTheme(themeId);
		if (success) {
			return WebUtility.getResponse(success, 200);
		} else {
			return WebUtility.getResponse(success, 400);

		}
	}

}
