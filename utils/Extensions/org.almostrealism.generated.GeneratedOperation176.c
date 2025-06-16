#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation176_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2295_v2156Offset = (int) offsetArr[0];
jint _2295_v2157Offset = (int) offsetArr[1];
jint _2295_v2156Size = (int) sizeArr[0];
jint _2295_v2157Size = (int) sizeArr[1];
jint _2295_v2156Dim0 = (int) dim0Arr[0];
jint _2295_v2157Dim0 = (int) dim0Arr[1];
double *_2295_v2156 = ((double *) argArr[0]);
double *_2295_v2157 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2295_v2156[global_id + _2295_v2156Offset] = (((global_id / 1200) == 9) ? 0.20035708516314465 : (((global_id / 1200) == 8) ? 0.7157369557001899 : (((global_id / 1200) == 7) ? 0.16728402008267917 : (((global_id / 1200) == 6) ? 0.968669201661231 : (((global_id / 1200) == 5) ? 0.5546410381601925 : (((global_id / 1200) == 4) ? 0.13332849521942602 : (((global_id / 1200) == 3) ? 0.6714381797583904 : (((global_id / 1200) == 2) ? 0.42952964647013003 : (((global_id / 1200) == 1) ? 0.8158726469619597 : 0.48934476519902315))))))))) * _2295_v2157[global_id + _2295_v2157Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
