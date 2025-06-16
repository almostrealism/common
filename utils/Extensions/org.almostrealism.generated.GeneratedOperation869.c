#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation869_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13230_v8538Offset = (int) offsetArr[0];
jint _13180_v8508Offset = (int) offsetArr[1];
jint _13225_v8528Offset = (int) offsetArr[2];
jint _13230_v8538Size = (int) sizeArr[0];
jint _13180_v8508Size = (int) sizeArr[1];
jint _13225_v8528Size = (int) sizeArr[2];
jint _13230_v8538Dim0 = (int) dim0Arr[0];
jint _13180_v8508Dim0 = (int) dim0Arr[1];
jint _13225_v8528Dim0 = (int) dim0Arr[2];
double *_13230_v8538 = ((double *) argArr[0]);
double *_13180_v8508 = ((double *) argArr[1]);
double *_13225_v8528 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13230_v8538[global_id + _13230_v8538Offset] = pow(pow(((_13180_v8508[((global_id / 16) * 2) + _13180_v8508Offset + 1] + _13180_v8508[((global_id / 16) * 2) + _13180_v8508Offset]) / 2.0) + 1.0E-5, 0.5), -1.0) * (((_13225_v8528[((((global_id / 16) * 8) + (global_id % 8)) * 2) + _13225_v8528Offset + 1] + _13225_v8528[((((global_id / 16) * 8) + (global_id % 8)) * 2) + _13225_v8528Offset]) * -0.5) + ((((- (global_id % 8)) + (global_id / 8)) == 0) ? 1 : 0));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
