#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation646_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10041_v6351Offset = (int) offsetArr[0];
jint _10046_v6354Offset = (int) offsetArr[1];
jint _10049_v6359Offset = (int) offsetArr[2];
jint _10041_v6351Size = (int) sizeArr[0];
jint _10046_v6354Size = (int) sizeArr[1];
jint _10049_v6359Size = (int) sizeArr[2];
jint _10041_v6351Dim0 = (int) dim0Arr[0];
jint _10046_v6354Dim0 = (int) dim0Arr[1];
jint _10049_v6359Dim0 = (int) dim0Arr[2];
double *_10041_v6351 = ((double *) argArr[0]);
double *_10046_v6354 = ((double *) argArr[1]);
double *_10049_v6359 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10049_v6359[global_id + _10049_v6359Offset] = (- ((_10046_v6354[(global_id * 16) + _10046_v6354Offset + 8] + _10046_v6354[(global_id * 16) + _10046_v6354Offset + 1] + _10046_v6354[(global_id * 16) + _10046_v6354Offset + 13] + _10046_v6354[(global_id * 16) + _10046_v6354Offset + 2] + _10046_v6354[(global_id * 16) + _10046_v6354Offset + 3] + _10046_v6354[(global_id * 16) + _10046_v6354Offset + 4] + _10046_v6354[(global_id * 16) + _10046_v6354Offset + 15] + _10046_v6354[(global_id * 16) + _10046_v6354Offset + 12] + _10046_v6354[(global_id * 16) + _10046_v6354Offset + 5] + _10046_v6354[(global_id * 16) + _10046_v6354Offset + 6] + _10046_v6354[(global_id * 16) + _10046_v6354Offset + 11] + _10046_v6354[(global_id * 16) + _10046_v6354Offset + 7] + _10046_v6354[(global_id * 16) + _10046_v6354Offset + 14] + _10046_v6354[(global_id * 16) + _10046_v6354Offset + 10] + _10046_v6354[(global_id * 16) + _10046_v6354Offset + 9] + _10046_v6354[(global_id * 16) + _10046_v6354Offset]) * _10041_v6351[_10041_v6351Offset])) + _10049_v6359[global_id + _10049_v6359Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
