#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation777_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11876_v7793Offset = (int) offsetArr[0];
jint _11871_v7790Offset = (int) offsetArr[1];
jint _11876_v7793Size = (int) sizeArr[0];
jint _11871_v7790Size = (int) sizeArr[1];
jint _11876_v7793Dim0 = (int) dim0Arr[0];
jint _11871_v7790Dim0 = (int) dim0Arr[1];
double *_11876_v7793 = ((double *) argArr[0]);
double *_11871_v7790 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11876_v7793[global_id + _11876_v7793Offset] = (_11871_v7790[global_id + _11871_v7790Offset + 75] + -0.048700321248903944) / 0.02831573030696967;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
