__kernel void scalarCopy_local(__local float *res, __local const float *m, const int resOffset, const int mOffset) {
    res[resOffset]     = m[mOffset];
    res[resOffset + 1] = m[mOffset + 1];
}

__kernel void scalarCopy_localToGlobal(__global float *res, __local const float *m, const int resOffset, const int mOffset) {
    res[resOffset]     = m[mOffset];
    res[resOffset + 1] = m[mOffset + 1];
}

__kernel void scalarCopy_globalToLocal(__local float *res, __global const float *m, const int resOffset, const int mOffset) {
    res[resOffset]     = m[mOffset];
    res[resOffset + 1] = m[mOffset + 1];
}

__kernel void rayCopy_local(__local float *res, __local const float *m, const int resOffset, const int mOffset) {
    res[resOffset]     = m[mOffset];
    res[resOffset + 1] = m[mOffset + 1];
    res[resOffset + 2] = m[mOffset + 2];
    res[resOffset + 3] = m[mOffset + 3];
    res[resOffset + 4] = m[mOffset + 4];
    res[resOffset + 5] = m[mOffset + 5];
}

__kernel void rayCopy_localToGlobal(__global float *res, __local const float *m, const int resOffset, const int mOffset) {
    res[resOffset]     = m[mOffset];
    res[resOffset + 1] = m[mOffset + 1];
    res[resOffset + 2] = m[mOffset + 2];
    res[resOffset + 3] = m[mOffset + 3];
    res[resOffset + 4] = m[mOffset + 4];
    res[resOffset + 5] = m[mOffset + 5];
}

__kernel void rayCopy_globalToLocal(__local float *res, __global const float *m, const int resOffset, const int mOffset) {
    res[resOffset]     = m[mOffset];
    res[resOffset + 1] = m[mOffset + 1];
    res[resOffset + 2] = m[mOffset + 2];
    res[resOffset + 3] = m[mOffset + 3];
    res[resOffset + 4] = m[mOffset + 4];
    res[resOffset + 5] = m[mOffset + 5];
}

__kernel void matrixCopy_local(__local float *res, __local const float *m, const int resOffset, const int mOffset) {
    res[resOffset]     = m[mOffset];
    res[resOffset + 1] = m[mOffset + 1];
    res[resOffset + 2] = m[mOffset + 2];
    res[resOffset + 3] = m[mOffset + 3];

    res[resOffset + 4] = m[mOffset + 4];
    res[resOffset + 5] = m[mOffset + 5];
    res[resOffset + 6] = m[mOffset + 6];
    res[resOffset + 7] = m[mOffset + 7];

    res[resOffset + 8]  = m[mOffset + 8];
    res[resOffset + 9]  = m[mOffset + 9];
    res[resOffset + 10] = m[mOffset + 10];
    res[resOffset + 11] = m[mOffset + 11];

    res[resOffset + 12] = m[mOffset + 12];
    res[resOffset + 13] = m[mOffset + 13];
    res[resOffset + 14] = m[mOffset + 14];
    res[resOffset + 15] = m[mOffset + 15];
}

__kernel void matrixCopy_localToGlobal(__global float *res, __local const float *m, const int resOffset, const int mOffset) {
    res[resOffset]     = m[mOffset];
    res[resOffset + 1] = m[mOffset + 1];
    res[resOffset + 2] = m[mOffset + 2];
    res[resOffset + 3] = m[mOffset + 3];

    res[resOffset + 4] = m[mOffset + 4];
    res[resOffset + 5] = m[mOffset + 5];
    res[resOffset + 6] = m[mOffset + 6];
    res[resOffset + 7] = m[mOffset + 7];

    res[resOffset + 8]  = m[mOffset + 8];
    res[resOffset + 9]  = m[mOffset + 9];
    res[resOffset + 10] = m[mOffset + 10];
    res[resOffset + 11] = m[mOffset + 11];

    res[resOffset + 12] = m[mOffset + 12];
    res[resOffset + 13] = m[mOffset + 13];
    res[resOffset + 14] = m[mOffset + 14];
    res[resOffset + 15] = m[mOffset + 15];
}

