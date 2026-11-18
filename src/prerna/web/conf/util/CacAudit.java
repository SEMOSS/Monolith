package prerna.web.conf.util;

import java.lang.reflect.Method;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.logging.SemossLogUtils;
import prerna.util.Utility;

/**
 * Writes the security events of the CAC/PIV filters (a login that succeeded, a
 * certificate that did not parse, a request refused for having no certificate).
 * <p>
 * Where the platform has a security audit trail
 * ({@code SemossLogUtils.logSecurityAuditEvent}, present in the IL4 lineage) the
 * event goes there. Where it does not (Semoss v6.0.0), the same facts are written
 * to the {@code prerna.security.audit.cac} logger, so the filters compile and
 * report on either. The method is looked up once, by reflection, so this class
 * needs no compile-time dependency on it.
 */
public final class CacAudit {

	private static final Logger classLogger = LogManager.getLogger(CacAudit.class);
	private static final Logger auditLogger = LogManager.getLogger("prerna.security.audit.cac");

	private static final Method AUDIT_TRAIL = findAuditTrail();

	private CacAudit() {
	}

	private static Method findAuditTrail() {
		try {
			return SemossLogUtils.class.getMethod("logSecurityAuditEvent", String.class, String.class, String.class,
					String.class, String.class, String.class, String.class, boolean.class, String.class,
					String.class);
		} catch (NoSuchMethodException | LinkageError e) {
			return null;
		}
	}

	/**
	 * @param eventType LOGIN_SUCCESS or LOGIN_FAILURE
	 * @param userId    the DoD id, or null when there is none
	 * @param userName  the name from the certificate, or null
	 * @param clientIp  the client address, which a caller can influence and so is
	 *                  cleaned before it reaches a log line
	 * @param success   whether the event is a success
	 * @param message   what happened
	 */
	public static void record(String eventType, String userId, String userName, String clientIp, boolean success,
			String message) {
		if (AUDIT_TRAIL != null) {
			try {
				AUDIT_TRAIL.invoke(null, eventType, userId, userName, clientIp, null, null, null, success, message,
						null);
				return;
			} catch (ReflectiveOperationException | RuntimeException e) {
				classLogger.warn("The security audit trail did not take a CAC event; writing it to the log instead", e);
			}
		}
		auditLogger.log(success ? Level.INFO : Level.WARN, "{} user={} name={} clientIp={} {}", eventType,
				Utility.cleanLogString(userId), Utility.cleanLogString(userName), Utility.cleanLogString(clientIp),
				Utility.cleanLogString(message));
	}
}
