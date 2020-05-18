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
package prerna.semoss.web.services.remote;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.Hashtable;

import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.StreamingOutput;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import prerna.engine.api.IConstructWrapper;
import prerna.engine.api.IEngine;
import prerna.engine.api.IRemoteQueryable;
import prerna.engine.impl.rdf.SesameJenaSelectWrapper;
import prerna.rdf.engine.wrappers.AbstractWrapper;
import prerna.rdf.engine.wrappers.SesameConstructWrapper;
import prerna.rdf.engine.wrappers.SesameSelectCheater;
import prerna.rdf.engine.wrappers.SesameSelectWrapper;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;
import prerna.web.services.util.GraphStreamingOutput;
import prerna.web.services.util.QueryResultHash;
import prerna.web.services.util.TupleStreamingOutput;
import prerna.web.services.util.WebUtility;

// the primary class that will expose any engine through a remote interface

public class EngineRemoteResource {
	
	private static final Logger logger = LogManager.getLogger(EngineRemoteResource.class); 
	private static final String STACKTRACE = "StackTrace: ";

	public IEngine coreEngine = null;
	String output = null;
	String uriBase = null;
	
	public void setEngine(IEngine coreEngine)
	{
		this.coreEngine = coreEngine;
		if(uriBase == null && DIHelper.getInstance().getCoreProp().containsKey(Constants.URI_BASE))
			uriBase = (String)DIHelper.getInstance().getCoreProp().get(Constants.URI_BASE);
		else
			uriBase = uriBase;
		
	}

	@POST
	@Path("getFromNeighbors")
	@Produces("application/json")
	public StreamingOutput getFromNeighbors(@FormParam("nodeType") String nodeType, @FormParam("neighborHood") int neighborHood) {
		return WebUtility.getSO(coreEngine.getFromNeighbors(nodeType, neighborHood));
	}

	@POST
	@Path("getToNeighbors")
	@Produces("application/json")
	public StreamingOutput getToNeighbors(@FormParam("nodeType") String nodeType, @FormParam("neighborHood") int neighborHood) {
		return WebUtility.getSO(coreEngine.getToNeighbors(nodeType, neighborHood));
	}

	@POST
	@Path("getNeighbors")
	@Produces("application/json")
	public StreamingOutput getNeighbors(@FormParam("nodeType") String nodeType, @FormParam("neighborHood") int neighborHood) {
		// TODO Auto-generated method stub
		return WebUtility.getSO(coreEngine.getNeighbors(nodeType, neighborHood));
	}

	@POST
	@Path("execGraphQuery")
	@Produces("application/json")
	public Object execGraphQuery(@FormParam("query") String query) {
		// Steps I need to do
		// Create a wrapper object
		// The wrapper consists of a unique number, the actual output object
		// sets this wrapper in the memory
		logger.info("Executing GRAPH Query " + Utility.cleanLogString(query));
		
		IConstructWrapper sjw = null;
		try {
			sjw = WrapperManager.getInstance().getCWrapper(coreEngine, query);
			/*
			SesameJenaConstructWrapper sjw = new SesameJenaConstructWrapper();
			sjw.setQuery(query);
			sjw.setEngine(coreEngine);
			sjw.execute();
			*/
			// need someway to get an indirection for now hardcoded
			((IRemoteQueryable)sjw).setRemoteAPI(uriBase + coreEngine.getEngineId());
			QueryResultHash.getInstance().addObject((SesameConstructWrapper)sjw);		
		} catch (Exception e) {
			logger.error(STACKTRACE,e);
		}
		
		return WebUtility.getSO(sjw);
	}
	
