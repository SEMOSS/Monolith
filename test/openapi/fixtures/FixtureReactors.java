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
package openapi.fixtures;

import java.util.List;

import io.swagger.v3.oas.annotations.Hidden;
import prerna.openapi.Internal;
import prerna.openapi.PixelInput;
import prerna.openapi.PixelOutput;
import prerna.reactor.AbstractReactor;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;

/** Reactors for OpenApiExporterTest: inferred, annotated, hidden, internal and unreadable. */
public final class FixtureReactors {

	private FixtureReactors() {
	}

	/** Nothing annotated: everything is inferred. */
	public static class InferredFixtureReactor extends AbstractReactor {
		public InferredFixtureReactor() {
			this.keysToGet = new String[] { "name", ReactorKeysEnum.LIMIT.getKey() };
			this.keyRequired = new int[] { 1, 0 };
		}

		@Override
		public NounMetadata execute() {
			return null;
		}

		@Override
		protected String getDescriptionForKey(String key) {
			return key.equals("name") ? "A name" : super.getDescriptionForKey(key);
		}
	}

	@PixelInput(name = "tags", type = List.class, items = String.class, description = "Some tags")
	@PixelOutput(value = Integer.class, description = "How many")
	public static class AnnotatedFixtureReactor extends AbstractReactor {
		public AnnotatedFixtureReactor() {
			this.keysToGet = new String[] { "tags" };
		}

		@Override
		public NounMetadata execute() {
			return null;
		}

		@Override
		public String getReactorDescription() {
			return "Counts tags";
		}
	}

	@Hidden
	public static class HiddenFixtureReactor extends InferredFixtureReactor {
	}

	@Internal("Testing only")
	public static class InternalFixtureReactor extends InferredFixtureReactor {
	}

	/** Its constructor fails, so it cannot be read. */
	public static class BrokenFixtureReactor extends AbstractReactor {
		public BrokenFixtureReactor() {
			throw new IllegalStateException("needs a running server");
		}

		@Override
		public NounMetadata execute() {
			return null;
		}
	}

	/** Two classes both called "Twin" in Pixel. */
	public static final class First {
		private First() {
		}

		public static class TwinReactor extends InferredFixtureReactor {
		}
	}

	public static final class Second {
		private Second() {
		}

		public static class TwinReactor extends InferredFixtureReactor {
		}
	}
}
