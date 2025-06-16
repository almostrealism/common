#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation524_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7181_v5058Offset = (int) offsetArr[0];
jint _7114_v5047Offset = (int) offsetArr[1];
jint _7178_v5050Offset = (int) offsetArr[2];
jint _7178_v5051Offset = (int) offsetArr[3];
jint _7180_v5056Offset = (int) offsetArr[4];
jint _7181_v5058Size = (int) sizeArr[0];
jint _7114_v5047Size = (int) sizeArr[1];
jint _7178_v5050Size = (int) sizeArr[2];
jint _7178_v5051Size = (int) sizeArr[3];
jint _7180_v5056Size = (int) sizeArr[4];
jint _7181_v5058Dim0 = (int) dim0Arr[0];
jint _7114_v5047Dim0 = (int) dim0Arr[1];
jint _7178_v5050Dim0 = (int) dim0Arr[2];
jint _7178_v5051Dim0 = (int) dim0Arr[3];
jint _7180_v5056Dim0 = (int) dim0Arr[4];
double *_7181_v5058 = ((double *) argArr[0]);
double *_7114_v5047 = ((double *) argArr[1]);
double *_7178_v5050 = ((double *) argArr[2]);
double *_7178_v5051 = ((double *) argArr[3]);
double *_7180_v5056 = ((double *) argArr[4]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7181_v5058[global_id + _7181_v5058Offset] = (_7114_v5047[(global_id / 2) + _7114_v5047Offset] * (_7178_v5050[global_id + _7178_v5050Offset] + _7178_v5051[global_id + _7178_v5051Offset])) * _7180_v5056[(global_id / 2) + _7180_v5056Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
