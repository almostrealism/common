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

package org.almostrealism.audio.test;

import org.almostrealism.audio.AudioTestFeatures;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.AdjustableDelayCell;
import org.almostrealism.heredity.Gene;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.util.stream.IntStream;

import static org.junit.Assert.assertTrue;

/**
 * Isolated tests of the {@code mself} feedback matrix used by
 * {@link org.almostrealism.studio.arrange.MixdownManager}'s EFX delay grid.
 *
 * <p>The tests use static {@link Gene} values (built with {@code g(double...)})
 * rather than chromosomes, so the feedback gains are unambiguous and any
 * divergence cannot be blamed on a chromosome cap that "should" have been
 * applied. An impulse {@link WaveData} is loaded as the input source, run
 * through an {@code mself} matrix of {@code N} delay cells with known
 * transmission and wetOut values, and the summed output is written to a
 * WAV file we can inspect.</p>
 *
 * <p>For an mself matrix with all-equal transmission {@code t} and wetOut
 * {@code w} across {@code N} cells, the worst-case row-sum loop gain is
 * {@code N*t*w}. With that below 1.0 the impulse response should decay to
 * silence; well above 1.0 it should grow without bound.</p>
 *
 * <p>The three test cases below sweep the loop gain through stable,
 * marginal, and unstable regions; the WAV outputs at
 * {@code results/mself-*.wav} are the primary artifact for inspection.</p>
 */
public class MselfFeedbackMatrixTest extends TestSuiteBase implements CellFeatures, AudioTestFeatures {

	private static final int SAMPLE_RATE = OutputLine.sampleRate;
	private static final int N = 4;
	private static final double DELAY_SECONDS = 0.05;
	private static final double DURATION_SECONDS = 1.5;

	@Test(timeout = 60000)
	public void mselfStable() {
		runMselfMatrix("mself-stable", 0.05, 0.4);
	}

	@Test(timeout = 60000)
	public void mselfMarginal() {
		runMselfMatrix("mself-marginal", 0.3, 0.6);
	}

	@Test(timeout = 60000)
	public void mselfUnstable() {
		runMselfMatrix("mself-unstable", 0.5, 0.7);
	}

	/**
	 * Mirrors {@link org.almostrealism.studio.arrange.MixdownManager}'s
	 * EFX delay-grid forward path WITHOUT feedback. Six source channels
	 * each fan out through {@code m(fi(), delays, tg)} into {@code N} delay
	 * cells with a wetIn gain, then sum.
	 *
	 * <p>This isolates the forward path. If the output diverges here, the
	 * issue is the forward fan-out + sum, not the mself feedback.</p>
	 */
	@Test(timeout = 60000)
	public void forwardPathOnly() {
		int sourceChannels = 6;
		double wetIn = 0.5;

		PackedCollection impulseData = new PackedCollection(SAMPLE_RATE);
		impulseData.setMem(0, 1.0);
		WaveData impulse = new WaveData(impulseData, SAMPLE_RATE);

		WaveData[] sources = new WaveData[sourceChannels];
		for (int i = 0; i < sourceChannels; i++) sources[i] = impulse;
		CellList sourceCells = w(0, sources);

		CellList delays = IntStream.range(0, N)
				.mapToObj(i -> new AdjustableDelayCell(SAMPLE_RATE, c(DELAY_SECONDS)))
				.collect(CellList.collector());

		Gene<PackedCollection> wetInRow = g(uniformRow(wetIn));

		File outputFile = new File("results/mself-forward-only.wav");
		outputFile.getParentFile().mkdirs();

		CellList result = sourceCells
				.m(fi(), delays, i -> wetInRow)
				.sum()
				.o(i -> outputFile);

		log("forwardPathOnly: " + sourceChannels + " channels x " + N
				+ " delays, wetIn=" + wetIn
				+ ", expected peak ~ " + sourceChannels + " * " + wetIn
				+ " = " + (sourceChannels * wetIn));

		result.sec(DURATION_SECONDS).get().run();
		assertTrue("Output WAV should exist at " + outputFile, outputFile.exists());
	}

	/**
	 * Same as {@link #forwardPathPlusBoundedMself} but uses the actual
	 * {@code MixdownManager#delayGene} routing topology &mdash; each channel
	 * routes only to delay layer 0 (with {@code wetIn} gain), and layers
	 * 1..N-1 get zero from the forward path (they only receive mself
	 * feedback). This is the production EFX bus topology.
	 */
	@Test(timeout = 60000)
	public void forwardPathDelayZeroOnlyPlusBoundedMself() {
		int sourceChannels = 6;
		double wetIn = 0.5;
		double transmission = 0.12;
		double wetOut = 0.25;

		PackedCollection impulseData = new PackedCollection(SAMPLE_RATE);
		impulseData.setMem(0, 1.0);
		WaveData impulse = new WaveData(impulseData, SAMPLE_RATE);

		WaveData[] sources = new WaveData[sourceChannels];
		for (int i = 0; i < sourceChannels; i++) sources[i] = impulse;
		CellList sourceCells = w(0, sources);

		CellList delays = IntStream.range(0, N)
				.mapToObj(i -> new AdjustableDelayCell(SAMPLE_RATE, c(DELAY_SECONDS)))
				.collect(CellList.collector());

		// Production topology: only delay 0 receives from channels.
		double[] delayZeroOnly = new double[N];
		delayZeroOnly[0] = wetIn;
		Gene<PackedCollection> wetInRow = g(delayZeroOnly);

		Gene<PackedCollection> transmissionRow = g(uniformRow(transmission));
		Gene<PackedCollection> wetOutRow = g(uniformRow(wetOut));

		File outputFile = new File("results/mself-forward-delay0-plus-bounded-mself.wav");
		outputFile.getParentFile().mkdirs();

		CellList result = sourceCells
				.m(fi(), delays, i -> wetInRow)
				.mself(fi(), i -> transmissionRow, fc(wetOutRow))
				.sum()
				.o(i -> outputFile);

		log("forwardPathDelayZeroOnlyPlusBoundedMself: " + sourceChannels + " channels x "
				+ N + " delays (channels -> delay 0 only); wetIn=" + wetIn
				+ ", transmission=" + transmission + ", wetOut=" + wetOut
				+ ", row-sum loop gain=" + (N * transmission * wetOut)
				+ ", forward sum estimate ~" + (sourceChannels * wetIn));

		result.sec(DURATION_SECONDS).get().run();
		assertTrue("Output WAV should exist at " + outputFile, outputFile.exists());
	}

