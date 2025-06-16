#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation384_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5276_v4062Offset = (int) offsetArr[0];
jint _5242_v4022Offset = (int) offsetArr[1];
jint _5245_v4027Offset = (int) offsetArr[2];
jint _5262_v4035Offset = (int) offsetArr[3];
jint _5271_v4051Offset = (int) offsetArr[4];
jint _5276_v4062Size = (int) sizeArr[0];
jint _5242_v4022Size = (int) sizeArr[1];
jint _5245_v4027Size = (int) sizeArr[2];
jint _5262_v4035Size = (int) sizeArr[3];
jint _5271_v4051Size = (int) sizeArr[4];
jint _5276_v4062Dim0 = (int) dim0Arr[0];
jint _5242_v4022Dim0 = (int) dim0Arr[1];
jint _5245_v4027Dim0 = (int) dim0Arr[2];
jint _5262_v4035Dim0 = (int) dim0Arr[3];
jint _5271_v4051Dim0 = (int) dim0Arr[4];
double *_5276_v4062 = ((double *) argArr[0]);
double *_5242_v4022 = ((double *) argArr[1]);
double *_5245_v4027 = ((double *) argArr[2]);
double *_5262_v4035 = ((double *) argArr[3]);
double *_5271_v4051 = ((double *) argArr[4]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5276_v4062[global_id + _5276_v4062Offset] = ((((((- (global_id % 120)) + (global_id / 120)) == 0) ? 1 : 0) + (_5262_v4035[(((global_id / 3600) * 120) + (global_id % 120)) + _5262_v4035Offset] * -0.03333333333333333)) * ((- (_5242_v4022[(global_id / 3600) + _5242_v4022Offset] / 30.0)) + _5245_v4027[(global_id / 120) + _5245_v4027Offset])) + ((((((- (global_id % 120)) + (global_id / 120)) == 0) ? 1 : 0) + (_5271_v4051[(((global_id / 3600) * 120) + (global_id % 120)) + _5271_v4051Offset] * -0.03333333333333333)) * ((- (_5242_v4022[(global_id / 3600) + _5242_v4022Offset] / 30.0)) + _5245_v4027[(global_id / 120) + _5245_v4027Offset]));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
