/*
 * Copyright 2025 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial.test;

import com.almostrealism.spatial.EditableSpatialWaveDetails;
import com.almostrealism.spatial.SpatialValue;
import com.almostrealism.spatial.SphericalBrush;
import com.almostrealism.spatial.TemporalSpatialContext;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests for the spatial drawing feature components.
 */
public class SpatialDrawingTest {

	/**
	 * Tests that position() and inverse() are true inverses.
	 */
	@Test
	public void testCoordinateRoundTrip() {
		TemporalSpatialContext context = new TemporalSpatialContext();
		context.setDuration(10.0);

		// Test various time/frequency combinations
		double[] times = {0.0, 1.0, 5.0, 9.5};
		double[] frequencies = {0.0, 0.25, 0.5, 0.75, 1.0};

		for (double time : times) {
			for (double freq : frequencies) {
				// Convert to position
				double internalTime = context.getSecondsToTime().applyAsDouble(time);
				Vector position = context.position(internalTime, 0, 0, freq);

				// Convert back to coordinates
				TemporalSpatialContext.TemporalCoordinates coords = context.inverse(position);

				// Verify round-trip accuracy
				Assert.assertEquals("Time mismatch for t=" + time + ", f=" + freq,
						time, coords.time(), 0.001);
				Assert.assertEquals("Frequency mismatch for t=" + time + ", f=" + freq,
						freq, coords.frequency(), 0.001);
				Assert.assertEquals("Layer mismatch", 0, coords.layer());
			}
		}
	}

	/**
	 * Tests that inverse() correctly extracts layer information.
	 */
	@Test
	public void testInverseWithLayers() {
		TemporalSpatialContext context = new TemporalSpatialContext();

		for (int layer = 0; layer < 5; layer++) {
			Vector position = context.position(1.0, 0, layer, 0.5);
			TemporalSpatialContext.TemporalCoordinates coords = context.inverse(position);
			Assert.assertEquals("Layer mismatch", layer, coords.layer());
		}
	}

	/**
	 * Tests SphericalBrush generates correct number of points.
	 */
	@Test
	public void testSphericalBrushDensity() {
		SphericalBrush brush = new SphericalBrush();
		brush.setDensity(100.0); // 100 points per second
		brush.setRadius(10.0);

		// At 60fps (duration ~0.016s), with pressure 1.0, expect ~2 points
		List<SpatialValue<?>> values = brush.stroke(new Vector(0, 0, 0), 1.0, 0.016);
		Assert.assertTrue("Expected 1-3 points, got " + values.size(),
				values.size() >= 1 && values.size() <= 3);

		// With 1 second duration, expect ~100 points
		values = brush.stroke(new Vector(0, 0, 0), 1.0, 1.0);
		Assert.assertEquals("Expected ~100 points", 100, values.size());

		// With half pressure, expect ~50 points
		values = brush.stroke(new Vector(0, 0, 0), 0.5, 1.0);
		Assert.assertEquals("Expected ~50 points", 50, values.size());
	}

	/**
	 * Tests SphericalBrush generates points within radius.
	 */
	@Test
	public void testSphericalBrushRadius() {
		SphericalBrush brush = new SphericalBrush();
		double radius = 10.0;
		brush.setRadius(radius);
		brush.setDensity(1000.0);

		Vector center = new Vector(100, 100, 0);
		List<SpatialValue<?>> values = brush.stroke(center, 1.0, 1.0);

		for (SpatialValue<?> value : values) {
			Vector pos = value.getPosition();
			double distance = Math.sqrt(
					Math.pow(pos.getX() - center.getX(), 2) +
					Math.pow(pos.getY() - center.getY(), 2) +
					Math.pow(pos.getZ() - center.getZ(), 2)
			);
			Assert.assertTrue("Point outside radius: " + distance, distance <= radius);
		}
	}

	/**
	 * Tests EditableSpatialWaveDetails creation and basic properties.
	 */
	@Test
	public void testEditableSpatialWaveDetailsCreation() {
		int timeFrames = 100;
		int frequencyBins = 256;
		double sampleRate = 44100;
		double freqSampleRate = 100;

		EditableSpatialWaveDetails canvas = new EditableSpatialWaveDetails(
				timeFrames, frequencyBins, sampleRate, freqSampleRate);

		Assert.assertFalse("Should not be modified initially", canvas.isModified());
		Assert.assertNotNull("Wave should not be null", canvas.getWave());
		Assert.assertEquals("Frame count mismatch", timeFrames, canvas.getWave().getFreqFrameCount());
		Assert.assertEquals("Bin count mismatch", frequencyBins, canvas.getWave().getFreqBinCount());
	}

