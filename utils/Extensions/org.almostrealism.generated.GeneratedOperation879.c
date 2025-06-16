#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation879_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13293_v8695Offset = (int) offsetArr[0];
jint _13293_v8696Offset = (int) offsetArr[1];
jint _13293_v8698Offset = (int) offsetArr[2];
jint _13293_v8695Size = (int) sizeArr[0];
jint _13293_v8696Size = (int) sizeArr[1];
jint _13293_v8698Size = (int) sizeArr[2];
jint _13293_v8695Dim0 = (int) dim0Arr[0];
jint _13293_v8696Dim0 = (int) dim0Arr[1];
jint _13293_v8698Dim0 = (int) dim0Arr[2];
double *_13293_v8695 = ((double *) argArr[0]);
double *_13293_v8696 = ((double *) argArr[1]);
double *_13293_v8698 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13293_v8695[global_id + _13293_v8695Offset] = _13293_v8696[global_id + _13293_v8696Offset] * _13293_v8698[global_id + _13293_v8698Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
