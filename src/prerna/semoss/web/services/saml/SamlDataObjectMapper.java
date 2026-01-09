/*******************************************************************************
 * Copyright 2015 Defense Health Agency (DHA)
 *
 * If your use of this software does not include any GPLv2 components:
 * 	Licensed under the Apache License, Version 2.0 (the "License");
 * 	you may not use this file except in compliance with the License.
 * 	You may obtain a copy of the License at
 *
 * 	  http://www.apache.org/licenses/LICENSE-2.0
 *
 * 	Unless required by applicable law or agreed to in writing, software
 * 	distributed under the License is distributed on an "AS IS" BASIS,
 * 	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * 	See the License for the specific language governing permissions and
 * 	limitations under the License.
 * ----------------------------------------------------------------------------
 * If your use of this software includes any GPLv2 components:
 * 	This program is free software; you can redistribute it and/or
 * 	modify it under the terms of the GNU General Public License
 * 	as published by the Free Software Foundation; either version 2
 * 	of the License, or (at your option) any later version.
 *
 * 	This program is distributed in the hope that it will be useful,
 * 	but WITHOUT ANY WARRANTY; without even the implied warranty of
 * 	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * 	GNU General Public License for more details.
 *******************************************************************************/
package prerna.semoss.web.services.saml;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Lists;

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
	
	private String nameId = null;
	private String issuer = null;
	private Set<String> validUserGroups = null;
	private Map<String, Collection<String>> extendedUserAttributes = null;
	
	// attributes
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
	
	public Map<String, Collection<String>> getValuesForAttributes(Map<String, Boolean> attributesWithMultiplicity) {
		Map<String, Collection<String>> result = new HashMap<>();
		for(String attributeKey : attributesWithMultiplicity.keySet()) {
			if (attributeKey == null) {
				continue;
			}
			String attributeKeyNormalized = attributeKey.toLowerCase();
			if(!attributeMapper.containsKey(attributeKeyNormalized)) {
				continue;
			}
			if(attributesWithMultiplicity.get(attributeKey)) {
				result.put(attributeKey, generateInputSet(attributeKeyNormalized));
			} else {
				result.put(attributeKey, Lists.newArrayList(generateInput(attributeKeyNormalized)));
			}
		}
		return result;
	}
	
	public String getIssuer() {
		return issuer;
	}

	public void setIssuer(String issuer) {
		this.issuer = issuer;
	}

	public Set<String> getValidUserGroups() {
		return validUserGroups;
	}

	public void setValidUserGroups(Set<String> validUserGroups) {
		this.validUserGroups = validUserGroups;
	}

	public Map<String, Collection<String>> getExtendedUserAttributes() {
		return extendedUserAttributes;
	}

	public void setExtendedUserAttributes(Map<String, Collection<String>> extendedUserAttributes) {
		this.extendedUserAttributes = extendedUserAttributes;
	}

	public String getNameId() {
		return nameId;
	}

	public void setNameId(String nameId) {
		this.nameId = nameId;
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
				
				buffer.append(String.join(defaultSep, sdoInputMap.get(input)));
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
