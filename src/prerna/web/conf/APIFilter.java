package prerna.web.conf;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.date.SemossDate;
import prerna.engine.api.IRDBMSEngine;
import prerna.engine.api.IRawSelectWrapper;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.requests.OverrideParametersServletRequest;

public class APIFilter implements Filter {

	private static final String NO_USER_HTML = "/noUserFail/";
	private static final Logger classLogger = LogManager.getLogger(APIFilter.class);

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException 
	{
		// TODO Auto-generated method stub
		// main filter for doing all API related stuff
		
		// if(projectId.equalsIgnoreCase("session"))
		
		// move these to headers at a later point
		String apiKey = request.getParameter("key");
		String pass = request.getParameter("pass");
		String sql = request.getParameter("sql");
		String format = request.getParameter("format");
		if(format == null)
			format = "json";
		
		if(apiKey == null) // try the header
			apiKey = ((HttpServletRequest)request).getHeader("key");
		if(pass == null) // try the header
			pass = ((HttpServletRequest)request).getHeader("pass");
		
		
		// get the insight from the session
		if (sql == null) {
			try {
				sql = IOUtils.toString(request.getReader());
				if (sql != null && sql.length() != 0) {
					sql = sql.replace("'", "\\\'");
					sql = sql.replace("\"", "\\\"");
					// System.err.println(sql2);
				}
			} catch (IOException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
		}

		String projectId = null;
		String insightId = null;
		String consumerId = null;
		String creatorId = null;
		boolean disabled = false;

		// pull the project and insight id from the key
		String pidQuery = "Select consumer_id, project_id, insight_id, creator_id, disabled, expires_on  from API_KEY where api_key = '"
				+ apiKey + "'";
		IRDBMSEngine secDB = (IRDBMSEngine) Utility.getDatabase(Constants.SECURITY_DB);
		
		HttpSession session = ((HttpServletRequest)request).getSession(true);

		// Determine if the request is valid
		try {
			IRawSelectWrapper wrapper = WrapperManager.getInstance().getRawWrapper(secDB, pidQuery);
			if (wrapper.hasNext()) 
			{
				Object[] rowValues = wrapper.next().getValues();
				consumerId = rowValues[0] + "";
				projectId = rowValues[1] + "";
				insightId = rowValues[2] + "";
				creatorId = rowValues[3] + "";
				if(rowValues[4] != null)
					disabled = (Boolean) rowValues[4];
				if(!disabled && rowValues[5] != null)
				{
					SemossDate ts = (SemossDate)rowValues[5];
					
					SemossDate cur = new SemossDate(System.currentTimeMillis());
					if(cur.compareTo(ts) < 0)
						disabled = true;
				}
			}
			
			// this account is disabled
			if(disabled)
			{
				sendRedirect((HttpServletRequest)request, (HttpServletResponse)response);
				return;
			}	
			
			// check the hash algorithm here
			// validate the key
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			String finalData = projectId + insightId + pass;
			byte[] digest = md.digest(finalData.getBytes());
			StringBuffer sb = new StringBuffer();
			for (int i = 0; i < digest.length; i++) {
				sb.append(Integer.toString((digest[i] & 0xff) + 0x100, 16).substring(1));
			}

			String generatedKey = sb.toString();
			if (!generatedKey.equals(apiKey)) // key is wrong
			{
				// throw an error to say this is not valid
				sendRedirect((HttpServletRequest)request, (HttpServletResponse)response);
				return;
			}

			String idQuery = "SELECT ID from SMSS_USER WHERE EMAIL = '" + consumerId + "'";
			wrapper = WrapperManager.getInstance().getRawWrapper(secDB, idQuery);
			if (wrapper.hasNext()) 
			{
				consumerId = wrapper.next().getValues()[0] + "";
			}

			// create the user
			User user = new User();
			AccessToken token = new AccessToken();
			token.setId(consumerId); // should we set the consumer id or the creator id ?
			token.setProvider(AuthProvider.API_USER);
			user.setAccessToken(token);
			session.setAttribute(Constants.SESSION_USER, user);
			session.setAttribute(Constants.SESSION_USER_ID_LOG, token.getId());
			
			// set parameters into the request
			OverrideParametersServletRequest newRequest = new OverrideParametersServletRequest((HttpServletRequest)request);
			Map <String, String> params = new HashMap<String, String>();
			//params.put("consumerId", consumerId);
			params.put("projectId", projectId);
			params.put("insightId", insightId);
			params.put("format", format);
			//params.put("creatorId", creatorId);
			params.put("sql", sql);
			newRequest.setParameters(params);

			// let this filter continue
			// make the insight for this user
			chain.doFilter(newRequest, response);
			
			
			// done invalidate the session
			session.invalidate();

		} catch (NoSuchAlgorithmException e) {
			// TODO Auto-generated catch block
			classLogger.error(Constants.STACKTRACE, e);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			classLogger.error(Constants.STACKTRACE, e);
		}
	}
	
	private void sendRedirect(HttpServletRequest request, HttpServletResponse response)
	{
		try {
			String fullUrl = Utility.cleanHttpResponse(request.getRequestURL().toString());
			String contextPath = request.getContextPath();
			
			String redirectUrl = fullUrl.substring(0, fullUrl.indexOf(contextPath) + contextPath.length()) + NO_USER_HTML;
			response.setStatus(302);
			response.sendRedirect(redirectUrl);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			classLogger.error(Constants.STACKTRACE, e);
		}

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
