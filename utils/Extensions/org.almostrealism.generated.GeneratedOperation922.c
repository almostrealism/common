#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation922_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13947_v9111Offset = (int) offsetArr[0];
jint _13947_v9112Offset = (int) offsetArr[1];
jint _13947_v9114Offset = (int) offsetArr[2];
jint _13947_v9111Size = (int) sizeArr[0];
jint _13947_v9112Size = (int) sizeArr[1];
jint _13947_v9114Size = (int) sizeArr[2];
jint _13947_v9111Dim0 = (int) dim0Arr[0];
jint _13947_v9112Dim0 = (int) dim0Arr[1];
jint _13947_v9114Dim0 = (int) dim0Arr[2];
double *_13947_v9111 = ((double *) argArr[0]);
double *_13947_v9112 = ((double *) argArr[1]);
double *_13947_v9114 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13947_v9111[global_id + _13947_v9111Offset] = _13947_v9112[global_id + _13947_v9112Offset + 8] * _13947_v9114[global_id + _13947_v9114Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
