#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation629_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9985_v6486Offset = (int) offsetArr[0];
jint _9985_v6487Offset = (int) offsetArr[1];
jint _9985_v6486Size = (int) sizeArr[0];
jint _9985_v6487Size = (int) sizeArr[1];
jint _9985_v6486Dim0 = (int) dim0Arr[0];
jint _9985_v6487Dim0 = (int) dim0Arr[1];
double *_9985_v6486 = ((double *) argArr[0]);
double *_9985_v6487 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9985_v6486[global_id + _9985_v6486Offset] = _9985_v6487[(((global_id % 16) * 16) + (global_id / 16)) + _9985_v6487Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
