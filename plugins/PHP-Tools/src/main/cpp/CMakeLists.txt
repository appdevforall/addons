cmake_minimum_required(VERSION 3.22.1)
project(tree_sitter_grammar C)

set(GRAMMAR_SOURCE_DIR ${GRAMMAR_DIR}/php/src)

add_library(tree-sitter-php SHARED ${GRAMMAR_SOURCE_DIR}/parser.c ${GRAMMAR_SOURCE_DIR}/scanner.c)
target_include_directories(tree-sitter-php PRIVATE ${GRAMMAR_SOURCE_DIR})
set_target_properties(tree-sitter-php PROPERTIES C_STANDARD 11)
target_link_options(tree-sitter-php PRIVATE -Wl,-z,max-page-size=16384)
