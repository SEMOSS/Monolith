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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.tika.io.IOUtils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import prerna.ds.TinkerFrame;
import prerna.ui.components.playsheets.datamakers.IDataMaker;

public class CacheAdmin {

	private static final String DM_EXTENSION = ".tg";
	private static final String JSON_EXTENSION = "_VizData.json";
	
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
	public static void deleteCacheFolder(String basePath, List<String> folderStructure, String name, Map<String, List<Object>> paramHash) {
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
	
	public static void deleteCacheFiles(String basePath, List<String> folderStructure, String name, Map<String, List<Object>> paramHash) {
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
		String baseFile = getBasePath(basePath, folderStructure, name, paramHash);
		String dmFilePath = baseFile + DM_EXTENSION;
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
		
		if(paramHash != null) {
			name += paramHash.toString().replaceAll("[^a-zA-Z0-9-_\\.]", "_");
		}
		String baseFile = directory + "/" + name;
		return baseFile;
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
}
