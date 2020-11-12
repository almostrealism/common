__kernel void prg(__global double *bank, __global double *cursors,
                    const int bankOffset, const int cursorsOffset,
                    const int bankSize, const int cursorsSize) {
	if (bank[bankOffset + 1] - bank[bankOffset] <= 0) return;

	for (int i = bank[bankOffset] + 1; i < bank[bankOffset + 1]; i++) {
		if (bank[2 * i] > cursors[cursorsOffset]) {
			bank[bankOffset] = i - 1;
			return;
		}
	}
}

__kernel void vat(__global double *res, __global double *bank, __global double *cursors,
                const int resOffset, const int bankOffset, const int cursorsOffset,
                const int resSize, const int bankSize, const int cursorsSize) {
    res[resOffset] = cursors[cursorsOffset];

	int left = -1;
	int right = -1;

	for (int i = bank[bankOffset]; i < bank[bankOffset + 1]; i++) {
		if (bank[2 * i] >= cursors[cursorsOffset]) {
			left = i > bank[bankOffset] ? i - 1 : -1;
			right = i;
	    	break;
		}
	}

	if (left == -1 || right == -1) {
	    res[resOffset + 1] = 0;
	    return;
	}

	if (bank[2 * left] > cursors[cursorsOffset]) {
	    res[resOffset + 1] = 0;
        return;
	}

	double v1 = bank[2 * left + 1];
	double v2 = bank[2 * right + 1];

	double t1 = cursors[cursorsOffset] - bank[2 * left];
	double t2 = bank[2 * right] - bank[2 * left];

	res[resOffset + 1] = v1 + (t1 / t2) * (v2 - v1);
}