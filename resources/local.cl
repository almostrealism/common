#pragma OPENCL EXTENSION cl_khr_fp64 : enable

__kernel void
add(__global double *a, __global const double *b, const int aoffset, const int boffset) {
    int gid = get_global_id(0);
	a[gid + aoffset] += b[gid + boffset];
}

__kernel void
subtract(__global double *a, __global const double *b, const int aoffset, const int boffset) {
    int gid = get_global_id(0);
	a[gid + aoffset] -= b[gid + boffset];
}

__kernel void
multiply(__global double *a, __global const double *b, const int aoffset, const int boffset) {
    int gid = get_global_id(0);
	a[gid + aoffset] *= b[gid + boffset];
}

__kernel void
divide(__global double *a, __global const double *b, const int aoffset, const int boffset) {
    int gid = get_global_id(0);
	a[gid + aoffset] /= b[gid + boffset];
}

__kernel void
lengthSq(__global double *res, __global const double *v, const int vOffset) {
    res[0] = v[vOffset] * v[vOffset] + v[vOffset + 1] * v[vOffset + 1] + v[vOffset + 2] * v[vOffset + 2];
}

__kernel void
crossProduct(__global double *res, __global const double *a, __global const double *b, const int resOffset, const int aOffset, const int bOffset)
{
    res[resOffset] = a[aOffset + 1] * b[bOffset + 2] - a[aOffset + 2] * b[bOffset + 1];
    res[resOffset + 1] = a[aOffset + 2] * b[bOffset] - a[aOffset] * b[bOffset + 2];
    res[resOffset + 2] = a[aOffset] * b[bOffset + 1] - a[aOffset + 1] * b[bOffset];
}

__kernel void
dotProduct(__global double *res, __global const double *a, __global const double *b, const int aoffset, const int boffset) {
    int n = 3;
    int stride = 1;
    int step = 3;

	double acc = 0.0;
	int row = get_global_id(0);
	int col = get_global_id(1);
	for (int i = 0; i < n; i++) {
		acc += a[aoffset + i + row * step] * b[boffset + col + i * stride];
	}
	res[row * n + col] = acc;
}

__kernel void
transformAsLocation(__global double *v, __global const double *m, const int vOffset, const int mOffset) {
    double x = v[vOffset];
    double y = v[vOffset + 1];
    double z = v[vOffset + 2];
    v[vOffset]      =     m[mOffset] * x + m[mOffset + 1] * y + m[mOffset + 2]  * z + m[mOffset + 3];
    v[vOffset + 1]  = m[mOffset + 4] * x + m[mOffset + 5] * y + m[mOffset + 6]  * z + m[mOffset + 7];
    v[vOffset + 2]  = m[mOffset + 8] * x + m[mOffset + 9] * y + m[mOffset + 10] * z + m[mOffset + 11];
}

__kernel void
transformAsOffset(__global double *v, __global const double *m, const int vOffset, const int mOffset) {
    double x = v[vOffset];
    double y = v[vOffset + 1];
    double z = v[vOffset + 2];
    v[vOffset]      =     m[mOffset] * x + m[mOffset + 1] * y + m[mOffset + 2]  * z;
    v[vOffset + 1]  = m[mOffset + 4] * x + m[mOffset + 5] * y + m[mOffset + 6]  * z;
    v[vOffset + 2]  = m[mOffset + 8] * x + m[mOffset + 9] * y + m[mOffset + 10] * z;
}

__kernel void
rayMatrixTransform(__global double *r, __global const double *m, const int rOffset, const int mOffset) {
    transformAsLocation(r, m, rOffset, mOffset);
    transformAsOffset(r, m, rOffset + 3, mOffset);
}

