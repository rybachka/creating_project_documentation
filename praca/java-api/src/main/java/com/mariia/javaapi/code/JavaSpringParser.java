package com.mariia.javaapi.code;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.javadoc.JavadocBlockTag;
import com.mariia.javaapi.code.ir.EndpointIR;
import com.mariia.javaapi.code.ir.ParamIR;
import com.mariia.javaapi.code.ir.ReturnIR;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.github.javaparser.ast.expr.*;  // ważne: używamy AST do czytania adnotacji

public class JavaSpringParser {

    private final JavaParser parser = new JavaParser();

    /** Przeskanuj projekt i zwróć listę endpointów z kodu. */
    public List<EndpointIR> parseProject(Path projectDir) throws IOException {
        List<EndpointIR> endpoints = new ArrayList<>();
        try (var stream = Files.walk(projectDir)) {
            for (Path file : stream.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList())) {
                parseFile(file, endpoints);
            }
        }
        return endpoints;
    }

    private void parseFile(Path file, List<EndpointIR> sink) {
        try {
            ParseResult<CompilationUnit> result = parser.parse(file);
            if (!result.isSuccessful() || result.getResult().isEmpty()) return;

            CompilationUnit cu = result.getResult().get();

            Optional<ClassOrInterfaceDeclaration> optClass =
                cu.findFirst(ClassOrInterfaceDeclaration.class,
                    c -> hasAnyAnnotation(c, "RestController", "Controller"));
            if (optClass.isEmpty()) return;

            ClassOrInterfaceDeclaration clazz = optClass.get();
            String classBase = getRequestMappingPath(clazz.getAnnotations());

            for (MethodDeclaration m : clazz.getMethods()) {
                HttpAnn httpAnn = pickHttpAnnotation(m);
                if (httpAnn == null) continue;

                EndpointIR ep = new EndpointIR();
                ep.http = httpAnn.http;
                ep.path = joinPaths(classBase, httpAnn.path);
                ep.operationId = clazz.getNameAsString() + "_" + m.getNameAsString();

                // --- Javadoc ---
                Map<String, String> paramDocs = new HashMap<>();
                AtomicReference<String> returnDocRef = new AtomicReference<>("");

                m.getJavadoc().ifPresent(jd -> {
                    var desc = jd.getDescription();
                    ep.description = (desc != null) ? desc.toText().trim() : "";
                    for (JavadocBlockTag tag : jd.getBlockTags()) {
                        JavadocBlockTag.Type type = tag.getType();
                        if (type == JavadocBlockTag.Type.PARAM) {
                            String name = tag.getName().orElse("").trim();
                            String text = tag.getContent().toText().trim();
                            if (!name.isEmpty() && !text.isEmpty()) paramDocs.put(name, text);
                        } else if (type == JavadocBlockTag.Type.RETURN) {
                            returnDocRef.set(tag.getContent().toText().trim());
                        }
                    }
                });

                // --- Parametry ---
                m.getParameters().forEach(p -> {
                    ParamIR pr = new ParamIR();
                    pr.name = p.getNameAsString();
                    boolean isPath = hasAnyAnnotation(p, "PathVariable");
                    boolean isReq  = hasAnyAnnotation(p, "RequestParam");
                    boolean isBody = hasAnyAnnotation(p, "RequestBody");
                    pr.in = isPath ? "path" : (isReq ? "query" : (isBody ? "body" : "query"));
                    pr.required = isPath || isBody;
                    pr.type = p.getType().asString();
                    pr.description = paramDocs.getOrDefault(pr.name, defaultParamDoc(pr.name));
                    ep.params.add(pr);
                });

                // --- Typ zwracany ---
                ReturnIR r = new ReturnIR();
                r.type = m.getType().asString();
                String retText = returnDocRef.get();
                r.description = (retText != null && !retText.isBlank()) ? retText : "Zwraca odpowiedź.";
                ep.returns = r;

                sink.add(ep);
            }

        } catch (Exception e) {
            System.err.println("Błąd parsowania pliku: " + file + " -> " + e.getMessage());
        }
    }

    // ===== Pomocnicze metody =====

    private static String defaultParamDoc(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "id" -> "Identyfikator zasobu.";
            case "page", "limit", "size" -> "Parametr paginacji.";
            default -> "Parametr " + name + ".";
        };
    }

    private static record HttpAnn(String http, String path) {}

    private static HttpAnn pickHttpAnnotation(MethodDeclaration m) {
        var anns = m.getAnnotations();
        for (var a : anns) {
            String n = a.getNameAsString();
            if (List.of("GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping").contains(n)) {
                String http = n.replace("Mapping", "").toUpperCase(Locale.ROOT);
                String path = extractMappingPath(a);  // <-- AST, nie toString()
                return new HttpAnn(http, path);
            }
        }
        var req = anns.stream().filter(a -> a.getNameAsString().equals("RequestMapping")).findFirst();
        if (req.isPresent()) {
            String path = extractMappingPath(req.get()); // <-- AST
            String http = extractRequestMethod(req.get());
            if (http == null) http = "GET";
            return new HttpAnn(http, path);
        }
        return null;
    }

    private static boolean hasAnyAnnotation(NodeWithAnnotations<?> node, String... names) {
        var set = new HashSet<>(Arrays.asList(names));
        return node.getAnnotations().stream().anyMatch(a -> set.contains(a.getNameAsString()));
    }

    /** Baza ścieżki z @RequestMapping na klasie (value/path). */
    private static String getRequestMappingPath(List<AnnotationExpr> anns) {
        for (var a : anns) {
            if (a.getNameAsString().equals("RequestMapping")) {
                String p = extractMappingPath(a);     // <-- AST
                return p == null ? "" : p;
            }
        }
        return "";
    }

    /** Wyciąga path/value z adnotacji mappingu poprzez AST (SingleMember i NormalAnnotation). */
    private static String extractMappingPath(AnnotationExpr a) {
        // @GetMapping("/x") albo @RequestMapping("/x")
        if (a.isSingleMemberAnnotationExpr()) {
            var v = a.asSingleMemberAnnotationExpr().getMemberValue();
            return firstStringLiteral(v, "");
        }
        // @GetMapping(path="/x") albo @RequestMapping(value={"/a","/b"})
        if (a.isNormalAnnotationExpr()) {
            var n = a.asNormalAnnotationExpr();
            for (var p : n.getPairs()) {
                var name = p.getNameAsString();
                if ("value".equals(name) || "path".equals(name)) {
                    return firstStringLiteral(p.getValue(), "");
                }
            }
            return "";
        }
        // Marker bez parametrów – brak ścieżki
        return "";
    }

    /** Pierwszy string z wyrażenia: literal albo pierwszy literal z tablicy. */
    private static String firstStringLiteral(Expression expr, String def) {
        if (expr == null) return def;
        if (expr.isStringLiteralExpr()) {
            return expr.asStringLiteralExpr().asString();
        }
        if (expr.isArrayInitializerExpr()) {
            var arr = expr.asArrayInitializerExpr();
            for (var e : arr.getValues()) {
                if (e.isStringLiteralExpr()) {
                    return e.asStringLiteralExpr().asString();
                }
            }
        }
        return def;
    }

    private static String extractRequestMethod(AnnotationExpr a) {
        // Tu niestety wiele osób trzyma method w stylu: @RequestMapping(method = RequestMethod.GET)
        // JavaParser nie ma wprost enuma Springa – najprościej sprawdzić tekst wartości parametru 'method'
        if (a.isNormalAnnotationExpr()) {
            var n = a.asNormalAnnotationExpr();
            for (var p : n.getPairs()) {
                if ("method".equals(p.getNameAsString())) {
                    String raw = p.getValue().toString();
                    if (raw.contains("RequestMethod.GET"))    return "GET";
                    if (raw.contains("RequestMethod.POST"))   return "POST";
                    if (raw.contains("RequestMethod.PUT"))    return "PUT";
                    if (raw.contains("RequestMethod.DELETE")) return "DELETE";
                    if (raw.contains("RequestMethod.PATCH"))  return "PATCH";
                }
            }
        }
        return null;
    }

    private static String joinPaths(String base, String sub) {
        String A = (base == null) ? "" : base.trim();
        String B = (sub  == null) ? "" : sub.trim();

        if (A.isEmpty() && B.isEmpty()) return "/";

        if (!A.isEmpty() && !A.startsWith("/")) A = "/" + A;
        if (A.endsWith("/")) A = A.substring(0, A.length() - 1);

        if (!B.isEmpty() && !B.startsWith("/")) B = "/" + B;

        String out = (A + B).replaceAll("//+", "/");
        if (out.isEmpty()) out = "/";
        return out;
    }
}
