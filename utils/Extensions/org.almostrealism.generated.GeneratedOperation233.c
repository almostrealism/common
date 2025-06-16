#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation233_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2699_v2566Offset = (int) offsetArr[0];
jint _2700_v2569Offset = (int) offsetArr[1];
jint _2702_v2574Offset = (int) offsetArr[2];
jint _2699_v2566Size = (int) sizeArr[0];
jint _2700_v2569Size = (int) sizeArr[1];
jint _2702_v2574Size = (int) sizeArr[2];
jint _2699_v2566Dim0 = (int) dim0Arr[0];
jint _2700_v2569Dim0 = (int) dim0Arr[1];
jint _2702_v2574Dim0 = (int) dim0Arr[2];
double *_2699_v2566 = ((double *) argArr[0]);
double *_2700_v2569 = ((double *) argArr[1]);
double *_2702_v2574 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2702_v2574[global_id + _2702_v2574Offset] = (_2699_v2566[((global_id * 4) % 4) + _2699_v2566Offset] * _2700_v2569[(global_id * 4) + _2700_v2569Offset]) + (_2700_v2569[(global_id * 4) + _2700_v2569Offset + 1] * _2699_v2566[_2699_v2566Offset + 1]) + (_2700_v2569[(global_id * 4) + _2700_v2569Offset + 2] * _2699_v2566[_2699_v2566Offset + 2]) + (_2700_v2569[(global_id * 4) + _2700_v2569Offset + 3] * _2699_v2566[_2699_v2566Offset + 3]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
