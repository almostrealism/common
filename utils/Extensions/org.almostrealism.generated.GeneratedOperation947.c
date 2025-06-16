#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation947_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _14284_v9361Offset = (int) offsetArr[0];
jint _14279_v9358Offset = (int) offsetArr[1];
jint _14284_v9361Size = (int) sizeArr[0];
jint _14279_v9358Size = (int) sizeArr[1];
jint _14284_v9361Dim0 = (int) dim0Arr[0];
jint _14279_v9358Dim0 = (int) dim0Arr[1];
double *_14284_v9361 = ((double *) argArr[0]);
double *_14279_v9358 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_14284_v9361[global_id + _14284_v9361Offset] = (_14279_v9358[global_id + _14279_v9358Offset] + -0.08551130594407358) / 0.013030528745082775;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
