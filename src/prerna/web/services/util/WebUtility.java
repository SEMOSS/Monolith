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
import java.util.Iterator;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.StreamingOutput;

import org.apache.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

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
               Gson gson = new GsonBuilder().disableHtmlEscaping().serializeSpecialFloatingPointValues().setPrettyPrinting().create();
               try {
                     final byte[] output2 = gson.toJson(vec).getBytes("UTF8");///Need to encode for special characters//
                     return new StreamingOutput() {
                         public void write(OutputStream outputStream) throws IOException, WebApplicationException {
                             try(
                          		   PrintStream ps = new PrintStream(outputStream); //using try with resources to automatically close PrintStream object since it implements AutoCloseable
                                ){
                          	   ps.write(output2, 0 , output2.length);
                             }
                          }};
               } catch (UnsupportedEncodingException e) {
                     logger.error("Failed to write object to stream");
               }      
        }
        return null;
		
	}
	
	public static StreamingOutput getSO(String insightId, String [] headers, Iterator iterator)
	{
        if(iterator != null)
        {
               Gson gson = new GsonBuilder().disableHtmlEscaping().serializeSpecialFloatingPointValues().setPrettyPrinting().create();
               try {
                     return new StreamingOutput() {
                         public void write(OutputStream outputStream) throws IOException, WebApplicationException {
                        	 boolean firstTime = true;
                        		 
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

}
