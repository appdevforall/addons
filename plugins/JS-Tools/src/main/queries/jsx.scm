(jsx_opening_element (identifier) @keyword (#match? @keyword "^[a-z][^.]*$"))
(jsx_closing_element (identifier) @keyword (#match? @keyword "^[a-z][^.]*$"))
(jsx_self_closing_element (identifier) @keyword (#match? @keyword "^[a-z][^.]*$"))

(jsx_attribute (property_identifier) @attribute)
