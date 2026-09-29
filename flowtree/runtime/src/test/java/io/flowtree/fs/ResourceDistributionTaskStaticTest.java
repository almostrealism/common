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

import java.util.List;

/**
 * Tests for {@link ResourceDistributionTask#getResourceClass(byte[])}, the
 * static header-to-type dispatch used by the distributed file system to select
 * the concrete {@link DistributedResource} subclass for a stored resource.
 *
 * <p>{@link ResourceDistributionTask} keeps its parser registry in a JVM-wide
 * static list. To keep the dispatch assertion order-independent, it uses a
 * header prefix and resource class unique to this test, so resolving to that
 * class can only happen if this test's own registration took effect rather than
 * some registration made by another test in the same JVM.
 */
public class ResourceDistributionTaskStaticTest extends TestSuiteBase {

	/** Header prefix matched only by {@link MarkerHeaderParser}. */
	private static final String MARKER_HEADER = "<ResourceDistributionTaskStaticTestMarker>";

	/**
	 * A resource type registered only by this test, so that resolving to it
	 * proves the registration under test actually took effect regardless of what
	 * other tests may have registered.
	 */
	public static final class MarkerResource extends DistributedResource {
		/** Constructs an empty marker resource. */
		public MarkerResource() {
			super();
		}
	}

	/**
	 * A parser that matches {@link #MARKER_HEADER} and advertises
	 * {@link MarkerResource}, used to verify header dispatch in isolation.
	 */
	private static final class MarkerHeaderParser implements ResourceHeaderParser {
		@Override
		public boolean doesHeaderMatch(byte[] head) {
			return head != null && new String(head).startsWith(MARKER_HEADER);
		}

		@Override
		public Class getResourceClass() {
			return MarkerResource.class;
		}
	}

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
	 * resolve to that parser's advertised class. A marker parser and class unique
	 * to this test are used so the assertion can only pass when this test's own
	 * {@link ResourceDistributionTask#addResourceClass(ResourceHeaderParser)}
	 * call registered the parser.
	 */
	@Test(timeout = 5000)
	public void registeredParserSelectsItsClass() {
		List snapshot = ResourceDistributionTaskState.snapshotRegistry();
		try {
			Assert.assertEquals(DistributedResource.class,
					ResourceDistributionTask.getResourceClass((MARKER_HEADER + "/dir").getBytes()));

			ResourceDistributionTask.addResourceClass(new MarkerHeaderParser());

			Assert.assertEquals(MarkerResource.class,
					ResourceDistributionTask.getResourceClass((MARKER_HEADER + "/dir").getBytes()));
		} finally {
			ResourceDistributionTaskState.restoreRegistry(snapshot);
		}
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
