package prerna.web.requests;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

public class OverrideParametersServletRequest extends HttpServletRequestWrapper {

	private Map<String, String> parameters = new HashMap<String, String>();
	
	public OverrideParametersServletRequest(HttpServletRequest request) {
		super(request);
	}
	
	public void setParameters(Map<String, String> parameters) {
		this.parameters = parameters;
	}

	@Override
	public String getParameter(String key) {
		return this.parameters.get(key);
	}
}
