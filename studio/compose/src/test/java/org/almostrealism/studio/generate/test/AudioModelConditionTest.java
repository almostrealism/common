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

package org.almostrealism.studio.generate.test;

import org.almostrealism.studio.generate.AudioModel;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Verifies how {@link AudioModel} reports and edits its conditioning inputs.
 */
public class AudioModelConditionTest extends TestSuiteBase {

	/** Only a non-blank text condition counts as a text condition. */
	@Test(timeout = 10000)
	public void blankTextIsNotACondition() {
		AudioModel model = new AudioModel("blank", new ArrayList<>(List.of(" ")));
		Assert.assertFalse(model.hasTextCondition());
		Assert.assertFalse(model.hasAnyCondition());

		model.setTextConditions(new ArrayList<>(List.of("kick")));
		Assert.assertTrue(model.hasTextCondition());
		Assert.assertTrue(model.hasAnyCondition());
	}

	/** An audio condition alone is enough to generate from, and removing it leaves none. */
	@Test(timeout = 10000)
	public void audioConditionsAreAddedAndRemoved() {
		AudioModel model = new AudioModel("audio");
		model.removeAudioCondition("missing");
		Assert.assertFalse(model.hasAnyCondition());

		model.addAudioCondition("sample");
		Assert.assertTrue(model.hasAnyCondition());
		Assert.assertFalse(model.hasTextCondition());

		model.removeAudioCondition("sample");
		Assert.assertFalse(model.hasAnyCondition());
	}

	/** A null or blank audio path cannot identify a file, so it is not a condition. */
	@Test(timeout = 10000)
	public void blankAudioIsNotACondition() {
		AudioModel model = new AudioModel("blankAudio");
		model.setAudioConditions(new ArrayList<>(Arrays.asList(null, "", "  ")));
		Assert.assertFalse(model.hasAudioCondition());
		Assert.assertFalse(model.hasAnyCondition());

		model.addAudioCondition("sample");
		Assert.assertTrue(model.hasAudioCondition());
		Assert.assertTrue(model.hasAnyCondition());
	}
}
