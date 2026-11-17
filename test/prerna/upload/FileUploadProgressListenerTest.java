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
package prerna.upload;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.lang.reflect.Field;
import java.text.DecimalFormat;

import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;

class FileUploadProgressListenerTest {

	private static Logger watch(FileUploadProgressListener listener) throws Exception {
		Logger logger = mock(Logger.class);
		Field field = FileUploadProgressListener.class.getDeclaredField("logger");
		field.setAccessible(true);
		field.set(listener, logger);
		return logger;
	}

	private static String percent(double value) {
		return new DecimalFormat("0.00%").format(value);
	}

	@Test
	void ignoresUpdatesBeforeTheFirstItem() throws Exception {
		FileUploadProgressListener listener = new FileUploadProgressListener(null);
		Logger logger = watch(listener);

		listener.update(5_000_000, 10_000_000, 0);

		verifyNoInteractions(logger);
	}

	@Test
	void logsTheItemAndPercentOncePerMegabyte() throws Exception {
		FileUploadProgressListener listener = new FileUploadProgressListener("");
		Logger logger = watch(listener);

		listener.update(1_000_000, 4_000_000, 1);
		listener.update(1_900_000, 4_000_000, 1);
		listener.update(2_000_000, 4_000_000, 1);

		verify(logger).info("Currently reading item {}", 1);
		verify(logger).info("{} complete with transfer", percent(0.25));
		verify(logger).info("{} complete with transfer", percent(0.5));
		verifyNoMoreInteractions(logger);
	}

	@Test
	void announcesEachNewItem() throws Exception {
		FileUploadProgressListener listener = new FileUploadProgressListener(null);
		Logger logger = watch(listener);

		listener.update(1_000_000, 2_000_000, 1);
		listener.update(2_000_000, 2_000_000, 2);

		verify(logger).info("Currently reading item {}", 1);
		verify(logger).info("Currently reading item {}", 2);
		verify(logger).info("{} complete with transfer", percent(0.5));
		verify(logger).info("{} complete with transfer", percent(1.0));
		verifyNoMoreInteractions(logger);
	}

	@Test
	void skipsPercentagesWhenTheLengthIsUnknown() throws Exception {
		FileUploadProgressListener listener = new FileUploadProgressListener(null);
		Logger logger = watch(listener);

		listener.update(3_000_000, -1, 1);

		verifyNoInteractions(logger);
	}
}
