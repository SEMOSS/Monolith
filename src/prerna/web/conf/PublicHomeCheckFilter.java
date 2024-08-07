package prerna.web.conf;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tika.Tika;

import com.google.common.base.Strings;

import prerna.auth.User;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.project.api.IProject;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

public class PublicHomeCheckFilter implements Filter {

	private static final Logger classLogger = LogManager.getLogger(PublicHomeCheckFilter.class);
	
	@Override
	public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2) throws IOException, ServletException {
		// we dont want any cache on portals
		HttpServletResponse response = ((HttpServletResponse) arg1);
		response.setHeader("Cache-Control", "private, no-store, no-cache, must-revalidate");

		// check to see if the user is logged in
		// if so pick the user object from session
		// isolate the index of public_home/ to the next /
		// see if this user has permission by using the engine map
		// if yes allow
		ServletContext context = arg0.getServletContext();

		HttpSession session = ((HttpServletRequest) arg0).getSession(false);
		String fullUrl = WebUtility.cleanHttpResponse(((HttpServletRequest) arg0).getRequestURL().toString());

		// default is public_home
		String publicHomeFolder = Utility.getPublicHomeFolder();

		// this will be the deployment name of the app
		//  Context Path  - this is already / Monolith
		String contextPath = context.getContextPath();
		String contextPathPublicHome = contextPath + "/" + publicHomeFolder ;
		String realPath = context.getRealPath(File.separator);

		// try to get the project id
		String projectId = fullUrl.substring(fullUrl.indexOf(contextPathPublicHome) + contextPathPublicHome.length() + 1);
		projectId = projectId.substring(0, projectId.indexOf("/"));

		if(!Strings.isNullOrEmpty(projectId)) {
			projectId = WebUtility.inputSanitizer(projectId);

			// check if user can access
			// first, need security on
			// second, if public, allow them to go - rest runPixel endpoint still have security enabled 
			// so this is the case when we want user to go to portal and have a login page there
			// third, if not public - you must have access
			if(!SecurityProjectUtils.projectIsGlobal(projectId)) {
				if(session != null) {
					User user = (User) session.getAttribute(Constants.SESSION_USER);
					if(!SecurityProjectUtils.userCanViewProject(user, projectId)) {
						arg1.getWriter().write("User does not have access to this project" );
						return;	
					}
				} else {
					arg1.getWriter().write("User must be logged in to access this project");
					return;
				}
			}

			IProject project = Utility.getProject(projectId);
			if(project == null) {
				arg1.getWriter().write("Unable to load project with id='" + projectId + "'");
				return;	
			}

			// try to create the public home from scratch
			File publicHomeDir = new File(realPath+"/"+publicHomeFolder); 
			// make the directory if it doesn't exist
			if(!publicHomeDir.exists()) {
				publicHomeDir.mkdir();
			}

			// are we already published?
			// and am i up to date with the last publish date?
			if(!project.requirePublish(false)) {
				// if we are doing blocks
				// and this is the root request
				// we have to return the json file
				if(project.getProjectType() == IProject.PROJECT_TYPE.BLOCKS) {
					if(fullUrl.endsWith(publicHomeFolder+"/"+project.getProjectId()+"/"+Constants.PORTALS_FOLDER+"/")) {
						RequestDispatcher dispatcher = arg0.getRequestDispatcher(IProject.BLOCK_FILE_NAME);
						dispatcher.forward(arg0, arg1);
						return;
					}
				}
				// then send along
				arg2.doFilter(arg0, arg1);
				return;
			}

			// dont need to pull from cloud again
			boolean successfulPublish = project.publish(realPath+"/"+publicHomeFolder, true);
			if(successfulPublish) {
				String thisPortalsPath = "/"+publicHomeFolder+"/"+projectId+"/"+Constants.PORTALS_FOLDER+"/";
				String fileToPull = realPath+thisPortalsPath;
				if(!fileToPull.endsWith("/")) {
					fileToPull += "/";
				} 
				// find the last index if we are trying to pull a specific file
				int index = fullUrl.indexOf(thisPortalsPath)+thisPortalsPath.length();
				String specificFile = null;
				if(index < fullUrl.length()) {
					specificFile = fullUrl.substring(fullUrl.indexOf(thisPortalsPath)+thisPortalsPath.length());
					fileToPull += specificFile;
				} else {
					if(project.getProjectType() == IProject.PROJECT_TYPE.BLOCKS) {
						fileToPull += IProject.BLOCK_FILE_NAME;
					} else {
						fileToPull += "index.html";
					}
				}

				File file = new File(fileToPull);
				if(!file.exists()) {
					String errorMessage = "Could not find file at path " + thisPortalsPath;
					if(specificFile != null) {
						errorMessage += specificFile;
					}
					response.getWriter().write(errorMessage);
					response.flushBuffer();
					return;
				}
				if(file.isDirectory()) {
					file = new File(file.getAbsolutePath()+"/index.html");
					if(!file.exists()) {
						String errorMessage = "Could not find file at path " + thisPortalsPath;
						if(specificFile != null) {
							errorMessage += specificFile;
						}
						response.getWriter().write(errorMessage);
						response.flushBuffer();
						return;
					}
				}
				// Set appropriate response headers
				String tomcatMimeType = context.getMimeType(file.getAbsolutePath());
				if(tomcatMimeType != null && !tomcatMimeType.isEmpty()) {
					response.setContentType(tomcatMimeType);
				} else {
					String fileProbeContentType = Files.probeContentType(Paths.get(file.getAbsolutePath()));
					if(fileProbeContentType != null && !fileProbeContentType.isEmpty()) {
						response.setContentType(fileProbeContentType);
					} else {
						try {
							Tika tika = new Tika();
				            String tikeContentType = tika.detect(file);
							response.setContentType(tikeContentType);
						} catch(IOException e) {
							classLogger.error(Constants.STACKTRACE, e);
							response.setContentType("application/octet-stream");
						}
					}
				}
				// Serve the file content
				try (FileInputStream  reader = new FileInputStream(file);
						// Get the response writer
						ServletOutputStream outputStream = response.getOutputStream()) {
					byte[] buffer = new byte[1024];
		            int bytesRead;
		            while ((bytesRead = reader.read(buffer)) != -1) {
		                outputStream.write(buffer, 0, bytesRead);
		            }
				} catch (IOException e) {
					classLogger.error(Constants.STACKTRACE, e);
					response.getWriter().write("Error serving the file.");
					response.flushBuffer();
				}
				return;

			} else {
				arg1.getWriter().write("Publish is not enabled on this project or there was an error publishing this project" );
				return;
			}
		}

		arg1.getWriter().write("Improper portal URL - unable to find project ID for the portal");
		return;
	}

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}


	@Override
	public void init(FilterConfig arg0) throws ServletException {
		// TODO Auto-generated method stub

	}
}
