#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation491_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6508_v4848Offset = (int) offsetArr[0];
jint _6507_v4846Offset = (int) offsetArr[1];
jint _6508_v4848Size = (int) sizeArr[0];
jint _6507_v4846Size = (int) sizeArr[1];
jint _6508_v4848Dim0 = (int) dim0Arr[0];
jint _6507_v4846Dim0 = (int) dim0Arr[1];
double *_6508_v4848 = ((double *) argArr[0]);
double *_6507_v4846 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6508_v4848[_6508_v4848Offset] = (_6507_v4846[_6507_v4846Offset + 5] + _6507_v4846[_6507_v4846Offset + 6] + _6507_v4846[_6507_v4846Offset + 10] + _6507_v4846[_6507_v4846Offset + 7] + _6507_v4846[_6507_v4846Offset + 13] + _6507_v4846[_6507_v4846Offset + 9] + _6507_v4846[_6507_v4846Offset + 8] + _6507_v4846[_6507_v4846Offset + 15] + _6507_v4846[_6507_v4846Offset] + _6507_v4846[_6507_v4846Offset + 1] + _6507_v4846[_6507_v4846Offset + 12] + _6507_v4846[_6507_v4846Offset + 2] + _6507_v4846[_6507_v4846Offset + 3] + _6507_v4846[_6507_v4846Offset + 4] + _6507_v4846[_6507_v4846Offset + 14] + _6507_v4846[_6507_v4846Offset + 11]) / 16.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
