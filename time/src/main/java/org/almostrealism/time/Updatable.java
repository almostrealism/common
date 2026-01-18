/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.time;

/**
 * Represents an object that can be updated periodically at a specific resolution.
 *
 * <p>The {@link Updatable} interface defines a contract for objects that maintain internal
 * state and need periodic updates, typically in simulation, animation, or real-time
 * processing contexts.</p>
 *
 * <h2>Purpose</h2>
 * <p>{@link Updatable} provides a standardized way to:</p>
 * <ul>
 *   <li>Trigger state updates at regular intervals</li>
 *   <li>Query the update frequency/resolution</li>
 *   <li>Coordinate updates across multiple components</li>
 * </ul>
 *
 * <h2>Usage Patterns</h2>
 *
 * <h3>Basic Implementation</h3>
 * <pre>{@code
 * public class AnimatedSprite implements Updatable {
 *     private int frame = 0;
 *     private final int frameRate = 60;  // 60 FPS
 *
 *     @Override
 *     public void update() {
 *         frame = (frame + 1) % totalFrames;
 *         // Update sprite state...
 *     }
 *
 *     @Override
 *     public int getResolution() {
 *         return frameRate;  // Updates per second
 *     }
 * }
 * }</pre>
 *
 * <h3>Update Loop</h3>
 * <pre>{@code
 * Updatable object = ...;
 * int resolution = object.getResolution();
 *
 * // Schedule updates at the object's resolution
 * ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
 * scheduler.scheduleAtFixedRate(
 *     object::update,
 *     0,
 *     1000 / resolution,  // Convert resolution to milliseconds
 *     TimeUnit.MILLISECONDS
 * );
 * }</pre>
 *
 * <h2>Resolution Semantics</h2>
 * <p>The {@link #getResolution()} value typically represents:</p>
 * <ul>
 *   <li><strong>Updates per second:</strong> For frame rates or sample rates</li>
 *   <li><strong>Time steps:</strong> For discrete-time simulations</li>
 *   <li><strong>Granularity level:</strong> For hierarchical update scheduling</li>
 * </ul>
 *
 * <h2>Integration with Temporal Framework</h2>
 * <p>While {@link Updatable} predates {@link Temporal}, they serve related purposes:</p>
 * <ul>
 *   <li><strong>{@link Temporal}:</strong> Modern approach with hardware acceleration support</li>
 *   <li><strong>{@link Updatable}:</strong> Simpler interface for basic periodic updates</li>
 * </ul>
 *
 * <p>For new code, prefer {@link Temporal} for better integration with the AlmostRealism
 * framework's hardware acceleration capabilities.</p>
 *
 * @see Temporal
 *
 * @author Michael Murray
 */
public interface Updatable {
	/**
	 * Performs one update of this object's state.
	 *
	 * <p>This method is called periodically to advance the object's internal state.
	 * Implementations should be lightweight and complete quickly to avoid blocking
	 * the update loop.</p>
	 *
	 * <h3>Implementation Guidelines</h3>
	 * <ul>
	 *   <li>Keep updates deterministic and idempotent where possible</li>
	 *   <li>Avoid blocking I/O or long-running operations</li>
	 *   <li>Consider thread safety if updates may occur concurrently</li>
	 * </ul>
	 */
	void update();

	/**
	 * Returns the resolution at which this object should be updated.
	 *
	 * <p>The resolution typically represents the number of updates per second,
	 * though the exact interpretation is implementation-specific.</p>
	 *
	 * <h3>Common Values</h3>
	 * <ul>
	 *   <li><strong>60:</strong> Standard frame rate (60 FPS)</li>
	 *   <li><strong>44100:</strong> Audio sample rate (44.1 kHz)</li>
	 *   <li><strong>1000:</strong> Millisecond precision</li>
	 * </ul>
	 *
	 * @return The update resolution, typically in updates per second
	 */
	int getResolution();
}