__kernel void matrixCopy_globalToLocal(__local float *res, __global const float *m, const int resOffset, const int mOffset) {
    res[resOffset]     = m[mOffset];
    res[resOffset + 1] = m[mOffset + 1];
    res[resOffset + 2] = m[mOffset + 2];
    res[resOffset + 3] = m[mOffset + 3];

    res[resOffset + 4] = m[mOffset + 4];
    res[resOffset + 5] = m[mOffset + 5];
    res[resOffset + 6] = m[mOffset + 6];
    res[resOffset + 7] = m[mOffset + 7];

    res[resOffset + 8]  = m[mOffset + 8];
    res[resOffset + 9]  = m[mOffset + 9];
    res[resOffset + 10] = m[mOffset + 10];
    res[resOffset + 11] = m[mOffset + 11];

    res[resOffset + 12] = m[mOffset + 12];
    res[resOffset + 13] = m[mOffset + 13];
    res[resOffset + 14] = m[mOffset + 14];
    res[resOffset + 15] = m[mOffset + 15];
}

__kernel void
add(__global float *res, __global const float *a, __global const float *b,
    const int resOffset, const int aOffset, const int bOffset,
    const int resSize, const int aSize, const int bSize) {
	res[resOffset] = a[aOffset] + b[bOffset];
	res[resOffset + 1] = a[aOffset + 1] + b[bOffset + 1];
	res[resOffset + 2] = a[aOffset + 2] + b[bOffset + 2];
}

__kernel void
subtract(__global float *res, __global const float *a, __global const float *b,
        const int resOffset, const int aOffset, const int bOffset,
        const int resSize, const int aSize, const int bSize) {
    res[resOffset] = a[aOffset] - b[bOffset];
    res[resOffset + 1] = a[aOffset + 1] - b[bOffset + 1];
    res[resOffset + 2] = a[aOffset + 2] - b[bOffset + 2];
}

__kernel void
multiply(__global float *res, __global const float *a, __global const float *b,
        const int resOffset, const int aOffset, const int bOffset,
        const int resSize, const int aSize, const int bSize) {
	res[resOffset] = a[aOffset] * b[bOffset];
	res[resOffset + 1] = a[aOffset + 1] * b[bOffset + 1];
	res[resOffset + 2] = a[aOffset + 2] * b[bOffset + 2];
}

__kernel void
divide(__global float *res, __global const float *a, __global const float *b,
        const int resOffset, const int aOffset, const int bOffset,
        const int resSize, const int aSize, const int bSize) {
	res[resOffset] = a[aOffset] / b[bOffset];
	res[resOffset + 1] = a[aOffset + 1] / b[bOffset + 1];
	res[resOffset + 2] = a[aOffset + 2] / b[bOffset + 2];
}

__kernel void
addTo(__global float *a, __global const float *b, const int aOffset, const int bOffset,
      const int aSize, const int bSize) {
	a[aOffset] += b[bOffset];
	a[aOffset + 1] += b[bOffset + 1];
	a[aOffset + 2] += b[bOffset + 2];
}

__kernel void
subtractFrom(__global float *a, __global const float *b, const int aOffset, const int bOffset,
            const int aSize, const int bSize) {
    a[aOffset] -= b[bOffset];
    a[aOffset + 1] -= b[bOffset + 1];
    a[aOffset + 2] -= b[bOffset + 2];
}

__kernel void
multiplyBy(__global float *a, __global const float *b, const int aOffset, const int bOffset,
           const int aSize, const int bSize) {
	a[aOffset] *= b[bOffset];
	a[aOffset + 1] *= b[bOffset + 1];
	a[aOffset + 2] *= b[bOffset + 2];
}

__kernel void
divideBy(__global float *a, __global const float *b, const int aOffset, const int bOffset,
         const int aSize, const int bSize) {
	a[aOffset] /= b[bOffset];
	a[aOffset + 1] /= b[bOffset + 1];
	a[aOffset + 2] /= b[bOffset + 2];
}

