package com.mariia.javaapi.code;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.type.Type;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Parser DTO – buduje mapę components.schemas z kodu projektu.
 * - Klasy i rekordy: ObjectSchema z właściwościami.
 * - Enumy: StringSchema z enum values.
 * - Typy generyczne, tablice i mapy: rozwinięte do poprawnych schematów (bez „gołych” array/map).
 * - Własności „required” na podstawie adnotacji walidacyjnych i @JsonProperty(required=true).
 * - Nazwy właściwości z @JsonProperty("..."), inaczej nazwa pola/parametru.
 */
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
        try (java.util.stream.Stream<Path> stream = Files.walk(projectDir)) {
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
            if (!result.isSuccessful() || !result.getResult().isPresent()) return;

            CompilationUnit cu = result.getResult().get();

            // === Enumy ===
            for (EnumDeclaration en : cu.findAll(EnumDeclaration.class)) {
                String enumName = en.getNameAsString();
                if (sink.containsKey(enumName)) continue;

                StringSchema s = new StringSchema();
                List<String> values = en.getEntries().stream()
                        .map(e -> e.getNameAsString())
                        .collect(Collectors.toList());
                s.setEnum(values);
                s.setName(enumName);
                sink.put(enumName, s);
            }

            // === Rekordy Java ===
            for (RecordDeclaration rec : cu.findAll(RecordDeclaration.class)) {
                String className = rec.getNameAsString();
                if (sink.containsKey(className)) continue;

                ObjectSchema schema = new ObjectSchema();
                schema.setName(className);

                Map<String, Schema> props = new LinkedHashMap<>();
                List<String> required = new ArrayList<>();

                rec.getParameters().forEach(p -> {
                    String propName = extractJsonName(p.getAnnotations(), p.getNameAsString());
                    Schema propSchema = mapType(p.getType().asString());
                    props.put(propName, propSchema);

                    if (isRequired(p.getAnnotations())) {
                        required.add(propName);
                    }
                });

                if (!props.isEmpty()) schema.setProperties(props);
                if (!required.isEmpty()) schema.setRequired(required);
                sink.put(className, schema);
            }

            // === Klasy DTO (pomijamy interfejsy i kontrolery) ===
            for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (clazz.isInterface()) continue;
                if (isController(clazz)) continue;

                String className = clazz.getNameAsString();
                if (sink.containsKey(className)) continue;

                // Jeżeli klasa nie ma żadnych pól instancyjnych – i tak zarejestruj „pusty” obiekt
                ObjectSchema schema = new ObjectSchema();
                schema.setName(className);

                Map<String, Schema> props = new LinkedHashMap<>();
                List<String> required = new ArrayList<>();

                for (FieldDeclaration field : clazz.getFields()) {
                    // pomijamy static / transient
                    if (field.hasModifier(Modifier.Keyword.STATIC) || field.hasModifier(Modifier.Keyword.TRANSIENT)) {
                        continue;
                    }
                    String rawType = field.getElementType().asString();
                    Schema propSchema = mapType(rawType);

                    // wsparcie @JsonProperty("...") i required na polu
                    boolean fieldRequired = isRequired(field.getAnnotations());
                    Optional<String> overriddenName = extractJsonNameOpt(field.getAnnotations());

                    field.getVariables().forEach(v -> {
                        String defaultName = v.getNameAsString();
                        String propName = overriddenName.orElse(defaultName);
                        props.put(propName, propSchema);
                        if (fieldRequired) required.add(propName);
                    });
                }

                if (!props.isEmpty()) schema.setProperties(props);
                if (!required.isEmpty()) schema.setRequired(required);

                sink.put(className, schema);
            }

        } catch (Exception e) {
            System.err.println("Błąd parsowania DTO: " + file + " -> " + e.getMessage());
        }
    }

    // ===== mapowanie typów (spójne i odporne na „gołe” array/map) =====

    private static final Set<String> PRIMITIVES = new HashSet<String>(Arrays.asList(
            "byte","short","int","long","float","double","boolean","char"
    ));

    private static final Set<String> BUILTINS = new HashSet<String>(Arrays.asList(
            "String","Integer","Long","Float","Double","BigDecimal",
            "Boolean","UUID","Object","Date","LocalDate","LocalDateTime","OffsetDateTime","Instant"
    ));

    private Schema mapType(String t) {
        if (t == null || t.trim().isEmpty()) return new ObjectSchema();
        String type = t.trim();

        // 1) Zdejmij najczęstsze wrappery generyczne
        if (startsWithAny(type, "ResponseEntity<", "Optional<", "CompletableFuture<", "Mono<", "Flux<")) {
            return mapType(stripGenerics(type)); // np. ResponseEntity<List<X>> -> List<X>
        }

        // 2) Page<T> -> obiekt z content[] + meta
        if (type.startsWith("Page<")) {
            Schema inner = mapType(stripGenerics(type));
            ArraySchema content = new ArraySchema();
            content.setItems(ensureNonNull(inner));

            ObjectSchema page = new ObjectSchema();
            Map<String, Schema> props = new LinkedHashMap<>();
            props.put("content", content);
            props.put("page", new IntegerSchema());
            props.put("size", new IntegerSchema());
            props.put("totalElements", new IntegerSchema());
            props.put("totalPages", new IntegerSchema());
            props.put("last", new BooleanSchema());
            page.setProperties(props);
            page.setName("Page«" + simpleName(stripGenerics(type)) + "»");
            return page;
        }

        // 3) Tablice i kolekcje
        if (type.endsWith("[]")) {
            ArraySchema arr = new ArraySchema();
            Schema items = mapType(type.substring(0, type.length() - 2));
            arr.setItems(ensureNonNull(items));
            return arr;
        }
        if (type.startsWith("List<") || type.startsWith("Set<") || type.startsWith("Collection<")) {
            ArraySchema arr = new ArraySchema();
            Schema items = mapType(stripGenerics(type));
            arr.setItems(ensureNonNull(items));
            return arr;
        }

        // 4) Mapy
        if (type.startsWith("Map<")) {
            String[] kv = splitMapKV(type); // [K,V]
            MapSchema ms = new MapSchema();
            Schema valueSchema = mapType(kv[1]);
            ms.setAdditionalProperties(ensureNonNull(valueSchema)); // nigdy Boolean.TRUE
            return ms;
        }

        // 5) Prymitywy i wbudowane
        if (PRIMITIVES.contains(type)) return primitiveToSchema(type);

        String simple = simpleName(type);
        if (BUILTINS.contains(simple)) return builtinToSchema(simple);

        // 6) Własne DTO – zwróć $ref do components.schemas
        Schema ref = new Schema();
        ref.$ref("#/components/schemas/" + simple);
        return ref;
    }

    private static Schema primitiveToSchema(String p) {
        if ("boolean".equals(p)) return new BooleanSchema();
        if ("char".equals(p)) return new StringSchema();
        if ("float".equals(p) || "double".equals(p)) return new NumberSchema();
        // byte/short/int/long
        return new IntegerSchema();
    }

    private static Schema builtinToSchema(String s) {
        if ("String".equals(s)) return new StringSchema();
        if ("Integer".equals(s) || "Long".equals(s)) return new IntegerSchema();
        if ("Float".equals(s) || "Double".equals(s) || "BigDecimal".equals(s)) return new NumberSchema();
        if ("Boolean".equals(s)) return new BooleanSchema();
        if ("UUID".equals(s)) return new StringSchema().format("uuid");
        if ("LocalDate".equals(s)) return new StringSchema().format("date");
        if ("LocalDateTime".equals(s) || "OffsetDateTime".equals(s) || "Date".equals(s) || "Instant".equals(s))
            return new StringSchema().format("date-time");
        // Object i reszta
        return new ObjectSchema();
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

    /** Rozdziela "Map<K,V>" na ["K","V"] z uwzględnieniem zagnieżdżeń generycznych. */
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

    // ===== pomocnicze: required i nazwy JSON =====

    private static boolean startsWithAny(String s, String... prefixes) {
        for (String p : prefixes) if (s.startsWith(p)) return true;
        return false;
    }

    /** Jeżeli schema jest null (np. błąd mapowania), zwracamy ObjectSchema, aby nigdy nie powstał „goły” element. */
    private static Schema ensureNonNull(Schema s) {
        return (s == null) ? new ObjectSchema() : s;
    }

    /** Czy pole/parametr powinien być „required” (na podstawie adnotacji walidacyjnych / JsonProperty.required). */
    private static boolean isRequired(List<AnnotationExpr> annotations) {
        if (annotations == null) return false;
        for (AnnotationExpr a : annotations) {
            String n = a.getNameAsString();
            if ("NotNull".equals(n) || "NonNull".equals(n) || "NotBlank".equals(n) || "NotEmpty".equals(n)) {
                return true;
            }
            if ("JsonProperty".equals(n)) {
                // szukamy pary required=true
                if (a.isNormalAnnotationExpr()) {
                    for (MemberValuePair p : a.asNormalAnnotationExpr().getPairs()) {
                        if ("required".equals(p.getNameAsString()) && "true".equalsIgnoreCase(p.getValue().toString())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Wyciągnij nazwę JSON z @JsonProperty("..."), jeśli jest; inaczej zwróć podaną defaultName. */
    private static String extractJsonName(List<AnnotationExpr> annotations, String defaultName) {
        if (annotations != null) {
            for (AnnotationExpr a : annotations) {
                if ("JsonProperty".equals(a.getNameAsString())) {
                    if (a.isSingleMemberAnnotationExpr()) {
                        String raw = a.asSingleMemberAnnotationExpr().getMemberValue().toString();
                        return trimQuotes(raw);
                    }
                    if (a.isNormalAnnotationExpr()) {
                        for (MemberValuePair p : a.asNormalAnnotationExpr().getPairs()) {
                            if ("value".equals(p.getNameAsString())) {
                                return trimQuotes(p.getValue().toString());
                            }
                        }
                    }
                }
            }
        }
        return defaultName;
    }

    private static Optional<String> extractJsonNameOpt(List<AnnotationExpr> annotations) {
        if (annotations == null) return Optional.empty();
        for (AnnotationExpr a : annotations) {
            if ("JsonProperty".equals(a.getNameAsString())) {
                if (a.isSingleMemberAnnotationExpr()) {
                    return Optional.of(trimQuotes(a.asSingleMemberAnnotationExpr().getMemberValue().toString()));
                }
                if (a.isNormalAnnotationExpr()) {
                    for (MemberValuePair p : a.asNormalAnnotationExpr().getPairs()) {
                        if ("value".equals(p.getNameAsString())) {
                            return Optional.of(trimQuotes(p.getValue().toString()));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static String trimQuotes(String s) {
        String x = s.trim();
        if ((x.startsWith("\"") && x.endsWith("\"")) || (x.startsWith("'") && x.endsWith("'"))) {
            return x.substring(1, x.length() - 1);
        }
        return x;
    }

    /** Proste wykrycie klas kontrolerów po adnotacjach. */
    private static boolean isController(ClassOrInterfaceDeclaration c) {
        for (AnnotationExpr a : c.getAnnotations()) {
            String n = a.getNameAsString();
            if ("RestController".equals(n) || "Controller".equals(n)) return true;
        }
        return false;
    }
}
