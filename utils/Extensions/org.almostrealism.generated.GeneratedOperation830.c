#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation830_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12582_v8132Offset = (int) offsetArr[0];
jint _12532_v8120Offset = (int) offsetArr[1];
jint _12535_v8125Offset = (int) offsetArr[2];
jint _12581_v8131Offset = (int) offsetArr[3];
jint _12582_v8132Size = (int) sizeArr[0];
jint _12532_v8120Size = (int) sizeArr[1];
jint _12535_v8125Size = (int) sizeArr[2];
jint _12581_v8131Size = (int) sizeArr[3];
jint _12582_v8132Dim0 = (int) dim0Arr[0];
jint _12532_v8120Dim0 = (int) dim0Arr[1];
jint _12535_v8125Dim0 = (int) dim0Arr[2];
jint _12581_v8131Dim0 = (int) dim0Arr[3];
double *_12582_v8132 = ((double *) argArr[0]);
double *_12532_v8120 = ((double *) argArr[1]);
double *_12535_v8125 = ((double *) argArr[2]);
double *_12581_v8131 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12582_v8132[global_id + _12582_v8132Offset] = ((- (_12532_v8120[(global_id / 3600) + _12532_v8120Offset] / 30.0)) + _12535_v8125[(global_id / 120) + _12535_v8125Offset]) * _12581_v8131[(((global_id / 3600) * 120) + (global_id % 120)) + _12581_v8131Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