__kernel void
lengthSq(__global float *res, __global const float *v, const int resOffset, const int vOffset,
         const int resSize, const int vSize) {
    res[resOffset] = v[vOffset] * v[vOffset] + v[vOffset + 1] * v[vOffset + 1] + v[vOffset + 2] * v[vOffset + 2];
}

__kernel void
crossProduct(__global float *res, __global const float *a, __global const float *b,
            const int resOffset, const int aOffset, const int bOffset,
            const int resSize, const int aSize, const int bSize)
{
    res[resOffset] = a[aOffset + 1] * b[bOffset + 2] - a[aOffset + 2] * b[bOffset + 1];
    res[resOffset + 1] = a[aOffset + 2] * b[bOffset] - a[aOffset] * b[bOffset + 2];
    res[resOffset + 2] = a[aOffset] * b[bOffset + 1] - a[aOffset + 1] * b[bOffset];
}

__kernel void
rayPointAt(__global float *res, __global float *r, __global float *t,
            const int resOffset, const int rOffset, const int tOffset,
            const int resSize, const int rSize, const int tSize) {
    res[resOffset] = r[rOffset] + t[tOffset] * r[rOffset + 3];
    res[resOffset + 1] = r[rOffset + 1] + t[tOffset] * r[rOffset + 4];
    res[resOffset + 2] = r[rOffset + 2] + t[tOffset] * r[rOffset + 5];
}

__kernel void
matrixProduct(__global float *res, __global const float *a, __global const float *b,
                const int resOffset, const int aOffset, const int bOffset,
                const int resSize, const int aSize, const int bSize) {
    for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 4; j++) {
            res[resOffset + j * 4 + i] = a[aOffset + j * 4] * 	b[bOffset + i] +
                             a[aOffset + j * 4 + 1] 	    * 	b[bOffset + 4 + i] +
    						 a[aOffset + j * 4 + 2] 	    *   b[bOffset + 8 + i] +
    						 a[aOffset + j * 4 + 3] 	    * 	b[bOffset + 12 + i];
    	}
    }
}

__kernel void
identityMatrix_local(__local float *m, const int mOffset) {
    m[mOffset]     =  1.0;
    m[mOffset + 1] =  0.0;
    m[mOffset + 2] =  0.0;
    m[mOffset + 3] =  0.0;

    m[mOffset + 4] =  0.0;
    m[mOffset + 5] =  1.0;
    m[mOffset + 6] =  0.0;
    m[mOffset + 7] =  0.0;

    m[mOffset + 8]  =  0.0;
    m[mOffset + 9]  =  0.0;
    m[mOffset + 10] =  1.0;
    m[mOffset + 11] =  0.0;

    m[mOffset + 12] =  0.0;
    m[mOffset + 13] =  0.0;
    m[mOffset + 14] =  0.0;
    m[mOffset + 15] =  1.0;
}

__kernel void
identityMatrix(__global float *m, const int mOffset, const int mSize) {
    __local float m_l[16];
    identityMatrix_local(m_l, 0);
    matrixCopy_localToGlobal(m, m_l, mOffset, 0);
}

__kernel void
matrixTranspose_local(__local float *res, __local const float *m, const int resOffset, const int mOffset) {
    for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 4; j++) {
            res[resOffset + i * 4 + j] = m[j * 4 + i];
        }
    }
}

__kernel void
matrixTranspose(__global float *res, __global const float *m, const int resOffset, const int mOffset,
                const int resSize, const int mSize) {
    __local float m_l[16];
    __local float res_l[16];
    matrixCopy_globalToLocal(m_l, m, 0, mOffset);
    matrixTranspose_local(res_l, m_l, 0, 0);
    matrixCopy_localToGlobal(res, res_l, resOffset, 0);
}

