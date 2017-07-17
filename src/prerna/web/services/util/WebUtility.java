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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import prerna.ds.export.gexf.IGexfIterator;
import prerna.ds.export.graph.IGraphExporter;

/**
 * The Utility class contains a variety of miscellaneous functions implemented extensively throughout SEMOSS.
 * Some of these functionalities include getting concept names, printing messages, loading engines, and writing Excel workbooks.
 */
public class WebUtility {

	public static int id = 0;
	static Logger logger = Logger.getLogger(prerna.web.services.util.WebUtility.class);

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
				logger.error("Failed to write object to stream");
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
	
	public static StreamingOutput getSO(byte[] output2)
	{
		try {
			return new StreamingOutput() {
				public void write(OutputStream outputStream) throws IOException, WebApplicationException {
					try(
							PrintStream ps = new PrintStream(outputStream); //using try with resources to automatically close PrintStream object since it implements AutoCloseable
							){
						ps.write(output2, 0 , output2.length);
					}
				}};
		} catch (Exception e) {
			e.printStackTrace();
			logger.error("Failed to write object to stream");
		}      
		
		return null;
	}

	public static StreamingOutput getSO(String insightId, String [] headers, Iterator iterator)
	{
		if(iterator != null)
		{
			Gson gson = getDefaultGson();
			try {
				return new StreamingOutput() {
					public void write(OutputStream outputStream) throws IOException, WebApplicationException {
						try(
								PrintStream ps = new PrintStream(outputStream); //using try with resources to automatically close PrintStream object since it implements AutoCloseable
								)
						{
							String headerStr ="{\"headers\": "; 
							ps.print(headerStr);
							String headerOutput = gson.toJson(headers);
							ps.print(headerOutput);

							ps.flush();

							ps.print(", \"data\": [");
							ps.flush();
							while(iterator.hasNext())
							{
								Object obj = iterator.next();
								ps.print(gson.toJson(obj));
								if(iterator.hasNext())
									ps.print(", ");                            		 
								ps.flush();
							}
							byte[] insight = new String("] , \"insightID\": \"" + insightId + "\" }").getBytes("UTF8");
							ps.write(insight, 0 , insight.length);
							ps.flush();
						}
					}};
			} catch (Exception e) {
				logger.error("Failed to write object to stream");
			}      
		}
		return null;
	}
	
	public static Response getResponse(String insightId, IGraphExporter iterator) {
		if(iterator != null) {
			StringBuilder builder = new StringBuilder();
			builder.append("{").append("\"insightID\":\"").append(insightId).append("\",");
			Gson gson = getDefaultGson();
			try {
				List<Map<String, Object>> edges = new ArrayList<Map<String, Object>>();
				while(iterator.hasNextEdge()) {
					edges.add(iterator.getNextEdge());
				}
				builder.append("\"edges\":").append(gson.toJson(edges)).append(",");
				
				List<Map<String, Object>> vertices = new ArrayList<Map<String, Object>>();
				while(iterator.hasNextVert()) {
					vertices.add(iterator.getNextVert());
				}
				builder.append("\"nodes\":").append(gson.toJson(vertices)).append(",");
				
				// add the meta data pieces
				builder.append("\"graphMeta\":").append(gson.toJson(iterator.getVertCounts()));
				builder.append("}");
				final byte[] output = builder.toString().getBytes("UTF8");
				int length = output.length;
				return Response.status(200).entity(WebUtility.getSO(output)).header("Content-Length", length).build();
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			}
			return Response.status(200).entity(WebUtility.getSO(insightId)).build();
		}
		
		return null;
	}

	public static StreamingOutput getSO(String insightId, IGexfIterator gexf) {
		if(gexf != null) {
			try {
				Gson gson = getDefaultGson();
				return new StreamingOutput() {
					public void write(OutputStream outputStream) throws IOException, WebApplicationException {
						try(
								PrintStream ps = new PrintStream(outputStream); //using try with resources to automatically close PrintStream object since it implements AutoCloseable
								)
						{
							// note: to be able to stream this
							// we have to substring the outputs after being gson'ed
							// so that the gexf points to a single string
							
							// get start of gexf
							ps.print("{\"gexf\": \"");
							ps.flush();
							String out = gson.toJson( gexf.getStartString() );
							ps.print( out.substring(1, out.length()-1) );
							ps.flush();

							// print the nodes
							out = gson.toJson( gexf.getNodeStart());
							ps.print( out.substring(1, out.length()-1) );
							ps.flush();
							while(gexf.hasNextNode()) {
								out = gson.toJson( gexf.getNextNodeString());
								ps.print( out.substring(1, out.length()-1) );
								ps.flush();
							}
							out = gson.toJson( gexf.getNodeEnd());
							ps.print( out.substring(1, out.length()-1) );
							ps.flush();

							// print the edges
							out = gson.toJson( gexf.getEdgeStart());
							ps.print( out.substring(1, out.length()-1) );
							ps.flush();
							while(gexf.hasNextEdge()) {
								out = gson.toJson( gexf.getNextEdgeString());
								ps.print( out.substring(1, out.length()-1) );
								ps.flush();
							}
							out = gson.toJson( gexf.getEdgeEnd());
							ps.print( out.substring(1, out.length()-1) );
							ps.flush();

							// end the gexf
							out = gson.toJson( gexf.getEndString());
							ps.print( out.substring(1, out.length()-1) );
							ps.flush();

							byte[] insight = new String("\" , \"insightID\": \"" + insightId + "\" }").getBytes("UTF8");
							ps.write(insight, 0 , insight.length);
							ps.flush();
							ps.close();
						}
					}};
			} catch (Exception e) {
				logger.error("Failed to write object to stream");
			}      
		}
		return null;
	}

	private static Gson getDefaultGson() {
		Gson gson = new GsonBuilder().disableHtmlEscaping().excludeFieldsWithModifiers(Modifier.TRANSIENT).registerTypeAdapter(Double.class, new NumberAdaptor()).create();
		return gson;
	}
}

/**
 * Generation of new NumberAdaptor to not send NaN/Infinity to the FE
 * since they are invalid JSON values
 */
class NumberAdaptor extends TypeAdapter<Double>{

	@Override 
	public Double read(JsonReader in) throws IOException {
		if (in.peek() == JsonToken.NULL) {
			in.nextNull();
			return null;
		}
		return in.nextDouble();
	}

	@Override 
	public void write(JsonWriter out, Double value) throws IOException {
		if (value == null) {
			out.nullValue();
			return;
		}
		double doubleValue = value.doubleValue();
		if(Double.isNaN(doubleValue) || Double.isInfinite(doubleValue)) {
			out.nullValue();
		} else {
			out.value(value);
		}
	}
}
