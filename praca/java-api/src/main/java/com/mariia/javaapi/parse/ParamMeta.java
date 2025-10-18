package com.mariia.javaapi.parse;

import java.util.List;

public class ParamMeta {
    public String name;            // id, name, email, body
    public String in;              // path | query | body | header
    public String type;            // java.lang.String, com...CreateUserRequest
    public boolean required;
    public List<String> constraints; // @NotBlank, @Email, @Size(min=..)
}
