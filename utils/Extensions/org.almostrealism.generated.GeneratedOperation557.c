#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation557_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7826_v5653Offset = (int) offsetArr[0];
jint _7821_v5645Offset = (int) offsetArr[1];
jint _7822_v5646Offset = (int) offsetArr[2];
jint _7825_v5652Offset = (int) offsetArr[3];
jint _7826_v5653Size = (int) sizeArr[0];
jint _7821_v5645Size = (int) sizeArr[1];
jint _7822_v5646Size = (int) sizeArr[2];
jint _7825_v5652Size = (int) sizeArr[3];
jint _7826_v5653Dim0 = (int) dim0Arr[0];
jint _7821_v5645Dim0 = (int) dim0Arr[1];
jint _7822_v5646Dim0 = (int) dim0Arr[2];
jint _7825_v5652Dim0 = (int) dim0Arr[3];
double *_7826_v5653 = ((double *) argArr[0]);
double *_7821_v5645 = ((double *) argArr[1]);
double *_7822_v5646 = ((double *) argArr[2]);
double *_7825_v5652 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7826_v5653[(global_id * _7826_v5653Dim0) + _7826_v5653Offset] = ((- _7822_v5646[(((global_id / _7822_v5646Size) * _7822_v5646Dim0) + (global_id % _7822_v5646Size)) + _7822_v5646Offset]) + _7821_v5645[(((global_id / 3) * _7821_v5645Dim0) + (global_id % 3)) + _7821_v5645Offset]) / _7825_v5652[(((global_id / _7825_v5652Size) * _7825_v5652Dim0) + (global_id % _7825_v5652Size)) + _7825_v5652Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
