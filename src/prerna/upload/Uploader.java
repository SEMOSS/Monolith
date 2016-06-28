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
package prerna.upload;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;

/**
 * Servlet implementation class Uploader
 */
@SuppressWarnings("serial")
public abstract class Uploader extends HttpServlet {

	protected int maxFileSize = 1000000 * 1024;
	protected int maxMemSize = 4 * 1024;
	protected String filePath;
	protected String tempFilePath = "";
	protected boolean securityEnabled;
	
	// we will control the adding of the engine into local master and solr
	// such that we dont send a success before those processes are complete
	boolean autoLoad = false;
	
	public void setFilePath(String filePath){
		this.filePath = filePath;
	}

	public void setTempFilePath(String tempFilePath){
		this.tempFilePath = tempFilePath;
	}
	
	public void setSecurityEnabled(boolean securityEnabled) {
		this.securityEnabled = securityEnabled;
	}

	public void writeFile(FileItem fi, File file){
		try {
			fi.write(file);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	protected void deleteFilesFromServer(String[] files) {
		for(String file : files) {
			java.nio.file.Path deleteFilePath = Paths.get(file);
			try {
			    Files.delete(deleteFilePath);
			} catch (NoSuchFileException x) {
			    System.err.format("%s: no such" + " file or directory%n", filePath);
			} catch (DirectoryNotEmptyException x) {
			    System.err.format("%s not empty%n", filePath);
			} catch (IOException x) {
			    // File permission problems are caught here.
			    System.err.println(x);
			}
		}
	}

	protected List<FileItem> processRequest(@Context HttpServletRequest request)
	{
		List<FileItem> fileItems = null;
		try {
			DiskFileItemFactory factory = new DiskFileItemFactory();
			// maximum size that will be stored in memory
			factory.setSizeThreshold(maxMemSize);
			// Location to save data that is larger than maxMemSize.
			factory.setRepository(new File(tempFilePath));
			// Create a new file upload handler
			ServletFileUpload upload = new ServletFileUpload(factory);
			// maximum file size to be uploaded.
			upload.setSizeMax(maxFileSize);
			
			// Parse the request to get file items
			fileItems = upload.parseRequest(request);
		} catch (FileUploadException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return fileItems;
	}
	
	protected Hashtable<String, String> getInputData(List<FileItem> fileItems) 
	{
		// Process the uploaded file items
		Iterator<FileItem> iteratorFileItems = fileItems.iterator();

		// collect all of the data input on the form
		Hashtable<String, String> inputData = new Hashtable<String, String>();
		File file;

		while(iteratorFileItems.hasNext()) 
		{
			FileItem fi = (FileItem) iteratorFileItems.next();
			// Get the uploaded file parameters
			String fieldName = fi.getFieldName();
			String fileName = fi.getName();
			String value = fi.getString();
			if (!fi.isFormField()) {
				if(fileName.equals("")) {
					continue;
				}
				else {
					if(fieldName.equals("file") || fieldName.equals("mapFile") || fieldName.equals("questionFile")) {
						value = filePath + "\\" + fileName.substring(fileName.lastIndexOf("\\") + 1);
						file = new File(value);
						writeFile(fi, file);
						System.out.println( "Saved Filename: " + fileName + "  to "+ file);
					}
				}
			} else {
				System.err.println("Type is " + fi.getFieldName() + fi.getString());
			}
			//need to handle multiple files getting selected for upload
			if(inputData.get(fieldName) != null)
			{
				value = inputData.get(fieldName) + ";" + value;
			}
			inputData.put(fieldName, value);
			fi.delete();
		}

		return inputData;
	}

}
