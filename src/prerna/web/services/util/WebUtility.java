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
package prerna.web.services.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Collectors;

import javax.servlet.ServletRequest;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.time.FastDateFormat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.javatuples.Pair;
import org.owasp.encoder.Encode;
import org.owasp.esapi.ESAPI;
import org.owasp.esapi.codecs.MySQLCodec;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;

import com.github.f4b6a3.uuid.alt.GUID;
import com.google.common.net.InternetDomainName;
import com.google.gson.Gson;
import com.google.json.JsonSanitizer;

import prerna.auth.User;
import prerna.logging.SemossLogUtils;
import prerna.om.ThreadStore;
import prerna.util.Constants;
import prerna.util.FstUtil;
import prerna.util.Utility;
import prerna.util.gson.GsonUtility;
import prerna.web.conf.DBLoader;

/**
 * The Utility class contains a variety of miscellaneous functions implemented
 * extensively throughout SEMOSS. Some of these functionalities include getting
 * concept names, printing messages, loading engines, and writing Excel
 * workbooks.
 */
public final class WebUtility {

	private static final Logger classLogger = LogManager.getLogger(WebUtility.class);

	private static final FastDateFormat expiresDateFormat = FastDateFormat.getInstance("EEE, dd MMM yyyy HH:mm:ss zzz",
			TimeZone.getTimeZone("GMT"));

	private static final List<String[]> noCacheHeaders = new ArrayList<String[]>();
	static {
		noCacheHeaders.add(new String[] { "Cache-Control", "private" });
	}

	private static Gson getDefaultGson() {
		return GsonUtility.getDefaultGson();
	}

	private WebUtility() {

	}

	/**
	 * Serialize the given object to JSON (UTF-8) and return it as a
	 * {@link StreamingOutput} that can be used as the entity of a JAX-RS response.
	 *
	 * @param vec the value to serialize
	 * @return a streaming output of the JSON bytes, or {@code null} if {@code vec}
	 *         is null or serialization fails
	 */
	public static StreamingOutput getSO(Object vec) {
		if (vec != null) {
			Gson gson = getDefaultGson();
			try {
				final byte[] output = gson.toJson(vec).getBytes("UTF8");
				return new StreamingOutput() {
					@Override
					public void write(OutputStream outputStream) throws IOException, WebApplicationException {
						try (PrintStream ps = new PrintStream(outputStream);) {
							ps.write(output, 0, output.length);
						}
					}
				};
			} catch (UnsupportedEncodingException e) {
				classLogger.error("Failed to serialize response payload to UTF-8 bytes", e);
			}
		}

		return null;
	}

	/**
	 * Stream the contents of a file as the body of a JAX-RS response. The file is
	 * deleted after the stream is fully written, making this useful for serving
	 * temp files generated for a single download.
	 *
	 * @param fileLocation absolute path to the file to stream (will be normalized)
	 * @return a streaming output that writes the file's contents, or {@code null}
	 *         if {@code fileLocation} is null or the file cannot be opened
	 */
	public static StreamingOutput getSOFile(String fileLocation) {
		if (fileLocation != null) {
			try {
				File daFile = new File(WebUtility.normalizePath(fileLocation));
				FileReader fr = new FileReader(daFile);
				BufferedReader br = new BufferedReader(fr);
				return new StreamingOutput() {
					@Override
					public void write(OutputStream outputStream) throws IOException, WebApplicationException {
						try (PrintWriter pw = new PrintWriter(outputStream);) {
							String data = null;
							while ((data = br.readLine()) != null) {
								pw.println(data);
							}
							// ps.write(data, 0 , data.length);
							fr.close();
							br.close();
							daFile.delete();
						}
					}
				};
			} catch (Exception e) {
				classLogger.error("Failed to open file '{}' for streaming response", fileLocation, e);
			}
		}

		return null;
	}

