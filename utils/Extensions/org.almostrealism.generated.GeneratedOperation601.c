#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation601_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9188_v6129Offset = (int) offsetArr[0];
jint _9186_v6127Offset = (int) offsetArr[1];
jint _9187_v6128Offset = (int) offsetArr[2];
jint _9188_v6129Size = (int) sizeArr[0];
jint _9186_v6127Size = (int) sizeArr[1];
jint _9187_v6128Size = (int) sizeArr[2];
jint _9188_v6129Dim0 = (int) dim0Arr[0];
jint _9186_v6127Dim0 = (int) dim0Arr[1];
jint _9187_v6128Dim0 = (int) dim0Arr[2];
double *_9188_v6129 = ((double *) argArr[0]);
double *_9186_v6127 = ((double *) argArr[1]);
double *_9187_v6128 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9188_v6129[(global_id * _9188_v6129Dim0) + _9188_v6129Offset] = _9186_v6127[(((global_id * 2) % 2) + (global_id * _9186_v6127Dim0)) + _9186_v6127Offset] * _9187_v6128[(((global_id * 2) % 2) + (global_id * _9187_v6128Dim0)) + _9187_v6128Offset];
_9188_v6129[(global_id * _9188_v6129Dim0) + _9188_v6129Offset + 1] = _9186_v6127[(global_id * _9186_v6127Dim0) + _9186_v6127Offset + 1] * _9187_v6128[(global_id * _9187_v6128Dim0) + _9187_v6128Offset + 1];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
