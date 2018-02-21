/*
 * Copyright 2017 Michael Murray
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

import java.beans.ExceptionListener;
import java.beans.XMLDecoder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Stack;

import org.almostrealism.algebra.DiscreteField;
import org.almostrealism.algebra.Vector;
import org.almostrealism.color.ColorProducer;
import org.almostrealism.color.RGB;
import org.almostrealism.color.Shader;
import org.almostrealism.color.ShaderContext;
import org.almostrealism.graph.Mesh;
import org.almostrealism.graph.Triangle;
import org.almostrealism.graph.io.GtsResource;
import org.almostrealism.graph.io.ObjResource;
import org.almostrealism.graph.io.PlyResource;
import org.almostrealism.graph.io.RawResource;
import org.almostrealism.space.*;

/**
 * @author  Michael Murray
 */
public class SpatialData {
	/** The integer code for a GTS encoding. */
	public static final int GTSEncoding = 8;

	/** The integer code for a ply encoding. */
	public static final int PLYEncoding = 16;

	/** The integer code for an obj encoding. */
	public static final int OBJEncoding = 32;

	public static Scene decodeScene(InputStream fileIn, int encoding,
			boolean ui, ExceptionListener listener) throws IOException {
		return SpatialData.decodeScene(fileIn, encoding, ui, listener, null);
	}

	/**
	 * Decodes the scene data read from the specified InputStream object using the encoding specified by
	 * the integer encoding code and returns the new Scene object. If ui is true only SurfaceUI objects will be used.
	 * The specified ExceptionListener is notified if an exception occurs when using an XMLDecoder.
	 * This method returns null if the encoding is not supported.
	 */
	public static Scene decodeScene(InputStream fileIn, int encoding, boolean ui, ExceptionListener listener, ShadableSurface s) throws IOException {
		if (encoding == FileDecoder.XMLEncoding) {
			XMLDecoder decoder = new XMLDecoder(fileIn);
			decoder.setExceptionListener(listener);
			
			Scene scene = (Scene) decoder.readObject();
			
			if (!ui) {
				Gradient sr[] = scene.getSurfaces();
				
				for (int i = 0; i < sr.length; i++)
					if (sr[i] instanceof ShadableSurfaceWrapper)
						sr[i] = ((ShadableSurfaceWrapper) sr[i]).getSurface();
			}

			// TODO  After the scene is loaded, the camera needs the UVW computation
			//       performed and all surfaces need their transform matrix computed

			return scene;
		} else if (encoding == FileDecoder.RAWEncoding) {
			if (ui == true) {
				System.out.println("FileDecoder: UI mode no longer supported.");
			}

			RawResource r = new RawResource();
			r.load(new IOStreams(fileIn));
			RawResource.SceneReader reader = new RawResource.SceneReader();
			return reader.transcode(r).getScene();
		} else if (encoding == GTSEncoding) {
			if (ui == true) {
				System.out.println("FileDecoder: UI mode no longer supported.");
				//				AbstractSurfaceUI sr[] = {SurfaceUIFactory.createSurfaceUI(m)};
				//				sr[0].setName("Mesh (" + m.getTriangles().length + " Triangles)");
				//				return new Scene(sr);
			}

			GtsResource r = new GtsResource();
			r.load(new IOStreams(fileIn));
			GtsResource.MeshReader reader = new GtsResource.MeshReader();
			if (s instanceof Mesh) reader.setInitialMesh((Mesh) s);
			return new Scene(new ShadableSurface[] { reader.transcode(r).getMesh() });
		} else if (encoding == PLYEncoding) {
			if (ui == true) {
				System.out.println("SpatialData: UI mode no longer supported.");
				//				AbstractSurfaceUI sr[] = {SurfaceUIFactory.createSurfaceUI(m)};
				//				sr[0].setName("Mesh (" + m.getTriangles().length + " Triangles)");
				//				return new Scene(sr);
			}

			PlyResource r = new PlyResource();
			r.load(new IOStreams(fileIn));
			PlyResource.MeshReader reader = new PlyResource.MeshReader();
			if (s instanceof Mesh) reader.setInitialMesh((Mesh) s);
			return new Scene(new ShadableSurface[] { reader.transcode(r).getMesh() });
		} else if (encoding == OBJEncoding) {
			ObjResource r = new ObjResource();
			r.load(new IOStreams(fileIn));
			ObjResource.MeshReader reader = new ObjResource.MeshReader();

			if (s instanceof Mesh) {
				System.out.println("SpatialData: Initial Mesh is not used by ObjResource");
			}

			return new Scene(new ShadableSurface[] { reader.transcode(r).getMesh() });
		} else {
			return null;
		}
	}
}
