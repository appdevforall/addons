(line_comment (doc_comment)) @comment
(block_comment (doc_comment)) @comment
(line_comment) @comment
(block_comment) @comment

(attribute_item) @attribute
(inner_attribute_item) @attribute

(escape_sequence) @string.escape
(char_literal) @string
(string_literal) @string
(raw_string_literal) @string

(boolean_literal) @boolean
(integer_literal) @number
(float_literal) @number

(macro_invocation
  macro: (identifier) @function.builtin
  "!" @function.builtin)
(macro_invocation
  macro: (scoped_identifier
    name: (identifier) @function.builtin))

(function_item (identifier) @function)
(function_signature_item (identifier) @function)

(call_expression
  function: (field_expression
    field: (field_identifier) @function.method))
(generic_function
  function: (field_expression
    field: (field_identifier) @function.method))
(call_expression
  function: (scoped_identifier
    name: (identifier) @function.call))
(generic_function
  function: (scoped_identifier
    name: (identifier) @function.call))
((call_expression
  function: (identifier) @constructor)
 (#match? @constructor "^[A-Z]"))
(call_expression
  function: (identifier) @function.call)
(generic_function
  function: (identifier) @function.call)

(struct_pattern
  type: (scoped_type_identifier
    name: (type_identifier) @constructor))

((scoped_identifier
  path: (identifier) @type)
 (#match? @type "^[A-Z]"))
((scoped_identifier
  path: (scoped_identifier
    name: (identifier) @type))
 (#match? @type "^[A-Z]"))
((scoped_type_identifier
  path: (identifier) @type)
 (#match? @type "^[A-Z]"))
((scoped_type_identifier
  path: (scoped_identifier
    name: (identifier) @type))
 (#match? @type "^[A-Z]"))

(lifetime (identifier) @label)
(parameter (identifier) @variable.parameter)

(use_list (self) @keyword)
(scoped_use_list (self) @keyword)
(scoped_identifier (self) @keyword)
(crate) @keyword
(super) @keyword
(mutable_specifier) @keyword
(self) @variable.builtin

"as" @keyword
"async" @keyword
"await" @keyword
"break" @keyword
"const" @keyword
"continue" @keyword
"default" @keyword
"dyn" @keyword
"else" @keyword
"enum" @keyword
"extern" @keyword
"fn" @keyword
"for" @keyword
"gen" @keyword
"if" @keyword
"impl" @keyword
"in" @keyword
"let" @keyword
"loop" @keyword
"macro_rules!" @keyword
"match" @keyword
"mod" @keyword
"move" @keyword
"pub" @keyword
"raw" @keyword
"ref" @keyword
"return" @keyword
"static" @keyword
"struct" @keyword
"trait" @keyword
"type" @keyword
"union" @keyword
"unsafe" @keyword
"use" @keyword
"where" @keyword
"while" @keyword
"yield" @keyword

(primitive_type) @type.builtin
(type_identifier) @type
(field_identifier) @property

((identifier) @constant
 (#match? @constant "^[A-Z][A-Z\\d_]+$"))
((identifier) @constructor
 (#match? @constructor "^[A-Z]"))

"*" @operator
"&" @operator
"'" @operator
