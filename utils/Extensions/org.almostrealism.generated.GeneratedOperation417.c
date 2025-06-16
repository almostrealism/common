#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation417_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5429_v4207Offset = (int) offsetArr[0];
jint _5424_v4204Offset = (int) offsetArr[1];
jint _5429_v4207Size = (int) sizeArr[0];
jint _5424_v4204Size = (int) sizeArr[1];
jint _5429_v4207Dim0 = (int) dim0Arr[0];
jint _5424_v4204Dim0 = (int) dim0Arr[1];
double *_5429_v4207 = ((double *) argArr[0]);
double *_5424_v4204 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5429_v4207[global_id + _5429_v4207Offset] = (_5424_v4204[global_id + _5424_v4204Offset] + -0.04171080130854573) / 0.02893311252207453;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
