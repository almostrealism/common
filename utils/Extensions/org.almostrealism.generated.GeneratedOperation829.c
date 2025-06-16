#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation829_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12580_v8197Offset = (int) offsetArr[0];
jint _12544_v8170Offset = (int) offsetArr[1];
jint _12578_v8193Offset = (int) offsetArr[2];
jint _12580_v8197Size = (int) sizeArr[0];
jint _12544_v8170Size = (int) sizeArr[1];
jint _12578_v8193Size = (int) sizeArr[2];
jint _12580_v8197Dim0 = (int) dim0Arr[0];
jint _12544_v8170Dim0 = (int) dim0Arr[1];
jint _12578_v8193Dim0 = (int) dim0Arr[2];
double *_12580_v8197 = ((double *) argArr[0]);
double *_12544_v8170 = ((double *) argArr[1]);
double *_12578_v8193 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12580_v8197[global_id + _12580_v8197Offset] = (- pow(pow((_12544_v8170[(global_id / 120) + _12544_v8170Offset] / 30.0) + 1.0E-5, 0.5), -2.0)) * ((pow((_12544_v8170[(global_id / 120) + _12544_v8170Offset] / 30.0) + 1.0E-5, -0.5) * 0.5) * (_12578_v8193[global_id + _12578_v8193Offset] * 0.03333333333333333));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