	/**
	 * Build a JSON response with the given entity and status code. Convenience
	 * overload for {@link #getResponse(Object, int, List, NewCookie...)} with no
	 * extra headers and no cookies.
	 *
	 * @param vec    the entity to serialize as JSON
	 * @param status the HTTP status code
	 * @return JAX-RS Response, or {@code null} if {@code vec} is null
	 */
	public static Response getResponse(Object vec, int status) {
		return getResponse(vec, status, null);
	}

	/**
	 * Build a JSON response with the given entity, status code, and cookies.
	 * Convenience overload for
	 * {@link #getResponse(Object, int, List, NewCookie...)} with no extra headers.
	 *
	 * @param vec     the entity to serialize as JSON
	 * @param status  the HTTP status code
	 * @param cookies cookies to attach to the response (may be null)
	 * @return JAX-RS Response, or {@code null} if {@code vec} is null
	 */
	public static Response getResponse(Object vec, int status, NewCookie[] cookies) {
		return getResponse(vec, status, null, cookies);
	}

	/**
	 * Build a JSON response that includes a {@code Cache-Control: private} header
	 * to prevent shared caches (e.g. proxies) from caching the payload.
	 *
	 * @param vec    the entity to serialize as JSON
	 * @param status the HTTP status code
	 * @return JAX-RS Response, or {@code null} if {@code vec} is null
	 */
	public static Response getResponseNoCache(Object vec, int status) {
		return getResponse(vec, status, noCacheHeaders);
	}

	/**
	 * Build a JSON response with no-cache headers and the given cookies. See
	 * {@link #getResponseNoCache(Object, int)}.
	 *
	 * @param vec     the entity to serialize as JSON
	 * @param status  the HTTP status code
	 * @param cookies cookies to attach to the response (may be null)
	 * @return JAX-RS Response, or {@code null} if {@code vec} is null
	 */
	public static Response getResponseNoCache(Object vec, int status, NewCookie[] cookies) {
		return getResponse(vec, status, noCacheHeaders, cookies);
	}

	/**
	 * Build a JSON response with the given entity, status code, optional extra
	 * headers, and optional cookies. Cookies are written via {@code Set-Cookie}
	 * headers so that {@code SameSite} attributes from
	 * {@link #convertCookieToHeader(NewCookie)} are preserved.
	 *
	 * @param vec        the entity to serialize as JSON
	 * @param status     the HTTP status code
	 * @param addHeaders extra headers to attach as {@code [name, value]} pairs (may
	 *                   be null)
	 * @param cookies    cookies to attach via {@code Set-Cookie} headers
	 * @return JAX-RS Response, or {@code null} if {@code vec} is null
	 */
	public static Response getResponse(Object vec, int status, List<String[]> addHeaders, NewCookie... cookies) {
		if (vec != null) {
			Gson gson = getDefaultGson();
			try {
				final byte[] output = gson.toJson(vec).getBytes("UTF8");
				int length = output.length;
				ResponseBuilder builder = Response.status(status).entity(WebUtility.getSO(output))
						.header("Content-Length", length);
				if (addHeaders != null && !addHeaders.isEmpty()) {
					for (int i = 0; i < addHeaders.size(); i++) {
						String[] headerInfo = addHeaders.get(i);
						builder.header(headerInfo[0], headerInfo[1]);
					}
				}
				if (cookies != null && cookies.length > 0) {
					// due to chrome updates, we require to add cookies
					// with samesite tags if they are not secure
					// so will set the cookies via the header
					for (NewCookie cookie : cookies) {
						// add the cookie to the header
						// with the SameSite Strict tag
						builder.header("Set-Cookie", convertCookieToHeader(cookie));
					}
				}
				return builder.build();
			} catch (UnsupportedEncodingException e) {
				classLogger.error("Failed to serialize response payload to UTF-8 bytes for status {}", status, e);
			}
			return Response.status(200).entity(WebUtility.getSO(vec)).build();
		}

		return null;
	}

