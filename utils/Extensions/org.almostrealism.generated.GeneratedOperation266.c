#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation266_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3145_v2843Offset = (int) offsetArr[0];
jint _3129_v2824Offset = (int) offsetArr[1];
jint _3132_v2829Offset = (int) offsetArr[2];
jint _3140_v2834Offset = (int) offsetArr[3];
jint _3145_v2843Size = (int) sizeArr[0];
jint _3129_v2824Size = (int) sizeArr[1];
jint _3132_v2829Size = (int) sizeArr[2];
jint _3140_v2834Size = (int) sizeArr[3];
jint _3145_v2843Dim0 = (int) dim0Arr[0];
jint _3129_v2824Dim0 = (int) dim0Arr[1];
jint _3132_v2829Dim0 = (int) dim0Arr[2];
jint _3140_v2834Dim0 = (int) dim0Arr[3];
double *_3145_v2843 = ((double *) argArr[0]);
double *_3129_v2824 = ((double *) argArr[1]);
double *_3132_v2829 = ((double *) argArr[2]);
double *_3140_v2834 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3145_v2843[global_id + _3145_v2843Offset] = ((- (_3129_v2824[(global_id / 30) + _3129_v2824Offset] / 30.0)) + _3132_v2829[global_id + _3132_v2829Offset]) / pow((_3140_v2834[(global_id / 30) + _3140_v2834Offset] / 30.0) + 1.0E-5, 0.5);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
