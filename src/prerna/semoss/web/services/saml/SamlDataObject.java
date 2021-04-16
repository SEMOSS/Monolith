package prerna.semoss.web.services.saml;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Container for the flushed out SAML attributes 
 */
public class SamlDataObject {

	private Map<String, String> attributes = new HashMap<>();
	
	public SamlDataObject() {

	}
	
	public void addAttribute(String key, String value) {
		this.attributes.put(key, value);
	}
	
	public Map<String, String> getAttributeMap() {
		return Collections.unmodifiableMap(this.attributes);
	}
}
