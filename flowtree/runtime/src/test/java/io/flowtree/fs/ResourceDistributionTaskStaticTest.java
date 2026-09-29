/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree.fs;

import io.almostrealism.persist.ResourceHeaderParser;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link ResourceDistributionTask#getResourceClass(byte[])}, the
 * static header-to-type dispatch used by the distributed file system to select
 * the concrete {@link DistributedResource} subclass for a stored resource.
 */
public class ResourceDistributionTaskStaticTest extends TestSuiteBase {

	/**
	 * A {@code null} header carries no type information, so the base
	 * {@link DistributedResource} class must be selected.
	 */
	@Test(timeout = 5000)
	public void nullHeaderResolvesToBaseClass() {
		Assert.assertEquals(DistributedResource.class,
				ResourceDistributionTask.getResourceClass(null));
	}

	/**
	 * A header that matches a registered {@link ResourceHeaderParser} must
	 * resolve to that parser's advertised class.
	 */
	@Test(timeout = 5000)
	public void registeredParserSelectsItsClass() {
		ResourceDistributionTask.addResourceClass(
				new ConcatenatedResource.ConcatenatedResourceHeaderParser());

		Assert.assertEquals(ConcatenatedResource.class,
				ResourceDistributionTask.getResourceClass("<ConcatenatedResource>/dir".getBytes()));
	}

	/**
	 * A non-null header that matches no registered parser must fall back to the
	 * base {@link DistributedResource} class.
	 */
	@Test(timeout = 5000)
	public void unmatchedHeaderResolvesToBaseClass() {
		Assert.assertEquals(DistributedResource.class,
				ResourceDistributionTask.getResourceClass("no known header".getBytes()));
	}
}
