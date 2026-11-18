package prerna.web.conf.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class CacAuditTest {

	@Test
	void recordsASuccessWithEveryValuePresent() {
		assertDoesNotThrow(() -> CacAudit.record("LOGIN_SUCCESS", "1234567890", "DOE JOHN ALLEN", "192.0.2.10", true,
				"CAC login succeeded for DOE JOHN ALLEN"));
	}

	@Test
	void recordsAFailureWithNoUser() {
		assertDoesNotThrow(() -> CacAudit.record("LOGIN_FAILURE", null, null, "192.0.2.10", false,
				"a client certificate was presented but did not parse as a valid CAC identity"));
	}

	@Test
	void recordsAClientAddressThatTriesToForgeALogLine() {
		assertDoesNotThrow(() -> CacAudit.record("LOGIN_FAILURE", null, null, "192.0.2.10\nLOGIN_SUCCESS forged", false,
				"refused"));
	}
}