	/**
	 * Render a {@link NewCookie} as a {@code Set-Cookie} header value, including a
	 * {@code SameSite=Strict} attribute (which JAX-RS's {@code NewCookie} cannot
	 * express directly). Used to satisfy the modern Chromium SameSite cookie
	 * requirements.
	 *
	 * @param cookie the cookie to serialize
	 * @return a string suitable for a {@code Set-Cookie} response header
	 */
	public static String convertCookieToHeader(NewCookie cookie) {
		StringBuilder c = new StringBuilder(64 + cookie.getValue().length());
		// add the cookie
		c.append(cookie.getName());
		c.append('=');
		c.append(cookie.getValue());
		// set same-site strict
		c.append("; ");
		c.append("SameSite");
		c.append('=');
		c.append(Utility.getSameSiteCookieValue());
		// get the domain
		if (cookie.getDomain() != null) {
			c.append("; ");
			c.append("domain");
			c.append('=');
			c.append(cookie.getDomain());
		}
		// the path
		if (cookie.getPath() != null) {
			c.append("; ");
			c.append("path");
			c.append('=');
			c.append(cookie.getPath());
		}
		if (cookie.isSecure()) {
			c.append("; secure");
		}
		if (cookie.isHttpOnly()) {
			c.append("; HttpOnly");
		}
		if (cookie.getMaxAge() >= 0) {
			c.append("; ");
			c.append("Expires");
			c.append('=');
			c.append(getExpires(cookie.getMaxAge()));
		}

		return c.toString();
	}

	/**
	 * Compute an HTTP-format {@code Expires} date for a cookie that should live for
	 * {@code maxAge} seconds from now (in GMT). Returned in the format required by
	 * RFC 7231 / cookie specs.
	 *
	 * @param maxAge the cookie's max-age in seconds (negative values are treated as
	 *               a session cookie expiring immediately)
	 * @return an RFC-formatted expires date string
	 */
	private static String getExpires(int maxAge) {
		if (maxAge < 0) {
			return "";
		}
		Calendar expireDate = Calendar.getInstance();
		expireDate.setTime(new Date());
		expireDate.add(Calendar.SECOND, maxAge);
		return expiresDateFormat.format(expireDate);
	}

	/**
	 * Wrap a pre-built byte array as a {@link StreamingOutput} so it can be used as
	 * the entity of a JAX-RS response without re-serializing.
	 *
	 * @param output the raw bytes to write to the response
	 * @return a streaming output that writes {@code output} verbatim
	 */
	public static StreamingOutput getSO(byte[] output) {
		try {
			return new StreamingOutput() {
				@Override
				public void write(OutputStream outputStream) throws IOException, WebApplicationException {
					try (PrintStream ps = new PrintStream(outputStream);) {
						ps.write(output, 0, output.length);
					}
				}
			};
		} catch (Exception e) {
			classLogger.error("Unexpected error wrapping byte[] payload as a streaming response", e);
		}

		return null;
	}

	/**
	 * Serialize {@code obj} with FST (a fast Java binary serializer) and return it
	 * as a {@link StreamingOutput} for binary JAX-RS responses. Used by internal
	 * endpoints that exchange Java objects directly rather than JSON.
	 *
	 * @param obj the object to FST-serialize
	 * @return a streaming output of the serialized bytes, or {@code null} if
	 *         {@code obj} is null or serialization fails
	 */
	public static StreamingOutput getBinarySO(Object obj) {
		if (obj != null) {
			try {
				final byte[] output = FstUtil.serialize(obj);
				return new StreamingOutput() {
					@Override
					public void write(OutputStream outputStream) throws IOException, WebApplicationException {
						try {
							outputStream.write(output);
							outputStream.flush();
						} catch (Exception ex) {
							classLogger.error("Failed to write binary payload to response output stream", ex);
						}
					}
				};
			} catch (Exception ex) {
				classLogger.error("Failed to serialize object as binary response payload", ex);
			}
		}
		return null;
	}

	/**
	 * Ensure no CRLF injection into responses for malicious attacks. Replaces
	 * newline, carriage-return, tab, and their URL-encoded forms with underscores,
	 * then HTML-encodes the result.
	 *
	 * @param message the candidate response string
	 * @return the cleaned string, or {@code null} if {@code message} is null
	 */
	public static String cleanHttpResponse(String message) {
		if (message == null) {
			return message;
		}
		message = message.replace('\n', '_').replace("%0d", "_").replace('\r', '_').replace("%0a", "_")
				.replace('\t', '_').replace("%09", "_");

		message = Encode.forHtml(message);
		return message;
	}

