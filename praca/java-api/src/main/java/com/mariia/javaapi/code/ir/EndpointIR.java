package com.mariia.javaapi.code.ir;


import java.util.ArrayList;
import java.util.List;

public class EndpointIR{
    public String http;
    public String path;
    public String operationId;
    public String summary;
    public String description;
    public List<ParamIR> params = new ArrayList<>();
    public ReturnIR returns;
}