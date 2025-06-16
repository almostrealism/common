#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation43_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _785_v540Offset = (int) offsetArr[0];
jint _785_v541Offset = (int) offsetArr[1];
jint _785_v543Offset = (int) offsetArr[2];
jint _785_v545Offset = (int) offsetArr[3];
jint _785_v540Size = (int) sizeArr[0];
jint _785_v541Size = (int) sizeArr[1];
jint _785_v543Size = (int) sizeArr[2];
jint _785_v545Size = (int) sizeArr[3];
jint _785_v540Dim0 = (int) dim0Arr[0];
jint _785_v541Dim0 = (int) dim0Arr[1];
jint _785_v543Dim0 = (int) dim0Arr[2];
jint _785_v545Dim0 = (int) dim0Arr[3];
double *_785_v540 = ((double *) argArr[0]);
double *_785_v541 = ((double *) argArr[1]);
double *_785_v543 = ((double *) argArr[2]);
double *_785_v545 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
jint _785_l0 = -1;
jint _785_l1 = -1;
jint _785_l2 = -1;
jint _785_l3 = -1;
jint _785_l4 = -1;
double _785_l5 = -1.0;
double _785_l6 = 0.0;
double _785_l7 = 0.0;
double _785_l8 = 0.0;
double _785_l9 = 0.0;
_785_l0 = (int) ceil(_785_v543[(global_id % 2) + _785_v543Offset] * _785_v545[_785_v545Offset]) - 1;
_785_l1 = _785_l0 > 0 ? _785_l0 - 1 : _785_l0;
_785_l2 = _785_l0;
if ((_785_l0) != (_785_v543[(global_id % 2) + _785_v543Offset] * _785_v545[_785_v545Offset])) {
    _785_l1 = _785_l1 + 1;
    _785_l2 = _785_l2 + 1;
}
if (_785_l1 == -1 || _785_l2 == -1) {
	_785_v540[(global_id * _785_v540Dim0) + _785_v540Offset] = 0;
} else if (pow(_785_v545[_785_v545Offset], -1.0) * _785_l1 > _785_v543[(global_id % 2) + _785_v543Offset]) {
	_785_v540[(global_id * _785_v540Dim0) + _785_v540Offset] = 0;
} else {
	_785_l6 = _785_v541[(_785_l1 % 10) + _785_v541Offset];
	_785_l7 = _785_v541[(_785_l2 % 10) + _785_v541Offset];
	_785_l8 = (_785_v543[(global_id % 2) + _785_v543Offset]) - (pow(_785_v545[_785_v545Offset], -1.0) * _785_l1);
	_785_l9 = (pow(_785_v545[_785_v545Offset], -1.0) * _785_l2) - (pow(_785_v545[_785_v545Offset], -1.0) * _785_l1);
	if (_785_l9 == 0) {
		_785_v540[(global_id * _785_v540Dim0) + _785_v540Offset] = _785_l6;
	} else {
		_785_v540[(global_id * _785_v540Dim0) + _785_v540Offset] = _785_l6 + (_785_l8 / _785_l9) * (_785_l7 - _785_l6);
	}
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
