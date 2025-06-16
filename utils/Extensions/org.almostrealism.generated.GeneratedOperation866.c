#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation866_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13219_v8504Offset = (int) offsetArr[0];
jint _13168_v8489Offset = (int) offsetArr[1];
jint _13218_v8503Offset = (int) offsetArr[2];
jint _13219_v8504Size = (int) sizeArr[0];
jint _13168_v8489Size = (int) sizeArr[1];
jint _13218_v8503Size = (int) sizeArr[2];
jint _13219_v8504Dim0 = (int) dim0Arr[0];
jint _13168_v8489Dim0 = (int) dim0Arr[1];
jint _13218_v8503Dim0 = (int) dim0Arr[2];
double *_13219_v8504 = ((double *) argArr[0]);
double *_13168_v8489 = ((double *) argArr[1]);
double *_13218_v8503 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13219_v8504[global_id + _13219_v8504Offset] = ((- ((_13168_v8489[((global_id / 16) * 2) + _13168_v8489Offset + 1] + _13168_v8489[((global_id / 16) * 2) + _13168_v8489Offset]) / 2.0)) + _13168_v8489[(global_id / 8) + _13168_v8489Offset]) * _13218_v8503[(((global_id / 16) * 8) + (global_id % 8)) + _13218_v8503Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
