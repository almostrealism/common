package org.almostrealism.graph.io;

import org.almostrealism.algebra.Vector;
import org.almostrealism.graph.Mesh;
import org.almostrealism.graph.MeshResource;
import org.almostrealism.io.ResourceTranscoder;
import org.almostrealism.io.UnicodeResource;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;

public class ObjResource extends UnicodeResource {
	public ObjResource() { }

	public ObjResource(File f) throws IOException { super(f); }

	public static class MeshTranscoder implements ResourceTranscoder<MeshResource, ObjResource> {
		@Override
		public ObjResource transcode(MeshResource r) {
			return null;
		}
	}
	
	public static class MeshReader implements ResourceTranscoder<ObjResource, MeshResource> {
		@Override
		public MeshResource transcode(ObjResource r) throws IOException {
			BufferedReader reader = new BufferedReader(new InputStreamReader(r.getInputStream()));

			ArrayList<float[]> vertices = new ArrayList<>();
			ArrayList<ObjPolygon> faces = new ArrayList<>();
			ArrayList<float[]> texCoords = new ArrayList<>();
			ArrayList<int[]> triangles = new ArrayList<int[]>();
			
			w: while (true) {
				String line = reader.readLine();
				if (line == null) break w;
				
				if (line.startsWith("v ")) {
					String s[] = line.split(" ");
					float f[] = new float[3];
					f[0] = Float.parseFloat(s[1]);
					f[1] = Float.parseFloat(s[2]);
					f[2] = Float.parseFloat(s[3]);
					vertices.add(f);
				} else if (line.startsWith("vt ")) {
					String s[] = line.split(" ");
					float f[] = new float[3];
					f[0] = Float.parseFloat(s[1]);
					f[1] = Float.parseFloat(s[2]);
					texCoords.add(f);
				} else if (line.startsWith("f ")) {
					String s[] = line.split(" ");
					int t[] = new int[3];
					
					ArrayList faceVerts = new ArrayList();
					ArrayList faceTexCoords = new ArrayList();
					
					for (int i = 1; i < s.length; i++) {
						String l[] = s[i].split("/");
						int vertIndex = Integer.parseInt(l[0]) - 1;
						
						if (i > 3) {
							System.out.println("Non triangular face encountered");
						} else {
							t[i - 1] = vertIndex;
						}
						
						float vertex[] = (float[]) vertices.get(vertIndex);
						faceVerts.add(vertex);
						
						if (l.length > 1 && l[1].length() > 0) {
							float texCoord[] = (float[]) texCoords.get(Integer.parseInt(l[1]) - 1);
							faceTexCoords.add(texCoord);
						} else {
							float texCoord[] = new float[] {0.0f, 0.0f};
							faceTexCoords.add(texCoord);
						}
					}
					
					triangles.add(t);
					
					ObjPolygon face = new ObjPolygon();
					face.setVertices((float[][]) faceVerts.toArray(new float[0][0]));
					face.setTexCoords((float[][]) faceTexCoords.toArray(new float[0][0]));
					faces.add(face);
				}
			}
			
			Vector v[] = new Vector[vertices.size()];
			for (int i = 0; i < v.length; i++) {
				float f[] = vertices.get(i);
				v[i] = new Vector(f);
			}
			
			return new MeshResource(new Mesh(v, triangles.toArray(new int[0][0])));
		}
	}
}
