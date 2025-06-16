#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation31_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _687_v415Offset = (int) offsetArr[0];
jint _687_v416Offset = (int) offsetArr[1];
jint _687_v415Size = (int) sizeArr[0];
jint _687_v416Size = (int) sizeArr[1];
jint _687_v415Dim0 = (int) dim0Arr[0];
jint _687_v416Dim0 = (int) dim0Arr[1];
double *_687_v415 = ((double *) argArr[0]);
double *_687_v416 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_687_v415[global_id + _687_v415Offset] = (global_id == 30) ? 188.49555921538757 : ((global_id == 29) ? 182.212373908208 : ((global_id == 28) ? 175.92918860102841 : ((global_id == 27) ? 169.64600329384882 : _687_v416[global_id + _687_v416Offset])));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
