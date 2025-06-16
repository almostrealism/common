#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation533_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7215_v5032Offset = (int) offsetArr[0];
jint _7220_v5035Offset = (int) offsetArr[1];
jint _7223_v5040Offset = (int) offsetArr[2];
jint _7215_v5032Size = (int) sizeArr[0];
jint _7220_v5035Size = (int) sizeArr[1];
jint _7223_v5040Size = (int) sizeArr[2];
jint _7215_v5032Dim0 = (int) dim0Arr[0];
jint _7220_v5035Dim0 = (int) dim0Arr[1];
jint _7223_v5040Dim0 = (int) dim0Arr[2];
double *_7215_v5032 = ((double *) argArr[0]);
double *_7220_v5035 = ((double *) argArr[1]);
double *_7223_v5040 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7223_v5040[global_id + _7223_v5040Offset] = (- ((_7220_v5035[(global_id * 2) + _7220_v5035Offset + 1] + _7220_v5035[(global_id * 2) + _7220_v5035Offset]) * _7215_v5032[_7215_v5032Offset])) + _7223_v5040[global_id + _7223_v5040Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
