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
import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.time.FastDateFormat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;

import com.google.gson.Gson;

import prerna.util.Constants;
import prerna.util.FstUtil;
import prerna.util.Utility;
import prerna.util.gson.GsonUtility;

/**
 * The Utility class contains a variety of miscellaneous functions implemented extensively throughout SEMOSS.
 * Some of these functionalities include getting concept names, printing messages, loading engines, and writing Excel workbooks.
 */
public class WebUtility {

	private static final Logger logger = LogManager.getLogger(WebUtility.class);

    private static final FastDateFormat expiresDateFormat= FastDateFormat.getInstance("EEE, dd MMM yyyy HH:mm:ss zzz", TimeZone.getTimeZone("GMT"));

	private static final List<String[]> noCacheHeaders = new Vector<String[]>();
	static {
		noCacheHeaders.add(new String[] {"Cache-Control", "private"});
//		noCacheHeaders.add(new String[] {"Cache-Control", "no-store, no-cache, must-revalidate, max-age=20, post-check=0, pre-check=0"});
//		noCacheHeaders.add(new String[] {"Pragma", "no-cache"});
	}

	private static Gson getDefaultGson() {
		return GsonUtility.getDefaultGson();
	}

	public static StreamingOutput getSO(Object vec)
	{
		if(vec != null)
		{
			Gson gson = getDefaultGson();
			try {
				final byte[] output = gson.toJson(vec).getBytes("UTF8");
				return new StreamingOutput() {
					public void write(OutputStream outputStream) throws IOException, WebApplicationException {
						try(
								PrintStream ps = new PrintStream(outputStream); //using try with resources to automatically close PrintStream object since it implements AutoCloseable
								){
							ps.write(output, 0 , output.length);
						}
					}};
			} catch (UnsupportedEncodingException e) {
				logger.error(Constants.STACKTRACE, e);
			}      
		}

		return null;
	}

	public static StreamingOutput getSOFile(String  fileLocation)
	{
		if(fileLocation != null)
		{
			try {
				File daFile = new File(WebUtility.normalizePath(fileLocation));
				FileReader fr = new FileReader(daFile);
				BufferedReader br = new BufferedReader(fr);
				return new StreamingOutput() {
					public void write(OutputStream outputStream) throws IOException, WebApplicationException {
						try(
								PrintWriter pw = new PrintWriter(outputStream); //using try with resources to automatically close PrintStream object since it implements AutoCloseable
								)
						{
							String data = null;
							while((data = br.readLine()) != null)
								pw.println(data);
							//ps.write(data, 0 , data.length);
							fr.close();
							br.close();
							daFile.delete();
						}
					}};
			} catch (Exception e) {
				logger.error(Constants.STACKTRACE, e);
			}      
		}

		return null;
	}

	
	public static Response getResponse(Object vec, int status) {
		return getResponse(vec, status, null);
	}
	
	public static Response getResponse(Object vec, int status, NewCookie[] cookies) {
		return getResponse(vec, status, null, cookies);
	}
	
	public static Response getResponseNoCache(Object vec, int status) {
		return getResponse(vec, status, noCacheHeaders);
	}
	
	public static Response getResponseNoCache(Object vec, int status, NewCookie[] cookies) {
		return getResponse(vec, status, noCacheHeaders, cookies);
	}

	public static Response getResponse(Object vec, int status, List<String[]> addHeaders, NewCookie... cookies) {
		if(vec != null) {
			Gson gson = getDefaultGson();
			try {
				final byte[] output = gson.toJson(vec).getBytes("UTF8");
				int length = output.length;
				ResponseBuilder builder = Response.status(status).entity(WebUtility.getSO(output)).header("Content-Length", length);
				if(addHeaders != null && !addHeaders.isEmpty()) {
					for(int i = 0; i < addHeaders.size(); i++) {
						String[] headerInfo = addHeaders.get(i);
						builder.header(headerInfo[0], headerInfo[1]);
					}
				}
				if(cookies != null && cookies.length > 0) {
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
				logger.error(Constants.STACKTRACE, e);
			}
			return Response.status(200).entity(WebUtility.getSO(vec)).build();
		}

		return null;
	}
	
	public static String convertCookieToHeader(NewCookie cookie) {
		StringBuilder c = new StringBuilder(64+cookie.getValue().length());
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
		if(cookie.getDomain() != null) {
			c.append("; ");
			c.append("domain");
			c.append('=');
			c.append(cookie.getDomain());
		}
		// the path
		if(cookie.getPath() != null) {
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
		if (cookie.getMaxAge()>=0) {
			c.append("; ");
			c.append("Expires");
			c.append('=');
			c.append(getExpires(cookie.getMaxAge()));
		}
		
		return c.toString();
	}

	private static String getExpires(int maxAge) {
		if (maxAge < 0) {
			return "";
		}
		Calendar expireDate = Calendar.getInstance();
		expireDate.setTime(new Date());
		expireDate.add(Calendar.SECOND,maxAge);
		return expiresDateFormat.format(expireDate);
	}

	public static StreamingOutput getSO(byte[] output) {
		try {
			return new StreamingOutput() {
				public void write(OutputStream outputStream) throws IOException, WebApplicationException {
					try(
							PrintStream ps = new PrintStream(outputStream); //using try with resources to automatically close PrintStream object since it implements AutoCloseable
							){
						ps.write(output, 0 , output.length);
					}
				}};
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
		}      

		return null;
	}
	
	public static StreamingOutput getBinarySO(Object obj)
	{
		if(obj != null)
		{
			try {
				final byte[] output = FstUtil.serialize(obj);
				return new StreamingOutput() 
				{
					public void write(OutputStream outputStream) throws IOException, WebApplicationException 
					{
						try
						{
								outputStream.write(output);
								outputStream.flush();
						}catch(Exception ex)
						{
							logger.error(Constants.STACKTRACE, ex);							
					}
				}
				};
			}catch(Exception ex)
			{
				logger.error(Constants.STACKTRACE, ex);											
			}
		}
		return null;
	}
	
	// this is to remove scripts from being passed
	// ex. <script>alert('XSS');</script> is blocked
	public static String inputSanitizer(String stringToNormalize) {
		if (stringToNormalize == null) {
			logger.info("input to sanitzer is null, returning null");
			return stringToNormalize;
		}
		
		PolicyFactory policy = Sanitizers.FORMATTING.and(Sanitizers.LINKS);
	    return policy.sanitize(stringToNormalize );
	}
	
	public static String normalizePath(String stringToNormalize) {
		if(stringToNormalize == null ) {
			return stringToNormalize;
		}
		//replacing \\ with /
		stringToNormalize=stringToNormalize.replace("\\", "/");
		//ensuring no double //
		while(stringToNormalize.contains("//")){
			stringToNormalize=stringToNormalize.replace("//", "/");
		}
		
		String normalizedString = Normalizer.normalize(stringToNormalize,Form.NFKC);

		 normalizedString = FilenameUtils.normalize(normalizedString);
		if (normalizedString == null) {
			logger.error("File path is null");
			throw new IllegalArgumentException("The filepath passed in is invalid");
		}
		normalizedString = normalizedString.replace("\\", "/");

		return normalizedString;
	}

}
