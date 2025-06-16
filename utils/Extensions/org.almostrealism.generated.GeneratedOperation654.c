#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation654_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10144_v6675Offset = (int) offsetArr[0];
jint _10144_v6676Offset = (int) offsetArr[1];
jint _10144_v6678Offset = (int) offsetArr[2];
jint _10144_v6675Size = (int) sizeArr[0];
jint _10144_v6676Size = (int) sizeArr[1];
jint _10144_v6678Size = (int) sizeArr[2];
jint _10144_v6675Dim0 = (int) dim0Arr[0];
jint _10144_v6676Dim0 = (int) dim0Arr[1];
jint _10144_v6678Dim0 = (int) dim0Arr[2];
double *_10144_v6675 = ((double *) argArr[0]);
double *_10144_v6676 = ((double *) argArr[1]);
double *_10144_v6678 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10144_v6675[global_id + _10144_v6675Offset] = _10144_v6676[global_id + _10144_v6676Offset] * _10144_v6678[global_id + _10144_v6678Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
