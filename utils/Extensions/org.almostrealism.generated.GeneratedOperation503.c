#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation503_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6668_v4958Offset = (int) offsetArr[0];
jint _6667_v4956Offset = (int) offsetArr[1];
jint _6668_v4958Size = (int) sizeArr[0];
jint _6667_v4956Size = (int) sizeArr[1];
jint _6668_v4958Dim0 = (int) dim0Arr[0];
jint _6667_v4956Dim0 = (int) dim0Arr[1];
double *_6668_v4958 = ((double *) argArr[0]);
double *_6667_v4956 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6668_v4958[_6668_v4958Offset] = (_6667_v4956[_6667_v4956Offset + 5] + _6667_v4956[_6667_v4956Offset + 6] + _6667_v4956[_6667_v4956Offset + 10] + _6667_v4956[_6667_v4956Offset + 7] + _6667_v4956[_6667_v4956Offset + 13] + _6667_v4956[_6667_v4956Offset + 9] + _6667_v4956[_6667_v4956Offset + 8] + _6667_v4956[_6667_v4956Offset + 15] + _6667_v4956[_6667_v4956Offset] + _6667_v4956[_6667_v4956Offset + 1] + _6667_v4956[_6667_v4956Offset + 12] + _6667_v4956[_6667_v4956Offset + 2] + _6667_v4956[_6667_v4956Offset + 3] + _6667_v4956[_6667_v4956Offset + 4] + _6667_v4956[_6667_v4956Offset + 14] + _6667_v4956[_6667_v4956Offset + 11]) / 16.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
