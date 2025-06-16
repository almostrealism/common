#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation486_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6448_v4826Offset = (int) offsetArr[0];
jint _6429_v4811Offset = (int) offsetArr[1];
jint _6448_v4826Size = (int) sizeArr[0];
jint _6429_v4811Size = (int) sizeArr[1];
jint _6448_v4826Dim0 = (int) dim0Arr[0];
jint _6429_v4811Dim0 = (int) dim0Arr[1];
double *_6448_v4826 = ((double *) argArr[0]);
double *_6429_v4811 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
double f_defaultTraversableExpressionComputation_6448_0 = (_6429_v4811[_6429_v4811Offset + 37] + _6429_v4811[_6429_v4811Offset + 38] + _6429_v4811[_6429_v4811Offset + 42] + _6429_v4811[_6429_v4811Offset + 39] + _6429_v4811[_6429_v4811Offset + 45] + _6429_v4811[_6429_v4811Offset + 41] + _6429_v4811[_6429_v4811Offset + 40] + _6429_v4811[_6429_v4811Offset + 47] + _6429_v4811[_6429_v4811Offset + 32] + _6429_v4811[_6429_v4811Offset + 33] + _6429_v4811[_6429_v4811Offset + 44] + _6429_v4811[_6429_v4811Offset + 34] + _6429_v4811[_6429_v4811Offset + 35] + _6429_v4811[_6429_v4811Offset + 36] + _6429_v4811[_6429_v4811Offset + 46] + _6429_v4811[_6429_v4811Offset + 43]) / 16.0;
_6448_v4826[_6448_v4826Offset] = ((((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 32]) * ((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 32])) + (((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 33]) * ((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 33])) + (((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 44]) * ((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 44])) + (((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 34]) * ((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 34])) + (((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 35]) * ((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 35])) + (((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 36]) * ((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 36])) + (((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 46]) * ((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 46])) + (((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 43]) * ((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 43])) + (((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 37]) * ((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 37])) + (((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 38]) * ((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 38])) + (((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 42]) * ((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 42])) + (((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 39]) * ((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 39])) + (((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 45]) * ((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 45])) + (((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 41]) * ((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 41])) + (((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 40]) * ((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 40])) + (((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 47]) * ((- f_defaultTraversableExpressionComputation_6448_0) + _6429_v4811[_6429_v4811Offset + 47]))) / 16.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
