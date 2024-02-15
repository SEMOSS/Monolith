package prerna.web.requests;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.util.Constants;

public class MultiReadHttpServletRequest extends HttpServletRequestWrapper {
	
	private static final Logger classLogger = LogManager.getLogger(MultiReadHttpServletRequest.class);
	
	private ByteArrayOutputStream cachedBytes;
	private Map<String, String[]> parameterMap;
	
	public MultiReadHttpServletRequest(HttpServletRequest request) {
		super(request);
//		try {
//			cacheInputStream();
//			this.parameterMap = parseParameters(getInputStream());
//		} catch (IOException e) {
//			classLogger.error(Constants.STACKTRACE, e);
//		}
	}

	@Override
	public ServletInputStream getInputStream() throws IOException {
		if (cachedBytes == null)
			cacheInputStream();

		return new CachedServletInputStream();
	}

	@Override
	public BufferedReader getReader() throws IOException{
		return new BufferedReader(new InputStreamReader(getInputStream()));
	}

	private void cacheInputStream() throws IOException {
		// Cache the inputstream in order to read it multiple times
		cachedBytes = new ByteArrayOutputStream();
		IOUtils.copy(super.getInputStream(), cachedBytes);
	}
	
	@Override
	public String getParameter(String name) {
		if(this.parameterMap.containsKey(name) && this.parameterMap.get(name).length > 0) {
			return this.parameterMap.get(name)[0];
		}
		return null;
	}
	
	@Override
	public Map<String, String[]> getParameterMap() {
		return this.parameterMap;
	}
	
	@Override
	public Enumeration<String> getParameterNames() {
		return Collections.enumeration(this.parameterMap.keySet());
	}
	
	@Override
	public String[] getParameterValues(String name) {
		if(this.parameterMap.containsKey(name)) {
			return this.parameterMap.get(name);
		}
		return null;
	}

	/**
	 * 
	 * @param inputStream
	 * @return
	 * @throws IOException
	 */
    private Map<String, String[]> parseParameters(InputStream inputStream) throws IOException {
        // Implement parsing logic according to your requirements
        // This is just a placeholder implementation
        Map<String, String[]> params = new HashMap<>();
        // Example: Parse query string parameters from input stream
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        String line;
        while ((line = reader.readLine()) != null) {
            String[] keyValuePairs = line.split("&");
            for (String pair : keyValuePairs) {
                String[] keyValue = pair.split("=");
                if (keyValue.length == 2) {
                    String key = keyValue[0];
                    String value = keyValue[1];
                    if (params.containsKey(key)) {
                        // If key already exists, append value to the array
                        String[] existingValues = params.get(key);
                        String[] newValues = new String[existingValues.length + 1];
                        System.arraycopy(existingValues, 0, newValues, 0, existingValues.length);
                        newValues[existingValues.length] = value;
                        params.put(key, newValues);
                    } else {
                        // If key does not exist, create a new array with the value
                        params.put(key, new String[]{value});
                    }
                }
            }
        }
        return params;
    }
	
	
	/* An inputstream which reads the cached request body */
	public class CachedServletInputStream extends ServletInputStream {
		
		private ByteArrayInputStream input;

		public CachedServletInputStream() {
			/* create a new input stream from the cached request body */
			input = new ByteArrayInputStream(cachedBytes.toByteArray());
		}

		@Override
		public int read() throws IOException {
			return input.read();
		}

		@Override
		public boolean isFinished() {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public boolean isReady() {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public void setReadListener(ReadListener arg0) {
			// TODO Auto-generated method stub
			
		}
	}
}