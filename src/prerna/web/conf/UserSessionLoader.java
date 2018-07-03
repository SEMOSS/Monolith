package prerna.web.conf;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.annotation.WebListener;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import prerna.algorithm.api.ITableDataFrame;
import prerna.ds.h2.H2Frame;
import prerna.ds.r.RDataTable;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.VarStore;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.sablecc2.om.task.TaskStore;
import prerna.sablecc2.reactor.imports.FileMeta;

@WebListener
public class UserSessionLoader implements HttpSessionListener {
	
	protected static final Logger LOGGER = LogManager.getLogger(UserSessionLoader.class.getName());

	public void sessionCreated(HttpSessionEvent sessionEvent) {

	}
	
	public void sessionDestroyed(HttpSessionEvent sessionEvent) {
		String sessionId = sessionEvent.getSession().getId();
		
		// clear up insight store
		InsightStore inStore = InsightStore.getInstance();
		Set<String> insightIDs = inStore.getInsightIDsForSession(sessionId);
		if(insightIDs != null) {
			Set<String> copy = new HashSet<String>(insightIDs);
			for(String insightId : copy) {
				Insight insight = InsightStore.getInstance().get(insightId);
				if(insight == null) {
					continue;
				}
				LOGGER.info("Trying to drop insight " + insightId);
				
				InsightStore.getInstance().remove(insightId);
				LOGGER.info("Successfully removed insight from insight store");

				// drop all the tasks that are currently running
				TaskStore taskStore = insight.getTaskStore();
				taskStore.clearAllTasks();
				LOGGER.info("Successfully cleared all stored Tasks for the insight");
				
				// drop all the frame connections
				VarStore varStore = insight.getVarStore();
				Set<String> keys = varStore.getKeys();
				// find all the vars which are frames
				// and drop them
				for(String key : keys) {
					NounMetadata noun = varStore.get(key);
					PixelDataType nType = noun.getNounType();
					if(nType == PixelDataType.FRAME) {
						ITableDataFrame dm = (ITableDataFrame) noun.getValue();
						dm.setLogger(LOGGER);
						//TODO: expose a delete on the frame to hide this crap
						// drop the existing tables/connections if present
						if(dm instanceof H2Frame) {
							H2Frame frame = (H2Frame)dm;
							frame.dropTable();
							if(!frame.isInMem()) {
								frame.dropOnDiskTemporalSchema();
							}
						} else if(dm instanceof RDataTable) {
							RDataTable frame = (RDataTable)dm;
							frame.closeConnection();
						}
					}
				}
				LOGGER.info("Successfully removed all frames from insight");

				insight.getVarStore().clear();
				LOGGER.info("Successfully removed all variables from varstore");
				
				// also delete any files that were used
				List<FileMeta> fileData = insight.getFilesUsedInInsight();
				if (fileData != null && !fileData.isEmpty()) {
					for (int fileIdx = 0; fileIdx < fileData.size(); fileIdx++) {
						FileMeta file = fileData.get(fileIdx);
						File f = new File(file.getFileLoc());
						f.delete();
						LOGGER.info("Successfully deleted File used in insight " + f.getName());
					}
				}
				
				Map<String, String> fileExports = insight.getExportFiles();
				if (fileExports != null && !fileExports.isEmpty()) {
					for (String fileKey : fileExports.keySet()){
						File f = new File(fileExports.get(fileKey));
						f.delete();
						LOGGER.info("Successfully deleted File used in insight " + f.getName());
					}
				}
				
				LOGGER.info("Successfully removed insight");
			}
			System.out.println("successfully removed insight information from session");
			
			// clear the current session store
			insightIDs.removeAll(copy);
		}
	}
	
}