	/**
	 * Tests applying brush strokes to EditableSpatialWaveDetails.
	 */
	@Test
	public void testApplyBrushStrokes() {
		int timeFrames = 100;
		int frequencyBins = 256;
		double sampleRate = 44100;
		double freqSampleRate = 100;

		EditableSpatialWaveDetails canvas = new EditableSpatialWaveDetails(
				timeFrames, frequencyBins, sampleRate, freqSampleRate);

		TemporalSpatialContext context = new TemporalSpatialContext();
		context.setDuration(1.0); // 1 second duration

		// Create a direct SpatialValue at a known position (time=0.5s, freq=0.5)
		// This bypasses the brush to test applyValues directly
		double internalTime = context.getSecondsToTime().applyAsDouble(0.5);
		Vector center = context.position(internalTime, 0, 0, 0.5);

		// Create a single value with known intensity (log(2) ≈ 0.693)
		List<SpatialValue<?>> values = List.of(
				new SpatialValue<>(center, Math.log(2.0), 0.5, true)
		);

		// Verify coordinates before applying
		TemporalSpatialContext.TemporalCoordinates coords = context.inverse(center);
		int expectedFrame = (int) (coords.time() * freqSampleRate);
		int expectedBin = (int) (coords.frequency() * frequencyBins);
		Assert.assertTrue("Frame index should be valid: " + expectedFrame,
				expectedFrame >= 0 && expectedFrame < timeFrames);
		Assert.assertTrue("Bin index should be valid: " + expectedBin,
				expectedBin >= 0 && expectedBin < frequencyBins);

		// Apply to canvas
		canvas.applyValues(values, context);

		Assert.assertTrue("Canvas should be modified", canvas.isModified());

		// Verify the specific cell was written
		PackedCollection freqData = canvas.getWave().getFreqData();
		int dataIndex = expectedFrame * frequencyBins + expectedBin;
		double written = freqData.toDouble(dataIndex);
		Assert.assertTrue("Expected cell should have non-zero value, got: " + written +
				" at frame=" + expectedFrame + ", bin=" + expectedBin, written > 0);
	}

	/**
	 * Tests clear functionality.
	 */
	@Test
	public void testClear() {
		EditableSpatialWaveDetails canvas = new EditableSpatialWaveDetails(
				100, 256, 44100, 100);

		TemporalSpatialContext context = new TemporalSpatialContext();
		context.setDuration(1.0);

		// Apply some strokes
		SphericalBrush brush = new SphericalBrush();
		Vector center = context.position(0.5, 0, 0, 0.5);
		canvas.applyValues(brush.stroke(center, 1.0, 0.1), context);

		Assert.assertTrue("Canvas should be modified", canvas.isModified());

		// Clear
		canvas.clear();

		Assert.assertFalse("Canvas should not be modified after clear", canvas.isModified());

		// Verify data is zeroed
		PackedCollection freqData = canvas.getWave().getFreqData();
		for (int i = 0; i < freqData.getMemLength(); i++) {
			Assert.assertEquals("Data should be zero after clear", 0.0, freqData.toDouble(i), 0.0001);
		}
	}

	/**
	 * Tests that getSeries returns the frequency data.
	 */
	@Test
	public void testGetSeries() {
		EditableSpatialWaveDetails canvas = new EditableSpatialWaveDetails(
				100, 256, 44100, 100);

		List<PackedCollection> series = canvas.getSeries(0);
		Assert.assertNotNull("Series should not be null", series);
		Assert.assertFalse("Series should not be empty", series.isEmpty());
		Assert.assertNotNull("First series element should not be null", series.get(0));
	}

