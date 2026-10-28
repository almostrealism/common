/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.almostrealism.profile.test;

import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.profile.OperationTimingListener;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.stream.IntStream;

/**
 * Tests for {@link OperationProfileNode#getRunCount()}, which counts the operation invocations
 * the runtime listener recorded across a profile subtree, excluding compilation entries.
 */
public class OperationProfileNodeRunCountTests extends TestSuiteBase {

	/**
	 * Runs recorded at a node and at its nested child are summed over the subtree, compilation
	 * entries are not counted, and the count survives a save and reload of the profile.
	 */
	@Test(timeout = 10000)
	public void countsRunsAcrossSubtree() throws IOException {
		OperationProfileNode root = new OperationProfileNode("root");
		OperationMetadata parent = new OperationMetadata("parent", "parent operation");
		OperationMetadata child = new OperationMetadata("child", "child operation");
		root.getProfileNode(parent).addChild(child);
		Assert.assertEquals(0, root.getRunCount());

		OperationTimingListener listener = root.getRuntimeListener();
		IntStream.range(0, 2).forEach(i -> listener.recordDuration(null, parent, 1000));
		IntStream.range(0, 3).forEach(i -> listener.recordDuration(parent, child, 1000));
		root.recordCompilation(child, null, "kernel source", 5000);

		Assert.assertEquals(5, root.getRunCount());
		Assert.assertEquals(5, root.getProfileNode(parent).getRunCount());
		Assert.assertEquals(3, root.getProfileNode(parent).getProfileNode(child).getRunCount());

		File file = new File(Files.createTempDirectory("profile-runs").toFile(), "profile.xml");
		root.save(file);
		Assert.assertEquals(5, OperationProfileNode.load(file).getRunCount());
	}
}