__kernel void
matrixToUpperTriangle(__local float *res, __local float *df, __local const float *m,
                        const int resOffset, const int dfOffset, const int mOffset) {
    matrixCopy_local(res, m, resOffset, mOffset);

    float f1 = 0;
    float temp = 0;
    int v = 1;

    df[dfOffset] = 1;

    for (int col = 0; col < 3; col++) {
        for (int row = col + 1; row < 4; row++) {
            v = 1;

            bool done = false;

            while (res[col * 4 + col] == 0 && !done) {
                if (col + v >= 4) {
                    df[dfOffset] = 0;
                    done = true;
				} else {
					for (int c = 0; c < 4; c++) {
						temp = res[col * 4 + c];
						res[resOffset + col * 4 + c] = res[resOffset + (col + v) * 4 + c];
						res[resOffset + (col + v) * 4 + c] = temp;
					}

					v++;
					df[dfOffset] = df[dfOffset] * -1;
			    }
			}

			if (res[resOffset + col * 4 + col] != 0) {
				f1 = (-1) * res[resOffset + row * 4 + col] / res[resOffset + col * 4 + col];

				for (int i = col; i < 4; i++) {
					res[resOffset + row * 4 + i] = f1 * res[resOffset + col * 4 + i] + res[resOffset + row * 4 + i];
				}
			}
		}
	}
}

__kernel void
matrixDeterminant_local(__local float *res, __local float *df, __local float *ut,
                        __local float *m,
                        const int resOffset, const int dfOffset, const int utOffset,
                        const int mOffset) {
    float det = 1.0;

    matrixToUpperTriangle(ut, df, m, 0, dfOffset, mOffset);

    for (int i = 0; i < 4; i++) {
        det = det * ut[utOffset + i * 4 + i];
    }

	res[resOffset] = det * df[dfOffset];
}

__kernel void
matrixDeterminant(__global float *res, __global const float *m,
                  const int resOffset, const int mOffset,
                  const int resSize, const int mSize) {
    __local float res_l[2];
    __local float m_l[16];

    __local float df_l[2];
    __local float ut_l[16];

    matrixCopy_globalToLocal(m_l, m, 0, mOffset);
    matrixDeterminant_local(res_l, df_l, ut_l, m_l, 0, 0, 0, 0);
    scalarCopy_localToGlobal(res, res_l, resOffset, 0);
}

__kernel void
matrixAdjoint_local(__local float *res, __local float *det, __local float *df,
                    __local float *ut,
                    __local float *ap, __local float *adj, __local const float *m,
                    const int resOffset, const int detOffset, const int dfOffset,
                    const int utOffset,
                    const int apOffset, const int adjOffset, const int mOffset) {
    int ii, jj, ia, ja;

    for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 4; j++) {
            ia = ja = 0;

            identityMatrix_local(ap, apOffset);

            for (ii = 0; ii < 4; ii++) {
                for (jj = 0; jj < 4; jj++) {
                    if ((ii != i) && (jj != j)) {
                        ap[apOffset + ia * 4 + ja] = m[mOffset + ii * 4 + jj];
                        ja++;
                    }
                }

                if ((ii != i) && (jj != j)) { ia++; }
                ja = 0;
            }

            matrixDeterminant_local(det, df, ut, ap, detOffset, dfOffset, utOffset, apOffset);
            adj[i * 4 + j] =  pow((float) -1.0, (float) i + j) * (float) det[detOffset];
        }
    }

    matrixTranspose_local(res, adj, resOffset, adjOffset);
}

__kernel void
matrixAdjoint(__global float *res, __global const float *m,
                const int resOffset, const int mOffset,
                const int resSize, const int mSize) {
    __local float m_l[16];
    __local float res_l[16];

    __local float det_l[2];
    __local float df_l[2];
    __local float ut_l[16];
    __local float ap_l[16];
    __local float adj_l[16];

    matrixCopy_globalToLocal(m_l, m, 0, mOffset);
    matrixAdjoint_local(res_l, det_l, df_l, ut_l, ap_l, adj_l, m_l, 0, 0, 0, 0, 0, 0, 0);
    matrixCopy_localToGlobal(res, res_l, resOffset, 0);
}

__kernel void
translationMatrix(__global float *m, __global const float *v, const int mOffset, const int vOffset,
                    const int mSize, const int vSize) {
    m[mOffset + 3]  = v[vOffset];
    m[mOffset + 7]  = v[vOffset + 1];
    m[mOffset + 11] = v[vOffset + 2];
}

