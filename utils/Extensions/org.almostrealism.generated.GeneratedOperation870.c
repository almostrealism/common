#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation870_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13233_v8485Offset = (int) offsetArr[0];
jint _13231_v8480Offset = (int) offsetArr[1];
jint _13231_v8481Offset = (int) offsetArr[2];
jint _13232_v8483Offset = (int) offsetArr[3];
jint _13233_v8485Size = (int) sizeArr[0];
jint _13231_v8480Size = (int) sizeArr[1];
jint _13231_v8481Size = (int) sizeArr[2];
jint _13232_v8483Size = (int) sizeArr[3];
jint _13233_v8485Dim0 = (int) dim0Arr[0];
jint _13231_v8480Dim0 = (int) dim0Arr[1];
jint _13231_v8481Dim0 = (int) dim0Arr[2];
jint _13232_v8483Dim0 = (int) dim0Arr[3];
double *_13233_v8485 = ((double *) argArr[0]);
double *_13231_v8480 = ((double *) argArr[1]);
double *_13231_v8481 = ((double *) argArr[2]);
double *_13232_v8483 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13233_v8485[global_id + _13233_v8485Offset] = (_13231_v8480[global_id + _13231_v8480Offset] + _13231_v8481[global_id + _13231_v8481Offset]) * _13232_v8483[(global_id / 8) + _13232_v8483Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
