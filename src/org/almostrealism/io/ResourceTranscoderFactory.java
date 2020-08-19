/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.io;

import io.almostrealism.js.JsonResource;
import org.almostrealism.graph.mesh.MeshResource;
import org.almostrealism.graph.io.GtsResource;
import org.almostrealism.graph.io.ObjResource;
import org.almostrealism.graph.io.PlyResource;
import org.almostrealism.graph.io.RawResource;
import org.almostrealism.space.SceneResource;
import org.almostrealism.util.Factory;

/**
 * {@link ResourceTranscoderFactory} is used to retrieve a {@link ResourceTranscoder} for a particular
 * conversion from one type to another.
 *
 * @param <IN>  Input resource type.
 * @param <OUT>  Output resource type.
 */
public class ResourceTranscoderFactory<IN extends Resource, OUT extends Resource> implements Factory<ResourceTranscoder<IN, OUT>> {
	private Class<IN> inType;
	private Class<OUT> outType;

	public ResourceTranscoderFactory(Class<IN> inputType, Class<OUT> outputType) {
		this.inType = inputType;
		this.outType = outputType;
	}

	@Override
	public ResourceTranscoder<IN, OUT> construct() {
		// TODO  This should probably be done with reflection so it doesn't have to be kept up to date by hand

		// TODO  There are many more of these types
		if (inType == MeshResource.class) {
			if (outType == JsonResource.class) {
				return (ResourceTranscoder<IN, OUT>) new MeshResource.JsonTranscoder();
			} else if (outType == GtsResource.class) {
				return (ResourceTranscoder<IN, OUT>) new GtsResource.MeshTranscoder();
			} else if (outType == ObjResource.class) {
				return (ResourceTranscoder<IN, OUT>) new ObjResource.MeshTranscoder();
			} else if (outType == PlyResource.class) {
				return (ResourceTranscoder<IN, OUT>) new PlyResource.MeshTranscoder();
			}
		} else if (inType == GtsResource.class) {
			if (outType == MeshResource.class) {
				return (ResourceTranscoder<IN, OUT>) new GtsResource.MeshReader();
			}
		} else if (inType == ObjResource.class) {
			if (outType == MeshResource.class) {
				return (ResourceTranscoder<IN, OUT>) new ObjResource.MeshReader();
			}
		} else if (inType == PlyResource.class) {
			if (outType == MeshResource.class) {
				return (ResourceTranscoder<IN, OUT>) new PlyResource.MeshReader();
			}
		} else if (inType == RawResource.class) {
			if (outType == SceneResource.class) {
				return (ResourceTranscoder<IN, OUT>) new RawResource.SceneReader();
			}
		} else if (inType == SceneResource.class) {
			if (outType == RawResource.class) {
				return (ResourceTranscoder<IN, OUT>) new RawResource.SceneTranscoder();
			}
		}

		return null;
	}
}
