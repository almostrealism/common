#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation471_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6268_v4667Offset = (int) offsetArr[0];
jint _6267_v4665Offset = (int) offsetArr[1];
jint _6268_v4667Size = (int) sizeArr[0];
jint _6267_v4665Size = (int) sizeArr[1];
jint _6268_v4667Dim0 = (int) dim0Arr[0];
jint _6267_v4665Dim0 = (int) dim0Arr[1];
double *_6268_v4667 = ((double *) argArr[0]);
double *_6267_v4665 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6268_v4667[_6268_v4667Offset] = (_6267_v4665[_6267_v4665Offset + 5] + _6267_v4665[_6267_v4665Offset + 6] + _6267_v4665[_6267_v4665Offset + 10] + _6267_v4665[_6267_v4665Offset + 7] + _6267_v4665[_6267_v4665Offset + 13] + _6267_v4665[_6267_v4665Offset + 9] + _6267_v4665[_6267_v4665Offset + 8] + _6267_v4665[_6267_v4665Offset + 15] + _6267_v4665[_6267_v4665Offset] + _6267_v4665[_6267_v4665Offset + 1] + _6267_v4665[_6267_v4665Offset + 12] + _6267_v4665[_6267_v4665Offset + 2] + _6267_v4665[_6267_v4665Offset + 3] + _6267_v4665[_6267_v4665Offset + 4] + _6267_v4665[_6267_v4665Offset + 14] + _6267_v4665[_6267_v4665Offset + 11]) / 16.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
