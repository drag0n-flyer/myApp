cmake_minimum_required(VERSION 3.22.1)
project(rnnoise_jni C)

set(RNNOISE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise)

if(NOT EXISTS ${RNNOISE_DIR}/rnnoise_data.c)
  message(FATAL_ERROR "rnnoise/rnnoise_data.c is missing: run scripts/android-fetch-rnnoise-model.sh (see rnnoise/VENDORED.md)")
endif()

add_library(rnnoise_jni SHARED
  rnnoise_jni.c
  ${RNNOISE_DIR}/celt_lpc.c
  ${RNNOISE_DIR}/denoise.c
  ${RNNOISE_DIR}/kiss_fft.c
  ${RNNOISE_DIR}/nnet.c
  ${RNNOISE_DIR}/nnet_default.c
  ${RNNOISE_DIR}/parse_lpcnet_weights.c
  ${RNNOISE_DIR}/pitch.c
  ${RNNOISE_DIR}/rnn.c
  ${RNNOISE_DIR}/rnnoise_data.c
  ${RNNOISE_DIR}/rnnoise_tables.c
)

target_include_directories(rnnoise_jni PRIVATE ${RNNOISE_DIR} ${RNNOISE_DIR}/include)
# DISABLE_DEBUG_FLOAT: upstream's default (configure.ac, unless --enable-dnn-debug-float). It leaves
# the float debug copy of the weights out of rnnoise_data.c, so the model runs on its int8 weights.
target_compile_definitions(rnnoise_jni PRIVATE NDEBUG RNNOISE_EXPORT= DISABLE_DEBUG_FLOAT)
target_compile_options(rnnoise_jni PRIVATE -O2 -fvisibility=hidden)
target_link_libraries(rnnoise_jni PRIVATE m)
# The loopback tests' tone stimulus (rnnoise_jni.c) is compiled into debug builds only.
target_compile_definitions(rnnoise_jni PRIVATE $<$<CONFIG:Debug>:APP_CAPTURE_TEST_TONE>)
