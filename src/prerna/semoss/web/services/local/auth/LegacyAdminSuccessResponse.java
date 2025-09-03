package prerna.semoss.web.services.local.auth;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Minimal success response DTO for documentation of legacy admin endpoints.
 */
public class LegacyAdminSuccessResponse {
    @Schema(description = "Indicates if the operation succeeded")
    public Boolean success;
}
