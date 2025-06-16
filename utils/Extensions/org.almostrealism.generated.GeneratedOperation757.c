#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation757_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11684_v7593Offset = (int) offsetArr[0];
jint _11648_v7566Offset = (int) offsetArr[1];
jint _11682_v7589Offset = (int) offsetArr[2];
jint _11684_v7593Size = (int) sizeArr[0];
jint _11648_v7566Size = (int) sizeArr[1];
jint _11682_v7589Size = (int) sizeArr[2];
jint _11684_v7593Dim0 = (int) dim0Arr[0];
jint _11648_v7566Dim0 = (int) dim0Arr[1];
jint _11682_v7589Dim0 = (int) dim0Arr[2];
double *_11684_v7593 = ((double *) argArr[0]);
double *_11648_v7566 = ((double *) argArr[1]);
double *_11682_v7589 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11684_v7593[global_id + _11684_v7593Offset] = (- pow(pow((_11648_v7566[(global_id / 100) + _11648_v7566Offset] / 25.0) + 1.0E-5, 0.5), -2.0)) * ((pow((_11648_v7566[(global_id / 100) + _11648_v7566Offset] / 25.0) + 1.0E-5, -0.5) * 0.5) * (_11682_v7589[global_id + _11682_v7589Offset] * 0.04));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
