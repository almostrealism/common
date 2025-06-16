#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation235_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2707_v2591Offset = (int) offsetArr[0];
jint _2708_v2588Offset = (int) offsetArr[1];
jint _2711_v2597Offset = (int) offsetArr[2];
jint _2707_v2591Size = (int) sizeArr[0];
jint _2708_v2588Size = (int) sizeArr[1];
jint _2711_v2597Size = (int) sizeArr[2];
jint _2707_v2591Dim0 = (int) dim0Arr[0];
jint _2708_v2588Dim0 = (int) dim0Arr[1];
jint _2711_v2597Dim0 = (int) dim0Arr[2];
double *_2707_v2591 = ((double *) argArr[0]);
double *_2708_v2588 = ((double *) argArr[1]);
double *_2711_v2597 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2711_v2597[global_id + _2711_v2597Offset] = (_2707_v2591[((global_id / 2) * 4) + _2707_v2591Offset + 1] * _2708_v2588[(((global_id * 4) + 1) % 8) + _2708_v2588Offset]) + (_2707_v2591[((global_id / 2) * 4) + _2707_v2591Offset + 2] * _2708_v2588[(((global_id * 4) + 2) % 8) + _2708_v2588Offset]) + (_2707_v2591[((global_id / 2) * 4) + _2707_v2591Offset + 3] * _2708_v2588[(((global_id * 4) + 3) % 8) + _2708_v2588Offset]) + (_2707_v2591[((global_id / 2) * 4) + _2707_v2591Offset] * _2708_v2588[((global_id * 4) % 8) + _2708_v2588Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
