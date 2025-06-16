#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation185_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2390_v2200Offset = (int) offsetArr[0];
jint _2359_v2194Offset = (int) offsetArr[1];
jint _2390_v2200Size = (int) sizeArr[0];
jint _2359_v2194Size = (int) sizeArr[1];
jint _2390_v2200Dim0 = (int) dim0Arr[0];
jint _2359_v2194Dim0 = (int) dim0Arr[1];
double *_2390_v2200 = ((double *) argArr[0]);
double *_2359_v2194 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2390_v2200[(global_id * _2390_v2200Dim0) + _2390_v2200Offset] = (((- (((global_id % 9) * 9) + ((global_id / 9) * 3) + (global_id % 3))) + ((global_id % 9) * 10)) == 0) ? _2359_v2194[(global_id % 3) + _2359_v2194Offset] : 0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
