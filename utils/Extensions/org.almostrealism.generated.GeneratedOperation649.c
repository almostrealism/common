#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation649_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10056_v6637Offset = (int) offsetArr[0];
jint _10051_v6629Offset = (int) offsetArr[1];
jint _10052_v6630Offset = (int) offsetArr[2];
jint _10055_v6636Offset = (int) offsetArr[3];
jint _10056_v6637Size = (int) sizeArr[0];
jint _10051_v6629Size = (int) sizeArr[1];
jint _10052_v6630Size = (int) sizeArr[2];
jint _10055_v6636Size = (int) sizeArr[3];
jint _10056_v6637Dim0 = (int) dim0Arr[0];
jint _10051_v6629Dim0 = (int) dim0Arr[1];
jint _10052_v6630Dim0 = (int) dim0Arr[2];
jint _10055_v6636Dim0 = (int) dim0Arr[3];
double *_10056_v6637 = ((double *) argArr[0]);
double *_10051_v6629 = ((double *) argArr[1]);
double *_10052_v6630 = ((double *) argArr[2]);
double *_10055_v6636 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10056_v6637[(global_id * _10056_v6637Dim0) + _10056_v6637Offset] = ((- _10052_v6630[(((global_id / _10052_v6630Size) * _10052_v6630Dim0) + (global_id % _10052_v6630Size)) + _10052_v6630Offset]) + _10051_v6629[(((global_id / 4) * _10051_v6629Dim0) + (global_id % 4)) + _10051_v6629Offset]) / _10055_v6636[(((global_id / _10055_v6636Size) * _10055_v6636Dim0) + (global_id % _10055_v6636Size)) + _10055_v6636Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
