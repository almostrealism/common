#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation44_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _789_v550Offset = (int) offsetArr[0];
jint _786_v547Offset = (int) offsetArr[1];
jint _787_v548Offset = (int) offsetArr[2];
jint _788_v549Offset = (int) offsetArr[3];
jint _789_v550Size = (int) sizeArr[0];
jint _786_v547Size = (int) sizeArr[1];
jint _787_v548Size = (int) sizeArr[2];
jint _788_v549Size = (int) sizeArr[3];
jint _789_v550Dim0 = (int) dim0Arr[0];
jint _786_v547Dim0 = (int) dim0Arr[1];
jint _787_v548Dim0 = (int) dim0Arr[2];
jint _788_v549Dim0 = (int) dim0Arr[3];
double *_789_v550 = ((double *) argArr[0]);
double *_786_v547 = ((double *) argArr[1]);
double *_787_v548 = ((double *) argArr[2]);
double *_788_v549 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
jint _789_l0 = -1;
jint _789_l1 = -1;
jint _789_l2 = -1;
jint _789_l3 = -1;
jint _789_l4 = -1;
double _789_l5 = -1.0;
double _789_l6 = 0.0;
double _789_l7 = 0.0;
double _789_l8 = 0.0;
double _789_l9 = 0.0;
_789_l0 = (int) ceil(_787_v548[(global_id * _787_v548Dim0) + _787_v548Offset] * _788_v549[(global_id * _788_v549Dim0) + _788_v549Offset]) - 1;
_789_l1 = _789_l0 > 0 ? _789_l0 - 1 : _789_l0;
_789_l2 = _789_l0;
if ((_789_l0) != (_787_v548[(global_id * _787_v548Dim0) + _787_v548Offset] * _788_v549[(global_id * _788_v549Dim0) + _788_v549Offset])) {
    _789_l1 = _789_l1 + 1;
    _789_l2 = _789_l2 + 1;
}
if (_789_l1 == -1 || _789_l2 == -1) {
	_789_v550[(global_id * _789_v550Dim0) + _789_v550Offset] = 0;
} else if (pow(_788_v549[(global_id * _788_v549Dim0) + _788_v549Offset], -1.0) * _789_l1 > _787_v548[(global_id * _787_v548Dim0) + _787_v548Offset]) {
	_789_v550[(global_id * _789_v550Dim0) + _789_v550Offset] = 0;
} else {
	_789_l6 = _786_v547[((global_id * _786_v547Dim0) + _789_l1) + _786_v547Offset];
	_789_l7 = _786_v547[((global_id * _786_v547Dim0) + _789_l2) + _786_v547Offset];
	_789_l8 = (_787_v548[(global_id * _787_v548Dim0) + _787_v548Offset]) - (pow(_788_v549[(global_id * _788_v549Dim0) + _788_v549Offset], -1.0) * _789_l1);
	_789_l9 = (pow(_788_v549[(global_id * _788_v549Dim0) + _788_v549Offset], -1.0) * _789_l2) - (pow(_788_v549[(global_id * _788_v549Dim0) + _788_v549Offset], -1.0) * _789_l1);
	if (_789_l9 == 0) {
		_789_v550[(global_id * _789_v550Dim0) + _789_v550Offset] = _789_l6;
	} else {
		_789_v550[(global_id * _789_v550Dim0) + _789_v550Offset] = _789_l6 + (_789_l8 / _789_l9) * (_789_l7 - _789_l6);
	}
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
