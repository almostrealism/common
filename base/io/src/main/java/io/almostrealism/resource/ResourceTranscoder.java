/*
 * Copyright 2018 Michael Murray
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

package io.almostrealism.resource;

import java.io.IOException;

/**
 * Converts a {@link Resource} of type {@code IN} to a {@link Resource} of type {@code OUT}.
 *
 * <p>Implementations may, for example, decode a binary image resource into a structured
 * data resource, or re-encode data in a different format.</p>
 *
 * @param <IN>  The input resource type
 * @param <OUT> The output resource type
 */
public interface ResourceTranscoder<IN extends Resource, OUT extends Resource> {
	/**
	 * Converts the given input resource to the output resource type.
	 *
	 * @param r The input resource to transcode
	 * @return The transcoded output resource
	 * @throws IOException If reading or writing during transcoding fails
	 */
	OUT transcode(IN r) throws IOException;
}
