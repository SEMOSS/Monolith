package prerna.upload;

import java.io.File;
import java.io.FileFilter;
import java.util.List;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.io.filefilter.WildcardFileFilter;

public class ImageUploader extends Uploader {
	
	@POST
	@Path("/appImage")
	@Produces("application/json")
	public void uploadAppImage(@Context HttpServletRequest request) {
		List<FileItem> fileItems = processRequest(request);
		// collect all of the data input on the form
		FileItem imageFile = null;
		String appName = null;
		
		for(FileItem fi : fileItems) {
			String fieldName = fi.getFieldName();
			String value = fi.getString();
			if(fieldName.equals("file")) {
				imageFile = fi;
			}
			if(fieldName.equals("app")) {
				appName = value;
			}
		}

		// now that we have the app name
		// and the image file
		// we want to write it into the app location
		String imageDir = filePath + DIR_SEPARATOR + appName + DIR_SEPARATOR + "version";
		File f = new File(imageDir);
		if(!f.exists()) {
			f.mkdirs();
		}
		String imageLoc = imageDir + DIR_SEPARATOR + "image." + imageFile.getContentType().split("/")[1];
		f = new File(imageLoc);
		// find all the existing image files
		// and delete them
		File[] oldImages = findImageFile(f.getParentFile());
		// delete if any exist
		if(oldImages != null) {
			for(File oldI : oldImages) {
				oldI.delete();
			}
		}
		writeFile(imageFile, f);
	}
	
	/**
	 * Find an image in the directory
	 * @param baseDir
	 * @return
	 */
	public static File[] findImageFile(String baseDir) {
		List<String> extensions = new Vector<String>();
		extensions.add("image.png");
		extensions.add("image.jpeg");
		extensions.add("image.jpg");
		extensions.add("image.gif");
		extensions.add("image.svg");
		FileFilter imageExtensionFilter = new WildcardFileFilter(extensions);
		File baseFolder = new File(baseDir);
		File[] imageFiles = baseFolder.listFiles(imageExtensionFilter);
		return imageFiles;
	}
	
	/**
	 * Find an image in the directory
	 * @param baseDir
	 * @return
	 */
	public static File[] findImageFile(File baseFolder) {
		List<String> extensions = new Vector<String>();
		extensions.add("image.png");
		extensions.add("image.jpeg");
		extensions.add("image.jpg");
		extensions.add("image.gif");
		extensions.add("image.svg");
		FileFilter imageExtensionFilter = new WildcardFileFilter(extensions);
		File[] imageFiles = baseFolder.listFiles(imageExtensionFilter);
		return imageFiles;
	}
}
