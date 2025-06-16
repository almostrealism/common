#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation497_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6588_v4903Offset = (int) offsetArr[0];
jint _6587_v4901Offset = (int) offsetArr[1];
jint _6588_v4903Size = (int) sizeArr[0];
jint _6587_v4901Size = (int) sizeArr[1];
jint _6588_v4903Dim0 = (int) dim0Arr[0];
jint _6587_v4901Dim0 = (int) dim0Arr[1];
double *_6588_v4903 = ((double *) argArr[0]);
double *_6587_v4901 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6588_v4903[_6588_v4903Offset] = (_6587_v4901[_6587_v4901Offset + 5] + _6587_v4901[_6587_v4901Offset + 6] + _6587_v4901[_6587_v4901Offset + 10] + _6587_v4901[_6587_v4901Offset + 7] + _6587_v4901[_6587_v4901Offset + 13] + _6587_v4901[_6587_v4901Offset + 9] + _6587_v4901[_6587_v4901Offset + 8] + _6587_v4901[_6587_v4901Offset + 15] + _6587_v4901[_6587_v4901Offset] + _6587_v4901[_6587_v4901Offset + 1] + _6587_v4901[_6587_v4901Offset + 12] + _6587_v4901[_6587_v4901Offset + 2] + _6587_v4901[_6587_v4901Offset + 3] + _6587_v4901[_6587_v4901Offset + 4] + _6587_v4901[_6587_v4901Offset + 14] + _6587_v4901[_6587_v4901Offset + 11]) / 16.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