__kernel void
pinholeCameraRayAt(__global double *res, __global const double *pos, __global const double *sd,
                    __global const double *l, __global const double *pd, __global const double *bl,
                    __global const double *fl, __global const double *u, __global const double *v, __global const double *w,
                    const int resOffset, const int posOffset, const int sdOffset,
                    const int lOffset, const int pdOffset, const int blOffset,
                    const int flOffset, const int uOffset, const int vOffset, const int wOffset) {
    double bu = pd[pdOffset] / 2;
    double bv = pd[pdOffset + 1] / 2;
    double au = -bu;
    double av = -bv;

    double p = au + (bu - au) * (pos[posOffset] / (sd[sdOffset] - 1));
    double q = av + (bv - av) * (pos[posOffset + 1] / (sd[sdOffset + 1] - 1));
    double r = -fl[flOffset];

    res[resOffset + 3] = p * u[uOffset] + q * v[vOffset] + r * w[wOffset];
    res[resOffset + 4] = p * u[uOffset + 1] + q * v[vOffset + 1] + r * w[wOffset + 1];
    res[resOffset + 5] = p * u[uOffset + 2] + q * v[vOffset + 2] + r * w[wOffset + 2];

    double len = sqrt(res[resOffset + 3] * res[resOffset + 3] +
                    res[resOffset + 4] * res[resOffset + 4] +
                    res[resOffset + 5] * res[resOffset + 5]);

    if (bl[blOffset] != 0.0 || bl[blOffset + 1] != 0.0) {
        double wx = res[resOffset + 3];
        double wy = res[resOffset + 4];
        double wz = res[resOffset + 5];

        double tx = res[resOffset + 3];
        double ty = res[resOffset + 4];
        double tz = res[resOffset + 5];

        if (tx < ty && ty < tz) {
            tx = 1.0;
        } else if (ty < tx && ty < tz) {
            ty = 1.0;
        } else {
            tz = 1.0;
        }

        double wl = sqrt(wx * wx + wy * wy + wz * wz);
        wx = wx / wl;
        wy = wy / wl;
        wz = wz / wl;

        double ux = ty * wz - tz * wy;
        double uy = tz * wx - tx * wz;
        double uz = tx * wy - ty * wx;

        double ul = sqrt(ux * ux + uy * uy + uz * uz);
        ux = ux / ul;
        uy = uy / ul;
        uz = uz / ul;

        double vx = wy * uz - wz * uy;
        double vy = wz * ux - wx * uz;
        double vz = wx * uy - wy * ux;

        res[resOffset + 3] = res[resOffset + 3] + ux * bl[blOffset] + vx * bl[blOffset + 1];
        res[resOffset + 4] = res[resOffset + 4] + uy * bl[blOffset] + vy * bl[blOffset + 1];
        res[resOffset + 5] = res[resOffset + 5] + uz * bl[blOffset] + vz * bl[blOffset + 1];

        double dl = sqrt(res[resOffset + 3] * res[resOffset + 3] +
                        res[resOffset + 4] * res[resOffset + 4] +
                        res[resOffset + 5] * res[resOffset + 5]);

        double d = len / dl;
        res[resOffset + 3] = res[resOffset + 3] * d;
        res[resOffset + 4] = res[resOffset + 4] * d;
        res[resOffset + 5] = res[resOffset + 5] * d;
    }

    res[resOffset] = l[lOffset];
    res[resOffset + 1] = l[lOffset + 1];
    res[resOffset + 2] = l[lOffset + 2];
}

__kernel void
triangleIntersectAt(__global double *res,
                    __global const double *r,
                    __global const double *abc, __global const double *def, __global const double *jkl,
                    const int rOffset, const int abcOffset, const int defOffset, const int jklOffset) {
    double j = jkl[jklOffset] - r[rOffset];
    double k = jkl[jklOffset + 1] - r[rOffset + 1];
    double l = jkl[jklOffset + 2] - r[rOffset + 2];

    double m = abc[abcOffset]       * (def[defOffset + 1] * r[rOffset + 5]  - r[rOffset + 4] * def[defOffset + 2]) +
               abc[abcOffset + 1]   * (r[rOffset + 3] * def[defOffset + 2]  - def[defOffset] * r[rOffset + 5]) +
               abc[abcOffset + 2]   * (def[defOffset] * r[rOffset + 4]      - def[defOffset + 1] * r[rOffset + 3]);

    if (m == 0) {
        res[0] = -1; // TODO  Better indicator of no intersection?
        return;
    }

    double u = jkl[jklOffset]       * (def[defOffset + 1] * r[rOffset + 5]  - r[rOffset + 4] * def[defOffset + 2]) +
               jkl[jklOffset + 1]   * (r[rOffset + 3] * def[defOffset + 2]  - def[defOffset] * r[rOffset + 5]) +
               jkl[jklOffset + 2]   * (def[defOffset] * r[rOffset + 4]      - def[defOffset + 1] * r[rOffset + 3]);
    u = u / m;

    if (u <= 0.0) {
        res[0] = -1; // TODO  Better indicator of no intersection?
        return;
    }

    double v = r[rOffset + 5] * (abc[abcOffset] * jkl[jklOffset + 1]      - jkl[jklOffset] * abc[abcOffset + 1]) +
               r[rOffset + 4] * (jkl[jklOffset] * abc[abcOffset + 2]      - abc[abcOffset] * jkl[jklOffset + 2]) +
               r[rOffset + 3] * (abc[abcOffset + 1] * jkl[jklOffset + 2]  - jkl[jklOffset + 1] * abc[abcOffset + 2]);
    v = v / m;

    if (v <= 0.0 || u + v >= 1.0)  {
        res[0] = -1; // TODO  Better indicator of no intersection?
        return;
    }

    double t = def[defOffset + 2] * (abc[abcOffset] * jkl[jklOffset + 1] - jkl[jklOffset] * abc[abcOffset + 1]) +
               def[defOffset + 1] * (jkl[jklOffset] * abc[abcOffset + 2] - abc[abcOffset] * jkl[jklOffset + 2]) +
               def[defOffset] * (abc[abcOffset + 1] * jkl[jklOffset + 2] - jkl[jklOffset + 1] * abc[abcOffset + 2]);
    res[0] = -1.0 * t / m;
}