#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation189_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2449_v2262Offset = (int) offsetArr[0];
jint _2449_v2263Offset = (int) offsetArr[1];
jint _2449_v2262Size = (int) sizeArr[0];
jint _2449_v2263Size = (int) sizeArr[1];
jint _2449_v2262Dim0 = (int) dim0Arr[0];
jint _2449_v2263Dim0 = (int) dim0Arr[1];
double *_2449_v2262 = ((double *) argArr[0]);
double *_2449_v2263 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2449_v2262[global_id + _2449_v2262Offset] = (((global_id / 6000) == 9) ? 0.6930454361269279 : (((global_id / 6000) == 8) ? 0.37173108594197923 : (((global_id / 6000) == 7) ? 0.7141164104477828 : (((global_id / 6000) == 6) ? 0.14771522185014074 : (((global_id / 6000) == 5) ? 0.9109191725522144 : (((global_id / 6000) == 4) ? 0.8290720056236606 : (((global_id / 6000) == 3) ? 0.16071184889386947 : (((global_id / 6000) == 2) ? 0.5484474917257498 : (((global_id / 6000) == 1) ? 0.8866989131046983 : 0.27216573926020415))))))))) * _2449_v2263[global_id + _2449_v2263Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
