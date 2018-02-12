package prerna.upload;

import java.io.File;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;

import org.apache.commons.fileupload.FileItem;

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
			String fileName = fi.getName();
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
		String imageDir = filePath + "\\" + appName + "\\version";
		File f = new File(imageDir);
		if(!f.exists()) {
			f.mkdirs();
		}
		String imageLoc = imageDir + "\\image.png";
		f = new File(imageLoc);
		// delete it if it exists
		if(f.exists()) {
			f.delete();
		}
		writeFile(imageFile, f);
	}
}
