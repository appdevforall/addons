cmake_minimum_required(VERSION 3.22.1)
project(tree_sitter_grammar C)

set(GRAMMAR_TARGET tree-sitter-${TREE_SITTER_GRAMMAR_NAME})
set(GRAMMAR_SOURCE_DIR ${TREE_SITTER_GRAMMAR_DIR}/src)
file(GLOB GRAMMAR_SOURCES ${GRAMMAR_SOURCE_DIR}/parser.c ${GRAMMAR_SOURCE_DIR}/scanner.c)

add_library(${GRAMMAR_TARGET} SHARED ${GRAMMAR_SOURCES})
target_include_directories(${GRAMMAR_TARGET} PRIVATE ${GRAMMAR_SOURCE_DIR})
set_target_properties(${GRAMMAR_TARGET} PROPERTIES C_STANDARD 11)
target_link_options(${GRAMMAR_TARGET} PRIVATE -Wl,-z,max-page-size=16384)
