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

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Vector;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.StreamingOutput;

import org.apache.log4j.Logger;

import com.google.gson.Gson;

import prerna.util.gson.GsonUtility;

/**
 * The Utility class contains a variety of miscellaneous functions implemented extensively throughout SEMOSS.
 * Some of these functionalities include getting concept names, printing messages, loading engines, and writing Excel workbooks.
 */
public class WebUtility {

	private static final String CLASS_NAME = WebUtility.class.getName();
	private static final Logger LOGGER = Logger.getLogger(CLASS_NAME);

	private static final List<String[]> noCacheHeaders = new Vector<String[]>();
	static {
		noCacheHeaders.add(new String[] {"Cache-Control", "no-store, no-cache, must-revalidate, max-age=0, post-check=0, pre-check=0"});
		noCacheHeaders.add(new String[] {"Pragma", "no-cache"});
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
				LOGGER.error("Failed to write object to stream. Stacktrace: ", e);
			}      
		}

		return null;
	}

	public static Response getResponse(Object vec, int status, NewCookie... cookies) {
		return getResponse(vec, status, null, cookies);
	}
	
	public static Response getResponseNoCache(Object vec, int status, NewCookie... cookies) {
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
					builder.cookie(cookies);
				}
				return builder.build();
			} catch (UnsupportedEncodingException e) {
				LOGGER.error("Stacktrace: ", e);
			}
			return Response.status(200).entity(WebUtility.getSO(vec)).build();
		}

		return null;
	}

	public static StreamingOutput getSO(byte[] output)
	{
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
			LOGGER.error("Failed to write object to stream. Stacktrace: ",e);
		}      

		return null;
	}

}