__kernel void
scaleMatrix(__global float *m, __global const float *v, const int mOffset, const int vOffset,
            const int mSize, const int vSize) {
    m[mOffset]  = v[vOffset];
    m[mOffset + 5]  = v[vOffset + 1];
    m[mOffset + 10] = v[vOffset + 2];
}

//
//__kernel void
//thinLensCameraRayAt(__global float *res, __global const float *pos, __global const float *sd,
//                    __global const float *rand,
//                    __global const float *l, __global const float *pd, __global const float *bl,
//                    __global const float *fl, __global const float *u, __global const float *v,
//                    __global const float *w,
//                    const int resOffset, const int posOffset, const int sdOffset, const int randOffset,
//                    const int lOffset, const int pdOffset, const int blOffset,
//                    const int flOffset, const int uOffset, const int vOffset, const int wOffset) {
//    float bu = pd[pdOffset] / 2;
//    float bv = pd[pdOffset + 1] / 2;
//    float au = -bu;
//    float av = -bv;
//
//    float p = au + (bu - au) * (pos[posOffset] / (sd[sdOffset] - 1));
//    float q = av + (bv - av) * (pos[posOffset + 1] / (sd[sdOffset + 1] - 1));
//    float r = -fl[flOffset];
//
//    res[resOffset + 3] = p * u[uOffset] + q * v[vOffset] + r * w[wOffset];
//    res[resOffset + 4] = p * u[uOffset + 1] + q * v[vOffset + 1] + r * w[wOffset + 1];
//    res[resOffset + 5] = p * u[uOffset + 2] + q * v[vOffset + 2] + r * w[wOffset + 2];
//
//    float len = sqrt(res[resOffset + 3] * res[resOffset + 3] +
//                    res[resOffset + 4] * res[resOffset + 4] +
//                    res[resOffset + 5] * res[resOffset + 5]);
//
//    if (bl[blOffset] != 0.0 || bl[blOffset + 1] != 0.0) {
//        float wx = res[resOffset + 3];
//        float wy = res[resOffset + 4];
//        float wz = res[resOffset + 5];
//
//        float tx = res[resOffset + 3];
//        float ty = res[resOffset + 4];
//        float tz = res[resOffset + 5];
//
//        if (tx < ty && ty < tz) {
//            tx = 1.0;
//        } else if (ty < tx && ty < tz) {
//            ty = 1.0;
//        } else {
//            tz = 1.0;
//        }
//
//        float wl = sqrt(wx * wx + wy * wy + wz * wz);
//        wx = wx / wl;
//        wy = wy / wl;
//        wz = wz / wl;
//
//        float ux = ty * wz - tz * wy;
//        float uy = tz * wx - tx * wz;
//        float uz = tx * wy - ty * wx;
//
//        float ul = sqrt(ux * ux + uy * uy + uz * uz);
//        ux = ux / ul;
//        uy = uy / ul;
//        uz = uz / ul;
//
//        float vx = wy * uz - wz * uy;
//        float vy = wz * ux - wx * uz;
//        float vz = wx * uy - wy * ux;
//
//        res[resOffset + 3] = res[resOffset + 3] +
//                            ux * bl[blOffset] * (rand[randOffset] - 0.5) +
//                            vx * bl[blOffset + 1] * (rand[randOffset + 1] - 0.5);
//        res[resOffset + 4] = res[resOffset + 4] +
//                            uy * bl[blOffset] * (rand[randOffset] - 0.5) +
//                            vy * bl[blOffset + 1] * (rand[randOffset + 1] - 0.5);
//        res[resOffset + 5] = res[resOffset + 5] +
//                            uz * bl[blOffset] * (rand[randOffset] - 0.5) +
//                            vz * bl[blOffset + 1] * (rand[randOffset + 1] - 0.5);
//
//        float dl = sqrt(res[resOffset + 3] * res[resOffset + 3] +
//                        res[resOffset + 4] * res[resOffset + 4] +
//                        res[resOffset + 5] * res[resOffset + 5]);
//
//        float d = len / dl;
//        res[resOffset + 3] = res[resOffset + 3] * d;
//        res[resOffset + 4] = res[resOffset + 4] * d;
//        res[resOffset + 5] = res[resOffset + 5] * d;
//    }
//
//    res[resOffset] = l[lOffset];
//    res[resOffset + 1] = l[lOffset + 1];
//    res[resOffset + 2] = l[lOffset + 2];
//}

