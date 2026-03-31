package prerna.web.conf;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;

/**
 * RateLimitFilter is a servlet filter that implements using the Bucket4j library.
 *
 * Keying strategy (in order):
 *  1) API Key header (default: X-API-Key) if present
 *  2) Authenticated user (HttpServletRequest#getRemoteUser) if present
 *  3) Client IP (X-Forwarded-For first hop, else request.getRemoteAddr())
 *
 * Response:
 *  - 429 Too Many Requests
 *  - Retry-After: seconds until next token is available
 */
public class RateLimitFilter implements Filter {

    private Set<String> excludedPaths = new HashSet<>();
    private static final long DEFAULT_LIMIT_PER_MINUTE = 120;
    private static final long DEFAULT_BURST_CAPACITY = 60;
    private static final Set<String> DEFAULT_METHODS = Set.of("POST", "PUT", "DELETE");

    private static final String PARAM_LIMIT_PER_MINUTE = "limitPerMinute";
    private static final String PARAM_BURST_CAPACITY = "burstCapacity";
    private static final String PARAM_METHODS = "methods";
    private static final String PARAM_API_KEY_HEADER = "apiKeyHeader";

    private static final String DEFAULT_API_KEY_HEADER = "X-API-Key";

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private volatile long limitPerMinute;
    private volatile long burstCapacity;
    private volatile Set<String> methods;
    private volatile String apiKeyHeader;
	
	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		this.limitPerMinute = parseLong(filterConfig.getInitParameter(PARAM_LIMIT_PER_MINUTE), DEFAULT_LIMIT_PER_MINUTE);
	      this.burstCapacity = parseLong(filterConfig.getInitParameter(PARAM_BURST_CAPACITY), DEFAULT_BURST_CAPACITY);
	      this.methods = parseMethods(filterConfig.getInitParameter(PARAM_METHODS), DEFAULT_METHODS);
	      this.apiKeyHeader = parseString(filterConfig.getInitParameter(PARAM_API_KEY_HEADER), DEFAULT_API_KEY_HEADER);
	
	    // Basic sanity checks
	      if (limitPerMinute <= 0) {
	          limitPerMinute = DEFAULT_LIMIT_PER_MINUTE;
	      }
	      if (burstCapacity <= 0) {
	          burstCapacity = Math.max(1, limitPerMinute / 2);
	      }
	}
	
	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
			throws IOException, ServletException {
        if (!(arg0 instanceof HttpServletRequest) || !(arg1 instanceof HttpServletResponse)) {
            arg2.doFilter(arg0, arg1);
            return;
        }
    
        HttpServletRequest req = (HttpServletRequest) arg0;
        HttpServletResponse res = (HttpServletResponse) arg1;
        
            // Only apply to selected methods (optional). If you want all methods, set methods="GET,POST,PUT,DELETE,OPTIONS"
        String method = req.getMethod();
        if (!methods.contains(method.toUpperCase(Locale.ROOT))) {
            arg2.doFilter(arg0, arg1);
            return;
        }
        
        String key = resolveKey(req);
        Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket(limitPerMinute, burstCapacity));
        
        // Consume one token per request
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        
        // Helpful headers for clients (optional but nice)
        res.setHeader("X-RateLimit-Limit", String.valueOf(limitPerMinute));
        res.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, probe.getRemainingTokens())));
        
        if (probe.isConsumed()) {
            arg2.doFilter(arg0, arg1);
            return;
        }
        
        long waitNanos = probe.getNanosToWaitForRefill();
        long retryAfterSeconds = Math.max(1, Duration.ofNanos(waitNanos).getSeconds());
        
        res.setStatus(429);
        res.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"error\":\"rate_limited\",\"message\":\"Too many requests. Please retry later.\"}");
    }
	
    private String resolveKey(HttpServletRequest req) {
            // 1) API key (best)
        String apiKey = headerValue(req, apiKeyHeader);
        if (apiKey != null && !apiKey.isBlank()) {
            return "apiKey:" + apiKey.trim();
        }
        
        // 2) Authenticated user
        String remoteUser = req.getRemoteUser();
        if (remoteUser != null && !remoteUser.isBlank()) {
            return "user:" + remoteUser.trim();
        }
        
        // 3) Client IP (fallback)
        String ip = clientIp(req);
        return "ip:" + ip;
	}
	private static Bucket newBucket(long limitPerMinute, long burstCapacity) {
	    Bandwidth limit = Bandwidth.builder()
	    .capacity(burstCapacity)
	    // greedily refill "limitPerMinute" tokens each 1 minute
	    .refillGreedy(limitPerMinute, Duration.ofMinutes(1))
	    .build();
	
	    return Bucket.builder()
	        .addLimit(limit)
	        .build();
	}
	
	private static String clientIp(HttpServletRequest req) {
	    // If behind a reverse proxy, ensure it is configured to set X-Forwarded-For
        String xff = headerValue(req, "X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // first hop
            String first = xff.split(",")[0].trim();
            if (!first.isBlank()) {
                return first;
            }
        }
        String remoteAddr = req.getRemoteAddr();
        return remoteAddr != null ? remoteAddr : "unknown";
	}
	
	private static String headerValue(HttpServletRequest req, String headerName) {
	    if (headerName == null || headerName.isBlank()) return null;
	    return req.getHeader(headerName);
	}
	
	private static long parseLong(String value, long defaultValue) {
	    if (value == null || value.isBlank()) return defaultValue;
	    try {
	        return Long.parseLong(value.trim());
	    } catch (NumberFormatException e) {
	        return defaultValue;
	    }
	}
	
	private static String parseString(String value, String defaultValue) {
	    if (value == null || value.isBlank()) return defaultValue;
	    return value.trim();
	}
	
	private static Set<String> parseMethods(String raw, Set<String> defaultMethods) {
        if (raw == null || raw.isBlank()) return defaultMethods;
        String[] parts = raw.split(",");
            Set<String> out = ConcurrentHashMap.newKeySet();
            for (String p : parts) {
                if (p != null) {
                    String m = p.trim().toUpperCase(Locale.ROOT);
                    if (!m.isBlank()) out.add(m);
                }
            }
            return out.isEmpty() ? defaultMethods : Collections.unmodifiableSet(out);
    }
  }