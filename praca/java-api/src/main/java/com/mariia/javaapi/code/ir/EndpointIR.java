package com.mariia.javaapi.code.ir;


import java.util.ArrayList;
import java.util.List;

public class EndpointIR{
    public String http;
    public String path;
    public String operationId;
    public String summary;


    public String description;//glowny opis z Javadoc 
    
    public List<ParamIR> params = new ArrayList<>();
    public ReturnIR returns;

    public String javadoc; 

    //komentarze tuz nad deklaracja metody/klasy(// lub /*... */)
    public List<String> leadingComments=new ArrayList<>();

    //komentarze z wewnatrz metody
    public List<String> inlineComments=new ArrayList<>();

    //zlapane TODO/FIXME/HACK i td
    public List<String>todos=new ArrayList<>();

    //krotkie streszczenie inline/leading 
    public List<String> notes = new ArrayList<>();

    
}