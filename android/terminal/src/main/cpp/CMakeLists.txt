cmake_minimum_required(VERSION 3.22)
project(wallflower_terminal C)

set(GHOSTTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../libghostty/${ANDROID_ABI})
if(NOT EXISTS ${GHOSTTY_DIR}/lib/libghostty-vt.a)
  message(FATAL_ERROR "libghostty-vt missing for ${ANDROID_ABI}; run scripts/build-libghostty.sh")
endif()

add_library(ghostty-vt STATIC IMPORTED)
set_target_properties(ghostty-vt PROPERTIES
  IMPORTED_LOCATION ${GHOSTTY_DIR}/lib/libghostty-vt.a
  INTERFACE_INCLUDE_DIRECTORIES ${GHOSTTY_DIR}/include)

add_library(wallflower_terminal SHARED terminal.c)
target_compile_options(wallflower_terminal PRIVATE -Wall -Wextra -Werror -O2)
target_link_libraries(wallflower_terminal ghostty-vt log)
target_link_options(wallflower_terminal PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL
  $<$<NOT:$<CONFIG:Debug>>:-Wl,--strip-all>)
