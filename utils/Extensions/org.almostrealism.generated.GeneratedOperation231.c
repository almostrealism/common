#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation231_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2693_v2551Offset = (int) offsetArr[0];
jint _2688_v2539Offset = (int) offsetArr[1];
jint _2690_v2544Offset = (int) offsetArr[2];
jint _2693_v2551Size = (int) sizeArr[0];
jint _2688_v2539Size = (int) sizeArr[1];
jint _2690_v2544Size = (int) sizeArr[2];
jint _2693_v2551Dim0 = (int) dim0Arr[0];
jint _2688_v2539Dim0 = (int) dim0Arr[1];
jint _2690_v2544Dim0 = (int) dim0Arr[2];
double *_2693_v2551 = ((double *) argArr[0]);
double *_2688_v2539 = ((double *) argArr[1]);
double *_2690_v2544 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2693_v2551[global_id + _2693_v2551Offset] = (_2690_v2544[((global_id / 4) * 3) + _2690_v2544Offset + 1] * _2688_v2539[(global_id % 4) + _2688_v2539Offset + 4]) + (_2690_v2544[((global_id / 4) * 3) + _2690_v2544Offset + 2] * _2688_v2539[(global_id % 4) + _2688_v2539Offset + 8]) + (_2690_v2544[(((global_id >= 4) & (global_id < 8)) ? 3 : 0) + _2690_v2544Offset] * _2688_v2539[(global_id % 4) + _2688_v2539Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