	/**
	 * Diagnoses what happens with negative X positions (the issue seen in logs).
	 *
	 * <p>In the application, mouse positions like [-193.811, 21.358, 0.0] were
	 * observed. This test verifies that such positions are correctly mapped.</p>
	 */
	@Test
	public void testNegativeXPositionHandling() {
		TemporalSpatialContext context = new TemporalSpatialContext();
		context.setDuration(10.0); // 10 seconds

		// Simulate the position from logs: [-193.811, 21.358, 0.0]
		Vector mousePosition = new Vector(-193.811, 21.358, 0.0);
		TemporalSpatialContext.TemporalCoordinates coords = context.inverse(mousePosition);

		System.out.println("=== Negative X Position Analysis ===");
		System.out.println("Input position: " + mousePosition);
		System.out.println("Extracted time: " + coords.time());
		System.out.println("Extracted frequency: " + coords.frequency());
		System.out.println("Extracted layer: " + coords.layer());

		// With duration=10, scale = min(340/10, 1000) = 34
		// time = x / scale / temporalScale = -193.811 / 34 / 1.0 = -5.7
		double expectedScale = Math.min(340.0 / 10.0, 1000);
		double expectedTime = mousePosition.getX() / expectedScale;
		System.out.println("Expected scale: " + expectedScale);
		System.out.println("Expected time: " + expectedTime);

		// This test documents the current behavior - time is negative
		Assert.assertTrue("Time should be negative for negative X", coords.time() < 0);
		System.out.println("ISSUE: Negative time values are skipped in applyValues()");
	}

	/**
	 * Tests what X position is needed for valid time indices.
	 */
	@Test
	public void testRequiredXPositionForValidTime() {
		TemporalSpatialContext context = new TemporalSpatialContext();

		// Without duration set, what scale is used?
		System.out.println("=== Required X Position Analysis ===");

		// When duration is 0 (not set), scale defaults to 3
		// For time=0.5s: X = 0.5 * 1.0 * 3 = 1.5
		Vector pos = context.position(context.getSecondsToTime().applyAsDouble(0.5), 0, 0, 0.5);
		System.out.println("Position for time=0.5s, freq=0.5 (no duration): " + pos);

		// With duration set
		context.setDuration(10.0);
		pos = context.position(context.getSecondsToTime().applyAsDouble(0.5), 0, 0, 0.5);
		System.out.println("Position for time=0.5s, freq=0.5 (duration=10): " + pos);

		// Verify positions are positive for valid time
		Assert.assertTrue("X should be positive for positive time", pos.getX() > 0);

		// Calculate what X values map to our 100-frame canvas
		int timeFrames = 100;
		double freqSampleRate = 100;
		double maxValidTime = (timeFrames - 1) / freqSampleRate; // 0.99 seconds

		System.out.println("Max valid time for 100 frames at 100Hz: " + maxValidTime + "s");

		// What X position gives us this time?
		Vector maxPos = context.position(context.getSecondsToTime().applyAsDouble(maxValidTime), 0, 0, 0.5);
		System.out.println("X position for max valid time: " + maxPos.getX());

		// Min valid X (time=0)
		Vector minPos = context.position(context.getSecondsToTime().applyAsDouble(0), 0, 0, 0.5);
		System.out.println("X position for time=0: " + minPos.getX());

		System.out.println("\nVALID X RANGE: [" + minPos.getX() + ", " + maxPos.getX() + "]");
		System.out.println("Mouse position X=-193.811 is WAY outside this range!");
	}

