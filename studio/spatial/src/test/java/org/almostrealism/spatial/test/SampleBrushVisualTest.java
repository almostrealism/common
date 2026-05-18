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

package org.almostrealism.spatial.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.spatial.EditableSpatialWaveDetails;
import org.almostrealism.spatial.FrequencyTimeseries;
import org.almostrealism.spatial.SampleBrush;
import org.almostrealism.spatial.SpatialValue;
import org.almostrealism.spatial.TemporalSpatialContext;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Before;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Visual test for {@link SampleBrush}. Produces three PNG files in
 * {@code results/} that show the source frequency distribution and the
 * canvas state after simulated mouse drags.
 *
 * <p>The intent is described in
 * {@code ringsdesktop/docs/AUDIO_SAMPLE_BRUSH_TEST.md}: dragging the brush
 * uniformly across the canvas should reproduce the source's frequency
 * distribution at the same time positions.</p>
 *
 * <p>The renders use the same {@link TemporalSpatialContext#position}
 * transform that the live UI uses, so PNG content matches what would
 * appear on screen.</p>
 */
public class SampleBrushVisualTest extends TestSuiteBase {

	private static final int SAMPLE_RATE = 44100;
	private static final int FREQ_BINS = 256;
	private static final int FREQ_FRAMES = 1100;
	private static final double FREQ_SAMPLE_RATE = 100.0;
	private static final double SOURCE_DURATION = FREQ_FRAMES / FREQ_SAMPLE_RATE;

	private static final int IMG_WIDTH = 800;
	private static final int IMG_HEIGHT = 400;
	private static final int MARGIN = 20;

	private static final String RESULTS_DIR = "results";

	private TemporalSpatialContext context;

	@Before
	public void setUp() {
		new File(RESULTS_DIR).mkdirs();
		context = new TemporalSpatialContext();
		context.setDuration(SOURCE_DURATION);
	}

	@Test(timeout = 60000)
	public void renderSourceAndBrushDrags() throws IOException {
		WaveDetails source = createSourcePattern();
		renderWaveDetailsToPng(source,
				new File(RESULTS_DIR, "sample-brush-source.png"));

		EditableSpatialWaveDetails canvasFull = createBlankCanvas();
		simulateUniformDrag(source, canvasFull, 0.0, SOURCE_DURATION);
		renderWaveDetailsToPng(canvasFull.getWave(),
				new File(RESULTS_DIR, "sample-brush-drag-from-start.png"));

		EditableSpatialWaveDetails canvasHalf = createBlankCanvas();
		simulateUniformDrag(source, canvasHalf, SOURCE_DURATION / 2, SOURCE_DURATION);
		renderWaveDetailsToPng(canvasHalf.getWave(),
				new File(RESULTS_DIR, "sample-brush-drag-from-middle.png"));

		log("Wrote sample brush visual test PNGs to " + RESULTS_DIR);
	}

	/**
	 * Creates a synthetic source with a recognizable dot pattern. Three
	 * horizontal bands at low/mid/high frequency, each with a different
	 * spacing between dots so the pattern is unmistakable when rendered.
	 */
	private WaveDetails createSourcePattern() {
		WaveDetails source = new WaveDetails("source-pattern", SAMPLE_RATE);
		source.setFreqBinCount(FREQ_BINS);
		source.setFreqFrameCount(FREQ_FRAMES);
		source.setFreqSampleRate(FREQ_SAMPLE_RATE);
		source.setFreqChannelCount(1);
		source.setFrameCount((int) (FREQ_FRAMES * SAMPLE_RATE / FREQ_SAMPLE_RATE));

		PackedCollection freqData = new PackedCollection(FREQ_FRAMES * FREQ_BINS);

		double dotMagnitude = FrequencyTimeseries.frequencyThreshold + 200;

		// Low band: bins around 25-35, dots every 50 frames
		paintDots(freqData, 30, 50, dotMagnitude);
		// Mid band: bins around 115-125, dots every 100 frames
		paintDots(freqData, 120, 100, dotMagnitude);
		// High band: bins around 200-210, dots every 150 frames
		paintDots(freqData, 205, 150, dotMagnitude);

		source.setFreqData(freqData);
		return source;
	}

	private void paintDots(PackedCollection freqData, int bin, int spacing, double magnitude) {
		int dotSize = 4;
		for (int frame = 0; frame < FREQ_FRAMES; frame += spacing) {
			for (int df = 0; df < dotSize && frame + df < FREQ_FRAMES; df++) {
				for (int db = -1; db <= 1; db++) {
					int b = bin + db;
					if (b < 0 || b >= FREQ_BINS) continue;
					int idx = (frame + df) * FREQ_BINS + b;
					freqData.setMem(idx, magnitude);
				}
			}
		}
	}

	private EditableSpatialWaveDetails createBlankCanvas() {
		return new EditableSpatialWaveDetails(
				"canvas", FREQ_FRAMES, FREQ_BINS, SAMPLE_RATE, FREQ_SAMPLE_RATE);
	}

	/**
	 * Simulates a uniform mouse drag across the canvas. The mouse moves at
	 * constant speed from {@code startSeconds} to {@code endSeconds} on the
	 * canvas, so dragging the full source duration takes the same time as
	 * the source itself.
	 */
	private void simulateUniformDrag(WaveDetails source,
									 EditableSpatialWaveDetails canvas,
									 double startSeconds, double endSeconds) {
		SampleBrush brush = new SampleBrush();
		brush.setContext(context);
		brush.setSource(source);
		brush.reset();

		int steps = 200;
		for (int i = 0; i <= steps; i++) {
			double t = i / (double) steps;
			double seconds = startSeconds + t * (endSeconds - startSeconds);
			double timeUnits = context.getSecondsToTime().applyAsDouble(seconds);
			Vector pos = context.position(timeUnits, 0, 0, 0);
			List<SpatialValue<?>> values =
					brush.stroke(pos, 1.0, i == 0 ? 0.0 : 0.05);
			canvas.applyValues(values, context);
		}
	}

	/**
	 * Renders a {@link WaveDetails}'s frequency data to a PNG using the
	 * same {@link TemporalSpatialContext#position} transform that the live
	 * UI uses for visualization.
	 */
	private void renderWaveDetailsToPng(WaveDetails details, File output) throws IOException {
		BufferedImage img = new BufferedImage(IMG_WIDTH, IMG_HEIGHT, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.BLACK);
		g.fillRect(0, 0, IMG_WIDTH, IMG_HEIGHT);
		g.dispose();

		PackedCollection freqData = details.getFreqData();
		int frameCount = details.getFreqFrameCount();
		int binCount = details.getFreqBinCount();

		// Compute the X range from the spatial transform applied to time 0..duration
		double duration = frameCount / details.getFreqSampleRate();
		Vector posStart = context.position(
				context.getSecondsToTime().applyAsDouble(0), 0, 0, 0);
		Vector posEnd = context.position(
				context.getSecondsToTime().applyAsDouble(duration), 0, 0, 0);
		double xMin = posStart.getX();
		double xMax = posEnd.getX();
		double xRange = xMax - xMin;
		if (xRange == 0) xRange = 1;

		// Y range is FREQUENCY_SPACING (frequency 0..1 maps to 0..200)
		double yMin = 0;
		double yMax = TemporalSpatialContext.FREQUENCY_SPACING;
		double yRange = yMax - yMin;

		int plotWidth = IMG_WIDTH - 2 * MARGIN;
		int plotHeight = IMG_HEIGHT - 2 * MARGIN;

		for (int frame = 0; frame < frameCount; frame++) {
			double seconds = frame / details.getFreqSampleRate();
			double timeUnits = context.getSecondsToTime().applyAsDouble(seconds);

			for (int bin = 0; bin < binCount; bin++) {
				double mag = freqData.toDouble(frame * binCount + bin);
				if (mag < FrequencyTimeseries.frequencyThreshold) continue;

				double normFreq = (double) bin / binCount;
				Vector pos = context.position(timeUnits, 0, 0, normFreq);

				int px = MARGIN + (int) ((pos.getX() - xMin) / xRange * plotWidth);
				int py = IMG_HEIGHT - MARGIN
						- (int) ((pos.getY() - yMin) / yRange * plotHeight);

				if (px < 0 || px >= IMG_WIDTH || py < 0 || py >= IMG_HEIGHT) continue;

				int intensity = (int) Math.min(255,
						(mag - FrequencyTimeseries.frequencyThreshold) * 1.0 + 80);
				int rgb = (intensity << 16) | (intensity << 8) | intensity;
				img.setRGB(px, py, rgb);
			}
		}

		ImageIO.write(img, "PNG", output);
	}
}
