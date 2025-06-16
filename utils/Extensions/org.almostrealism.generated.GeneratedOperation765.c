#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation765_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11703_v7501Offset = (int) offsetArr[0];
jint _11703_v7502Offset = (int) offsetArr[1];
jint _11703_v7501Size = (int) sizeArr[0];
jint _11703_v7502Size = (int) sizeArr[1];
jint _11703_v7501Dim0 = (int) dim0Arr[0];
jint _11703_v7502Dim0 = (int) dim0Arr[1];
double *_11703_v7501 = ((double *) argArr[0]);
double *_11703_v7502 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11703_v7501[(global_id * _11703_v7501Dim0) + _11703_v7501Offset] = 0.0;
for (int _11703_i = 0; _11703_i < 100;) {
jint k_11703_i = (global_id * 100) + _11703_i;
_11703_v7501[(global_id * _11703_v7501Dim0) + _11703_v7501Offset] = _11703_v7502[(k_11703_i) + _11703_v7502Offset] + _11703_v7501[(global_id * _11703_v7501Dim0) + _11703_v7501Offset];
_11703_i = _11703_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
