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

import com.github.javaparser.ast.comments.*;
import com.github.javaparser.ast.expr.*;

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
                    ep.javadoc = (desc != null) ? desc.toText().trim() : "";
                    ep.description = ep.javadoc; // główny opis = Javadoc
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

                // --- LEADING COMMENTS (tuż nad metodą) ---
                m.getComment().ifPresent(c -> {
                    String txt = normalizeComment(c.getContent());
                    if (!txt.isBlank()) ep.leadingComments.add(txt);
                });

                // Fallback opisu: jeśli brak Javadoc → użyj wyczyszczonego leading
                if ((ep.description == null || ep.description.isBlank()) && !ep.leadingComments.isEmpty()) {
                    List<String> cleanedLeading = new ArrayList<>();
                    for (String s : ep.leadingComments) cleanedLeading.add(stripTechnicalMarkers(s));
                    ep.description = String.join(" ", limitAndClean(cleanedLeading, 1, 300));
                }

                // --- INLINE COMMENTS (wewnątrz metody) + TODO/FIXME ---
                List<Comment> all = m.getAllContainedComments();
                for (Comment c : all) {
                    String raw = normalizeComment(c.getContent());
                    if (raw.isBlank()) continue;
                    String upper = raw.toUpperCase(Locale.ROOT);
                    if (upper.contains("TODO") || upper.contains("FIXME") || upper.contains("HACK")) {
                        ep.todos.add(extractTodo(raw));
                    } else {
                        ep.inlineComments.add(raw);
                    }
                }

                // Zbuduj krótkie notatki (łączymy leading + inline, bez Javadoc i bez TODO)
                List<String> combined = new ArrayList<>();

                for (String s : ep.leadingComments) {
                    if (looksLikeJavadocChunk(s)) continue;
                    if (equalsIgnoreCaseTrim(s, ep.javadoc)) continue;
                    combined.add(stripTechnicalMarkers(s));
                }
                for (String s : ep.inlineComments) {
                    if (looksLikeJavadocChunk(s)) continue;
                    combined.add(stripTechnicalMarkers(s));
                }

                combined = dedupe(combined);
                ep.notes = limitAndClean(combined, 10, 180);

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

                    String fromJavadoc = paramDocs.get(pr.name);
                    if (fromJavadoc != null && !fromJavadoc.isBlank()) {
                        pr.description = fromJavadoc;
                        pr.descriptionFromJavadoc = true;
                    } else {
                        pr.description = defaultParamDoc(pr.name);
                        pr.descriptionFromJavadoc = false;
                    }
                    ep.params.add(pr);
                });

                // --- Typ zwracany (ZNORMALIZOWANY) ---
                ReturnIR r = new ReturnIR();

                String rawReturn = m.getType().asString();
                String core = normalizeReturnType(rawReturn);

                // Jeśli w Javadoc brak @return, ustaw sensowny opis (No Content dla void)
                String retText = returnDocRef.get();
                if (retText == null || retText.isBlank()) {
                    retText = "void".equals(core) ? "No Content." : "Zwraca odpowiedź.";
                }

                r.type = core;
                r.description = retText;
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
                String path = extractMappingPath(a);
                return new HttpAnn(http, path);
            }
        }
        var req = anns.stream().filter(a -> a.getNameAsString().equals("RequestMapping")).findFirst();
        if (req.isPresent()) {
            String path = extractMappingPath(req.get());
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
                String p = extractMappingPath(a);
                return p == null ? "" : p;
            }
        }
        return "";
    }

    /** Wyciąga path/value z adnotacji mappingu (SingleMember/NormalAnnotation). */
    private static String extractMappingPath(AnnotationExpr a) {
        if (a.isSingleMemberAnnotationExpr()) {
            var v = a.asSingleMemberAnnotationExpr().getMemberValue();
            return firstStringLiteral(v, "");
        }
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
        return "";
    }

    /** Pierwszy string z wyrażenia: literal lub pierwszy literal z tablicy. */
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

    // ===== Normalizacja komentarzy / TODO / limity =====

    private static String normalizeComment(String s) {
        if (s == null) return "";
        String x = s.replace("\r", " ").replace("\n", " ").trim();
        x = x.replaceAll("^\\s*\\*+\\s*", "");           // leading '*' z bloków
        x = x.replaceAll("\\s+", " ");                   // wielokrotne spacje
        // wytnij markery komentarzy
        x = x.replaceAll("^/*+\\s*", "").replaceAll("\\s*\\*+/$", "").replaceAll("^//\\s*", "");
        return x.trim();
    }

    private static boolean looksLikeJavadocChunk(String s) {
        if (s == null) return false;
        String t = s.toLowerCase(Locale.ROOT);
        return t.contains("@param") || t.contains("@return") || t.contains("@throws");
    }

    private static boolean equalsIgnoreCaseTrim(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.trim().equalsIgnoreCase(b.trim());
    }

    private static String stripTechnicalMarkers(String s) {
        if (s == null) return "";
        String t = s.trim();
        t = t.replaceAll(
            "(?i)^(INLINE(-BLOCK)?|LINIA|LEADING(-BLOCK)?|VALIDATION|VALIDATE|CHECK|PERMS|LOOKUP|STOCK|CONFLICT)\\s*:\\s*",
            ""
        );
        return t.trim();
    }

    /** Skróć listę do maxN elementów, każdy do maxLen znaków. */
    private static List<String> limitAndClean(List<String> src, int maxN, int maxLen) {
        List<String> out = new ArrayList<>();
        for (String s : src) {
            if (s == null) continue;
            String t = s.strip();
            if (t.isBlank()) continue;
            if (t.length() > maxLen) t = t.substring(0, maxLen).trim() + "…";
            out.add(t);
            if (out.size() >= maxN) break;
        }
        return out;
    }

    private static String extractTodo(String s) {
        String t = s.strip();
        t = t.replaceAll("(?i)^(//|/\\*+|\\*+/?)+\\s*", "");
        t = t.replaceAll("(?i)\\b(TODO|FIXME|HACK)\\b\\s*:?\\s*", "");
        return t.isBlank() ? "do zrobienia" : t;
    }

    private static List<String> dedupe(List<String> in) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String s : in) {
            String t = (s == null) ? "" : s.trim();
            if (!t.isBlank()) set.add(t);
        }
        return new ArrayList<>(set);
    }

    // ===== NOWE: normalizacja typu zwracanego (zdejmowanie wrapperów) =====

    private static String normalizeReturnType(String rawType) {
        if (rawType == null || rawType.trim().isEmpty()) return "void";
        String t = rawType.trim();

        // ResponseEntity<Void> → void
        if (t.startsWith("ResponseEntity<")) {
            String inner = stripGenerics(t);
            if ("Void".equals(inner) || "void".equalsIgnoreCase(inner)) {
                return "void";
            }
            // ResponseEntity<T> → T
            t = inner;
        }

        // typowe asynch./reaktywne wrappery
        t = unwrapKnownWrapper(t, "Optional");
        t = unwrapKnownWrapper(t, "CompletableFuture");
        t = unwrapKnownWrapper(t, "Mono");
        t = unwrapKnownWrapper(t, "Flux");
        t = unwrapKnownWrapper(t, "Callable");
        t = unwrapKnownWrapper(t, "DeferredResult");

        // nic nie zwraca
        if ("void".equals(t) || "Void".equals(t)) return "void";

        // zostaw dokładny zapis kolekcji/tabl. (np. List<X>, Set<Y>, Z[])
        return t;
    }

    private static String unwrapKnownWrapper(String type, String wrapperSimpleName) {
        String prefix = wrapperSimpleName + "<";
        if (type.startsWith(prefix) && type.endsWith(">")) {
            return stripGenerics(type);
        }
        return type;
    }

    private static String stripGenerics(String g) {
        int lt = g.indexOf('<');
        int gt = g.lastIndexOf('>');
        if (lt >= 0 && gt > lt) return g.substring(lt + 1, gt).trim();
        return g;
    }
}
