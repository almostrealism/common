#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation237_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2726_v2609Offset = (int) offsetArr[0];
jint _2712_v2604Offset = (int) offsetArr[1];
jint _2713_v2605Offset = (int) offsetArr[2];
jint _2726_v2609Size = (int) sizeArr[0];
jint _2712_v2604Size = (int) sizeArr[1];
jint _2713_v2605Size = (int) sizeArr[2];
jint _2726_v2609Dim0 = (int) dim0Arr[0];
jint _2712_v2604Dim0 = (int) dim0Arr[1];
jint _2713_v2605Dim0 = (int) dim0Arr[2];
double *_2726_v2609 = ((double *) argArr[0]);
double *_2712_v2604 = ((double *) argArr[1]);
double *_2713_v2605 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2726_v2609[(global_id * _2726_v2609Dim0) + _2726_v2609Offset] = floor(_2712_v2604[(((global_id / _2712_v2604Size) * _2712_v2604Dim0) + (global_id % _2712_v2604Size)) + _2712_v2604Offset] / _2713_v2605[(((global_id / _2713_v2605Size) * _2713_v2605Dim0) + (global_id % _2713_v2605Size)) + _2713_v2605Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
