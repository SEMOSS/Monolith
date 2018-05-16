package prerna.semoss.web.services;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;

import prerna.sablecc2.reactor.utils.ImageCaptureReactor;
import prerna.solr.SolrUtility;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.insight.TextToGraphic;
import prerna.web.services.util.WebUtility;

@Path("/app-{appName}")
public class AppResource {

	private static final String DIR_SEPARATOR = java.nio.file.FileSystems.getDefault().getSeparator();
	
	@GET
	@Path("/appImage/download")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	public Response downloadAppImage(@PathParam("appName") String app) {
		File exportFile = getAppImageFile(app);
		if(exportFile.exists()) {
			String exportName = app + "_Image." + FilenameUtils.getExtension(exportFile.getAbsolutePath());
			return Response.status(200).entity(exportFile).header("Content-Disposition", "attachment; filename=" + exportName).build();
		} else {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("errorMessage", "error sending image file");
			return Response.status(400).entity(errorMap).build();
		}
	}
	
	/**
	 * Use to find the file for the image
	 * @param app
	 * @return
	 */
	private File getAppImageFile(String app) {
		String baseFolder = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER);
		String fileLocation = baseFolder + DIR_SEPARATOR + "db" + DIR_SEPARATOR + app + DIR_SEPARATOR + "version";
		File f = findImageFile(fileLocation);
		if(f != null) {
			return f;
		} else {
			// make the image
			f = new File(fileLocation);
			f.mkdirs();
			fileLocation = fileLocation + DIR_SEPARATOR + "image.png";
			TextToGraphic.makeImage(app, fileLocation);
			f = new File(fileLocation);
			return f;
		}
	}
	
	@GET
	@Path("/insightImage/download")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	public Response downloadInsightImage(@Context HttpServletRequest request, @PathParam("appName") String app, @QueryParam("rdbmsId") String insightId, @QueryParam("params") String params) {
		File exportFile = getInsightImageFile(app, insightId, request.getHeader("Referer"), params);
		if(exportFile.exists()) {
			String exportName = app + "_Image." + FilenameUtils.getExtension(exportFile.getAbsolutePath());
			return Response.status(200).entity(exportFile).header("Content-Disposition", "attachment; filename=" + exportName).build();
		} else {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("errorMessage", "error sending image file");
			return Response.status(400).entity(errorMap).build();
		}
	}
	
	/**
	 * Use to find the file for the image
	 * @param app
	 * @return
	 */
	private File getInsightImageFile(String app, String insightId, String feUrl, String params) {
		String baseFolder = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER);
		String fileLocation = "";
		if(params != null && !params.isEmpty() && !params.equals("undefined")) {
			fileLocation = baseFolder + DIR_SEPARATOR + "db" + DIR_SEPARATOR + app + DIR_SEPARATOR + "version" + DIR_SEPARATOR + insightId + params + DIR_SEPARATOR + "image.png";
		} else {
			fileLocation = baseFolder + DIR_SEPARATOR + "db" + DIR_SEPARATOR + app + DIR_SEPARATOR + "version" + DIR_SEPARATOR + insightId + DIR_SEPARATOR + "image.png";
		}
		File f = new File(fileLocation);
		if(f.exists()) {
			return f;
		} else {
			// try making the image
			ImageCaptureReactor.runImageCapture(feUrl, app, insightId, params);
			if(f.exists()) {
				return f;
			} else {
				// return stock image
				f = SolrUtility.getStockImage(app, insightId);
				return f;
			}
		}
	}
	
	
	/*
	 * Currently not used below...
	 */
	
	@GET
	@Path("/appWidget")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	public Response getAppWidget(@PathParam("appName") String app, @QueryParam("widget") String widgetName, @QueryParam("file") String fileName) {
		
		final String basePath = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER);
		String appWidgetDirLoc = basePath + DIR_SEPARATOR + "db" + 
				DIR_SEPARATOR + app + 
				DIR_SEPARATOR + "version" + 
				DIR_SEPARATOR + "widgets";

		// Get widget file
		String widgetFile = appWidgetDirLoc + DIR_SEPARATOR + widgetName + DIR_SEPARATOR + fileName;
		File f = new File(widgetFile);
		FileInputStream fis = null;
		if (f.exists()) {
			try {
				fis = new FileInputStream(f);
				byte[] byteArray = IOUtils.toByteArray(fis);
				// return file
				return Response.status(200).entity(byteArray).build();
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				closeStream(fis);
			}
		}
		// return error
		Map<String, String> errorMap = new HashMap<String, String>();
		errorMap.put("errorMessage", "error sending widget file " + widgetName + "\\" + fileName);
		return WebUtility.getResponse(errorMap, 400);
	}

	/////////////////////////////////////////////////////////////////
	
	/*
	 * Utility methods
	 */
	
	/**
	 * Find an image in the directory
	 * @param baseDir
	 * @return
	 */
	private File findImageFile(String baseDir) {
		List<String> extensions = new Vector<String>();
		extensions.add("image.png");
		extensions.add("image.jpeg");
		extensions.add("image.jpg");
		extensions.add("image.gif");
		extensions.add("image.svg");
		FileFilter imageExtensionFilter = new WildcardFileFilter(extensions);
		File baseFolder = new File(baseDir);
		File[] imageFiles = baseFolder.listFiles(imageExtensionFilter);
		if(imageFiles != null && imageFiles.length > 0) {
			return imageFiles[0];
		}
		return null;
	}
	
	/**
	 * Close a file stream
	 * @param fis
	 */
	private void closeStream(FileInputStream fis) {
		if(fis != null) {
			try {
				fis.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	
	
}
