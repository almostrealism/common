#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation886_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13384_v8760Offset = (int) offsetArr[0];
jint _13384_v8761Offset = (int) offsetArr[1];
jint _13384_v8763Offset = (int) offsetArr[2];
jint _13384_v8760Size = (int) sizeArr[0];
jint _13384_v8761Size = (int) sizeArr[1];
jint _13384_v8763Size = (int) sizeArr[2];
jint _13384_v8760Dim0 = (int) dim0Arr[0];
jint _13384_v8761Dim0 = (int) dim0Arr[1];
jint _13384_v8763Dim0 = (int) dim0Arr[2];
double *_13384_v8760 = ((double *) argArr[0]);
double *_13384_v8761 = ((double *) argArr[1]);
double *_13384_v8763 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13384_v8760[global_id + _13384_v8760Offset] = _13384_v8761[global_id + _13384_v8761Offset + 4] * _13384_v8763[global_id + _13384_v8763Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
