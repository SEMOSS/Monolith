package prerna.upload;

import java.text.DecimalFormat;

import org.apache.commons.fileupload.ProgressListener;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import prerna.sablecc2.comm.InMemoryConsole;

public class FileUploadProgressListener implements ProgressListener {

	private static final String CLASS_NAME = FileUploadProgressListener.class.getName();
	private static DecimalFormat formatter = new DecimalFormat("0.00%");
	
	private Logger logger = null;
	private long megaBytes = -1;
	private int currentItem = -1;
	
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
