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

import java.text.DecimalFormat;

import org.apache.commons.fileupload.ProgressListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.sablecc2.comm.InMemoryConsole;

public class FileUploadProgressListener implements ProgressListener {

	private static final String CLASS_NAME = FileUploadProgressListener.class.getName();
	
	private Logger logger = null;
	private long megaBytes = -1;
	private int currentItem = -1;
	private DecimalFormat formatter = new DecimalFormat("0.00%");
		
	public FileUploadProgressListener(String jobId) {
		if(jobId != null && !jobId.isEmpty()) {
			this.logger = new InMemoryConsole(jobId, CLASS_NAME);
		} else {
			this.logger = LogManager.getLogger(CLASS_NAME);
		}
		this.logger.info("Starting to upload files");
	}
	
	@Override
	public void update(long pBytesRead, long pContentLength, int pItems) {
		// for some reason, this is always called at 0
		// and after 1 log
		// it skips to the actual file
		// must be some initial processing that is happening
		if(pItems == 0) {
			return;
		}
		long mBytes = pBytesRead / 1000000;
		if (megaBytes == mBytes) {
			return;
		}
		megaBytes = mBytes;
		if (pContentLength != -1) {
			if(currentItem != pItems) {
				currentItem = pItems;
				logger.info("Currently reading item " + currentItem);
			}
			double percentComplete = (double) pBytesRead / pContentLength;
			logger.info(formatter.format(percentComplete) + " complete with transfer");
		}
	}

}
