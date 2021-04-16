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
	
	/**
	 * Store all the keys are lower case
	 * @param key
	 * @param value
	 */
	public void addAttribute(String key, String value) {
		this.attributes.put(key.toLowerCase(), value);
	}
	
	public Map<String, String> getAttributeMap() {
		return Collections.unmodifiableMap(this.attributes);
	}
}
