#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation46_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _797_v558Offset = (int) offsetArr[0];
jint _794_v555Offset = (int) offsetArr[1];
jint _795_v556Offset = (int) offsetArr[2];
jint _796_v557Offset = (int) offsetArr[3];
jint _797_v558Size = (int) sizeArr[0];
jint _794_v555Size = (int) sizeArr[1];
jint _795_v556Size = (int) sizeArr[2];
jint _796_v557Size = (int) sizeArr[3];
jint _797_v558Dim0 = (int) dim0Arr[0];
jint _794_v555Dim0 = (int) dim0Arr[1];
jint _795_v556Dim0 = (int) dim0Arr[2];
jint _796_v557Dim0 = (int) dim0Arr[3];
double *_797_v558 = ((double *) argArr[0]);
double *_794_v555 = ((double *) argArr[1]);
double *_795_v556 = ((double *) argArr[2]);
double *_796_v557 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
jint _797_l0 = -1;
jint _797_l1 = -1;
jint _797_l2 = -1;
jint _797_l3 = -1;
jint _797_l4 = -1;
double _797_l5 = -1.0;
double _797_l6 = 0.0;
double _797_l7 = 0.0;
double _797_l8 = 0.0;
double _797_l9 = 0.0;
_797_l0 = (int) ceil((_795_v556[(global_id * _795_v556Dim0) + _795_v556Offset] * _796_v557[(global_id * _796_v557Dim0) + _796_v557Offset]) + -1.0) - 1;
_797_l1 = _797_l0 > 0 ? _797_l0 - 1 : _797_l0;
_797_l2 = _797_l0;
if ((_797_l0 + 1.0) != (_795_v556[(global_id * _795_v556Dim0) + _795_v556Offset] * _796_v557[(global_id * _796_v557Dim0) + _796_v557Offset])) {
    _797_l1 = _797_l1 + 1;
    _797_l2 = _797_l2 + 1;
}
if (_797_l1 == -1 || _797_l2 == -1) {
	_797_v558[(global_id * _797_v558Dim0) + _797_v558Offset] = 0;
} else if (pow(_796_v557[(global_id * _796_v557Dim0) + _796_v557Offset], -1.0) * (_797_l1 + 1.0) > _795_v556[(global_id * _795_v556Dim0) + _795_v556Offset]) {
	_797_v558[(global_id * _797_v558Dim0) + _797_v558Offset] = 0;
} else {
	_797_l6 = _794_v555[((global_id * _794_v555Dim0) + _797_l1) + _794_v555Offset];
	_797_l7 = _794_v555[((global_id * _794_v555Dim0) + _797_l2) + _794_v555Offset];
	_797_l8 = (_795_v556[(global_id * _795_v556Dim0) + _795_v556Offset]) - (pow(_796_v557[(global_id * _796_v557Dim0) + _796_v557Offset], -1.0) * (_797_l1 + 1.0));
	_797_l9 = (pow(_796_v557[(global_id * _796_v557Dim0) + _796_v557Offset], -1.0) * (_797_l2 + 1.0)) - (pow(_796_v557[(global_id * _796_v557Dim0) + _796_v557Offset], -1.0) * (_797_l1 + 1.0));
	if (_797_l9 == 0) {
		_797_v558[(global_id * _797_v558Dim0) + _797_v558Offset] = _797_l6;
	} else {
		_797_v558[(global_id * _797_v558Dim0) + _797_v558Offset] = _797_l6 + (_797_l8 / _797_l9) * (_797_l7 - _797_l6);
	}
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
