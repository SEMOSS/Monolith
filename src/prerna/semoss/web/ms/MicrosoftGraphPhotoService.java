/*******************************************************************************
 * Copyright 2015 Defense Health Agency (DHA)
 *
 * If your use of this software does not include any GPLv2 components:
 * 	Licensed under the Apache License, Version 2.0 (the "License");
 * 	you may not use this file except in compliance with the License.
 * 	You may obtain a copy of the License at
 *
 * 	  http://www.apache.org/licenses/LICENSE-2.0
 *
 * 	Unless required by applicable law or agreed to in writing, software
 * 	distributed under the License is distributed on an "AS IS" BASIS,
 * 	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * 	See the License for the specific language governing permissions and
 * 	limitations under the License.
 * ----------------------------------------------------------------------------
 * If your use of this software includes any GPLv2 components:
 * 	This program is free software; you can redistribute it and/or
 * 	modify it under the terms of the GNU General Public License
 * 	as published by the Free Software Foundation; either version 2
 * 	of the License, or (at your option) any later version.
 *
 * 	This program is distributed in the hope that it will be useful,
 * 	but WITHOUT ANY WARRANTY; without even the implied warranty of
 * 	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * 	GNU General Public License for more details.
 *******************************************************************************/
package prerna.semoss.web.ms;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import prerna.auth.User;
import prerna.collaboration.BrainPeopleUtils;
import prerna.collaboration.CollaborationDbUtils;
import prerna.semoss.web.app.MicrosoftGraphApplication;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Utility;

/**
 * Microsoft profile photos for the Collaboration avatars, served under {@code /msgraph} (see
 * {@link MicrosoftGraphApplication}).
 * <p>
 * {@code GET /msgraph/photo?email=someone@contoso.com}
 * <p>
 * A photo is read with the signed in user's own Microsoft login, so they only ever see one Microsoft would show
 * them; {@link BrainPeopleUtils#photo} decides whose photo there is to ask for. Anyone without one answers 404,
 * which leaves the avatar on its initials. Nothing is kept on the server: the browser caches each answer. With
 * Collaboration off on this instance, every request answers 404.
 */
@Path("/")
public class MicrosoftGraphPhotoService {

	private static final Logger classLogger = LogManager.getLogger(MicrosoftGraphPhotoService.class);

	// photos rarely change; a miss (no photo, no permission, throttled) is asked again sooner
	private static final String PHOTO_CACHE = "private, max-age=86400";
	private static final String MISS_CACHE = "private, max-age=3600";

	/**
	 * @param req the request, carrying the session and the {@code email} parameter
	 * @return the photo as JPEG, 404 when there is none to show or Collaboration is off, or 401 without a
	 *         session
	 */
	@GET
	@Path("/photo")
	@Produces("image/jpeg")
	public Response photo(@Context HttpServletRequest req) {
		// off unless Collaboration is (COLLABORATION_DATABASE_ENABLED in RDF_Map), the same check its reactors make
		if (!Utility.isCollaborationDatabaseEnabled() || !CollaborationDbUtils.isInitalized()) {
			return Response.status(Response.Status.NOT_FOUND).build();
		}
		User user;
		try {
			user = ResourceUtility.getUser(req);
		} catch (IllegalAccessException e) {
			return Response.status(Response.Status.UNAUTHORIZED).build();
		}
		try {
			byte[] photo = BrainPeopleUtils.photo(user, req.getParameter("email"));
			if (photo != null && photo.length > 0) {
				return Response.ok(photo, "image/jpeg").header("Cache-Control", PHOTO_CACHE).build();
			}
		} catch (Exception e) {
			// most often Graph's 404 for someone who never set a photo
			classLogger.debug("No Microsoft photo to show: {}", e.getMessage());
		}
		return Response.status(Response.Status.NOT_FOUND).header("Cache-Control", MISS_CACHE).build();
	}
}
