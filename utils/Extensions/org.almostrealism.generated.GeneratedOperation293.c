#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation293_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3547_v3105Offset = (int) offsetArr[0];
jint _3547_v3106Offset = (int) offsetArr[1];
jint _3547_v3108Offset = (int) offsetArr[2];
jint _3547_v3105Size = (int) sizeArr[0];
jint _3547_v3106Size = (int) sizeArr[1];
jint _3547_v3108Size = (int) sizeArr[2];
jint _3547_v3105Dim0 = (int) dim0Arr[0];
jint _3547_v3106Dim0 = (int) dim0Arr[1];
jint _3547_v3108Dim0 = (int) dim0Arr[2];
double *_3547_v3105 = ((double *) argArr[0]);
double *_3547_v3106 = ((double *) argArr[1]);
double *_3547_v3108 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3547_v3105[global_id + _3547_v3105Offset] = _3547_v3106[global_id + _3547_v3106Offset] * _3547_v3108[global_id + _3547_v3108Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
