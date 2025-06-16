#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation435_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5598_v4326Offset = (int) offsetArr[0];
jint _5598_v4327Offset = (int) offsetArr[1];
jint _5598_v4329Offset = (int) offsetArr[2];
jint _5598_v4326Size = (int) sizeArr[0];
jint _5598_v4327Size = (int) sizeArr[1];
jint _5598_v4329Size = (int) sizeArr[2];
jint _5598_v4326Dim0 = (int) dim0Arr[0];
jint _5598_v4327Dim0 = (int) dim0Arr[1];
jint _5598_v4329Dim0 = (int) dim0Arr[2];
double *_5598_v4326 = ((double *) argArr[0]);
double *_5598_v4327 = ((double *) argArr[1]);
double *_5598_v4329 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5598_v4326[global_id + _5598_v4326Offset] = _5598_v4327[global_id + _5598_v4327Offset] * _5598_v4329[global_id + _5598_v4329Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
