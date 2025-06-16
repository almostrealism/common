#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation793_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12132_v7895Offset = (int) offsetArr[0];
jint _12096_v7868Offset = (int) offsetArr[1];
jint _12130_v7891Offset = (int) offsetArr[2];
jint _12132_v7895Size = (int) sizeArr[0];
jint _12096_v7868Size = (int) sizeArr[1];
jint _12130_v7891Size = (int) sizeArr[2];
jint _12132_v7895Dim0 = (int) dim0Arr[0];
jint _12096_v7868Dim0 = (int) dim0Arr[1];
jint _12130_v7891Dim0 = (int) dim0Arr[2];
double *_12132_v7895 = ((double *) argArr[0]);
double *_12096_v7868 = ((double *) argArr[1]);
double *_12130_v7891 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12132_v7895[global_id + _12132_v7895Offset] = (- pow(pow((_12096_v7868[(global_id / 80) + _12096_v7868Offset] / 20.0) + 1.0E-5, 0.5), -2.0)) * ((pow((_12096_v7868[(global_id / 80) + _12096_v7868Offset] / 20.0) + 1.0E-5, -0.5) * 0.5) * (_12130_v7891[global_id + _12130_v7891Offset] * 0.05));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