	/**
	 * Percent-encode a value so it can be safely embedded as a URI component.
	 *
	 * @param message the value to encode
	 * @return the URI-component-encoded value, or {@code null} if {@code message}
	 *         is null
	 */
	public static String encodeHTTPUri(String message) {
		if (message == null) {
			return message;
		}
		return Encode.forUriComponent(message);
	}

	/**
	 * Strip HTML/JS that could be used for XSS while preserving common safe markup
	 * (formatting, links, blocks, styles, images, tables). Use this for untrusted
	 * user input that may end up in HTML responses.
	 *
	 * @param stringToSanitize the candidate input
	 * @return the sanitized string, or {@code null} if input is null
	 */
	public static String inputSanitizer(String stringToSanitize) {
		if (stringToSanitize == null) {
			classLogger.debug("Input to inputSanitizer is null, returning null");
			return stringToSanitize;
		}

		PolicyFactory policy = Sanitizers.FORMATTING.and(Sanitizers.LINKS).and(Sanitizers.BLOCKS).and(Sanitizers.STYLES)
				.and(Sanitizers.IMAGES).and(Sanitizers.TABLES);
		MySQLCodec mySQLCodec = new MySQLCodec(MySQLCodec.Mode.ANSI);
		return ESAPI.encoder().encodeForSQL(mySQLCodec, policy.sanitize(stringToSanitize));
	}

	/**
	 * Escape a string for use in a SQL literal (ANSI / MySQL flavor) using ESAPI.
	 * Does not strip HTML/JS — use {@link #inputSanitizer(String)} for that.
	 *
	 * @param stringToSanitize the candidate input
	 * @return the SQL-escaped string, or {@code null} if input is null
	 */
	public static String inputSQLSanitizer(String stringToSanitize) {
		if (stringToSanitize == null) {
			classLogger.debug("Input to inputSQLSanitizer is null, returning null");
			return stringToSanitize;
		}

		MySQLCodec mySQLCodec = new MySQLCodec(MySQLCodec.Mode.ANSI);
		return ESAPI.encoder().encodeForSQL(mySQLCodec, stringToSanitize);
	}

	/**
	 * Apply {@link #inputSQLSanitizer(String)} to each element of the list.
	 *
	 * @param listToSanitize the list of candidate strings
	 * @return a new list with each element SQL-escaped, or {@code null} if input is
	 *         null
	 */
	public static List<String> inputSQLSanitizer(List<String> listToSanitize) {
		if (listToSanitize == null) {
			return null;
		}

		ArrayList<String> newList = new ArrayList<>(listToSanitize.size());
		for (String s : listToSanitize) {
			newList.add(inputSQLSanitizer(s));
		}
		return newList;
	}

	/**
	 * Apply {@link #inputSanitizer(String)} to each element of the list.
	 *
	 * @param listToSanitize the list of candidate strings
	 * @return a new list with each element HTML-sanitized, or {@code null} if input
	 *         is null
	 */
	public static List<String> inputSanitizer(List<String> listToSanitize) {
		if (listToSanitize == null) {
			return null;
		}
		ArrayList<String> newList = new ArrayList<>(listToSanitize.size());
		for (String s : listToSanitize) {
			newList.add(inputSanitizer(s));
		}
		return newList;
	}

	/**
	 * Apply {@link #inputSanitizer(String)} to each element of the set.
	 *
	 * @param listToSanitize the set of candidate strings
	 * @return a new set with each element HTML-sanitized, or {@code null} if input
	 *         is null
	 */
	public static HashSet<String> inputSanitizer(HashSet<String> listToSanitize) {
		if (listToSanitize == null) {
			return null;
		}
		HashSet<String> newList = new HashSet<>(listToSanitize.size());
		for (String s : listToSanitize) {
			newList.add(inputSanitizer(s));
		}
		return newList;
	}

