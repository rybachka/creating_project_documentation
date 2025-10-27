package com.mariia.javaapi.code;

public enum DescribeMode {
    PLAIN, RULE, AI;

    public String asQuery() {
        return name().toLowerCase();
    }
}
