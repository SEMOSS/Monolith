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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

class SamlDataObjectTest {

	@Test
	void filesEveryAttributeUnderItsLowerCaseName() {
		SamlDataObject sdo = new SamlDataObject();
		sdo.addAttribute("Mail", "ada@example.com");
		sdo.addAttribute("GivenName", new String[] { "Ada", "Augusta" });

		Map<String, String[]> attributes = sdo.getAttributeMap();
		assertEquals(2, attributes.size());
		assertArrayEquals(new String[] { "ada@example.com" }, attributes.get("mail"));
		assertArrayEquals(new String[] { "Ada", "Augusta" }, attributes.get("givenname"));
	}

	@Test
	void aRepeatedAttributeKeepsTheLastValues() {
		SamlDataObject sdo = new SamlDataObject();
		sdo.addAttribute("mail", "first@example.com");
		sdo.addAttribute("MAIL", "second@example.com");

		assertArrayEquals(new String[] { "second@example.com" }, sdo.getAttributeMap().get("mail"));
	}

	@Test
	void handsOutAReadOnlyView() {
		SamlDataObject sdo = new SamlDataObject();
		Map<String, String[]> attributes = sdo.getAttributeMap();

		assertThrows(UnsupportedOperationException.class, () -> attributes.put("id", new String[] { "admin" }));
		sdo.addAttribute("id", "u-1");
		assertEquals(1, attributes.size(), "the view follows later attributes");
	}
}