	/**
	 * Tests the full round-trip: apply values → elements() → verify regeneration.
	 */
	@Test
	public void testFullDrawingRoundTrip() {
		int timeFrames = 100;
		int frequencyBins = 256;
		double sampleRate = 44100;
		double freqSampleRate = 100;

		EditableSpatialWaveDetails canvas = new EditableSpatialWaveDetails(
				timeFrames, frequencyBins, sampleRate, freqSampleRate);

		TemporalSpatialContext context = new TemporalSpatialContext();
		// Duration = timeFrames / freqSampleRate = 100 / 100 = 1.0 seconds
		context.setDuration(timeFrames / freqSampleRate);

		System.out.println("=== Full Round Trip Test ===");

		// Create a value at the CENTER of the canvas (time=0.5s, freq=0.5)
		double targetTime = 0.5;
		double targetFreq = 0.5;
		double internalTime = context.getSecondsToTime().applyAsDouble(targetTime);
		Vector position = context.position(internalTime, 0, 0, targetFreq);

		System.out.println("Target: time=" + targetTime + "s, freq=" + targetFreq);
		System.out.println("Created position: " + position);

		// Verify coordinates round-trip
		TemporalSpatialContext.TemporalCoordinates coords = context.inverse(position);
		System.out.println("Inverse coords: time=" + coords.time() + ", freq=" + coords.frequency());

		int expectedFrame = (int) (coords.time() * freqSampleRate);
		int expectedBin = (int) (coords.frequency() * frequencyBins);
		System.out.println("Expected frame: " + expectedFrame + ", expected bin: " + expectedBin);

		Assert.assertTrue("Frame should be valid", expectedFrame >= 0 && expectedFrame < timeFrames);
		Assert.assertTrue("Bin should be valid", expectedBin >= 0 && expectedBin < frequencyBins);

		// Create a high-magnitude value to ensure visibility
		double targetMagnitude = 100.0; // Well above the threshold of 35
		double logValue = Math.log(targetMagnitude + 1);
		System.out.println("Value to apply: log(" + targetMagnitude + "+1) = " + logValue);

		List<SpatialValue<?>> inputValues = List.of(
				new SpatialValue<>(position, logValue, 0.5, true)
		);

		// Apply to canvas
		canvas.applyValues(inputValues, context);
		Assert.assertTrue("Canvas should be modified", canvas.isModified());

		// Check what was written
		PackedCollection freqData = canvas.getWave().getFreqData();
		int dataIndex = expectedFrame * frequencyBins + expectedBin;
		double writtenValue = freqData.toDouble(dataIndex);
		System.out.println("Written to freqData[" + dataIndex + "]: " + writtenValue);

		// Verify non-zero
		Assert.assertTrue("Written value should be non-zero", writtenValue > 0);

		// Now get elements back from the canvas
		List<SpatialValue> regeneratedElements = canvas.elements(context);
		System.out.println("Regenerated " + regeneratedElements.size() + " elements");

		// There should be at least one element
		Assert.assertTrue("Should regenerate at least one element", regeneratedElements.size() > 0);

		// Find elements near our target position
		boolean foundNearTarget = false;
		for (SpatialValue element : regeneratedElements) {
			double dist = distanceTo(element.getPosition(), position);
			if (dist < 20) {
				System.out.println("Found element near target: " + element.getPosition() +
						", value=" + element.getValue() + ", distance=" + dist);
				foundNearTarget = true;
			}
		}

		Assert.assertTrue("Should find regenerated element near target position", foundNearTarget);
	}

	/**
	 * Tests that brush strokes produce values that survive the round-trip.
	 */
	@Test
	public void testBrushStrokeRoundTrip() {
		int timeFrames = 100;
		int frequencyBins = 256;
		double sampleRate = 44100;
		double freqSampleRate = 100;

		EditableSpatialWaveDetails canvas = new EditableSpatialWaveDetails(
				timeFrames, frequencyBins, sampleRate, freqSampleRate);

		TemporalSpatialContext context = new TemporalSpatialContext();
		context.setDuration(timeFrames / freqSampleRate);

		System.out.println("=== Brush Stroke Round Trip Test ===");

		// Create brush centered in valid range
		SphericalBrush brush = new SphericalBrush();
		brush.setRadius(10.0);
		brush.setDensity(100.0);

		// Center the brush at a valid position (NOT raw mouse coordinates)
		double targetTime = 0.5;
		double targetFreq = 0.5;
		Vector center = context.position(
				context.getSecondsToTime().applyAsDouble(targetTime),
				0, 0, targetFreq);

		System.out.println("Brush center (proper spatial coords): " + center);

		// Generate stroke
		List<SpatialValue<?>> strokeValues = brush.stroke(center, 1.0, 0.1);
		System.out.println("Generated " + strokeValues.size() + " stroke values");

		// Print some sample positions
		for (int i = 0; i < Math.min(5, strokeValues.size()); i++) {
			SpatialValue<?> v = strokeValues.get(i);
			TemporalSpatialContext.TemporalCoordinates coords = context.inverse(v.getPosition());
			System.out.println("  Value " + i + ": pos=" + v.getPosition() +
					" -> time=" + coords.time() + ", freq=" + coords.frequency() +
					", value=" + v.getValue());
		}

		// Apply to canvas
		canvas.applyValues(strokeValues, context);

		// Count how many non-zero cells we have
		PackedCollection freqData = canvas.getWave().getFreqData();
		int nonZeroCells = 0;
		for (int i = 0; i < freqData.getMemLength(); i++) {
			if (freqData.toDouble(i) > 0) {
				nonZeroCells++;
			}
		}
		System.out.println("Non-zero cells in freqData: " + nonZeroCells);

		// Should have written something
		Assert.assertTrue("Should have non-zero cells", nonZeroCells > 0);

		// Regenerate elements
		List<SpatialValue> regeneratedElements = canvas.elements(context);
		System.out.println("Regenerated " + regeneratedElements.size() + " elements");

		Assert.assertTrue("Should regenerate elements", regeneratedElements.size() > 0);
	}

