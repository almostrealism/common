#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation865_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13217_v8574Offset = (int) offsetArr[0];
jint _13180_v8542Offset = (int) offsetArr[1];
jint _13214_v8568Offset = (int) offsetArr[2];
jint _13217_v8574Size = (int) sizeArr[0];
jint _13180_v8542Size = (int) sizeArr[1];
jint _13214_v8568Size = (int) sizeArr[2];
jint _13217_v8574Dim0 = (int) dim0Arr[0];
jint _13180_v8542Dim0 = (int) dim0Arr[1];
jint _13214_v8568Dim0 = (int) dim0Arr[2];
double *_13217_v8574 = ((double *) argArr[0]);
double *_13180_v8542 = ((double *) argArr[1]);
double *_13214_v8568 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13217_v8574[global_id + _13217_v8574Offset] = (- pow(pow(((_13180_v8542[((global_id / 8) * 2) + _13180_v8542Offset + 1] + _13180_v8542[((global_id / 8) * 2) + _13180_v8542Offset]) / 2.0) + 1.0E-5, 0.5), -2.0)) * ((pow(((_13180_v8542[((global_id / 8) * 2) + _13180_v8542Offset + 1] + _13180_v8542[((global_id / 8) * 2) + _13180_v8542Offset]) / 2.0) + 1.0E-5, -0.5) * 0.5) * ((_13214_v8568[(global_id * 2) + _13214_v8568Offset + 1] + _13214_v8568[(global_id * 2) + _13214_v8568Offset]) * 0.5));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
