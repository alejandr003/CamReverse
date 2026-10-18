cmake_minimum_required(VERSION 3.22.1)
project(camrevert C)

# Puente mínimo a PPCS_Initialize(). Ver cpp/ppcs_shim.c
add_library(pcs_shim SHARED ppcs_shim.c)
target_link_libraries(pcs_shim log)
