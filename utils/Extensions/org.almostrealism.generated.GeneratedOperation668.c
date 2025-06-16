#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation668_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10338_v6793Offset = (int) offsetArr[0];
jint _10337_v6791Offset = (int) offsetArr[1];
jint _10338_v6793Size = (int) sizeArr[0];
jint _10337_v6791Size = (int) sizeArr[1];
jint _10338_v6793Dim0 = (int) dim0Arr[0];
jint _10337_v6791Dim0 = (int) dim0Arr[1];
double *_10338_v6793 = ((double *) argArr[0]);
double *_10337_v6791 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10338_v6793[_10338_v6793Offset] = (_10337_v6791[_10337_v6791Offset] + _10337_v6791[_10337_v6791Offset + 1] + _10337_v6791[_10337_v6791Offset + 2] + _10337_v6791[_10337_v6791Offset + 3]) / 4.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
