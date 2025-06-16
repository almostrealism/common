#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation236_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2714_v2601Offset = (int) offsetArr[0];
jint _2712_v2599Offset = (int) offsetArr[1];
jint _2713_v2600Offset = (int) offsetArr[2];
jint _2714_v2601Size = (int) sizeArr[0];
jint _2712_v2599Size = (int) sizeArr[1];
jint _2713_v2600Size = (int) sizeArr[2];
jint _2714_v2601Dim0 = (int) dim0Arr[0];
jint _2712_v2599Dim0 = (int) dim0Arr[1];
jint _2713_v2600Dim0 = (int) dim0Arr[2];
double *_2714_v2601 = ((double *) argArr[0]);
double *_2712_v2599 = ((double *) argArr[1]);
double *_2713_v2600 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2714_v2601[(global_id * _2714_v2601Dim0) + _2714_v2601Offset] = _2712_v2599[(((global_id / _2712_v2599Size) * _2712_v2599Dim0) + (global_id % _2712_v2599Size)) + _2712_v2599Offset] / _2713_v2600[(((global_id / _2713_v2600Size) * _2713_v2600Dim0) + (global_id % _2713_v2600Size)) + _2713_v2600Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
