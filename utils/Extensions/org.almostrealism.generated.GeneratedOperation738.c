#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation738_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11383_v7461Offset = (int) offsetArr[0];
jint _11378_v7458Offset = (int) offsetArr[1];
jint _11383_v7461Size = (int) sizeArr[0];
jint _11378_v7458Size = (int) sizeArr[1];
jint _11383_v7461Dim0 = (int) dim0Arr[0];
jint _11378_v7458Dim0 = (int) dim0Arr[1];
double *_11383_v7461 = ((double *) argArr[0]);
double *_11378_v7458 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11383_v7461[global_id + _11383_v7461Offset] = (_11378_v7458[global_id + _11378_v7458Offset + 60] + -0.04413178133353889) / 0.02959601155303059;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