__kernel void
planeXYIntersectAt(__global float *res, __global const float *r,
                    const int resOffset, const int rOffset,
                    const int resSize, const int rSize) {
    if (r[rOffset + 5] == 0) {
        res[resOffset] = -1;
    } else {
        res[resOffset] = -r[rOffset + 2] / r[rOffset + 5];
    }
}

__kernel void
planeXZIntersectAt(__global float *res, __global const float *r,
                    const int resOffset, const int rOffset,
                    const int resSize, const int rSize) {
    if (r[rOffset + 4] == 0) {
        res[resOffset] = -1;
    } else {
        res[resOffset] = -r[rOffset + 1] / r[rOffset + 4];
    }
}

__kernel void
planeYZIntersectAt(__global float *res, __global const float *r,
                    const int resOffset, const int rOffset,
                    const int resSize, const int rSize) {
    if (r[rOffset + 3] == 0) {
        res[resOffset] = -1;
    } else {
        res[resOffset] = -r[rOffset] / r[rOffset + 3];
    }
}

__kernel void
triangleIntersectAt(__global float *res,
                    __global const float *r,
                    __global const float *data,
                    const int resOffset, const int rOffset,
                    const int dataOffset,
                    const int resSize, const int rSize,
                    const int dataSize) {
    float m =  data[dataOffset]       * (data[dataOffset + 4] * r[rOffset + 5]  - r[rOffset + 4] * data[dataOffset + 5]) +
               data[dataOffset + 1]   * (r[rOffset + 3] * data[dataOffset + 5]  - data[dataOffset + 3] * r[rOffset + 5]) +
               data[dataOffset + 2]   * (data[dataOffset + 3] * r[rOffset + 4]  - data[dataOffset + 4] * r[rOffset + 3]);

    if (m == 0) {
        res[resOffset] = -1;
        return;
    }

    float u =  data[dataOffset + 6]   * (data[dataOffset + 4] * r[rOffset + 5]   - r[rOffset + 4] * data[dataOffset + 5]) +
               data[dataOffset + 7]   * (r[rOffset + 3] * data[dataOffset + 5]   - data[dataOffset + 3] * r[rOffset + 5]) +
               data[dataOffset + 8]   * (data[dataOffset + 3] * r[rOffset + 4]   - data[dataOffset + 4] * r[rOffset + 3]);
    u = u / m;

    if (u <= 0.0) {
        res[resOffset] = -1;
        return;
    }

    float v =  r[rOffset + 5] * (data[dataOffset] * data[dataOffset + 7]     - data[dataOffset + 6] * data[dataOffset + 1]) +
               r[rOffset + 4] * (data[dataOffset + 6] * data[dataOffset + 2]     - data[dataOffset] * data[dataOffset + 8]) +
               r[rOffset + 3] * (data[dataOffset + 1] * data[dataOffset + 8] - data[dataOffset + 7] * data[dataOffset + 2]);
    v = v / m;

    if (v <= 0.0 || u + v >= 1.0)  {
        res[resOffset] = -1;
        return;
    }

    float t =  data[dataOffset + 5] * (data[dataOffset] * data[dataOffset + 7] - data[dataOffset + 6] * data[dataOffset + 1]) +
               data[dataOffset + 4] * (data[dataOffset + 6] * data[dataOffset + 2] - data[dataOffset] * data[dataOffset + 8]) +
               data[dataOffset + 3] * (data[dataOffset + 1] * data[dataOffset + 8] - data[dataOffset + 7] * data[dataOffset + 2]);
    res[resOffset] = -1.0 * t / m;
}