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
package prerna.web.conf.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserFileLogUtilTest {

	@TempDir
	Path dir;

	private static List<String> awaitLines(Path file, int count) throws Exception {
		long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline) {
			if (Files.exists(file)) {
				List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
				if (lines.size() >= count) {
					return lines;
				}
			}
			Thread.onSpinWait();
		}
		return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
	}

	@Test
	void refusesABlankFilePath() {
		assertThrows(IOException.class, () -> UserFileLogUtil.getInstance(null, ","));
		assertThrows(IOException.class, () -> UserFileLogUtil.getInstance("  ", ","));
	}

	@Test
	void appendsEachRowJoinedByTheSeparatorUnderNewFolders() throws Exception {
		Path file = dir.resolve("logs/cac/users.csv");
		UserFileLogUtil log = UserFileLogUtil.getInstance(file.toString(), "|");

		log.addToQueue(new String[] { "1234567890", "DOE JOHN", "2026-11-10 10:00:00", "10.0.0.9" });
		log.addToQueue(new String[0]);
		log.addToQueue(new String[] { "jane@agency.gov" });

		assertEquals(List.of("1234567890|DOE JOHN|2026-11-10 10:00:00|10.0.0.9", "jane@agency.gov"),
				awaitLines(file, 2));
	}

	@Test
	void appendsToAnExistingLog() throws Exception {
		Path file = dir.resolve("users.csv");
		Files.writeString(file, "earlier,row\n");

		UserFileLogUtil.getInstance(file.toString(), ",").addToQueue(new String[] { "later", "row" });

		assertEquals(List.of("earlier,row", "later,row"), awaitLines(file, 2));
	}

	@Test
	void keepsOneLoggerPerFile() throws Exception {
		String first = dir.resolve("a.csv").toString();
		String second = dir.resolve("b.csv").toString();

		assertSame(UserFileLogUtil.getInstance(first, ","), UserFileLogUtil.getInstance(first, ";"));
		assertNotSame(UserFileLogUtil.getInstance(first, ","), UserFileLogUtil.getInstance(second, ","));
	}
}
