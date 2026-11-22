# Watchtower NDK crash handler (SDK-003). Built only with -Pwatchtower.ndk=true (build.gradle.kts).
cmake_minimum_required(VERSION 3.22)
project(watchtower_ndk C)
add_library(watchtower-ndk SHARED watchtower_ndk.c)
target_compile_options(watchtower-ndk PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_libraries(watchtower-ndk dl)
