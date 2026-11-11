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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SamlDataObjectMapperTest {

	@Test
	void leavesEveryUnmappedFieldEmpty() {
		SamlDataObjectMapper mapper = new SamlDataObjectMapper(sdo(Map.of("mail", "ada@example.com")), Map.of());

		assertNull(mapper.getId());
		assertNull(mapper.getName());
		assertNull(mapper.getEmail());
		assertNull(mapper.getUsername());
		assertNull(mapper.getGroupType());
		assertTrue(mapper.getUserGroups().isEmpty());
	}

	@Test
	void buildsAFieldFromItsAttributesInTheConfiguredOrder() {
		SamlDataObject sdo = sdo(Map.of("sn", "Lovelace", "givenname", "Ada", "mail", "ada@example.com", "uid",
				"u-1", "samaccountname", "alovelace"));
		Map<String, String[]> mapping = new HashMap<>();
		mapping.put("name", new String[] { "givenname", "middlename", "sn" });
		mapping.put("email", new String[] { "mail" });
		mapping.put("id", new String[] { "uid" });
		mapping.put("username", new String[] { "samaccountname" });

		SamlDataObjectMapper mapper = new SamlDataObjectMapper(sdo, mapping);

		assertEquals("Ada Lovelace", mapper.getName(), "an attribute the assertion lacks is skipped");
		assertEquals("ada@example.com", mapper.getEmail());
		assertEquals("u-1", mapper.getId());
		assertEquals("alovelace", mapper.getUsername());
	}

	@Test
	void joinsTheValuesOfAMultiValuedAttribute() {
		SamlDataObject sdo = new SamlDataObject();
		sdo.addAttribute("cn", new String[] { "Ada", "Augusta" });
		SamlDataObjectMapper mapper = new SamlDataObjectMapper(sdo, Map.of("name", new String[] { "cn" }));

		assertEquals("Ada Augusta", mapper.getName());
	}

	@Test
	void answersNothingWhenNoneOfTheMappedAttributesArrived() {
		SamlDataObjectMapper mapper = new SamlDataObjectMapper(sdo(Map.of("mail", "ada@example.com")),
				Map.of("id", new String[] { "uid", "employeeid" }));

		assertNull(mapper.getId());
	}

	@Test
	void collectsGroupsAcrossEveryMappedAttribute() {
		SamlDataObject sdo = new SamlDataObject();
		sdo.addAttribute("memberof", new String[] { "analysts", "admins" });
		sdo.addAttribute("roles", new String[] { "admins", "auditors" });
		sdo.addAttribute("idp", "corp-idp");
		Map<String, String[]> mapping = Map.of("groups", new String[] { "memberof", "roles", "absent" }, "provider",
				new String[] { "idp" });

		SamlDataObjectMapper mapper = new SamlDataObjectMapper(sdo, mapping);

		assertEquals(Set.of("analysts", "admins", "auditors"), mapper.getUserGroups());
		assertEquals("corp-idp", mapper.getGroupType());
	}

	@Test
	void readsTheWhitelistedAttributesByTheirMultiplicity() {
		SamlDataObject sdo = new SamlDataObject();
		sdo.addAttribute("dept", new String[] { "R&D", "Ops" });
		sdo.addAttribute("site", "HQ");
		Map<String, String[]> mapping = Map.of("department", new String[] { "dept" }, "site", new String[] { "site" });
		Map<String, Boolean> wanted = new LinkedHashMap<>();
		wanted.put("Department", true);
		wanted.put("site", false);
		wanted.put("clearance", false);
		wanted.put(null, true);

		Map<String, Collection<String>> values = new SamlDataObjectMapper(sdo, mapping).getValuesForAttributes(wanted);

		assertEquals(Set.of("Department", "site"), values.keySet(), "keys keep the case they were asked for");
		assertEquals(Set.of("R&D", "Ops"), Set.copyOf(values.get("Department")));
		assertEquals(List.of("HQ"), values.get("site"));
		assertFalse(values.containsKey("clearance"));
	}

	@Test
	void carriesTheNameIdIssuerAndWhatTheServletWorkedOut() {
		SamlDataObjectMapper mapper = new SamlDataObjectMapper(new SamlDataObject(), Map.of());
		mapper.setNameId("transient-1");
		mapper.setIssuer("https://idp.example.com");
		mapper.setValidUserGroups(Set.of("analysts"));
		mapper.setExtendedUserAttributes(Map.of("site", List.of("HQ")));

		assertEquals("transient-1", mapper.getNameId());
		assertEquals("https://idp.example.com", mapper.getIssuer());
		assertEquals(Set.of("analysts"), mapper.getValidUserGroups());
		assertEquals(List.of("HQ"), mapper.getExtendedUserAttributes().get("site"));
	}

	private static SamlDataObject sdo(Map<String, String> values) {
		SamlDataObject sdo = new SamlDataObject();
		values.forEach(sdo::addAttribute);
		return sdo;
	}
}
