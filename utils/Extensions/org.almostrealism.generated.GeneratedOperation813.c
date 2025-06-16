#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation813_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12324_v8095Offset = (int) offsetArr[0];
jint _12319_v8092Offset = (int) offsetArr[1];
jint _12324_v8095Size = (int) sizeArr[0];
jint _12319_v8092Size = (int) sizeArr[1];
jint _12324_v8095Dim0 = (int) dim0Arr[0];
jint _12319_v8092Dim0 = (int) dim0Arr[1];
double *_12324_v8095 = ((double *) argArr[0]);
double *_12319_v8092 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12324_v8095[global_id + _12324_v8095Offset] = (_12319_v8092[global_id + _12319_v8092Offset + 60] + -0.04264541225854663) / 0.023161948137017607;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
