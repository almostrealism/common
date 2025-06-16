#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation39_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _778_v516Offset = (int) offsetArr[0];
jint _778_v518Offset = (int) offsetArr[1];
jint _778_v520Offset = (int) offsetArr[2];
jint _778_v516Size = (int) sizeArr[0];
jint _778_v518Size = (int) sizeArr[1];
jint _778_v520Size = (int) sizeArr[2];
jint _778_v516Dim0 = (int) dim0Arr[0];
jint _778_v518Dim0 = (int) dim0Arr[1];
jint _778_v520Dim0 = (int) dim0Arr[2];
double *_778_v516 = ((double *) argArr[0]);
double *_778_v518 = ((double *) argArr[1]);
double *_778_v520 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
if (_778_v516[_778_v516Offset + 1] - _778_v516[_778_v516Offset] > 0) {
for (int i = _778_v516[_778_v516Offset] + 1; i < _778_v516[_778_v516Offset + 1]; i++) {
	if (_778_v518[_778_v518Offset] > _778_v516[(i * 2) + _778_v516Offset]) {
		_778_v516[_778_v516Offset] = i;
	}
	if (_778_v518[_778_v518Offset] < _778_v516[(i * 2) + _778_v516Offset]) {
		break;
	}
}
}

}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
