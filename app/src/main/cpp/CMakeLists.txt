cmake_minimum_required(VERSION 3.22.1)

project("dezzy_dsp" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(dezzy_dsp SHARED
    dezzy_dsp.cpp
)

find_library(log-lib log)
find_library(m-lib m)

target_link_libraries(dezzy_dsp
    ${log-lib}
    ${m-lib}
)
