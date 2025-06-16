#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation252_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2860_v2689Offset = (int) offsetArr[0];
jint _2860_v2691Offset = (int) offsetArr[1];
jint _2863_v2696Offset = (int) offsetArr[2];
jint _2870_v2709Offset = (int) offsetArr[3];
jint _2860_v2689Size = (int) sizeArr[0];
jint _2860_v2691Size = (int) sizeArr[1];
jint _2863_v2696Size = (int) sizeArr[2];
jint _2870_v2709Size = (int) sizeArr[3];
jint _2860_v2689Dim0 = (int) dim0Arr[0];
jint _2860_v2691Dim0 = (int) dim0Arr[1];
jint _2863_v2696Dim0 = (int) dim0Arr[2];
jint _2870_v2709Dim0 = (int) dim0Arr[3];
double *_2860_v2689 = ((double *) argArr[0]);
double *_2860_v2691 = ((double *) argArr[1]);
double *_2863_v2696 = ((double *) argArr[2]);
double *_2870_v2709 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2870_v2709[global_id + _2870_v2709Offset] = (1.0 / pow((_2863_v2696[_2863_v2696Offset] / 768.0) + 1.0E-5, 0.5)) * (_2860_v2689[global_id + _2860_v2689Offset] * _2860_v2691[global_id + _2860_v2691Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
