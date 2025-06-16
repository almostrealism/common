#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation40_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _779_v522Offset = (int) offsetArr[0];
jint _779_v524Offset = (int) offsetArr[1];
jint _779_v526Offset = (int) offsetArr[2];
jint _779_v522Size = (int) sizeArr[0];
jint _779_v524Size = (int) sizeArr[1];
jint _779_v526Size = (int) sizeArr[2];
jint _779_v522Dim0 = (int) dim0Arr[0];
jint _779_v524Dim0 = (int) dim0Arr[1];
jint _779_v526Dim0 = (int) dim0Arr[2];
double *_779_v522 = ((double *) argArr[0]);
double *_779_v524 = ((double *) argArr[1]);
double *_779_v526 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
if (_779_v522[_779_v522Offset + 1] - _779_v522[_779_v522Offset] > 0) {
for (int i = _779_v522[_779_v522Offset] + 1; i < _779_v522[_779_v522Offset + 1]; i++) {
	if (_779_v524[_779_v524Offset] > _779_v522[(i * 2) + _779_v522Offset]) {
		_779_v522[_779_v522Offset] = i;
	}
	if (_779_v524[_779_v524Offset] < _779_v522[(i * 2) + _779_v522Offset]) {
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
