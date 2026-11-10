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
package prerna.semoss.web.services.config;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The requests and responses of {@link TrustedTokenService}, for its OpenAPI
 * annotations.
 */
public final class TrustedTokenServiceSchemas {

	private TrustedTokenServiceSchemas() {
	}

	@Schema(description = "The trusted token issued to the calling IP address")
	public record TokenGetTokenResult(@Schema(description = "The token value", requiredMode = REQUIRED) String token,
			@Schema(description = "When the token was issued, on a single server; on a cluster this slot carries the IP address the token is bound to") String dateAdded,
			@Schema(description = "The client id the token was issued to, when one was sent") String clientId) {
	}

	@Schema(description = "A refused token request")
	public record TokenGetTokenError(@Schema(description = "Always false", requiredMode = REQUIRED) boolean success,
			@Schema(description = "Why the token was refused", requiredMode = REQUIRED) String errorMessage) {
	}

	@Schema(description = "The form a token is requested with")
	public record TokenGetTokenRequest(
			@Schema(description = "The API client id", requiredMode = REQUIRED) @JsonProperty("client_id") String clientId,
			@Schema(description = "The API client's secret key; required when the server checks API user credentials") @JsonProperty("secret_key") String secretKey) {
	}
}
