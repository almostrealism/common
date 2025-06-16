#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation721_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11236_v7291Offset = (int) offsetArr[0];
jint _11200_v7264Offset = (int) offsetArr[1];
jint _11234_v7287Offset = (int) offsetArr[2];
jint _11236_v7291Size = (int) sizeArr[0];
jint _11200_v7264Size = (int) sizeArr[1];
jint _11234_v7287Size = (int) sizeArr[2];
jint _11236_v7291Dim0 = (int) dim0Arr[0];
jint _11200_v7264Dim0 = (int) dim0Arr[1];
jint _11234_v7287Dim0 = (int) dim0Arr[2];
double *_11236_v7291 = ((double *) argArr[0]);
double *_11200_v7264 = ((double *) argArr[1]);
double *_11234_v7287 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11236_v7291[global_id + _11236_v7291Offset] = (- pow(pow((_11200_v7264[(global_id / 120) + _11200_v7264Offset] / 30.0) + 1.0E-5, 0.5), -2.0)) * ((pow((_11200_v7264[(global_id / 120) + _11200_v7264Offset] / 30.0) + 1.0E-5, -0.5) * 0.5) * (_11234_v7287[global_id + _11234_v7287Offset] * 0.03333333333333333));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
