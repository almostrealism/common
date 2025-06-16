#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation653_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10143_v6670Offset = (int) offsetArr[0];
jint _10143_v6671Offset = (int) offsetArr[1];
jint _10143_v6673Offset = (int) offsetArr[2];
jint _10143_v6670Size = (int) sizeArr[0];
jint _10143_v6671Size = (int) sizeArr[1];
jint _10143_v6673Size = (int) sizeArr[2];
jint _10143_v6670Dim0 = (int) dim0Arr[0];
jint _10143_v6671Dim0 = (int) dim0Arr[1];
jint _10143_v6673Dim0 = (int) dim0Arr[2];
double *_10143_v6670 = ((double *) argArr[0]);
double *_10143_v6671 = ((double *) argArr[1]);
double *_10143_v6673 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10143_v6670[global_id + _10143_v6670Offset] = _10143_v6671[global_id + _10143_v6671Offset] * _10143_v6673[global_id + _10143_v6673Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
