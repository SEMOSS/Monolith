package prerna.semoss.web.services.saml;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.exec.util.StringUtils;

/**
 *  This class aligns the flushed out SAML attributes to the generated format
 *  for the SEMOSS user object.
 *  
 *  Attribute mapper specifies for this SEMOSS user attribute, which SAML attributes
 *  are used to generate it in an array
 *
 */
public class SamlDataObjectMapper {

	private SamlDataObject sdo;
	private Map<String, String[]> sdoInputMap;
	
	// id = [dod_edi_pn_id]
	// name = [firstname, middlename, lastname]
	private Map<String, String[]> attributeMapper;
	
	private String defaultSep = " ";
	
	public SamlDataObjectMapper(SamlDataObject sdo, Map<String, String[]> attributeMapper) {
		this.sdo = sdo;
		this.sdoInputMap = sdo.getAttributeMap();
		this.attributeMapper = attributeMapper;
	}
	
	public String getId() {
		if(attributeMapper.containsKey("id")) {
			return generateInput("id");
		}
		
		return null;
	}
	
	public String getName() {
		if(attributeMapper.containsKey("name")) {
			return generateInput("name");
		}
		
		return null;
	}
	
	public String getEmail() {
		if(attributeMapper.containsKey("email")) {
			return generateInput("email");
		}
		
		return null;
	}
	
	public String getUsername() {
		if(attributeMapper.containsKey("username")) {
			return generateInput("username");
		}
		
		return null;
	}
	
	public Set<String> getUserGroups() {
		if(attributeMapper.containsKey("groups")) {
			return generateInputSet("groups");
		}
		
		return new HashSet<>();
	}
	
	public String getGroupType() {
		if(attributeMapper.containsKey("provider")) {
			return generateInput("provider");
		}
		
		return null;
	}
	
	private String generateInput(String attributeKey) {
		StringBuffer buffer = new StringBuffer();
		String[] idInputs = attributeMapper.get(attributeKey);
		
		int counter = 0;
		for(String input : idInputs) {
			if(sdoInputMap.containsKey(input)) {
				if(counter > 0) {
					buffer.append(defaultSep);
				}
				
				buffer.append(StringUtils.toString(sdoInputMap.get(input), defaultSep));
				counter++;
			}
		}
		
		if(buffer.length() == 0) {
			return null;
		}
		
		return buffer.toString();
	}
	
	private Set<String> generateInputSet(String attributeKey) {
		Set<String> result = new HashSet<>();
		
		String[] idInputs = attributeMapper.get(attributeKey);
		
		for(String input : idInputs) {
			if(sdoInputMap.containsKey(input)) {
				result.addAll(Arrays.asList(sdoInputMap.get(input)));
			}
		}
		
		return result;
	}

}
