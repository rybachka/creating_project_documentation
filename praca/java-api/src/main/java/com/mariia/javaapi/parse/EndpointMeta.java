package com.mariia.javaapi.parse;

import java.util.List;
import java.util.Map;

public class EndpointMeta {
    public String controller;      // com.mariia...UsersController
    public String methodName;      // create, getById, hello
    public String httpMethod;      // GET/POST/PUT/DELETE...
    public String path;            // /api/users/{id}
    public List<ParamMeta> params; // path/query/body...
    public ReturnMeta returns;     // typ zwracany
    public Map<String,Object> extras; // np. produces/consumes, tags itp.
}

