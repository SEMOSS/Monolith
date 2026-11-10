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
package prerna.semoss.web.form;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The requests and responses of {@link FormResource}, for its OpenAPI
 * annotations.
 */
public final class FormResourceSchemas {

	private FormResourceSchemas() {
	}

	@Schema(description = "The form access to grant or revoke")
	public record FormModifyUserAccessRequest(
			@Schema(description = "\"Add\" to grant access, \"Remove\" to revoke it", allowableValues = { "Add",
					"Remove" }, requiredMode = REQUIRED) String addOrRemove,
			@Schema(description = "The CAC or SAML id of the user", requiredMode = REQUIRED) String userid,
			@Schema(description = "The instance to grant or revoke; when removing, leave it out to revoke all of the user's access") String instanceName,
			@Schema(description = "Whether the user is the system admin of the instance, when adding") String ownerStatus) {
	}

	@Schema(description = "The instance to rename")
	public record FormRenameInstanceRequest(
			@Schema(description = "The id or alias of the database that holds the instance", requiredMode = REQUIRED) String dbName,
			@Schema(description = "The instance's current URI", requiredMode = REQUIRED) String originalUri,
			@Schema(description = "The instance's new URI", requiredMode = REQUIRED) String newUri,
			@Schema(description = "\"true\" to delete the original instance") String deleteInstanceBoolean) {
	}

	@Schema(description = "The instance to certify")
	public record FormCertifyInstanceRequest(
			@Schema(description = "The id or alias of the database that holds the instance", requiredMode = REQUIRED) String dbName,
			@Schema(description = "The instance type", requiredMode = REQUIRED) String instanceType,
			@Schema(description = "The instance name; the user must be its system admin", requiredMode = REQUIRED) String instanceName) {
	}

	@Schema(description = "The form instances the user can access")
	public record FormGetUserInstanceAuthResult(
			@Schema(description = "The user's CAC or SAML id", requiredMode = REQUIRED) String cac_id,
			@Schema(description = "Each instance name the user can access mapped to \"true\" or \"false\", whether the user is its system admin; the keys are instance names", requiredMode = REQUIRED) Map<String, String> validInstances) {
	}
}