	@POST
	@Path("execSelectQuery")
	@Produces("application/json")
	public Object execSelectQuery(@FormParam("query") String query) {
		logger.info("Executing Select Query  " + Utility.cleanLogString(query));
		AbstractWrapper sjsw = null;
		try {
			sjsw = (AbstractWrapper) WrapperManager.getInstance().getSWrapper(coreEngine, query);
			sjsw.setRemote(true);
			// need someway to get an indirection for now hardcoded
			((IRemoteQueryable)sjsw).setRemoteAPI(uriBase + coreEngine.getEngineId());
			QueryResultHash.getInstance().addObject(sjsw);		
		} catch (Exception e) {
			logger.error(STACKTRACE,e);
		}
		
		return WebUtility.getSO(sjsw);
	}


	@POST
	@Path("execCheaterQuery")
	@Produces("application/json")
	public Object execCheaterQuery(@FormParam("query") String query) {
		logger.info("Executing Select Query  " + Utility.cleanLogString(query));
		AbstractWrapper sjsw = null;
		try {
			sjsw = (AbstractWrapper) WrapperManager.getInstance().getChWrapper(coreEngine, query);
			sjsw.setRemote(true);
			// need someway to get an indirection for now hardcoded
			((IRemoteQueryable)sjsw).setRemoteAPI(uriBase + coreEngine.getEngineId());
			QueryResultHash.getInstance().addObject(sjsw);		
		} catch (Exception e) {
			logger.error(STACKTRACE,e);
		}
	
		return WebUtility.getSO(sjsw);
	}

	@POST
	@Path("getEntityOfType")
	@Produces("application/json")
	public StreamingOutput getEntityOfType(@FormParam("sparqlQuery") String sparqlQuery) {
		return WebUtility.getSO(coreEngine.getEntityOfType(sparqlQuery));
	}

	@POST
	@Path("execAskQuery")
	@Produces("application/json")
	public StreamingOutput execAskQuery(@FormParam("query") String query) {
		try {
			return WebUtility.getSO(coreEngine.execQuery(query));
		} catch (Exception e) {
			logger.error(STACKTRACE,e);
			Hashtable<String, Object> ret = new Hashtable<>();
			ret.put("errorMessage", e.getMessage());
			return WebUtility.getSO(ret);
		}
	}


//	@POST
//	@Path("getParamValues")
//	@Produces("application/json")
//	public StreamingOutput getParamValues(@FormParam("label") String label, @FormParam("type") String type,
//			@FormParam("insightId") String insightId, @FormParam("query") String query) {
//		// TODO Auto-generated method stub
//		return WebUtility.getSO(coreEngine.getParamValues(label, type, insightId, query));
//	}
	
	@POST
	@Path("getInsightDefinition")
	@Produces("application/text")
	public String getInsightDefinition() {
		logger.info("ENgine is " + coreEngine);
		return coreEngine.getInsightDefinition();
	}

	@POST
	@Path("getOWLDefinition")
	@Produces("application/xml")
	public String getOWLDefinition() {
		return coreEngine.getOWLDefinition();
	}
	
	// do the has Next
	@POST
	@Path("hasNext")
	@Produces("application/json")
	public StreamingOutput hasNext(@FormParam("id") String id)
	{
		boolean retValue = false;
		logger.info("Got the id " + id);
		if(id != null)
		{
			Object wrapper = QueryResultHash.getInstance().getObject(id);

			logger.info("Got the object as well" + wrapper);
//			if(wrapper instanceof SesameJenaConstructWrapper)
//				retValue = ((SesameJenaConstructWrapper)wrapper).hasNext();
//			else if(wrapper instanceof SesameJenaSelectWrapper)
//				retValue = ((SesameJenaSelectWrapper)wrapper).hasNext();
//			if(wrapper instanceof SesameJenaSelectCheater)
//				retValue = ((SesameJenaSelectCheater)wrapper).hasNext();

			//TODO: this shouldnt' be commented out!!!
			// retValue = ((IEngineWrapper) wrapper).hasNext();
			// if(!retValue) // cleanup
			QueryResultHash.getInstance().cleanObject(id);
		}
		
		return WebUtility.getSO(retValue);
	}