	/**
	 * Apply {@link #inputSanitizer(String)} to each element of the set, preserving
	 * iteration order.
	 *
	 * @param listToSanitize the ordered set of candidate strings
	 * @return a new linked set with each element HTML-sanitized, or {@code null} if
	 *         input is null
	 */
	public static LinkedHashSet<String> inputSanitizer(LinkedHashSet<String> listToSanitize) {
		if (listToSanitize == null) {
			return null;
		}
		LinkedHashSet<String> newList = new LinkedHashSet<>(listToSanitize.size());
		for (String s : listToSanitize) {
			newList.add(inputSanitizer(s));
		}
		return newList;
	}

	/**
	 * Given JSON-like content, produce a string of JSON that is safe to embed and
	 * safe to pass to JavaScript's {@code eval} operator. Uses Google's
	 * {@code JsonSanitizer} which fixes common issues (unquoted keys, trailing
	 * commas, etc.) and rejects content that cannot be made safe.
	 *
	 * @param jsonStringToSanitize the candidate JSON string
	 * @return a sanitized, embed-safe JSON string
	 */
	public static String jsonSanitizer(String jsonStringToSanitize) {
		return JsonSanitizer.sanitize(jsonStringToSanitize);
	}

	/**
	 * Normalize a file path: convert backslashes to forward slashes, collapse
	 * repeated slashes, and run NFKC + commons {@code FilenameUtils.normalize} to
	 * resolve {@code .} / {@code ..} segments. Throws if normalization resolves to
	 * {@code null} (e.g. path attempts to escape its root).
	 *
	 * @param stringToNormalize the path to normalize
	 * @return the normalized path, or {@code null} if input is null
	 * @throws IllegalArgumentException if the path normalizes to null
	 */
	public static String normalizePath(String stringToNormalize) {
		if (stringToNormalize == null) {
			return stringToNormalize;
		}
		// replacing \\ with /
		stringToNormalize = stringToNormalize.replace("\\", "/");
		// ensuring no double //
		while (stringToNormalize.contains("//")) {
			stringToNormalize = stringToNormalize.replace("//", "/");
		}

		String normalizedString = Normalizer.normalize(stringToNormalize, Form.NFKC);
		normalizedString = FilenameUtils.normalize(normalizedString);
		if (normalizedString == null) {
			classLogger.error("File path normalization returned null for input '{}'", stringToNormalize);
			throw new IllegalArgumentException("The filepath passed in is invalid");
		}
		normalizedString = normalizedString.replace("\\", "/");

		return normalizedString;
	}

	/**
	 * Append expired-clone cookies to {@code newCookies} for every cookie on the
	 * incoming request whose name looks like a session cookie (e.g. JSESSIONID).
	 * Used during logout / session invalidation so the client clears them.
	 *
	 * @param request    the incoming request whose cookies should be inspected
	 * @param newCookies the list to append the expiring cookie clones to
	 */
	public static void expireSessionCookies(HttpServletRequest request, List<NewCookie> newCookies) {
		if (request == null || newCookies == null) {
			return;
		}

		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return;
		}

		String sessionCookieName = DBLoader.getSessionIdKey();
		String contextPath = request.getContextPath();
		if (contextPath == null || contextPath.trim().isEmpty()) {
			contextPath = "/";
		}
		contextPath = cleanHttpResponse(contextPath);

		// Browsers block SameSite=None cookies unless Secure is also present.
		boolean secureRequiredForSameSite = "none".equalsIgnoreCase(Utility.getSameSiteCookieValue());
		boolean secureFlag = request.isSecure() || secureRequiredForSameSite;

