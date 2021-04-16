package prerna.semoss.web.services.saml;

import java.util.Map;

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
	private Map<String, String> sdoInputMap;
	
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
	
	private String generateInput(String attributeKey) {
		StringBuffer buffer = new StringBuffer();
		String[] idInputs = attributeMapper.get(attributeKey);
		
		int counter = 0;
		for(String input : idInputs) {
			if(sdoInputMap.containsKey(input)) {
				if(counter > 0) {
					buffer.append(defaultSep);
				}
				
				buffer.append(sdoInputMap.get(input));
				counter++;
			}
		}
		
		if(buffer.length() == 0) {
			return null;
		}
		
		return buffer.toString();
	}

}
