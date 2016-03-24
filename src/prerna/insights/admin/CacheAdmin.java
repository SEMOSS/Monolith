package prerna.insights.admin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.tika.io.IOUtils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import prerna.ds.TinkerFrame;
import prerna.ui.components.playsheets.datamakers.IDataMaker;
import prerna.util.Constants;
import prerna.util.DIHelper;


/**
 * Class used to create/delete/get cached datamakers and viz data for Insights
 */
//TODO: this file needs to be responsible for all caching and io related to caching, paths should not be built outside - Encapsulate
public class CacheAdmin {

	private static final String DM_EXTENSION = ".tg";
	private static final String JSON_EXTENSION = "_VizData.json";
	
	private static String basepath = DIHelper.getInstance().getProperty(Constants.INSIGHT_CACHE_DIR);
	private static String csvbasepath = DIHelper.getInstance().getProperty(Constants.CSV_INSIGHT_CACHE_FOLDER);
	/**
	 * 
	 * @param insight
	 * @param obj
	 */
	public static String createCache(IDataMaker dm, Map<String, Object> vizData, String basePath, List<String> folderStructure, String name, Map<String, List<Object>> paramHash) {		
		String baseFile = getBasePath(basePath, folderStructure, name, paramHash);
		if(dm instanceof TinkerFrame) {
			String dmFilePath = baseFile + DM_EXTENSION;
			if(!(new File(dmFilePath).exists())) {
				((TinkerFrame)dm).save(dmFilePath);
			}
		}
		String jsonFilePath = baseFile + JSON_EXTENSION;
		if(!(new File(jsonFilePath).exists())) {
			Map<String, Object> saveObj = new HashMap<>();
			saveObj.putAll(vizData);
			saveObj.put("insightID", null);
			CacheAdmin.writeToFile(jsonFilePath, saveObj);
		}
		return baseFile;
	}
	
	/**
	 * 
	 * @param insight
	 * 
	 * Deletes the cache associated with the insight
	 */
	public static void deleteCacheFolder(String basePath, List<String> folderStructure, String name) {
		//grab variables from insight that are used to create the file name
		String baseFolderPath = getBaseFolder(basePath, folderStructure);
		File basefolder = new File(baseFolderPath);
		if(basefolder.isDirectory()) {
			try {
				FileUtils.forceDelete(basefolder);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	public static void deleteCacheFiles(String basePath, List<String> folderStructure, String name) {
		String baseFolderPath = getBaseFolder(basePath, folderStructure);
		File basefolder = new File(baseFolderPath);
		if(basefolder.isDirectory()) {
			File[] files = basefolder.listFiles(new FilenameFilter(){
				@Override
				public boolean accept(File dir, String fileName) {
					return fileName.startsWith(name);
				}
			});
			for(File f : files) {
				try {
					FileUtils.forceDelete(f);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	/**
	 * 
	 * @return
	 */
	public static IDataMaker getCachedDataMaker(String basePath, List<String> folderStructure, String name, Map<String, List<Object>> paramHash) {
		TinkerFrame tf = null;
		String dmFilePath = getDMPath(basePath, folderStructure, name, paramHash);
		File f = new File(dmFilePath);
		// if that graph cache exists load it and sent to the FE
		if(f.exists() && !f.isDirectory()) {
			tf = TinkerFrame.open(dmFilePath);
		}
		
		return tf;
	}
	
	/**
	 * 
	 * @return
	 */
	public static String getVizData(String basePath, List<String> folderStructure, String name, Map<String, List<Object>> paramHash) {
		String vizData = null;
		String baseFile = getBasePath(basePath, folderStructure, name, paramHash);
		String jsonFilePath = baseFile + JSON_EXTENSION;
		File f = new File(jsonFilePath);
		// if that viz is serialized
		if(f.exists() && !f.isDirectory()) {
			vizData  = CacheAdmin.readFromFileString(jsonFilePath);
		}
		
		return vizData;
	}
	
	private static String getBaseFolder(String basePath, List<String> folderStructure) {
		String directory = basePath;
		for(String s : folderStructure) {
			directory += "/" + s.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
		}

		File basefolder = new File(directory);
		if(!basefolder.exists()) {
			try {
				FileUtils.forceMkdir(basefolder);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		return directory;
	}
	
	private static String getBasePath(String basePath, List<String> folderStructure, String name, Map<String, List<Object>> paramHash) {
		name = name.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
		String directory = getBaseFolder(basePath, folderStructure);
		
		String paramStr = getParamString(paramHash);
		if(!paramStr.isEmpty())
			name += "_" + getParamString(paramHash);
		
		String baseFile = directory + "/" + name;
		return baseFile;
	}
	
	
	/**
	 * 
	 * @param paramHash
	 * @return
	 * 
	 * Converts paramHash to a string that will be used for file naming
	 * The purpose of this is to guarantee paramHashes will always yield the same value and the file name does not become too long
	 * 
	 * Note: ~77,000 random strings, 50% chance two hashes will collide
	 * 		
	 */
	private static String getParamString(Map<String, List<Object>> paramHash) {
		
		if(paramHash == null || paramHash.isEmpty()) return "";
		
		List<String> keys = new ArrayList<String>(paramHash.keySet());
		Collections.sort(keys);
		
		StringBuilder paramString = new StringBuilder();
		
		for(String key : keys) {
			List<Object> params = paramHash.get(key);
			Collections.sort(params, new Comparator<Object>() {
				public int compare(Object o1, Object o2) {
					return o1.toString().toLowerCase().compareTo(o2.toString().toLowerCase());
				}
			});
			
			paramString.append(key+":::");
			for(Object param : params) {
				paramString.append(param);
			}
		}
		
		//use other types of hashing if this won't be sufficient
		return paramString.toString().hashCode()+"";
	}
	
	public static String getDMPath(String basePath, List<String> folderStructure, String name, Map<String, List<Object>> paramHash){
		String base = getBasePath(basePath, folderStructure, name, paramHash);
		String dmFilePath = base + DM_EXTENSION;
		return dmFilePath;
	}
	
	/**
	 * 
	 * @param fileName
	 * @param vec
	 */
	private static void writeToFile(String fileName, Object vec) {
		FileOutputStream os = null;
		try {
			Gson gson = new GsonBuilder().disableHtmlEscaping().serializeSpecialFloatingPointValues().setPrettyPrinting().create();
			String data = gson.toJson(vec);
			os = new FileOutputStream(new File(fileName));
			IOUtils.write(data, os);
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				if(os != null) {
					os.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	/**
	 * 
	 * @param fileName
	 * @return
	 */
	private static String readFromFileString(String fileName) {
    	Reader is = null;
    	FileReader fr = null;
        try {
        	fr = new FileReader(new File(fileName));
            is = new BufferedReader(fr);
            String retData = IOUtils.toString(is);//(is, "UTF8");
            return retData;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
        	try {
	        	if(fr != null) {
	        		fr.close();
	        	}
	        	if(is != null) {
					is.close();
	        	}
        	} catch (IOException e) {
				e.printStackTrace();
			}
        }

        return null;
	}
	
	public static void deleteCache(String dbName, String insightID) {
		String directory = basepath;
		dbName = dbName.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
		insightID = insightID.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
		directory += "/" + dbName + "/" + insightID;
		File basefolder = new File(directory);
		if(basefolder.isDirectory()) {
			try {
				FileUtils.forceDelete(basefolder);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}
