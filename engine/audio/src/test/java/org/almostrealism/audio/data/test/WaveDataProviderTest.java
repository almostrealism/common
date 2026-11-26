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

package org.almostrealism.audio.data.test;

import org.almostrealism.audio.data.SupplierWaveDataProvider;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.data.WaveDataProvider;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Pins the resampling guard on the {@link WaveDataProvider#get(int)} default
 * method directly, independent of any particular file-backed provider.
 *
 * <p>A source with no usable sample rate (a provider that reports
 * {@link WaveDataProvider#getSampleRate() getSampleRate()} {@code <= 0}, such as
 * a file already marked corrupt) has nothing to resample. The frame-count
 * arithmetic for resampling divides by the source rate, so entering it with a
 * zero source rate would raise {@link ArithmeticException}. The guard must
 * short-circuit to the native {@link WaveDataProvider#get() get()} data
 * instead.</p>
 */
public class WaveDataProviderTest extends TestSuiteBase {

	/** Number of frames in the fixture audio. */
	private static final int FRAMES = 16;

	/** A target sample rate that differs from the provider's zero native rate. */
	private static final int TARGET_SAMPLE_RATE = 44100;

	/**
	 * Requesting {@link WaveDataProvider#get(int)} at a target rate from a
	 * provider whose native sample rate is zero returns the native data rather
	 * than dividing by the zero source rate. The frame-count arithmetic on the
	 * resampling path divides by {@code getSampleRate()}, so a zero source rate
	 * reaching it would throw {@link ArithmeticException}; this verifies the
	 * guard returns {@code get()} unchanged instead.
	 */
	@Test(timeout = 60000)
	public void getAtTargetRateSkipsResamplingWhenSourceRateIsZero() {
		PackedCollection wave = new PackedCollection(FRAMES);
		WaveDataProvider provider =
				new SupplierWaveDataProvider("zero-rate", () -> wave, 0);

		Assert.assertEquals("the provider reports a zero native sample rate",
				0, provider.getSampleRate());
		Assert.assertFalse("a zero-rate provider is not readable",
				provider.isReadable());

		WaveData native_ = provider.get();
		WaveData resampled = provider.get(TARGET_SAMPLE_RATE);

		Assert.assertNotNull("the zero-rate guard returns native data, not null",
				resampled);
		Assert.assertEquals("the native (zero) sample rate is preserved, not the target",
				0, resampled.getSampleRate());
		Assert.assertEquals("the native frame count is preserved — no resampling occurred",
				native_.getFrameCount(), resampled.getFrameCount());
		Assert.assertEquals("the native frame count equals the fixture length",
				FRAMES, resampled.getFrameCount());
	}
}
