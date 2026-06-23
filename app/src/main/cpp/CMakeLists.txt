cmake_minimum_required(VERSION 3.18.1)
project("fandoghshekan")

# ۱. معرفی کتابخانه نیتیو Xray که در قدم قبل دانلود کردیم
add_library(xray-core SHARED IMPORTED)
set_target_properties(xray-core PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libxray.so)

# ۲. ساخت کتابخانه پل ارتباطی خودمان
add_library(native-lib SHARED native-lib.c)

# ۳. لینک کردن کتابخانه‌ها به یکدیگر
find_library(log-lib log)
target_link_libraries(native-lib xray-core ${log-lib})
