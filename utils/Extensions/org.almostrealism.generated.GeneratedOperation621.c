#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation621_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9499_v6320Offset = (int) offsetArr[0];
jint _9494_v6317Offset = (int) offsetArr[1];
jint _9499_v6320Size = (int) sizeArr[0];
jint _9494_v6317Size = (int) sizeArr[1];
jint _9499_v6320Dim0 = (int) dim0Arr[0];
jint _9494_v6317Dim0 = (int) dim0Arr[1];
double *_9499_v6320 = ((double *) argArr[0]);
double *_9494_v6317 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9499_v6320[global_id + _9499_v6320Offset] = (_9494_v6317[global_id + _9494_v6317Offset + 6] + -0.04967406170242337) / 0.003194210770220435;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
