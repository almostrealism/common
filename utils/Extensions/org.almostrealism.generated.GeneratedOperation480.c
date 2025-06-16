#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation480_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6368_v4771Offset = (int) offsetArr[0];
jint _6349_v4756Offset = (int) offsetArr[1];
jint _6368_v4771Size = (int) sizeArr[0];
jint _6349_v4756Size = (int) sizeArr[1];
jint _6368_v4771Dim0 = (int) dim0Arr[0];
jint _6349_v4756Dim0 = (int) dim0Arr[1];
double *_6368_v4771 = ((double *) argArr[0]);
double *_6349_v4756 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
double f_defaultTraversableExpressionComputation_6368_0 = (_6349_v4756[_6349_v4756Offset + 21] + _6349_v4756[_6349_v4756Offset + 22] + _6349_v4756[_6349_v4756Offset + 26] + _6349_v4756[_6349_v4756Offset + 23] + _6349_v4756[_6349_v4756Offset + 29] + _6349_v4756[_6349_v4756Offset + 25] + _6349_v4756[_6349_v4756Offset + 24] + _6349_v4756[_6349_v4756Offset + 31] + _6349_v4756[_6349_v4756Offset + 16] + _6349_v4756[_6349_v4756Offset + 17] + _6349_v4756[_6349_v4756Offset + 28] + _6349_v4756[_6349_v4756Offset + 18] + _6349_v4756[_6349_v4756Offset + 19] + _6349_v4756[_6349_v4756Offset + 20] + _6349_v4756[_6349_v4756Offset + 30] + _6349_v4756[_6349_v4756Offset + 27]) / 16.0;
_6368_v4771[_6368_v4771Offset] = ((((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 16]) * ((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 16])) + (((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 17]) * ((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 17])) + (((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 28]) * ((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 28])) + (((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 18]) * ((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 18])) + (((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 19]) * ((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 19])) + (((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 20]) * ((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 20])) + (((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 30]) * ((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 30])) + (((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 27]) * ((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 27])) + (((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 21]) * ((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 21])) + (((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 22]) * ((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 22])) + (((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 26]) * ((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 26])) + (((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 23]) * ((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 23])) + (((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 29]) * ((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 29])) + (((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 25]) * ((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 25])) + (((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 24]) * ((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 24])) + (((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 31]) * ((- f_defaultTraversableExpressionComputation_6368_0) + _6349_v4756[_6349_v4756Offset + 31]))) / 16.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