		for (Cookie c : cookies) {
			if (sessionCookieName.equals(c.getName())) {
				String cookieName = cleanHttpResponse(c.getName());
				String cookieDomain = cleanHttpResponse(c.getDomain());

				// Expire cookie on the app context path.
				NewCookie expireAtContextPath = new NewCookie(cookieName, "", contextPath, cookieDomain,
						"Expire session cookie", 0, secureFlag);
				newCookies.add(expireAtContextPath);

				// Also expire at root path in case the session cookie was set there.
				if (!"/".equals(contextPath)) {
					NewCookie expireAtRootPath = new NewCookie(cookieName, "", "/", cookieDomain,
							"Expire session cookie", 0, secureFlag);
					newCookies.add(expireAtRootPath);
				}
			}
		}
	}

	/**
	 * Validate that {@code urlString} points to a top-private domain present in the
	 * configured whitelist ({@link Constants#WHITE_LIST_DOMAINS}). If the whitelist
	 * is empty/unset all URLs are accepted.
	 *
	 * @param urlString the URL to check
	 * @throws IllegalArgumentException if the URL is malformed or its domain is not
	 *                                  whitelisted
	 */
	public static void checkIfValidDomain(String urlString) {
		String whiteListDomains = Utility.getDIHelperProperty(Constants.WHITE_LIST_DOMAINS);
		if (whiteListDomains == null || (whiteListDomains = whiteListDomains.trim()).isEmpty()) {
			return;
		}

		List<String> domainList = Arrays.stream(whiteListDomains.split(",")).collect(Collectors.toList());
		try {
			URL url = URI.create(urlString).toURL();
			final String host = url.getHost();
			final InternetDomainName domainName = InternetDomainName.from(host).topPrivateDomain();
			if (!domainList.contains(domainName.toString())) {
				throw new IllegalArgumentException("You are not allowed to make requests to the URL: " + urlString);
			}
		} catch (MalformedURLException e) {
			classLogger.error("Invalid URL '{}' provided to domain whitelist check", urlString, e);
			throw new IllegalArgumentException("Invalid URL: " + urlString + ". Detailed message: " + e.getMessage());
		}
	}

	/**
	 * Pick the SPA fragment to redirect to for unauthenticated requests. Most
	 * requests get {@code #/login}, but requests coming from the public home page
	 * get a different fragment so the user lands back on the public site.
	 *
	 * @param request the incoming request (uses the {@code referer} header)
	 * @return the SPA fragment path to redirect to (e.g. {@code "#/login"})
	 */
	public static String determineLoginExtension(HttpServletRequest request) {
		String referer = request.getHeader("referer");
		String login = "#/login";
		if (referer != null && !referer.contains("/public_home/") && (referer.endsWith("SemossWeb")
				|| referer.endsWith("semoss-ui") || referer.endsWith("SemossWeb/") || referer.endsWith("semoss-ui/"))) {
			login = "#!/login";
		}
		return login;
	}

	/**
	 * Resolve the client IP for the request, preferring the X-FORWARDED-FOR header
	 * when present (for requests that came through a proxy / load balancer) and
	 * falling back to the direct remote address.
	 */
	public static String getClientIp(HttpServletRequest request) {
		String remoteAddr = "";
		if (request != null) {
			remoteAddr = inputSanitizer(request.getHeader("X-FORWARDED-FOR"));
			if (remoteAddr == null || "".equals(remoteAddr)) {
				remoteAddr = request.getRemoteAddr();
			}
		}

		return inputSanitizer(remoteAddr);
	}

	/**
	 * Populate the SLF4J/Log4j {@link ThreadContext} (MDC) with per-request fields
	 * used by the structured logger: a fresh request id, client ip,
	 * service/method/endpoint, host, and login event fields. Also stores local
	 * hostname/port/protocol on {@link ThreadStore}. Should be called at the start
	 * of every servlet/filter that wants request-scoped log fields.
	 *
	 * @param servletRequest the incoming request to derive context from
	 */
	public static void loggingContext(ServletRequest servletRequest) {
		ThreadContext.put(SemossLogUtils.REQUEST_ID, GUID.v7().toUUID().toString());

		HttpServletRequest request = (HttpServletRequest) servletRequest;
		ThreadContext.put(SemossLogUtils.CLIENT_IP, getClientIp(request));
		ThreadContext.put(SemossLogUtils.SERVICE_NAME, request.getContextPath());
		ThreadContext.put(SemossLogUtils.METHOD, request.getMethod());
		ThreadContext.put(SemossLogUtils.ENDPOINT, request.getRequestURI());
		ThreadContext.put(SemossLogUtils.HOST, request.getHeader("Host"));
		loggingContextLoginEvent(request.getSession(false));

		// also store local values
		ThreadStore.setLocalHostname(getLocalHostname(request));
		ThreadStore.setLocalPort(getLocalPort(request));
		ThreadStore.setLocalProtocol(getLocalProtocol(request));
	}

	/**
	 * Add login-event fields (user id, login type) to the logging
	 * {@link ThreadContext} based on the user attached to the given session. A
	 * no-op if the session is null or has no logged-in user.
	 *
	 * @param session the current HTTP session (may be null)
	 */
	public static void loggingContextLoginEvent(HttpSession session) {
		if (session != null) {
			ThreadContext.put(SemossLogUtils.SESSION_ID, session.getId());

			User user = (User) session.getAttribute(Constants.SESSION_USER);
			if (user != null) {
				Pair<String, String> login = User.getPrimaryUserIdAndTypePair(user);
				ThreadContext.put(SemossLogUtils.USER_ID, login.getValue0());
				ThreadContext.put(SemossLogUtils.USER_TYPE, login.getValue1());
			} else {
				ThreadContext.put(SemossLogUtils.USER_ID, "UNKNOWN");
			}
		} else {
			ThreadContext.put(SemossLogUtils.USER_ID, "UNKNOWN");
			ThreadContext.put(SemossLogUtils.SESSION_ID, "UNKNOWN");
		}
	}

	/**
	 * Get the url being made for the request excluding query params
	 * 
	 * @param request HttpServletRequest object for the request
	 * @return the string containing the request url
	 */
	public static String getCurrentCallbackUrl(HttpServletRequest request) {
		String defaultCallbackUrl = request.getRequestURL().toString();
		// applicationUrl should equal https://<dns.com>/optional_route/Monolith
		String applicationUrl = Utility.getApplicationUrl();
		if (applicationUrl == null || (applicationUrl = applicationUrl.trim()).isEmpty()) {
			return WebUtility.cleanHttpResponse(defaultCallbackUrl);
		}

		// This logic is to ensure we preserve the optional route (reverse-proxy
		// prefixes) which is present in applicationUrl.

		// requestUri should equal something like /Monolith/api/auth/login2/salesforce
		String requestUri = request.getRequestURI();
		// contextPath should equal /Monolith
		String contextPath = request.getContextPath();

		String pathToAppend = requestUri;
		// Remove training / from applicationUrl ... shouldn't be but just in case
		// normalizedApplicationUrl should be https://<dns.com>/optional_route/Monolith
		String normalizedApplicationUrl = applicationUrl.endsWith("/")
				? applicationUrl.substring(0, applicationUrl.length() - 1)
				: applicationUrl;
		// Remove /Monolith from the requestUri so we can append it to the appliationUrl
		// without having double /Monoltih
		if (requestUri != null && contextPath != null && !contextPath.isEmpty() && requestUri.startsWith(contextPath)
				&& normalizedApplicationUrl.endsWith(contextPath)) {
			pathToAppend = requestUri.substring(contextPath.length());
		}

		if (pathToAppend == null) {
			pathToAppend = "";
		}
		if (!pathToAppend.isEmpty() && !pathToAppend.startsWith("/")) {
			pathToAppend = "/" + pathToAppend;
		}

		return WebUtility.cleanHttpResponse(normalizedApplicationUrl + pathToAppend);
	}

	/**
	 * Get the protocol the client used to reach us ({@code http} or {@code https}).
	 * Honors {@code X-Forwarded-Proto} for requests behind a proxy / load balancer.
	 *
	 * @param request the incoming request
	 * @return the protocol string seen by the client
	 */
	public static String getProtocol(HttpServletRequest request) {
		// Check if behind a proxy/load balancer
		String forwardedProto = request.getHeader("X-Forwarded-Proto");
		if (forwardedProto != null && !forwardedProto.isEmpty()) {
			return forwardedProto.toLowerCase();
		}

		// Check if the request is secure
		if (request.isSecure()) {
			return "https";
		}

		// Fallback to the scheme from the request
		return request.getScheme();
	}

	/**
	 * Get the hostname the client used to reach us. Honors {@code X-Forwarded-Host}
	 * (and the {@code Host} header) so we report the external hostname rather than
	 * the internal container hostname.
	 *
	 * @param request the incoming request
	 * @return the hostname string seen by the client
	 */
	public static String getHostname(HttpServletRequest request) {
		// Check X-Forwarded-Host header (for proxied requests)
		String forwardedHost = request.getHeader("X-Forwarded-Host");
		if (forwardedHost != null && !forwardedHost.isEmpty()) {
			// X-Forwarded-Host may contain port, so strip it
			return forwardedHost.split(":")[0];
		}

		// Get from Host header
		String hostHeader = request.getHeader("Host");
		if (hostHeader != null && !hostHeader.isEmpty()) {
			// Host header may contain port, so strip it
			return hostHeader.split(":")[0];
		}

		// Fallback to server name
		return request.getServerName();
	}

	/**
	 * Get the port the client used to reach us. Honors {@code X-Forwarded-Port} for
	 * requests behind a proxy / load balancer, falling back to the request's server
	 * port.
	 *
	 * @param request the incoming request
	 * @return the port number seen by the client
	 */
	public static int getPort(HttpServletRequest request) {
		// Check X-Forwarded-Port header (for proxied requests)
		String forwardedPort = request.getHeader("X-Forwarded-Port");
		if (forwardedPort != null && !forwardedPort.isEmpty()) {
			try {
				return Integer.parseInt(forwardedPort);
			} catch (NumberFormatException e) {
				// Fall through to other methods
			}
		}

		// Check if port is in Host header
		String hostHeader = request.getHeader("Host");
		if (hostHeader != null && hostHeader.contains(":")) {
			try {
				String portStr = hostHeader.split(":")[1];
				return Integer.parseInt(portStr);
			} catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
				// Fall through to other methods
			}
		}

		// Get from server port
		int serverPort = request.getServerPort();
		if (serverPort > 0) {
			return serverPort;
		}

		// Default ports based on protocol
		return request.isSecure() ? 443 : 80;
	}

	/**
	 * Get the protocol the request actually arrived on at the container (ignoring
	 * any {@code X-Forwarded-Proto} header). Useful when we need to know how the
	 * request landed locally rather than what the client saw.
	 *
	 * @param request the incoming request
	 * @return the local protocol ({@code http} or {@code https})
	 */
	public static String getLocalProtocol(HttpServletRequest request) {
		// Use request.isSecure() which reflects the actual connection to this container
		if (request.isSecure()) {
			return "https";
		}

		// Return the actual scheme used to connect to this container
		return request.getScheme();
	}

	/**
	 * Get the hostname the request actually arrived on at the container (ignoring
	 * any {@code X-Forwarded-Host} header). Useful when we need the container-local
	 * hostname rather than the externally-visible one.
	 *
	 * @param request the incoming request
	 * @return the local hostname string
	 */
	public static String getLocalHostname(HttpServletRequest request) {
		// request.getServerName() returns the actual server name that received the
		// request
		String serverName = request.getServerName();
		if (serverName != null && !serverName.isEmpty()) {
			return serverName;
		}

		// Fallback to localhost if server name is not available
		return "localhost";
	}

	/**
	 * Get the port the request actually arrived on at the container (ignoring any
	 * {@code X-Forwarded-Port} header). Useful when we need the container-local
	 * port rather than the externally-visible one.
	 *
	 * @param request the incoming request
	 * @return the local port number
	 */
	public static int getLocalPort(HttpServletRequest request) {
		// request.getLocalPort() returns the actual port this container is listening
		// on
		int serverPort = request.getLocalPort();
		if (serverPort > 0) {
			return serverPort;
		}

		// Default ports based on protocol as fallback
		return request.isSecure() ? 443 : 80;
	}
}
