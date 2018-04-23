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
import java.lang.reflect.Modifier;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import prerna.date.SemossDate;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.PixelStreamUtility;
import prerna.util.gson.NumberAdapter;
import prerna.util.gson.SemossDateAdapter;

/**
 * The Utility class contains a variety of miscellaneous functions implemented extensively throughout SEMOSS.
 * Some of these functionalities include getting concept names, printing messages, loading engines, and writing Excel workbooks.
 */
public class WebUtility {

	private static final String CLASS_NAME = WebUtility.class.getName();
	private static final Logger LOGGER = Logger.getLogger(CLASS_NAME);

	private static Gson getDefaultGson() {
		Gson gson = new GsonBuilder()
				.disableHtmlEscaping()
				.excludeFieldsWithModifiers(Modifier.STATIC, Modifier.TRANSIENT)
				.registerTypeAdapter(Double.class, new NumberAdapter())
				.registerTypeAdapter(SemossDate.class, new SemossDateAdapter())
				.create();
		return gson;
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
				e.printStackTrace();
				LOGGER.error("Failed to write object to stream");
			}      
		}

		return null;
	}

	public static Response getResponse(Object vec, int status) {
		if(vec != null) {
			Gson gson = getDefaultGson();
			try {
				final byte[] output = gson.toJson(vec).getBytes("UTF8");
				int length = output.length;
				return Response.status(status).entity(WebUtility.getSO(output)).header("Content-Length", length).build();
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
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
			e.printStackTrace();
			LOGGER.error("Failed to write object to stream");
		}      

		return null;
	}

	/**
	 * Collect pixel data from the runner
	 * @param runner
	 * @return
	 */
	public static StreamingOutput collectPixelData(PixelRunner runner) {
		// get the default gson object
		Gson gson = getDefaultGson();

		// now process everything
		try {
			return new StreamingOutput() {
				public void write(OutputStream outputStream) throws IOException, WebApplicationException {
					try(PrintStream ps = new PrintStream(outputStream)) {
						// we want to ignore the first index since it will be a job
						PixelStreamUtility.processPixelRunner(ps, gson, runner, true);
					}
				}};
		} catch (Exception e) {
			LOGGER.error("Failed to write object to stream");
		}
		return null;
	}

}
