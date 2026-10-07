cmake_minimum_required(VERSION 3.22.1)
project(tree_sitter_grammars C)

function(add_grammar name source_dir)
    set(target tree-sitter-${name})
    add_library(${target} SHARED ${source_dir}/parser.c ${source_dir}/scanner.c)
    target_include_directories(${target} PRIVATE ${source_dir})
    set_target_properties(${target} PROPERTIES C_STANDARD 11)
    target_link_options(${target} PRIVATE -Wl,-z,max-page-size=16384)
endfunction()

add_grammar(javascript ${GRAMMARS_DIR}/javascript/src)
add_grammar(typescript ${GRAMMARS_DIR}/typescript/typescript/src)
add_grammar(tsx ${GRAMMARS_DIR}/typescript/tsx/src)