	/**
	 * Forward path PLUS mself feedback, mirroring exactly what
	 * {@link org.almostrealism.studio.arrange.MixdownManager} does in its
	 * createEfx EFX path. Uses {@link #N} delay layers, {@code 6} source
	 * channels, and bounded transmission/wetOut equivalent to the
	 * production {@link
	 * org.almostrealism.studio.arrange.MixdownManager.Configuration} caps.
	 *
	 * <p>This is the "is the combination unstable even when each piece
	 * looks stable" check. NOTE: this version uses uniform fan-out (each
	 * channel to all delays). For the production "channel -> delay 0 only"
	 * topology see {@link #forwardPathDelayZeroOnlyPlusBoundedMself}.</p>
	 */
	@Test(timeout = 60000)
	public void forwardPathPlusBoundedMself() {
		int sourceChannels = 6;
		double wetIn = 0.5;
		double transmission = 0.12;
		double wetOut = 0.25;

		PackedCollection impulseData = new PackedCollection(SAMPLE_RATE);
		impulseData.setMem(0, 1.0);
		WaveData impulse = new WaveData(impulseData, SAMPLE_RATE);

		WaveData[] sources = new WaveData[sourceChannels];
		for (int i = 0; i < sourceChannels; i++) sources[i] = impulse;
		CellList sourceCells = w(0, sources);

		CellList delays = IntStream.range(0, N)
				.mapToObj(i -> new AdjustableDelayCell(SAMPLE_RATE, c(DELAY_SECONDS)))
				.collect(CellList.collector());

		Gene<PackedCollection> wetInRow = g(uniformRow(wetIn));
		Gene<PackedCollection> transmissionRow = g(uniformRow(transmission));
		Gene<PackedCollection> wetOutRow = g(uniformRow(wetOut));

		File outputFile = new File("results/mself-forward-plus-bounded-mself.wav");
		outputFile.getParentFile().mkdirs();

		CellList result = sourceCells
				.m(fi(), delays, i -> wetInRow)
				.mself(fi(), i -> transmissionRow, fc(wetOutRow))
				.sum()
				.o(i -> outputFile);

		log("forwardPathPlusBoundedMself: " + sourceChannels + " channels x " + N
				+ " delays, wetIn=" + wetIn
				+ ", transmission=" + transmission
				+ ", wetOut=" + wetOut
				+ ", row-sum loop gain=" + (N * transmission * wetOut)
				+ ", forward sum estimate ~" + (sourceChannels * wetIn));

		result.sec(DURATION_SECONDS).get().run();
		assertTrue("Output WAV should exist at " + outputFile, outputFile.exists());
	}

	/**
	 * Builds an mself matrix scenario with all-equal transmission and wetOut
	 * values, runs an impulse-loaded source through it, and writes the
	 * summed output to {@code results/<name>.wav}.
	 *
	 * @param name file stem for the output WAV
	 * @param transmission per-edge transmission gain (uniform across the
	 *                     {@code N x N} matrix)
	 * @param wetOut       per-cell wetOut gain (uniform across the {@code N}
	 *                     parallel paths)
	 */
	private void runMselfMatrix(String name, double transmission, double wetOut) {
		PackedCollection impulseData = new PackedCollection(SAMPLE_RATE);
		impulseData.setMem(0, 1.0);
		WaveData impulse = new WaveData(impulseData, SAMPLE_RATE);

		CellList sources = w(0, impulse);

		CellList delays = IntStream.range(0, N)
				.mapToObj(i -> new AdjustableDelayCell(SAMPLE_RATE, c(DELAY_SECONDS)))
				.collect(CellList.collector());

		Gene<PackedCollection> transmissionRow = g(uniformRow(transmission));
		Gene<PackedCollection> wetOutRow = g(uniformRow(wetOut));

		File outputFile = new File("results/" + name + ".wav");
		outputFile.getParentFile().mkdirs();

		CellList result = sources
				.m(fi(), delays)
				.mself(fi(), i -> transmissionRow, fc(wetOutRow))
				.sum()
				.o(i -> outputFile);

		log(name + ": N=" + N
				+ ", transmission=" + transmission
				+ ", wetOut=" + wetOut
				+ ", row-sum loop gain=" + (N * transmission * wetOut));

		result.sec(DURATION_SECONDS).get().run();

		assertTrue("Output WAV should exist at " + outputFile, outputFile.exists());
		log(name + ": wrote " + outputFile + " (" + outputFile.length() + " bytes)");
	}

	private double[] uniformRow(double value) {
		double[] r = new double[N];
		for (int i = 0; i < N; i++) r[i] = value;
		return r;
	}
}
