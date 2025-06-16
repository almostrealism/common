#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation164_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2167_v2085Offset = (int) offsetArr[0];
jint _2164_v2081Offset = (int) offsetArr[1];
jint _2167_v2085Size = (int) sizeArr[0];
jint _2164_v2081Size = (int) sizeArr[1];
jint _2167_v2085Dim0 = (int) dim0Arr[0];
jint _2164_v2081Dim0 = (int) dim0Arr[1];
double *_2167_v2085 = ((double *) argArr[0]);
double *_2164_v2081 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2167_v2085[global_id + _2167_v2085Offset] = (_2164_v2081[(global_id * 3) + _2164_v2081Offset + 1] + _2164_v2081[(global_id * 3) + _2164_v2081Offset + 2] + _2164_v2081[(global_id * 3) + _2164_v2081Offset]) * (((global_id / 9) == 2) ? 5.0E-4 : (((global_id / 9) == 1) ? 0.005 : 0.05));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
