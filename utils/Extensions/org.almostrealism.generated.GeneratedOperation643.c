#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation643_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10012_v6338Offset = (int) offsetArr[0];
jint _10036_v6341Offset = (int) offsetArr[1];
jint _10039_v6346Offset = (int) offsetArr[2];
jint _10012_v6338Size = (int) sizeArr[0];
jint _10036_v6341Size = (int) sizeArr[1];
jint _10039_v6346Size = (int) sizeArr[2];
jint _10012_v6338Dim0 = (int) dim0Arr[0];
jint _10036_v6341Dim0 = (int) dim0Arr[1];
jint _10039_v6346Dim0 = (int) dim0Arr[2];
double *_10012_v6338 = ((double *) argArr[0]);
double *_10036_v6341 = ((double *) argArr[1]);
double *_10039_v6346 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10039_v6346[global_id + _10039_v6346Offset] = (- ((_10036_v6341[(global_id * 16) + _10036_v6341Offset + 8] + _10036_v6341[(global_id * 16) + _10036_v6341Offset + 1] + _10036_v6341[(global_id * 16) + _10036_v6341Offset + 13] + _10036_v6341[(global_id * 16) + _10036_v6341Offset + 2] + _10036_v6341[(global_id * 16) + _10036_v6341Offset + 3] + _10036_v6341[(global_id * 16) + _10036_v6341Offset + 4] + _10036_v6341[(global_id * 16) + _10036_v6341Offset + 15] + _10036_v6341[(global_id * 16) + _10036_v6341Offset + 12] + _10036_v6341[(global_id * 16) + _10036_v6341Offset + 5] + _10036_v6341[(global_id * 16) + _10036_v6341Offset + 6] + _10036_v6341[(global_id * 16) + _10036_v6341Offset + 11] + _10036_v6341[(global_id * 16) + _10036_v6341Offset + 7] + _10036_v6341[(global_id * 16) + _10036_v6341Offset + 14] + _10036_v6341[(global_id * 16) + _10036_v6341Offset + 10] + _10036_v6341[(global_id * 16) + _10036_v6341Offset + 9] + _10036_v6341[(global_id * 16) + _10036_v6341Offset]) * _10012_v6338[_10012_v6338Offset])) + _10039_v6346[global_id + _10039_v6346Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
