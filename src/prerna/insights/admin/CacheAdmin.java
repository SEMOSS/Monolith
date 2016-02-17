package prerna.insights.admin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.tika.io.IOUtils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import prerna.om.Insight;
import prerna.util.Constants;
import prerna.util.DIHelper;

public class CacheAdmin {

	private static final String DIRECTORY = (String)DIHelper.getInstance().getProperty(Constants.BASE_FOLDER)+"/InsightCache";
	public enum FileType{VIZ_DATA, INSTANCE_DATA, GRAPH_DATA, RECIPE_DATA};
	
	public static void createCache() {
		
	}
	
	public static void createL1Cache(String engineName, String insightID) {
		createDirectory(engineName, insightID);
	}
	
	public static void createL1Cache(Insight insight) {
		String engineName = insight.getEngineName();
		String insightRDBMSID = insight.getRdbmsId();
		createDirectory(engineName, insightRDBMSID);
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
	 * @param engineName
	 * @param DatabaseID
	 * @param insightID
	 * @param params
	 * @param filetype
	 * @return
	 * 
	 * get the full directory and filename for the given paramters
	 */
	public static String getFileName(String engineName, String databaseID, String insightID, Map<String, List<Object>> params, FileType filetype) {
		engineName = engineName.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
		databaseID = databaseID.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
		insightID = insightID.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
		String paramsStr = params.toString().replaceAll("[^a-zA-Z0-9-_\\.]", "_");

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
	
	/**
	 * 
	 * @param engineName
	 * @param insightID
	 * 
	 * create a unique directory for this insight ID and engine name if the directory doesn't already exist
	 */
	public static void createDirectory(String engineName, String insightID) {
		engineName = engineName.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
		insightID = insightID.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
		
		File basefolder = new File(getDirectoryName(engineName, insightID));
		if(!basefolder.exists()) {
			try {
				FileUtils.forceMkdir(basefolder);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	private static String fileNameExtension(FileType filetype) {
		
		String fileExt = "";
		
		switch(filetype) {
		case VIZ_DATA: fileExt = "_VizData.json"; break;
		case INSTANCE_DATA: fileExt = "_filterModel.json"; break;
		case GRAPH_DATA: fileExt = ".tg"; break;
		case RECIPE_DATA: fileExt = "_Recipe.dat"; break;
		}
	
		return fileExt;
	}
	
	public static void writeToFile(String fileName, Object vec) {
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
	
	public static byte[] readFromFile(String fileName) {
      	
    	Reader is;

        try {
            is = new BufferedReader(new FileReader(new File(fileName)));

            byte[] retData = IOUtils.toByteArray(is, "UTF8");
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
            // Always close files.
    }
	
	public static String readFromFileString(String fileName) {
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
