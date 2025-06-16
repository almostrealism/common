#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation485_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6428_v4793Offset = (int) offsetArr[0];
jint _6427_v4791Offset = (int) offsetArr[1];
jint _6428_v4793Size = (int) sizeArr[0];
jint _6427_v4791Size = (int) sizeArr[1];
jint _6428_v4793Dim0 = (int) dim0Arr[0];
jint _6427_v4791Dim0 = (int) dim0Arr[1];
double *_6428_v4793 = ((double *) argArr[0]);
double *_6427_v4791 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6428_v4793[_6428_v4793Offset] = (_6427_v4791[_6427_v4791Offset + 5] + _6427_v4791[_6427_v4791Offset + 6] + _6427_v4791[_6427_v4791Offset + 10] + _6427_v4791[_6427_v4791Offset + 7] + _6427_v4791[_6427_v4791Offset + 13] + _6427_v4791[_6427_v4791Offset + 9] + _6427_v4791[_6427_v4791Offset + 8] + _6427_v4791[_6427_v4791Offset + 15] + _6427_v4791[_6427_v4791Offset] + _6427_v4791[_6427_v4791Offset + 1] + _6427_v4791[_6427_v4791Offset + 12] + _6427_v4791[_6427_v4791Offset + 2] + _6427_v4791[_6427_v4791Offset + 3] + _6427_v4791[_6427_v4791Offset + 4] + _6427_v4791[_6427_v4791Offset + 14] + _6427_v4791[_6427_v4791Offset + 11]) / 16.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
