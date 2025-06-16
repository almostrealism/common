#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation853_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12858_v8453Offset = (int) offsetArr[0];
jint _12854_v8445Offset = (int) offsetArr[1];
jint _12857_v8450Offset = (int) offsetArr[2];
jint _12858_v8453Size = (int) sizeArr[0];
jint _12854_v8445Size = (int) sizeArr[1];
jint _12857_v8450Size = (int) sizeArr[2];
jint _12858_v8453Dim0 = (int) dim0Arr[0];
jint _12854_v8445Dim0 = (int) dim0Arr[1];
jint _12857_v8450Dim0 = (int) dim0Arr[2];
double *_12858_v8453 = ((double *) argArr[0]);
double *_12854_v8445 = ((double *) argArr[1]);
double *_12857_v8450 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12858_v8453[global_id + _12858_v8453Offset] = ((- (_12854_v8445[(global_id / 30) + _12854_v8445Offset] / 30.0)) + _12857_v8450[global_id + _12857_v8450Offset]) * ((- (_12854_v8445[(global_id / 30) + _12854_v8445Offset] / 30.0)) + _12857_v8450[global_id + _12857_v8450Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
