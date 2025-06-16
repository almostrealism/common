#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation195_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2524_v2314Offset = (int) offsetArr[0];
jint _2510_v2308Offset = (int) offsetArr[1];
jint _2524_v2314Size = (int) sizeArr[0];
jint _2510_v2308Size = (int) sizeArr[1];
jint _2524_v2314Dim0 = (int) dim0Arr[0];
jint _2510_v2308Dim0 = (int) dim0Arr[1];
double *_2524_v2314 = ((double *) argArr[0]);
double *_2510_v2308 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2524_v2314[(global_id * _2524_v2314Dim0) + _2524_v2314Offset] = (((- (((global_id % 4) * 4) + ((global_id / 4) * 2) + (global_id % 2))) + ((global_id % 4) * 5)) == 0) ? _2510_v2308[(global_id % 2) + _2510_v2308Offset] : 0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
