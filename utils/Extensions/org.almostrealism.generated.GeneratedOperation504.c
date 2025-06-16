#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation504_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6688_v4991Offset = (int) offsetArr[0];
jint _6669_v4976Offset = (int) offsetArr[1];
jint _6688_v4991Size = (int) sizeArr[0];
jint _6669_v4976Size = (int) sizeArr[1];
jint _6688_v4991Dim0 = (int) dim0Arr[0];
jint _6669_v4976Dim0 = (int) dim0Arr[1];
double *_6688_v4991 = ((double *) argArr[0]);
double *_6669_v4976 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
double f_defaultTraversableExpressionComputation_6688_0 = (_6669_v4976[_6669_v4976Offset + 85] + _6669_v4976[_6669_v4976Offset + 86] + _6669_v4976[_6669_v4976Offset + 90] + _6669_v4976[_6669_v4976Offset + 87] + _6669_v4976[_6669_v4976Offset + 93] + _6669_v4976[_6669_v4976Offset + 89] + _6669_v4976[_6669_v4976Offset + 88] + _6669_v4976[_6669_v4976Offset + 95] + _6669_v4976[_6669_v4976Offset + 80] + _6669_v4976[_6669_v4976Offset + 81] + _6669_v4976[_6669_v4976Offset + 92] + _6669_v4976[_6669_v4976Offset + 82] + _6669_v4976[_6669_v4976Offset + 83] + _6669_v4976[_6669_v4976Offset + 84] + _6669_v4976[_6669_v4976Offset + 94] + _6669_v4976[_6669_v4976Offset + 91]) / 16.0;
_6688_v4991[_6688_v4991Offset] = ((((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 80]) * ((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 80])) + (((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 81]) * ((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 81])) + (((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 92]) * ((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 92])) + (((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 82]) * ((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 82])) + (((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 83]) * ((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 83])) + (((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 84]) * ((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 84])) + (((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 94]) * ((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 94])) + (((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 91]) * ((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 91])) + (((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 85]) * ((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 85])) + (((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 86]) * ((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 86])) + (((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 90]) * ((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 90])) + (((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 87]) * ((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 87])) + (((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 93]) * ((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 93])) + (((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 89]) * ((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 89])) + (((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 88]) * ((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 88])) + (((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 95]) * ((- f_defaultTraversableExpressionComputation_6688_0) + _6669_v4976[_6669_v4976Offset + 95]))) / 16.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
