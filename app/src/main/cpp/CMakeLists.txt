cmake_minimum_required(VERSION 3.18.1)
project("fandoghshekan")

add_library(native-lib SHARED native-lib.c)

find_library(log-lib log)
target_link_libraries(native-lib ${log-lib})
