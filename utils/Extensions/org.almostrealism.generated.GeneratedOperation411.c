#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation411_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5390_v4162Offset = (int) offsetArr[0];
jint _5386_v4154Offset = (int) offsetArr[1];
jint _5389_v4159Offset = (int) offsetArr[2];
jint _5390_v4162Size = (int) sizeArr[0];
jint _5386_v4154Size = (int) sizeArr[1];
jint _5389_v4159Size = (int) sizeArr[2];
jint _5390_v4162Dim0 = (int) dim0Arr[0];
jint _5386_v4154Dim0 = (int) dim0Arr[1];
jint _5389_v4159Dim0 = (int) dim0Arr[2];
double *_5390_v4162 = ((double *) argArr[0]);
double *_5386_v4154 = ((double *) argArr[1]);
double *_5389_v4159 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5390_v4162[global_id + _5390_v4162Offset] = ((- (_5386_v4154[_5386_v4154Offset] / 30.0)) + _5389_v4159[global_id + _5389_v4159Offset]) * ((- (_5386_v4154[_5386_v4154Offset] / 30.0)) + _5389_v4159[global_id + _5389_v4159Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
