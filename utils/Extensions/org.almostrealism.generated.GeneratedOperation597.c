#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation597_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9165_v5838Offset = (int) offsetArr[0];
jint _9170_v5841Offset = (int) offsetArr[1];
jint _9173_v5846Offset = (int) offsetArr[2];
jint _9165_v5838Size = (int) sizeArr[0];
jint _9170_v5841Size = (int) sizeArr[1];
jint _9173_v5846Size = (int) sizeArr[2];
jint _9165_v5838Dim0 = (int) dim0Arr[0];
jint _9170_v5841Dim0 = (int) dim0Arr[1];
jint _9173_v5846Dim0 = (int) dim0Arr[2];
double *_9165_v5838 = ((double *) argArr[0]);
double *_9170_v5841 = ((double *) argArr[1]);
double *_9173_v5846 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9173_v5846[global_id + _9173_v5846Offset] = (- ((_9170_v5841[(global_id * 8) + _9170_v5841Offset + 1] + _9170_v5841[(global_id * 8) + _9170_v5841Offset + 2] + _9170_v5841[(global_id * 8) + _9170_v5841Offset + 3] + _9170_v5841[(global_id * 8) + _9170_v5841Offset + 4] + _9170_v5841[(global_id * 8) + _9170_v5841Offset + 5] + _9170_v5841[(global_id * 8) + _9170_v5841Offset + 6] + _9170_v5841[(global_id * 8) + _9170_v5841Offset + 7] + _9170_v5841[(global_id * 8) + _9170_v5841Offset]) * _9165_v5838[_9165_v5838Offset])) + _9173_v5846[global_id + _9173_v5846Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
