#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation45_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _793_v554Offset = (int) offsetArr[0];
jint _790_v551Offset = (int) offsetArr[1];
jint _791_v552Offset = (int) offsetArr[2];
jint _792_v553Offset = (int) offsetArr[3];
jint _793_v554Size = (int) sizeArr[0];
jint _790_v551Size = (int) sizeArr[1];
jint _791_v552Size = (int) sizeArr[2];
jint _792_v553Size = (int) sizeArr[3];
jint _793_v554Dim0 = (int) dim0Arr[0];
jint _790_v551Dim0 = (int) dim0Arr[1];
jint _791_v552Dim0 = (int) dim0Arr[2];
jint _792_v553Dim0 = (int) dim0Arr[3];
double *_793_v554 = ((double *) argArr[0]);
double *_790_v551 = ((double *) argArr[1]);
double *_791_v552 = ((double *) argArr[2]);
double *_792_v553 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
jint _793_l0 = -1;
jint _793_l1 = -1;
jint _793_l2 = -1;
jint _793_l3 = -1;
jint _793_l4 = -1;
double _793_l5 = -1.0;
double _793_l6 = 0.0;
double _793_l7 = 0.0;
double _793_l8 = 0.0;
double _793_l9 = 0.0;
_793_l0 = (int) ceil((_791_v552[(global_id * _791_v552Dim0) + _791_v552Offset] * _792_v553[(global_id * _792_v553Dim0) + _792_v553Offset]) + -1.0) - 1;
_793_l1 = _793_l0 > 0 ? _793_l0 - 1 : _793_l0;
_793_l2 = _793_l0;
if ((_793_l0 + 1.0) != (_791_v552[(global_id * _791_v552Dim0) + _791_v552Offset] * _792_v553[(global_id * _792_v553Dim0) + _792_v553Offset])) {
    _793_l1 = _793_l1 + 1;
    _793_l2 = _793_l2 + 1;
}
if (_793_l1 == -1 || _793_l2 == -1) {
	_793_v554[(global_id * _793_v554Dim0) + _793_v554Offset] = 0;
} else if (pow(_792_v553[(global_id * _792_v553Dim0) + _792_v553Offset], -1.0) * (_793_l1 + 1.0) > _791_v552[(global_id * _791_v552Dim0) + _791_v552Offset]) {
	_793_v554[(global_id * _793_v554Dim0) + _793_v554Offset] = 0;
} else {
	_793_l6 = _790_v551[((global_id * _790_v551Dim0) + _793_l1) + _790_v551Offset];
	_793_l7 = _790_v551[((global_id * _790_v551Dim0) + _793_l2) + _790_v551Offset];
	_793_l8 = (_791_v552[(global_id * _791_v552Dim0) + _791_v552Offset]) - (pow(_792_v553[(global_id * _792_v553Dim0) + _792_v553Offset], -1.0) * (_793_l1 + 1.0));
	_793_l9 = (pow(_792_v553[(global_id * _792_v553Dim0) + _792_v553Offset], -1.0) * (_793_l2 + 1.0)) - (pow(_792_v553[(global_id * _792_v553Dim0) + _792_v553Offset], -1.0) * (_793_l1 + 1.0));
	if (_793_l9 == 0) {
		_793_v554[(global_id * _793_v554Dim0) + _793_v554Offset] = _793_l6;
	} else {
		_793_v554[(global_id * _793_v554Dim0) + _793_v554Offset] = _793_l6 + (_793_l8 / _793_l9) * (_793_l7 - _793_l6);
	}
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
