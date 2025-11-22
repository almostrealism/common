/*
 * Copyright 2016 Michael Murray
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

package org.almostrealism.io;

/**
 * Interface for objects that need to perform post-processing after being decoded.
 *
 * <p>This interface is used as a callback mechanism during deserialization or
 * decoding processes. After an object has been fully reconstructed from its
 * serialized form, the {@link #afterDecoding()} method is called to allow
 * the object to perform any necessary initialization or validation that
 * depends on having all fields populated.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public class MyDecodable implements DecodePostProcessing {
 *     private transient Cache cache;  // Not serialized
 *     private List<String> items;
 *
 *     @Override
 *     public void afterDecoding() {
 *         // Rebuild transient fields after deserialization
 *         this.cache = buildCacheFrom(items);
 *     }
 * }
 * }</pre>
 */
public interface DecodePostProcessing {
	/**
	 * Called after the object has been decoded/deserialized.
	 * Implementations should use this to initialize transient fields
	 * or perform validation that requires all fields to be populated.
	 */
	void afterDecoding();
}
