#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation47_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _801_v562Offset = (int) offsetArr[0];
jint _798_v559Offset = (int) offsetArr[1];
jint _799_v560Offset = (int) offsetArr[2];
jint _800_v561Offset = (int) offsetArr[3];
jint _801_v562Size = (int) sizeArr[0];
jint _798_v559Size = (int) sizeArr[1];
jint _799_v560Size = (int) sizeArr[2];
jint _800_v561Size = (int) sizeArr[3];
jint _801_v562Dim0 = (int) dim0Arr[0];
jint _798_v559Dim0 = (int) dim0Arr[1];
jint _799_v560Dim0 = (int) dim0Arr[2];
jint _800_v561Dim0 = (int) dim0Arr[3];
double *_801_v562 = ((double *) argArr[0]);
double *_798_v559 = ((double *) argArr[1]);
double *_799_v560 = ((double *) argArr[2]);
double *_800_v561 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
jint _801_l0 = -1;
jint _801_l1 = -1;
jint _801_l2 = -1;
jint _801_l3 = -1;
jint _801_l4 = -1;
double _801_l5 = -1.0;
double _801_l6 = 0.0;
double _801_l7 = 0.0;
double _801_l8 = 0.0;
double _801_l9 = 0.0;
_801_l0 = (int) ceil((_799_v560[(global_id * _799_v560Dim0) + _799_v560Offset] * _800_v561[(global_id * _800_v561Dim0) + _800_v561Offset]) + -1.0) - 1;
_801_l1 = _801_l0 > 0 ? _801_l0 - 1 : _801_l0;
_801_l2 = _801_l0;
if ((_801_l0 + 1.0) != (_799_v560[(global_id * _799_v560Dim0) + _799_v560Offset] * _800_v561[(global_id * _800_v561Dim0) + _800_v561Offset])) {
    _801_l1 = _801_l1 + 1;
    _801_l2 = _801_l2 + 1;
}
if (_801_l1 == -1 || _801_l2 == -1) {
	_801_v562[(global_id * _801_v562Dim0) + _801_v562Offset] = 0;
} else if (pow(_800_v561[(global_id * _800_v561Dim0) + _800_v561Offset], -1.0) * (_801_l1 + 1.0) > _799_v560[(global_id * _799_v560Dim0) + _799_v560Offset]) {
	_801_v562[(global_id * _801_v562Dim0) + _801_v562Offset] = 0;
} else {
	_801_l6 = _798_v559[((global_id * _798_v559Dim0) + _801_l1) + _798_v559Offset];
	_801_l7 = _798_v559[((global_id * _798_v559Dim0) + _801_l2) + _798_v559Offset];
	_801_l8 = (_799_v560[(global_id * _799_v560Dim0) + _799_v560Offset]) - (pow(_800_v561[(global_id * _800_v561Dim0) + _800_v561Offset], -1.0) * (_801_l1 + 1.0));
	_801_l9 = (pow(_800_v561[(global_id * _800_v561Dim0) + _800_v561Offset], -1.0) * (_801_l2 + 1.0)) - (pow(_800_v561[(global_id * _800_v561Dim0) + _800_v561Offset], -1.0) * (_801_l1 + 1.0));
	if (_801_l9 == 0) {
		_801_v562[(global_id * _801_v562Dim0) + _801_v562Offset] = _801_l6;
	} else {
		_801_v562[(global_id * _801_v562Dim0) + _801_v562Offset] = _801_l6 + (_801_l8 / _801_l9) * (_801_l7 - _801_l6);
	}
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
