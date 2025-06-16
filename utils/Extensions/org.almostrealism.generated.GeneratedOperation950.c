#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation950_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _14293_v9371Offset = (int) offsetArr[0];
jint _14319_v9399Offset = (int) offsetArr[1];
jint _14323_v9408Offset = (int) offsetArr[2];
jint _14293_v9371Size = (int) sizeArr[0];
jint _14319_v9399Size = (int) sizeArr[1];
jint _14323_v9408Size = (int) sizeArr[2];
jint _14293_v9371Dim0 = (int) dim0Arr[0];
jint _14319_v9399Dim0 = (int) dim0Arr[1];
jint _14323_v9408Dim0 = (int) dim0Arr[2];
double *_14293_v9371 = ((double *) argArr[0]);
double *_14319_v9399 = ((double *) argArr[1]);
double *_14323_v9408 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_14323_v9408[global_id + _14323_v9408Offset] = (((- (pow(exp(_14293_v9371[_14293_v9371Offset]) + exp(_14293_v9371[_14293_v9371Offset + 1]), -1.0) * exp(_14293_v9371[((global_id * 3) / 2) + _14293_v9371Offset]))) + ((((global_id == 1) ? -1 : 0) == 0) ? 1 : 0)) * _14319_v9399[((global_id * 2) % 2) + _14319_v9399Offset]) + (((- (pow(exp(_14293_v9371[_14293_v9371Offset]) + exp(_14293_v9371[_14293_v9371Offset + 1]), -1.0) * exp(_14293_v9371[((global_id * 3) / 2) + _14293_v9371Offset]))) + (((- global_id) == -1) ? 1 : 0)) * _14319_v9399[_14319_v9399Offset + 1]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
