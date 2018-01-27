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

package org.almostrealism.graph.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;

import org.almostrealism.algebra.Vector;
import org.almostrealism.graph.Mesh;

import com.almostrealism.raytracer.primitives.ObjPolygon;

/**
 * The {@link WavefrontObjParser} is used to parse 3d objects stored in
 * the Wavefront OBJ file format. Not all features of the file format are
 * supported.
 * 
 * @author  Michael Murray
 */
public class WavefrontObjParser {
	private BufferedReader reader;
	private ArrayList<float[]> vertices;
	private ArrayList faces, texCoords;
	private ArrayList<int[]> triangles;
	
	public WavefrontObjParser(InputStream in) {
		this.reader = new BufferedReader(new InputStreamReader(in));
		this.vertices = new ArrayList();
		this.texCoords = new ArrayList();
		this.faces = new ArrayList();
		this.triangles = new ArrayList<int[]>();
	}
	
	public void parse() throws IOException {
		w: while (true) {
			String line = this.reader.readLine();
			if (line == null) break w;
			
			if (line.startsWith("v ")) {
				String s[] = line.split(" ");
				float f[] = new float[3];
				f[0] = Float.parseFloat(s[1]);
				f[1] = Float.parseFloat(s[2]);
				f[2] = Float.parseFloat(s[3]);
				this.vertices.add(f);
			} else if (line.startsWith("vt ")) {
				String s[] = line.split(" ");
				float f[] = new float[3];
				f[0] = Float.parseFloat(s[1]);
				f[1] = Float.parseFloat(s[2]);
				this.texCoords.add(f);
			} else if (line.startsWith("f ")) {
				String s[] = line.split(" ");
				int t[] = new int[3];
				
				ArrayList faceVerts = new ArrayList();
				ArrayList faceTexCoords = new ArrayList();
				
				for (int i = 1; i < s.length; i++) {
					String l[] = s[i].split("/");
					int vertIndex = Integer.parseInt(l[0]) - 1;
					
					if (i > 3) {
						System.out.println("Non triangular vertex encountered");
					} else {
						t[i - 1] = vertIndex;
					}
					
					float vertex[] = (float[]) this.vertices.get(vertIndex);
					faceVerts.add(vertex);
					
					if (l.length > 1 && l[1].length() > 0) {
						float texCoord[] = (float[]) this.texCoords.get(Integer.parseInt(l[1]) - 1);
						faceTexCoords.add(texCoord);
					} else {
						float texCoord[] = new float[] {0.0f, 0.0f};
						faceTexCoords.add(texCoord);
					}
				}
				
				this.triangles.add(t);
				
				ObjPolygon face = new ObjPolygon();
				face.setVertices((float[][]) faceVerts.toArray(new float[0][0]));
				face.setTexCoords((float[][]) faceTexCoords.toArray(new float[0][0]));
				this.faces.add(face);
			}
		}
	}
	
	public ObjPolygon[] getFaces() {
		return (ObjPolygon[]) this.faces.toArray(new ObjPolygon[0]);
	}
	
	public Mesh getMesh() {
		Vector v[] = new Vector[vertices.size()];
		for (int i = 0; i < v.length; i++) {
			float f[] = vertices.get(i);
			v[i] = new Vector(f);
		}
		
		return new Mesh(v, triangles.toArray(new int[0][0]));
	}
	
	public static Mesh parse(InputStream in) throws IOException {
		WavefrontObjParser p = new WavefrontObjParser(in);
		p.parse();
		return p.getMesh();
	}
}
