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

import org.json.JSONObject;

import io.swagger.v3.oas.annotations.Hidden;
import prerna.openapi.Internal;
import prerna.reactor.AbstractReactor;
import prerna.reactor.ReactorDefinition;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;

/**
 * Reactors for OpenApiExporterTest: inferred, defined, keyless, undefined,
 * hidden, internal and unreadable.
 */
public final class FixtureReactors {

	private FixtureReactors() {
	}

	/**
	 * Describes one key through the deprecated method; everything else is inferred.
	 */
	public static class InferredFixtureReactor extends AbstractReactor {
		public InferredFixtureReactor() {
			this.keysToGet = new String[] { "name", ReactorKeysEnum.LIMIT.getKey() };
			this.keyRequired = new int[] { 1, 0 };
		}

		@Override
		public NounMetadata execute() {
			return null;
		}

		@SuppressWarnings("deprecation")
		@Override
		protected String getDescriptionForKey(String key) {
			return key.equals("name") ? "A name" : super.getDescriptionForKey(key);
		}
	}

	public static class DefinedFixtureReactor extends AbstractReactor {
		public DefinedFixtureReactor() {
			this.keysToGet = new String[] { "tags" };
		}

		@Override
		public NounMetadata execute() {
			return null;
		}

		@Override
		public ReactorDefinition getReactorDefinition() {
			return super.getReactorDefinition().description("Counts tags")
					.key("tags", key -> key.list(String.class).description("Some tags"))
					.output(Integer.class, "How many");
		}
	}

	public static class NoKeysFixtureReactor extends AbstractReactor {
		@Override
		public NounMetadata execute() {
			return null;
		}

		@Override
		public ReactorDefinition getReactorDefinition() {
			return super.getReactorDefinition().description("Says hello").noKeys().output(String.class, "A greeting");
		}
	}

	/** Declares its output as a JSON Schema. */
	public static class RowsFixtureReactor extends AbstractReactor {
		@Override
		public NounMetadata execute() {
			return null;
		}

		@Override
		public ReactorDefinition getReactorDefinition() {
			JSONObject row = new JSONObject().put("type", "object").put("properties",
					new JSONObject().put("id", new JSONObject().put("type", "string").put("description", "The id")));
			return super.getReactorDefinition().description("Lists rows").noKeys().outputSchema(
					new JSONObject().put("type", "array").put("description", "Some rows").put("items", row));
		}
	}

	/** Declares nothing about itself, so it is not published. */
	public static class UndefinedFixtureReactor extends AbstractReactor {
		public UndefinedFixtureReactor() {
			this.keysToGet = new String[] { "anything" };
		}

		@Override
		public NounMetadata execute() {
			return null;
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

		@Override
		public ReactorDefinition getReactorDefinition() {
			return super.getReactorDefinition().description("Needs a running server");
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
