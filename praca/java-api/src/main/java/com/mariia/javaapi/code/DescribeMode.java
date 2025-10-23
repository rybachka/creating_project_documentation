package com.mariia.javaapi.code;

public enum DescribeMode {
    PLAIN, RULE, MT5;

    public String asQuery() {
        return name().toLowerCase();
    }
}
