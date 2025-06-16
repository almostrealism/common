#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation610_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9347_v6212Offset = (int) offsetArr[0];
jint _9347_v6213Offset = (int) offsetArr[1];
jint _9347_v6215Offset = (int) offsetArr[2];
jint _9347_v6212Size = (int) sizeArr[0];
jint _9347_v6213Size = (int) sizeArr[1];
jint _9347_v6215Size = (int) sizeArr[2];
jint _9347_v6212Dim0 = (int) dim0Arr[0];
jint _9347_v6213Dim0 = (int) dim0Arr[1];
jint _9347_v6215Dim0 = (int) dim0Arr[2];
double *_9347_v6212 = ((double *) argArr[0]);
double *_9347_v6213 = ((double *) argArr[1]);
double *_9347_v6215 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9347_v6212[global_id + _9347_v6212Offset] = _9347_v6213[global_id + _9347_v6213Offset + 2] * _9347_v6215[global_id + _9347_v6215Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
