__kernel void
add(__global double *a, __global const double *b, const int aoffset, const int boffset) {
	int gid = get_global_id(0);
	a[gid + aoffset] += b[gid + boffset];
}