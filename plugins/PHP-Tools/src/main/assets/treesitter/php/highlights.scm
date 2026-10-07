(comment) @comment

[
  (string)
  (string_content)
  (encapsed_string)
  (heredoc)
  (heredoc_body)
  (nowdoc_body)
] @string

(escape_sequence) @string.escape

[
  (integer)
  (float)
] @number

(boolean) @boolean
(null) @constant.builtin

[
  (php_tag)
  "?>"
] @keyword

((name) @variable.builtin
 (#eq? @variable.builtin "this"))

((variable_name (name) @variable.builtin)
 (#eq? @variable.builtin "this"))

(relative_scope) @variable.builtin

(namespace_definition
  name: (namespace_name
    (name) @module))

(namespace_name
  (name) @module)

(namespace_use_clause
  type: "function"
  [
    (name) @function
    (qualified_name
      (name) @function)
    alias: (name) @function
  ])

(namespace_use_clause
  type: "const"
  [
    (name) @constant
    (qualified_name
      (name) @constant)
    alias: (name) @constant
  ])

(namespace_use_clause
  [
    (name) @type
    (qualified_name
      (name) @type)
    alias: (name) @type
  ])

(relative_name "namespace" @module)

(primitive_type) @type.builtin
(cast_type) @type.builtin

(named_type (name) @type.builtin
  (#any-of? @type.builtin "static" "self"))

(named_type [
  (name) @type
  (qualified_name (name) @type)
  (relative_name (name) @type)
])

(class_declaration name: (name) @type)
(interface_declaration name: (name) @type)
(trait_declaration name: (name) @type)
(enum_declaration name: (name) @type)

(base_clause [(name) (qualified_name)] @type)
(class_interface_clause [(name) (qualified_name)] @type)

(scoped_call_expression
  scope: [
    (name) @type
    (qualified_name (name) @type)
    (relative_name (name) @type)
  ])

(class_constant_access_expression
  .
  [(name) (qualified_name)] @type)

(method_declaration name: (name) @constructor
  (#eq? @constructor "__construct"))

(object_creation_expression [
  (name) @constructor
  (qualified_name (name) @constructor)
  (relative_name (name) @constructor)
])

(array_creation_expression "array" @function.builtin)
(list_literal "list" @function.builtin)
(exit_statement "exit" @function.builtin)

(function_definition
  name: (name) @function)

(method_declaration
  name: (name) @function.method)

(function_call_expression
  function: [
    (qualified_name (name))
    (relative_name (name))
    (name)
  ] @function.call)

(scoped_call_expression
  name: (name) @function.method)

(member_call_expression
  name: (name) @function.method)

(nullsafe_member_call_expression
  name: (name) @function.method)

(const_declaration (const_element (name) @constant))

((name) @constant.builtin
 (#match? @constant.builtin "^__[A-Z][A-Z\\d_]+__$"))

((name) @constant
 (#match? @constant "^_?[A-Z][A-Z\\d_]+$"))

(property_element
  (variable_name) @property)

(member_access_expression
  name: (variable_name (name)) @property)

(member_access_expression
  name: (name) @property)

(nullsafe_member_access_expression
  name: (name) @property)

[
  "and"
  "as"
  "break"
  "case"
  "catch"
  "class"
  "clone"
  "const"
  "continue"
  "declare"
  "default"
  "do"
  "echo"
  "else"
  "elseif"
  "enddeclare"
  "endfor"
  "endforeach"
  "endif"
  "endswitch"
  "endwhile"
  "enum"
  "extends"
  "finally"
  "fn"
  "for"
  "foreach"
  "function"
  "global"
  "goto"
  "if"
  "implements"
  "include"
  "include_once"
  "instanceof"
  "insteadof"
  "interface"
  "match"
  "namespace"
  "new"
  "or"
  "print"
  "require"
  "require_once"
  "return"
  "switch"
  "throw"
  "trait"
  "try"
  "use"
  "while"
  "xor"
  "yield"
  (abstract_modifier)
  (final_modifier)
  (readonly_modifier)
  (static_modifier)
  (visibility_modifier)
] @keyword

(yield_expression "from" @keyword)
(function_static_declaration "static" @keyword)

[
  "="
  "+="
  "-="
  "*="
  "/="
  ".="
  "%="
  "**="
  "??="
  "+"
  "-"
  "*"
  "/"
  "%"
  "**"
  "."
  "=="
  "==="
  "!="
  "!=="
  "<>"
  "<"
  ">"
  "<="
  ">="
  "<=>"
  "&&"
  "||"
  "!"
  "??"
  "->"
  "?->"
  "=>"
  "::"
  "++"
  "--"
] @operator

"$" @variable

(variable_name) @variable
