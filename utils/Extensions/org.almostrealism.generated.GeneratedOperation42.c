#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation42_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _784_v535Offset = (int) offsetArr[0];
jint _784_v536Offset = (int) offsetArr[1];
jint _784_v538Offset = (int) offsetArr[2];
jint _784_v535Size = (int) sizeArr[0];
jint _784_v536Size = (int) sizeArr[1];
jint _784_v538Size = (int) sizeArr[2];
jint _784_v535Dim0 = (int) dim0Arr[0];
jint _784_v536Dim0 = (int) dim0Arr[1];
jint _784_v538Dim0 = (int) dim0Arr[2];
double *_784_v535 = ((double *) argArr[0]);
double *_784_v536 = ((double *) argArr[1]);
double *_784_v538 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_784_v535[_784_v535Offset + 1] = 1.0;
jint _784_l0 = -1;
jint _784_l1 = -1;
double _784_l2 = 0.0;
double _784_l3 = 0.0;
double _784_l4 = 0.0;
double _784_l5 = 0.0;
for (int i = _784_v536[_784_v536Offset]; i < _784_v536[_784_v536Offset + 1]; i++) {
	if (_784_v536[(i * 2) + _784_v536Offset] >= _784_v538[_784_v538Offset]) {
		_784_l0 = i > _784_v536[_784_v536Offset] ? i - 1 : (_784_v536[(i * 2) + _784_v536Offset] == _784_v538[_784_v538Offset] ? i : -1);
		_784_l1 = i;
		break;
	}
}
if (_784_l0 == -1 || _784_l1 == -1) {
	_784_v535[(global_id * _784_v535Dim0) + _784_v535Offset] = 0;
} else if (_784_v536[(_784_l0 * 2) + _784_v536Offset] > _784_v538[_784_v538Offset]) {
	_784_v535[(global_id * _784_v535Dim0) + _784_v535Offset] = 0;
} else {
	_784_l2 = _784_v536[(_784_l0 * 2) + _784_v536Offset + 1];
	_784_l3 = _784_v536[(_784_l1 * 2) + _784_v536Offset + 1];
	_784_l4 = _784_v538[_784_v538Offset] - _784_v536[(_784_l0 * 2) + _784_v536Offset];
	_784_l5 = _784_v536[(_784_l1 * 2) + _784_v536Offset] - _784_v536[(_784_l0 * 2) + _784_v536Offset];
	if (_784_l5 == 0) {
		_784_v535[(global_id * _784_v535Dim0) + _784_v535Offset] = _784_l2;
	} else {
		_784_v535[(global_id * _784_v535Dim0) + _784_v535Offset] = _784_l2 + (_784_l4 / _784_l5) * (_784_l3 - _784_l2);
	}
}

}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
