#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation702_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10908_v7158Offset = (int) offsetArr[0];
jint _10906_v7156Offset = (int) offsetArr[1];
jint _10907_v7157Offset = (int) offsetArr[2];
jint _10908_v7158Size = (int) sizeArr[0];
jint _10906_v7156Size = (int) sizeArr[1];
jint _10907_v7157Size = (int) sizeArr[2];
jint _10908_v7158Dim0 = (int) dim0Arr[0];
jint _10906_v7156Dim0 = (int) dim0Arr[1];
jint _10907_v7157Dim0 = (int) dim0Arr[2];
double *_10908_v7158 = ((double *) argArr[0]);
double *_10906_v7156 = ((double *) argArr[1]);
double *_10907_v7157 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10908_v7158[(global_id * _10908_v7158Dim0) + _10908_v7158Offset] = _10906_v7156[(((global_id * 2) % 2) + (global_id * _10906_v7156Dim0)) + _10906_v7156Offset] * _10907_v7157[(((global_id * 2) % 2) + (global_id * _10907_v7157Dim0)) + _10907_v7157Offset];
_10908_v7158[(global_id * _10908_v7158Dim0) + _10908_v7158Offset + 1] = _10906_v7156[(global_id * _10906_v7156Dim0) + _10906_v7156Offset + 1] * _10907_v7157[(global_id * _10907_v7157Dim0) + _10907_v7157Offset + 1];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
