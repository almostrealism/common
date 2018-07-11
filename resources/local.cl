__kernel void
add(__global double *a, __global const double *b, const int aoffset, const int boffset) {
    int gid = get_global_id(0);
	a[aoffset] += b[boffset];
}

__kernel void
subtract(__global double *a, __global const double *b, const int aoffset, const int boffset) {
    int gid = get_global_id(0);
	a[gid + aoffset] -= b[gid + boffset];
}

__kernel void
multiply(__global double *a, __global const double *b, const int aoffset, const int boffset) {
    int gid = get_global_id(0);
	a[aoffset] *= b[boffset];
}

__kernel void
divide(__global double *a, __global const double *b, const int aoffset, const int boffset) {
    int gid = get_global_id(0);
	a[aoffset] /= b[boffset];
}

__kernel void
dotProduct(__global double *res, __global const double *a, __global const double *b, const int aoffset, const int boffset, const int n, const int stride, const int step)
{
	double acc = 0.0;
	int row = get_global_id(0);
	int col = get_global_id(1);
	for (int i = 0; i < n; i++) {
		acc += a[aoffset + i + row * step] * b[boffset + col + i * stride];
	}
	res[row * n + col] = acc;
}

__kernel void
transformAsLocation(__global const double *m, __global double *v, const int moffset, const int voffset) {
    int gid = get_global_id(0);
    double x = v[gid+voffset];
    double y = v[gid+voffset+1];
    double z = v[gid+voffset+2];
    v[gid + voffset] = m[4 * gid +moffset] * x + m[4 * gid + moffset + 1] * y + m[4 * gid + moffset + 2] * z + m[4 * gid + moffset + 3];
}

__kernel void
transformAsOffset(__global const double *m, __global double *v, const int moffset, const int voffset) {
    int gid = get_global_id(0);
    double x = v[gid+voffset];
    double y = v[gid+voffset+1];
    double z = v[gid+voffset+2];
    v[gid+voffset] = m[4 * gid + moffset] * x + m[4 * gid + moffset + 1] * y + m[4 * gid + moffset + 2] * z;
}