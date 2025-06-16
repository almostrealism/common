#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation494_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6553_v4890Offset = (int) offsetArr[0];
jint _6553_v4891Offset = (int) offsetArr[1];
jint _6553_v4893Offset = (int) offsetArr[2];
jint _6553_v4890Size = (int) sizeArr[0];
jint _6553_v4891Size = (int) sizeArr[1];
jint _6553_v4893Size = (int) sizeArr[2];
jint _6553_v4890Dim0 = (int) dim0Arr[0];
jint _6553_v4891Dim0 = (int) dim0Arr[1];
jint _6553_v4893Dim0 = (int) dim0Arr[2];
double *_6553_v4890 = ((double *) argArr[0]);
double *_6553_v4891 = ((double *) argArr[1]);
double *_6553_v4893 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6553_v4890[global_id + _6553_v4890Offset] = _6553_v4891[global_id + _6553_v4891Offset + 48] * _6553_v4893[global_id + _6553_v4893Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
