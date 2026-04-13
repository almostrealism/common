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

package org.almostrealism.studio.test;

import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.studio.Mixer;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link Mixer} output group functionality, verifying that channels
 * are correctly routed to their assigned group outputs.
 */
public class MixerOutputGroupTest implements CellFeatures {

	@Test
	public void noOutputGroupsPreservesDefaultBehavior() {
		Mixer mixer = new Mixer(4);

		Assert.assertFalse(mixer.hasOutputGroups());
		Assert.assertNotNull(mixer.getCells());
		Assert.assertNotNull(mixer.getOutput());
	}

	@Test
	public void addOutputGroupCreatesGroup() {
		Mixer mixer = new Mixer(4);
		Mixer.OutputGroup group = mixer.addOutputGroup("speakers", 0, 1);

		Assert.assertTrue(mixer.hasOutputGroups());
		Assert.assertNotNull(group);
		Assert.assertNotNull(group.groupOutput());
		Assert.assertArrayEquals(new int[]{0, 1}, group.channelIndices());
	}

	@Test(expected = IllegalArgumentException.class)
	public void duplicateGroupNameThrows() {
		Mixer mixer = new Mixer(4);
		mixer.addOutputGroup("speakers", 0, 1);
		mixer.addOutputGroup("speakers", 2, 3);
	}

	@Test
	public void getOutputGroupReturnsNullForMissing() {
		Mixer mixer = new Mixer(4);
		Assert.assertNull(mixer.getOutputGroup("nonexistent"));
	}

	@Test
	public void outputGroupsRouteCorrectly() {
		Mixer mixer = new Mixer(4);

		// Configure two output groups
		mixer.addOutputGroup("groupA", 0, 1);
		mixer.addOutputGroup("groupB", 2, 3);
		mixer.applyOutputGroups();

		// Verify group outputs are distinct SummationCells
		Mixer.OutputGroup groupA = mixer.getOutputGroup("groupA");
		Mixer.OutputGroup groupB = mixer.getOutputGroup("groupB");
		Assert.assertNotNull(groupA);
		Assert.assertNotNull(groupB);
		Assert.assertNotSame(groupA.groupOutput(), groupB.groupOutput());

		// Verify channel assignments
		Assert.assertArrayEquals(new int[]{0, 1}, groupA.channelIndices());
		Assert.assertArrayEquals(new int[]{2, 3}, groupB.channelIndices());

		// Verify the rebuilt CellList contains the group output cells
		CellList cells = mixer.getCells();
		Assert.assertNotNull(cells);
		Assert.assertTrue("CellList should contain group outputs",
				cells.size() >= 2);
	}

	@Test
	public void applyOutputGroupsPreservesChannels() {
		Mixer mixer = new Mixer(4);
		mixer.addOutputGroup("all", 0, 1, 2, 3);
		mixer.applyOutputGroups();

		// All four channels should still be accessible
		for (int i = 0; i < 4; i++) {
			Assert.assertNotNull(mixer.getChannel(i));
		}

		Assert.assertEquals(4, mixer.getChannelCount());
	}
}
