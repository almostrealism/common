#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation698_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10885_v6867Offset = (int) offsetArr[0];
jint _10890_v6870Offset = (int) offsetArr[1];
jint _10893_v6875Offset = (int) offsetArr[2];
jint _10885_v6867Size = (int) sizeArr[0];
jint _10890_v6870Size = (int) sizeArr[1];
jint _10893_v6875Size = (int) sizeArr[2];
jint _10885_v6867Dim0 = (int) dim0Arr[0];
jint _10890_v6870Dim0 = (int) dim0Arr[1];
jint _10893_v6875Dim0 = (int) dim0Arr[2];
double *_10885_v6867 = ((double *) argArr[0]);
double *_10890_v6870 = ((double *) argArr[1]);
double *_10893_v6875 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10893_v6875[global_id + _10893_v6875Offset] = (- ((_10890_v6870[(global_id * 2) + _10890_v6870Offset + 1] + _10890_v6870[(global_id * 2) + _10890_v6870Offset]) * _10885_v6867[_10885_v6867Offset])) + _10893_v6875[global_id + _10893_v6875Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
