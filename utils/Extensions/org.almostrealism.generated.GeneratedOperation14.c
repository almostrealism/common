#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation14_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _317_v211Offset = (int) offsetArr[0];
jint _318_v214Offset = (int) offsetArr[1];
jint _318_v215Offset = (int) offsetArr[2];
jint _317_v211Size = (int) sizeArr[0];
jint _318_v214Size = (int) sizeArr[1];
jint _318_v215Size = (int) sizeArr[2];
jint _317_v211Dim0 = (int) dim0Arr[0];
jint _318_v214Dim0 = (int) dim0Arr[1];
jint _318_v215Dim0 = (int) dim0Arr[2];
double *_317_v211 = ((double *) argArr[0]);
double *_318_v214 = ((double *) argArr[1]);
double *_318_v215 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_317_v211[_317_v211Offset] = 0.002342981809057548;
_317_v211[_317_v211Offset + 1] = 0.0026840193923539286;
_317_v211[_317_v211Offset + 2] = -0.002013972708317378;
_317_v211[_317_v211Offset + 3] = -0.012165182157384656;
_317_v211[_317_v211Offset + 4] = -0.011133803964824829;
_317_v211[_317_v211Offset + 5] = 0.018958876911674638;
_317_v211[_317_v211Offset + 6] = 0.05364813743857179;
_317_v211[_317_v211Offset + 7] = 0.023582649877818124;
_317_v211[_317_v211Offset + 8] = -0.11020537436833551;
_317_v211[_317_v211Offset + 9] = -0.2826902662699673;
_317_v211[_317_v211Offset + 10] = 0.63718820861678;
_317_v211[_317_v211Offset + 11] = -0.28269026626996735;
_317_v211[_317_v211Offset + 12] = -0.11020537436833554;
_317_v211[_317_v211Offset + 13] = 0.023582649877818128;
_317_v211[_317_v211Offset + 14] = 0.05364813743857181;
_317_v211[_317_v211Offset + 15] = 0.01895887691167464;
_317_v211[_317_v211Offset + 16] = -0.01113380396482483;
_317_v211[_317_v211Offset + 17] = -0.012165182157384657;
_317_v211[_317_v211Offset + 18] = -0.002013972708317379;
_317_v211[_317_v211Offset + 19] = 0.0026840193923539286;
_317_v211[_317_v211Offset + 20] = 0.002342981809057548;
_317_v211[_317_v211Offset + 21] = -0.002342981809057548;
_317_v211[_317_v211Offset + 22] = -0.0026840193923539286;
_317_v211[_317_v211Offset + 23] = 0.002013972708317378;
_317_v211[_317_v211Offset + 24] = 0.012165182157384656;
_317_v211[_317_v211Offset + 25] = 0.011133803964824829;
_317_v211[_317_v211Offset + 26] = -0.018958876911674638;
_317_v211[_317_v211Offset + 27] = -0.05364813743857179;
_317_v211[_317_v211Offset + 28] = -0.023582649877818124;
_317_v211[_317_v211Offset + 29] = 0.11020537436833551;
_317_v211[_317_v211Offset + 30] = 0.2826902662699673;
_317_v211[_317_v211Offset + 31] = 0.36281179138321995;
_317_v211[_317_v211Offset + 32] = 0.28269026626996735;
_317_v211[_317_v211Offset + 33] = 0.11020537436833554;
_317_v211[_317_v211Offset + 34] = -0.023582649877818128;
_317_v211[_317_v211Offset + 35] = -0.05364813743857181;
_317_v211[_317_v211Offset + 36] = -0.01895887691167464;
_317_v211[_317_v211Offset + 37] = 0.01113380396482483;
_317_v211[_317_v211Offset + 38] = 0.012165182157384657;
_317_v211[_317_v211Offset + 39] = 0.002013972708317379;
_317_v211[_317_v211Offset + 40] = -0.0026840193923539286;
_317_v211[_317_v211Offset + 41] = -0.002342981809057548;
_318_v214[(global_id * _318_v214Dim0) + _318_v214Offset] = _317_v211[(((int) (floor(_318_v215[_318_v215Offset] * 2.0) * 21.0)) + (global_id * _317_v211Dim0)) + _317_v211Offset];
_318_v214[(global_id * _318_v214Dim0) + _318_v214Offset + 1] = _317_v211[(((int) ((floor(_318_v215[_318_v215Offset] * 2.0) * 21.0) + 1.0)) + (global_id * _317_v211Dim0)) + _317_v211Offset];
_318_v214[(global_id * _318_v214Dim0) + _318_v214Offset + 2] = _317_v211[(((int) ((floor(_318_v215[_318_v215Offset] * 2.0) * 21.0) + 2.0)) + (global_id * _317_v211Dim0)) + _317_v211Offset];
_318_v214[(global_id * _318_v214Dim0) + _318_v214Offset + 3] = _317_v211[(((int) ((floor(_318_v215[_318_v215Offset] * 2.0) * 21.0) + 3.0)) + (global_id * _317_v211Dim0)) + _317_v211Offset];
_318_v214[(global_id * _318_v214Dim0) + _318_v214Offset + 4] = _317_v211[(((int) ((floor(_318_v215[_318_v215Offset] * 2.0) * 21.0) + 4.0)) + (global_id * _317_v211Dim0)) + _317_v211Offset];
_318_v214[(global_id * _318_v214Dim0) + _318_v214Offset + 5] = _317_v211[(((int) ((floor(_318_v215[_318_v215Offset] * 2.0) * 21.0) + 5.0)) + (global_id * _317_v211Dim0)) + _317_v211Offset];
_318_v214[(global_id * _318_v214Dim0) + _318_v214Offset + 6] = _317_v211[(((int) ((floor(_318_v215[_318_v215Offset] * 2.0) * 21.0) + 6.0)) + (global_id * _317_v211Dim0)) + _317_v211Offset];
_318_v214[(global_id * _318_v214Dim0) + _318_v214Offset + 7] = _317_v211[(((int) ((floor(_318_v215[_318_v215Offset] * 2.0) * 21.0) + 7.0)) + (global_id * _317_v211Dim0)) + _317_v211Offset];
_318_v214[(global_id * _318_v214Dim0) + _318_v214Offset + 8] = _317_v211[(((int) ((floor(_318_v215[_318_v215Offset] * 2.0) * 21.0) + 8.0)) + (global_id * _317_v211Dim0)) + _317_v211Offset];
_318_v214[(global_id * _318_v214Dim0) + _318_v214Offset + 9] = _317_v211[(((int) ((floor(_318_v215[_318_v215Offset] * 2.0) * 21.0) + 9.0)) + (global_id * _317_v211Dim0)) + _317_v211Offset];
_318_v214[(global_id * _318_v214Dim0) + _318_v214Offset + 10] = _317_v211[(((int) ((floor(_318_v215[_318_v215Offset] * 2.0) * 21.0) + 10.0)) + (global_id * _317_v211Dim0)) + _317_v211Offset];
_318_v214[(global_id * _318_v214Dim0) + _318_v214Offset + 11] = _317_v211[(((int) ((floor(_318_v215[_318_v215Offset] * 2.0) * 21.0) + 11.0)) + (global_id * _317_v211Dim0)) + _317_v211Offset];
_318_v214[(global_id * _318_v214Dim0) + _318_v214Offset + 12] = _317_v211[(((int) ((floor(_318_v215[_318_v215Offset] * 2.0) * 21.0) + 12.0)) + (global_id * _317_v211Dim0)) + _317_v211Offset];
_318_v214[(global_id * _318_v214Dim0) + _318_v214Offset + 13] = _317_v211[(((int) ((floor(_318_v215[_318_v215Offset] * 2.0) * 21.0) + 13.0)) + (global_id * _317_v211Dim0)) + _317_v211Offset];
_318_v214[(global_id * _318_v214Dim0) + _318_v214Offset + 14] = _317_v211[(((int) ((floor(_318_v215[_318_v215Offset] * 2.0) * 21.0) + 14.0)) + (global_id * _317_v211Dim0)) + _317_v211Offset];
_318_v214[(global_id * _318_v214Dim0) + _318_v214Offset + 15] = _317_v211[(((int) ((floor(_318_v215[_318_v215Offset] * 2.0) * 21.0) + 15.0)) + (global_id * _317_v211Dim0)) + _317_v211Offset];
_318_v214[(global_id * _318_v214Dim0) + _318_v214Offset + 16] = _317_v211[(((int) ((floor(_318_v215[_318_v215Offset] * 2.0) * 21.0) + 16.0)) + (global_id * _317_v211Dim0)) + _317_v211Offset];
_318_v214[(global_id * _318_v214Dim0) + _318_v214Offset + 17] = _317_v211[(((int) ((floor(_318_v215[_318_v215Offset] * 2.0) * 21.0) + 17.0)) + (global_id * _317_v211Dim0)) + _317_v211Offset];
_318_v214[(global_id * _318_v214Dim0) + _318_v214Offset + 18] = _317_v211[(((int) ((floor(_318_v215[_318_v215Offset] * 2.0) * 21.0) + 18.0)) + (global_id * _317_v211Dim0)) + _317_v211Offset];
_318_v214[(global_id * _318_v214Dim0) + _318_v214Offset + 19] = _317_v211[(((int) ((floor(_318_v215[_318_v215Offset] * 2.0) * 21.0) + 19.0)) + (global_id * _317_v211Dim0)) + _317_v211Offset];
_318_v214[(global_id * _318_v214Dim0) + _318_v214Offset + 20] = _317_v211[(((int) ((floor(_318_v215[_318_v215Offset] * 2.0) * 21.0) + 20.0)) + (global_id * _317_v211Dim0)) + _317_v211Offset];

}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
