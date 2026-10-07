(type_identifier) @type
(predefined_type) @type.builtin

(required_parameter
  pattern: (identifier) @variable.parameter)
(optional_parameter
  pattern: (identifier) @variable.parameter)

(method_signature
  name: (property_identifier) @function.method)

[
  "abstract"
  "asserts"
  "declare"
  "enum"
  "implements"
  "infer"
  "interface"
  "is"
  "keyof"
  "namespace"
  "override"
  "private"
  "protected"
  "public"
  "readonly"
  "satisfies"
  "type"
] @keyword
