package com.mariia.javaapi.code;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import io.swagger.v3.oas.models.media.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import com.github.javaparser.ast.body.RecordDeclaration;
import io.swagger.v3.oas.models.media.*;
import com.github.javaparser.ast.body.EnumDeclaration;


public class JavaDtoParser {

    private final JavaParser parser = new JavaParser();

    /** Przeskanuj projekt i zbuduj mapę {nazwa klasy -> schema}. */
    public Map<String, Schema> parseDtos(Path projectDir) throws IOException {
        Map<String, Schema> schemas = new LinkedHashMap<>();

        if (!Files.exists(projectDir)) {
            System.err.println("[WARN] Pomijam skanowanie DTO – brak katalogu: " + projectDir);
            return schemas;
        }

        List<Path> javaFiles;
        try (var stream = Files.walk(projectDir)) {
            javaFiles = stream.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList());
        }
        System.out.println("[DTO] plików .java znaleziono: " + javaFiles.size() + " w " + projectDir);

        if (javaFiles.isEmpty()) return schemas;

        for (Path file : javaFiles) parseFile(file, schemas);

        System.out.println("[DTO] liczba schematów: " + schemas.size());
        return schemas;
    }

    private void parseFile(Path file, Map<String, Schema> sink) {
        try {
            ParseResult<CompilationUnit> result = parser.parse(file);
            if (!result.isSuccessful() || result.getResult().isEmpty()) return;

            CompilationUnit cu = result.getResult().get();

            for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                // pomijamy interfejsy i kontrolery
                if (clazz.isInterface() || clazz.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().matches("RestController|Controller")))
                    continue;

                if (clazz.getFields().isEmpty()) continue; // proste kryterium na DTO

                String className = clazz.getNameAsString();
                ObjectSchema schema = new ObjectSchema();
                schema.setName(className);

                Map<String, Schema> props = new LinkedHashMap<>();
                for (FieldDeclaration field : clazz.getFields()) {
                    String rawType = field.getElementType().asString();
                    Schema propSchema = mapType(rawType);
                    field.getVariables().forEach(v -> props.put(v.getNameAsString(), propSchema));
                }

                schema.setProperties(props);
                sink.put(className, schema);
            }



            for (RecordDeclaration rec : cu.findAll(RecordDeclaration.class)) {
                String className = rec.getNameAsString();
                ObjectSchema schema = new ObjectSchema();
                schema.setName(className);

                Map<String, Schema> props = new LinkedHashMap<>();
                rec.getParameters().forEach(p ->
                    props.put(p.getNameAsString(), mapType(p.getType().asString()))
                );

                schema.setProperties(props);
                sink.put(className, schema);
            }

            for (EnumDeclaration en : cu.findAll(EnumDeclaration.class)) {
                String n = en.getNameAsString();
                StringSchema s = new StringSchema();
                List<String> values = en.getEntries().stream()
                        .map(e -> e.getNameAsString())
                        .toList();
                s.setEnum(values);
                sink.put(n, s);
            }


        } catch (Exception e) {
            System.err.println("Błąd parsowania DTO: " + file + " -> " + e.getMessage());
        }
    }

    // ===== mapowanie typów (spójne z CodeToDocsService) =====

    private static final Set<String> PRIMITIVES = Set.of(
            "byte","short","int","long","float","double","boolean","char"
    );
    private static final Set<String> BUILTINS = Set.of(
            "String","Integer","Long","Float","Double","BigDecimal",
            "Boolean","UUID","Object","Date","LocalDate","LocalDateTime","OffsetDateTime"
    );

    private Schema mapType(String t) {
        if (t == null || t.isBlank()) return new ObjectSchema();
        String type = t.trim();
        // 1) Zdejmij najczęstsze wrappery generyczne:
        if (type.startsWith("ResponseEntity<") ||
            type.startsWith("Optional<") ||
            type.startsWith("CompletableFuture<") ||
            type.startsWith("Mono<") ||
            type.startsWith("Flux<")) {
            return mapType(stripGenerics(type)); // np. ResponseEntity<List<X>> -> List<X>
        }

        // 2) Dedykowane mapowanie Page<T> -> obiekt z content[] + metadane
        if (type.startsWith("Page<")) {
            Schema inner = mapType(stripGenerics(type));
            ArraySchema content = new ArraySchema();
            content.setItems(inner);

            ObjectSchema page = new ObjectSchema();
            Map<String, Schema> props = new LinkedHashMap<>();
            props.put("content", content);
            props.put("page", new IntegerSchema());
            props.put("size", new IntegerSchema());
            props.put("totalElements", new IntegerSchema()); // możesz użyć IntegerSchema/NumberSchema
            props.put("totalPages", new IntegerSchema());
            props.put("last", new BooleanSchema());
            page.setProperties(props);
            page.setName("Page«" + simpleName(stripGenerics(type)) + "»");
            return page;
        }
        if (type.endsWith("[]")) {
            ArraySchema arr = new ArraySchema();
            arr.setItems(mapType(type.substring(0, type.length() - 2)));
            return arr;
        }
        if (type.startsWith("List<") || type.startsWith("Set<")) {
            ArraySchema arr = new ArraySchema();
            arr.setItems(mapType(stripGenerics(type)));
            return arr;
        }
        if (type.startsWith("Map<")) {
            String[] kv = splitMapKV(type); // [K,V]
            MapSchema ms = new MapSchema();
            ms.setAdditionalProperties(mapType(kv[1]));
            return ms;
        }
        if (PRIMITIVES.contains(type)) return primitiveToSchema(type);

        String simple = simpleName(type);
        if (BUILTINS.contains(simple)) return builtinToSchema(simple);

        return new Schema().$ref("#/components/schemas/" + simple);
    }

    private static Schema primitiveToSchema(String p) {
        switch (p) {
            case "byte":
            case "short":
            case "int":
            case "long": return new IntegerSchema();
            case "float":
            case "double": return new NumberSchema();
            case "boolean": return new BooleanSchema();
            case "char": return new StringSchema();
            default: return new ObjectSchema();
        }
    }

    private static Schema builtinToSchema(String s) {
        switch (s) {
            case "String": return new StringSchema();
            case "Integer":
            case "Long":   return new IntegerSchema();
            case "Float":
            case "Double":
            case "BigDecimal": return new NumberSchema();
            case "Boolean": return new BooleanSchema();
            case "UUID": return new StringSchema().format("uuid");
            case "LocalDate": return new StringSchema().format("date");
            case "LocalDateTime":
            case "OffsetDateTime":
            case "Date": return new StringSchema().format("date-time");
            case "Object": default: return new ObjectSchema();
        }
    }

    private static String simpleName(String qname) {
        String s = qname;
        int gen = s.indexOf('<');
        if (gen >= 0) s = s.substring(0, gen);
        int dot = s.lastIndexOf('.');
        return (dot >= 0) ? s.substring(dot + 1) : s;
    }
    private static String stripGenerics(String g) {
        int lt = g.indexOf('<');
        int gt = g.lastIndexOf('>');
        if (lt >= 0 && gt > lt) return g.substring(lt + 1, gt).trim();
        return g;
    }
    private static String[] splitMapKV(String g) {
        String inner = stripGenerics(g);
        int depth = 0, commaPos = -1;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) { commaPos = i; break; }
        }
        if (commaPos < 0) return new String[]{"String","Object"};
        String k = inner.substring(0, commaPos).trim();
        String v = inner.substring(commaPos + 1).trim();
        return new String[]{k, v};
    }
}
