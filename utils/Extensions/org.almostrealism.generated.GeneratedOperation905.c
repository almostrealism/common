#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation905_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13795_v8831Offset = (int) offsetArr[0];
jint _13793_v8826Offset = (int) offsetArr[1];
jint _13793_v8827Offset = (int) offsetArr[2];
jint _13794_v8829Offset = (int) offsetArr[3];
jint _13795_v8831Size = (int) sizeArr[0];
jint _13793_v8826Size = (int) sizeArr[1];
jint _13793_v8827Size = (int) sizeArr[2];
jint _13794_v8829Size = (int) sizeArr[3];
jint _13795_v8831Dim0 = (int) dim0Arr[0];
jint _13793_v8826Dim0 = (int) dim0Arr[1];
jint _13793_v8827Dim0 = (int) dim0Arr[2];
jint _13794_v8829Dim0 = (int) dim0Arr[3];
double *_13795_v8831 = ((double *) argArr[0]);
double *_13793_v8826 = ((double *) argArr[1]);
double *_13793_v8827 = ((double *) argArr[2]);
double *_13794_v8829 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13795_v8831[global_id + _13795_v8831Offset] = (_13793_v8826[global_id + _13793_v8826Offset] + _13793_v8827[global_id + _13793_v8827Offset]) * _13794_v8829[(global_id / 16) + _13794_v8829Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
