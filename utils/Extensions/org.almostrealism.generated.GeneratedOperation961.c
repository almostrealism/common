#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation961_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _14500_v9617Offset = (int) offsetArr[0];
jint _14488_v9603Offset = (int) offsetArr[1];
jint _14493_v9598Offset = (int) offsetArr[2];
jint _14500_v9617Size = (int) sizeArr[0];
jint _14488_v9603Size = (int) sizeArr[1];
jint _14493_v9598Size = (int) sizeArr[2];
jint _14500_v9617Dim0 = (int) dim0Arr[0];
jint _14488_v9603Dim0 = (int) dim0Arr[1];
jint _14493_v9598Dim0 = (int) dim0Arr[2];
double *_14500_v9617 = ((double *) argArr[0]);
double *_14488_v9603 = ((double *) argArr[1]);
double *_14493_v9598 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_14500_v9617[(global_id * _14500_v9617Dim0) + _14500_v9617Offset] = 0.0;
for (int _14500_i = 0; _14500_i < 25088;) {
jint k_14500_i = (global_id * 25088) + _14500_i;
jlong f_aggregatedProducerComputation_14500_body_0 = (((((k_14500_i) / 2517630976) * 25088) + ((k_14500_i) % 25088)) % 100352) * 100352;
jlong f_aggregatedProducerComputation_14500_body_1 = (((((k_14500_i) / 2517630976) * 25088) + ((k_14500_i) % 25088)) / 100352) + (((k_14500_i) % 2517630976) / 25088) + f_aggregatedProducerComputation_14500_body_0;
_14500_v9617[(global_id * _14500_v9617Dim0) + _14500_v9617Offset] = (((- ((((((((((int) _14488_v9603[((((((((k_14500_i) / 2517630976) * 25088) + ((k_14500_i) % 25088)) / 100352) + (((k_14500_i) % 2517630976) / 25088) + (((f_aggregatedProducerComputation_14500_body_1 / 100352) / 25088) * 100352) + ((f_aggregatedProducerComputation_14500_body_1 / 100352) * -100352) + f_aggregatedProducerComputation_14500_body_0) / 100352) % 4) + _14488_v9603Offset]) * 100352) + ((((((((k_14500_i) / 2517630976) * 25088) + ((k_14500_i) % 25088)) / 100352) + (((k_14500_i) % 2517630976) / 25088) + (((f_aggregatedProducerComputation_14500_body_1 / 100352) / 25088) * 100352) + ((f_aggregatedProducerComputation_14500_body_1 / 100352) * -100352) + f_aggregatedProducerComputation_14500_body_0) / 100352) * 2517630976) + ((((((((k_14500_i) / 2517630976) * 25088) + ((k_14500_i) % 25088)) / 100352) + (((k_14500_i) % 2517630976) / 25088) + (((f_aggregatedProducerComputation_14500_body_1 / 100352) / 25088) * 100352) + ((f_aggregatedProducerComputation_14500_body_1 / 100352) * -100352) + f_aggregatedProducerComputation_14500_body_0) / 100352) * -100352) + (((((k_14500_i) / 2517630976) * 25088) + ((k_14500_i) % 25088)) / 100352) + (((k_14500_i) % 2517630976) / 25088) + (((f_aggregatedProducerComputation_14500_body_1 / 100352) / 25088) * 100352) + ((f_aggregatedProducerComputation_14500_body_1 / 100352) * -100352) + f_aggregatedProducerComputation_14500_body_0) % 10070523904) / 100352) * 100353) + (- (((((int) _14488_v9603[((((((((k_14500_i) / 2517630976) * 25088) + ((k_14500_i) % 25088)) / 100352) + (((k_14500_i) % 2517630976) / 25088) + (((f_aggregatedProducerComputation_14500_body_1 / 100352) / 25088) * 100352) + ((f_aggregatedProducerComputation_14500_body_1 / 100352) * -100352) + f_aggregatedProducerComputation_14500_body_0) / 100352) % 4) + _14488_v9603Offset]) * 100352) + ((((((((k_14500_i) / 2517630976) * 25088) + ((k_14500_i) % 25088)) / 100352) + (((k_14500_i) % 2517630976) / 25088) + (((f_aggregatedProducerComputation_14500_body_1 / 100352) / 25088) * 100352) + ((f_aggregatedProducerComputation_14500_body_1 / 100352) * -100352) + f_aggregatedProducerComputation_14500_body_0) / 100352) * 2517630976) + ((((((((k_14500_i) / 2517630976) * 25088) + ((k_14500_i) % 25088)) / 100352) + (((k_14500_i) % 2517630976) / 25088) + (((f_aggregatedProducerComputation_14500_body_1 / 100352) / 25088) * 100352) + ((f_aggregatedProducerComputation_14500_body_1 / 100352) * -100352) + f_aggregatedProducerComputation_14500_body_0) / 100352) * -100352) + (((((k_14500_i) / 2517630976) * 25088) + ((k_14500_i) % 25088)) / 100352) + (((k_14500_i) % 2517630976) / 25088) + (((f_aggregatedProducerComputation_14500_body_1 / 100352) / 25088) * 100352) + ((f_aggregatedProducerComputation_14500_body_1 / 100352) * -100352) + f_aggregatedProducerComputation_14500_body_0) % 10070523904))) == 0) ? 1 : 0)) + ((((((f_aggregatedProducerComputation_14500_body_1 % 10070523904) / 100352) * 100353) + (- (f_aggregatedProducerComputation_14500_body_1 % 10070523904))) == 0) ? 1 : 0)) * _14493_v9598[((f_aggregatedProducerComputation_14500_body_1 / 100352) % 100352) + _14493_v9598Offset]) + _14500_v9617[(global_id * _14500_v9617Dim0) + _14500_v9617Offset];
_14500_i = _14500_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
