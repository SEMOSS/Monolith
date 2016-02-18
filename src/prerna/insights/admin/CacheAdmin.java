package prerna.insights.admin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.tika.io.IOUtils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import prerna.ds.TinkerFrame;
import prerna.om.Insight;
import prerna.ui.components.playsheets.datamakers.IDataMaker;
import prerna.util.Constants;
import prerna.util.DIHelper;

public class CacheAdmin {

	//TODO: Generate filenames based on hashing/hashcodes
	
	private static final String DIRECTORY = (String)DIHelper.getInstance().getProperty(Constants.BASE_FOLDER)+"/InsightCache";
	public enum FileType{VIZ_DATA, GRAPH_DATA, /*RECIPE_DATA, INSTANCE_DATA,*/};
	private static HashSet<String> supportedTypes;
	
	static {
		supportedTypes = new HashSet<>();
		supportedTypes.add("TinkerFrame");
	}
	
	/**
	 * 
	 * @param insight
	 * @param obj
	 */
	public static void createCache(Insight insight, Map<String, Object> vizData) {		
		if(supportedTypes.contains(insight.getDataMakerName())) {
			createDirectory(insight);
			createL2Cache(insight);
			String fileName = CacheAdmin.getFileName(insight.getEngineName(), insight.getDatabaseID(), insight.getRdbmsId(), insight.getParamHash(), FileType.VIZ_DATA);
			if(!(new File(fileName).exists())) {
				Map<String, Object> saveObj = new HashMap<>();
				saveObj.putAll(vizData);
				saveObj.put("insightID", null);
				CacheAdmin.writeToFile(fileName, saveObj);
			}
		}
	}
	
	/**
	 * 
	 * @param insight
	 */
	private static void createL2Cache(Insight insight) {
		IDataMaker dataTable = insight.getDataMaker();
		if(dataTable instanceof TinkerFrame) {
			String file = CacheAdmin.getFileName(insight.getEngineName(), insight.getDatabaseID(), insight.getRdbmsId(), insight.getParamHash(), FileType.GRAPH_DATA);
			if(!(new File(file).exists())) {
				((TinkerFrame)dataTable).save(file);
			}
		} else {
		}
	}
	
	/**
	 * 
	 * @param insight
	 * 
	 * Deletes the cache associated with the insight
	 */
	public static void deleteCache(Insight insight) {
		
		//grab variables from insight that are used to create the file name
		String engineName = insight.getEngineName();
		String insightRDBMSID = insight.getRdbmsId();
		File basefolder = new File(getDirectoryName(engineName, insightRDBMSID));
		if(!basefolder.exists()) {
			try {
				FileUtils.forceDelete(basefolder);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	/**
	 * 
	 * @param insight
	 * @return
	 */
	public static IDataMaker getCachedDataMaker(Insight insight) {
		TinkerFrame tf = null;
		String fileName = getFileName(insight.getEngineName(), insight.getDatabaseID(), insight.getRdbmsId(), insight.getParamHash(), FileType.GRAPH_DATA);			
		File f = new File(fileName);
		
		// if that graph cache exists load it and sent to the FE
		if(f.exists() && !f.isDirectory()) {
			tf = TinkerFrame.open(fileName);
		}
		
		return tf;
	}
	
	/**
	 * 
	 * @param insight
	 * @return
	 */
	public static String getVizData(Insight insight) {
		String vizData = null;
		
		String fileName = getFileName(insight.getEngineName(), insight.getDatabaseID(), insight.getRdbmsId(), insight.getParamHash(), FileType.VIZ_DATA);
		File f = new File(fileName);
		if(f.exists() && !f.isDirectory()) {
			vizData = CacheAdmin.readFromFileString(fileName);
		}
		
		return vizData;
	}
	
	/**
	 * 
	 * @param engineName
	 * @param insightID
	 * 
	 * create a unique directory for this insight ID and engine name if the directory doesn't already exist
	 */
	private static void createDirectory(Insight insight) {
		File basefolder = new File(getDirectoryName(insight.getEngineName(), insight.getRdbmsId()));
		if(!basefolder.exists()) {
			try {
				FileUtils.forceMkdir(basefolder);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	/**
	 * 
	 * @param engineName
	 * @param DatabaseID
	 * @param insightID
	 * @param params
	 * @param filetype
	 * @return
	 * 
	 * get the full directory and filename for the given paramters
	 */
	private static String getFileName(String engineName, String databaseID, String insightID, Map<String, List<Object>> params, FileType filetype) {
		if(engineName != null) engineName = engineName.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
		if(databaseID != null) databaseID = databaseID.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
		if(insightID != null) insightID = insightID.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
		String paramsStr = "";
		if(params != null) {
			paramsStr = params.toString().replaceAll("[^a-zA-Z0-9-_\\.]", "_");
		} 

		String fileNameBase = fileNameBase(engineName, databaseID, insightID, paramsStr);
		String fileNameExt = fileNameExtension(filetype);		
		String fileName = fileNameBase + fileNameExt;
		return fileName;
	}
	
	/**
	 * 
	 * @param engineName
	 * @param DatabaseID
	 * @param insightID
	 * @param params
	 * @return
	 */
	private static String fileNameBase(String engineName, String DatabaseID, String insightID, String params) {
		String fileName = getDirectoryName(engineName, insightID);
		
		if(params != null) {
			fileName += "/" + engineName + DatabaseID + insightID + params.toString();
		} else {
			fileName += "/" + engineName + DatabaseID + insightID;
		}
		
		return fileName;
	}
	
	/**
	 * 
	 * @param engineName
	 * @param insightID
	 * @return
	 * 
	 * return the name of the base folder in the directory for this engine name and insightID
	 */
	private static String getDirectoryName(String engineName, String insightID) {
		return DIRECTORY+"/"+engineName+insightID;
	}
	
	private static String fileNameExtension(FileType filetype) {
		
		String fileExt = "";
		
		switch(filetype) {
			case VIZ_DATA: fileExt = "_VizData.json"; break;
			case GRAPH_DATA: fileExt = ".tg"; break;
//			case INSTANCE_DATA: fileExt = "_filterModel.json"; break;
//			case RECIPE_DATA: fileExt = "_Recipe.dat"; break;
		}
	
		return fileExt;
	}
	
	/**
	 * 
	 * @param fileName
	 * @param vec
	 */
	private static void writeToFile(String fileName, Object vec) {
		try {
			Gson gson = new GsonBuilder().disableHtmlEscaping().serializeSpecialFloatingPointValues().setPrettyPrinting().create();
			String data = gson.toJson(vec);
			IOUtils.write(data, new FileOutputStream(new File(fileName)));
		} catch (UnsupportedEncodingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
//	private static byte[] readFromFile(String fileName) {
//      	
//    	Reader is;
//
//        try {
//            is = new BufferedReader(new FileReader(new File(fileName)));
//
//            byte[] retData = IOUtils.toByteArray(is, "UTF8");
//            is.close();
//            return retData;
//        } catch (FileNotFoundException e) {
//            // TODO Auto-generated catch block
//            e.printStackTrace();
//        } catch (IOException e) {
//            // TODO Auto-generated catch block
//            e.printStackTrace();
//        }
//
//        return null;
//            // Always close files.
//    }
	
	/**
	 * 
	 * @param fileName
	 * @return
	 */
	private static String readFromFileString(String fileName) {
    	Reader is;

        try {
            is = new BufferedReader(new FileReader(new File(fileName)));

            String retData = IOUtils.toString(is);//(is, "UTF8");
            is.close();
            return retData;
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return null;
	}
}