	/**
	 * Documents the coordinate transformation using visualization origin.
	 *
	 * <p>Raw viewer coordinates must be transformed to canvas coordinates
	 * by subtracting the visualization origin offset. This offset is applied
	 * when rendering, so we reverse it to convert back to canvas space.</p>
	 */
	@Test
	public void testVisualizationOriginTransformation() {
		int timeFrames = 1000;  // 10 seconds at 100 Hz
		int frequencyBins = 256;
		double sampleRate = 44100;
		double freqSampleRate = 100;

		EditableSpatialWaveDetails canvas = new EditableSpatialWaveDetails(
				timeFrames, frequencyBins, sampleRate, freqSampleRate);

		TemporalSpatialContext context = new TemporalSpatialContext();
		context.setDuration(timeFrames / freqSampleRate);

		System.out.println("=== Visualization Origin Transformation Test ===");

		// The visualization origin offset (from SpatioTemporalUserExperience)
		// leftShift=180, upShift=30, origin = (-180, -70-30, 0) = (-180, -100, 0)
		Vector visualizationOrigin = new Vector(-180, -100, 0);
		System.out.println("Visualization origin: " + visualizationOrigin);

		// Raw viewer position (clicking near left edge of visualization)
		Vector rawMousePosition = new Vector(-154.304, 45.3553, 0.0);
		System.out.println("Raw viewer position: " + rawMousePosition);

		// Transform by subtracting visualization origin
		// canvasPosition = viewerPosition - visualizationOrigin
		Vector transformedPosition = new Vector(
				rawMousePosition.getX() - visualizationOrigin.getX(),
				rawMousePosition.getY() - visualizationOrigin.getY(),
				rawMousePosition.getZ() - visualizationOrigin.getZ()
		);
		System.out.println("Transformed position: " + transformedPosition);

		// Verify the transformation makes sense
		// X: -154.304 - (-180) = 25.696 (near left edge, positive)
		// Y: 45.3553 - (-100) = 145.3553 (mid-height in frequency range)
		Assert.assertTrue("Transformed X should be positive", transformedPosition.getX() > 0);

		// Now brush strokes at the transformed position should succeed
		SphericalBrush brush = new SphericalBrush();
		brush.setRadius(10.0);
		brush.setDensity(100.0);

		List<SpatialValue<?>> strokeValues = brush.stroke(transformedPosition, 1.0, 0.1);
		System.out.println("Generated " + strokeValues.size() + " stroke values");

		// Count valid positions after transformation
		int validCount = 0;
		for (SpatialValue<?> v : strokeValues) {
			TemporalSpatialContext.TemporalCoordinates coords = context.inverse(v.getPosition());
			int frameIndex = (int) (coords.time() * freqSampleRate);
			int binIndex = (int) (coords.frequency() * frequencyBins);

			boolean frameValid = frameIndex >= 0 && frameIndex < timeFrames;
			boolean binValid = binIndex >= 0 && binIndex < frequencyBins;

			if (frameValid && binValid) {
				validCount++;
			}
		}

		System.out.println("Valid values after transformation: " + validCount);

		// Most values should now be valid (some may be outside due to brush radius)
		Assert.assertTrue("Most values should be valid after transformation",
				validCount > strokeValues.size() / 2);

		// Apply to canvas
		canvas.applyValues(strokeValues, context);

		// Count non-zero cells - should have written some data
		PackedCollection freqData = canvas.getWave().getFreqData();
		int nonZeroCells = 0;
		for (int i = 0; i < freqData.getMemLength(); i++) {
			if (freqData.toDouble(i) > 0) {
				nonZeroCells++;
			}
		}
		System.out.println("Non-zero cells in freqData: " + nonZeroCells);

		Assert.assertTrue("Should have non-zero cells after transformed strokes", nonZeroCells > 0);
	}

	private double distanceTo(Vector a, Vector b) {
		return Math.sqrt(
				Math.pow(a.getX() - b.getX(), 2) +
				Math.pow(a.getY() - b.getY(), 2) +
				Math.pow(a.getZ() - b.getZ(), 2)
		);
	}
}