	@POST
	@Path("getDisplayVariables")
	@Produces("application/json")
	public StreamingOutput getDisplayVariables(@FormParam("id") String id)
	{
		String [] retValue = null;
		logger.info("Got the id " + id);
		if(id != null)
		{
			Object wrapper = QueryResultHash.getInstance().getObject(id);

			logger.info("Got the object as well" + wrapper);
//			if(wrapper instanceof SesameJenaConstructWrapper)
//				retValue = ((SesameJenaConstructWrapper)wrapper).hasNext();
//			else if(wrapper instanceof SesameJenaSelectWrapper)
//				retValue = ((SesameJenaSelectWrapper)wrapper).hasNext();
//			if(wrapper instanceof SesameJenaSelectCheater)
//				retValue = ((SesameJenaSelectCheater)wrapper).hasNext();

			if(wrapper instanceof SesameSelectWrapper)
			{
				logger.info(" Select.... ");
				retValue = ((SesameSelectWrapper)wrapper).getDisplayVariables();
				//return new TupleStreamingOutput(((SesameSelectWrapper)(wrapper)).tqr);
			}
		}		
		return WebUtility.getSO(retValue);
	}

	
	@POST
	@Path("next")
	@Produces("application/json")
	public StreamingOutput next(@FormParam("id") String id)
	{
		if(id != null)
		{
			// I can avoid the wrapper BS below by just putting through an interface
			// good things come to people who wait
			Object wrapper = QueryResultHash.getInstance().getObject(id);
			QueryResultHash.getInstance().cleanObject(id);
			if(wrapper instanceof SesameSelectCheater)
			{
				logger.info(" Cheater.... ");
				return new TupleStreamingOutput(((SesameSelectCheater)wrapper).tqr);
			}
			else if(wrapper instanceof SesameConstructWrapper)
			{
				logger.info(" Construct.... ");
				return new GraphStreamingOutput(((SesameConstructWrapper)(wrapper)).gqr);				
			}
			else if(wrapper instanceof SesameSelectWrapper)
			{
				logger.info(" Select.... ");
				return new TupleStreamingOutput(((SesameSelectWrapper)(wrapper)).tqr);
			}
		}
		// set the data into the statement
		
		
		// not sure if I should flesh the entire select query object or just the data hash yet
		return null;
	}

	@POST
	@Path("bvnext")
	@Produces("application/json")
	public StreamingOutput bvnext(@FormParam("id") String id)
	{
		// this is only applicable to select query
		Object retValue = null;
		if(id != null)
		{
			// I can avoid the wrapper BS below by just putting through an interface
			// good things come to people who wait
			Object wrapper = QueryResultHash.getInstance().getObject(id);
			if(wrapper instanceof SesameJenaSelectWrapper)
				retValue = ((SesameJenaSelectWrapper)wrapper).BVnext();
		}
		// not sure if I should flesh the entire select query object or just the data hash yet
		return WebUtility.getSO(retValue);
	}	
	
	@POST
	@Path("getProperty")
	@Produces("application/text")
	public String getProperty(@FormParam("key") String key)
	{
		return coreEngine.getProperty(key);
	}


	@POST
	@Path("streamTester")
	@Produces("application/text")
	public StreamingOutput getStreamTester()
	{
		   return new StreamingOutput() {
		         public void write(OutputStream outputStream) throws IOException, WebApplicationException {
		            ObjectOutputStream os = new ObjectOutputStream(outputStream);
		            Integer myInt = null;
		            for(int i = 0;i< 1000000;i++)
		            {
		            	myInt = new Integer(i);
			            //ps.println("Sending " + i);
		            	os.writeObject(myInt);
			            if (i %1000 == 0) {
							try {
								Thread.sleep(200);
							} catch (InterruptedException e) {
								Thread.currentThread().interrupt();
								logger.error(STACKTRACE,e);
							}
			            }
		            }
		         }};		
	}

}
