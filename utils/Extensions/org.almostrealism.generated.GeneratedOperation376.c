#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation376_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5246_v4010Offset = (int) offsetArr[0];
jint _5242_v4002Offset = (int) offsetArr[1];
jint _5245_v4007Offset = (int) offsetArr[2];
jint _5246_v4010Size = (int) sizeArr[0];
jint _5242_v4002Size = (int) sizeArr[1];
jint _5245_v4007Size = (int) sizeArr[2];
jint _5246_v4010Dim0 = (int) dim0Arr[0];
jint _5242_v4002Dim0 = (int) dim0Arr[1];
jint _5245_v4007Dim0 = (int) dim0Arr[2];
double *_5246_v4010 = ((double *) argArr[0]);
double *_5242_v4002 = ((double *) argArr[1]);
double *_5245_v4007 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5246_v4010[global_id + _5246_v4010Offset] = ((- (_5242_v4002[(global_id / 30) + _5242_v4002Offset] / 30.0)) + _5245_v4007[global_id + _5245_v4007Offset]) * ((- (_5242_v4002[(global_id / 30) + _5242_v4002Offset] / 30.0)) + _5245_v4007[global_id + _5245_v4007Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
