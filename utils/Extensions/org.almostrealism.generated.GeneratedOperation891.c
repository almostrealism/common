#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation891_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13431_v8800Offset = (int) offsetArr[0];
jint _13431_v8801Offset = (int) offsetArr[1];
jint _13431_v8803Offset = (int) offsetArr[2];
jint _13431_v8800Size = (int) sizeArr[0];
jint _13431_v8801Size = (int) sizeArr[1];
jint _13431_v8803Size = (int) sizeArr[2];
jint _13431_v8800Dim0 = (int) dim0Arr[0];
jint _13431_v8801Dim0 = (int) dim0Arr[1];
jint _13431_v8803Dim0 = (int) dim0Arr[2];
double *_13431_v8800 = ((double *) argArr[0]);
double *_13431_v8801 = ((double *) argArr[1]);
double *_13431_v8803 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13431_v8800[global_id + _13431_v8800Offset] = _13431_v8801[global_id + _13431_v8801Offset + 6] * _13431_v8803[global_id + _13431_v8803Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
