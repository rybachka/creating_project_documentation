package com.mariia.javaapi.code;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Parser konfiguracji Spring Security.
 * Cel:
 *  - Przeczytać realny kod SecurityFilterChain / WebSecurityConfigurerAdapter,
 *  - Wyciągnąć:
 *      * mechanizm uwierzytelniania (JWT bearer / basic / session / brak),
 *      * reguły autoryzacji: requestMatchers(...).permitAll()/authenticated()/hasRole(...),
 *      * anyRequest().authenticated() jako globalny fallback.
 */
public class JavaSecurityParser {

    private final JavaParser parser = new JavaParser();

    // === MODELE ===
    public enum AuthMechanism {
        NONE,
        BEARER_JWT,
        BASIC,
        SESSION,
        OTHER
    }

    public record SecurityRule(
            String httpMethod,      // GET/POST/... lub null, jeśli nie określono
            String pattern,         // np. "/api/auth/**", "/api/books/**", "/**"
            String ruleType,        // "permitAll", "authenticated", "hasRole", "hasAnyRole", "hasAuthority", "hasAnyAuthority"
            List<String> roles      // np. ["USER"], ["USER","ADMIN"] albo []
    ) {}

    public record SecurityModel(
            List<SecurityRule> rules,
            AuthMechanism authMechanism
    ) {}

    // ===== API PUBLICZNE =====
    //Przeskanuj projekt i zwróć model security:
    // - listę reguł autoryzacji (permitAll/authenticated/hasRole...),
    //- wykryty mechanizm uwierzytelniania (JWT/bearer, basic, session...).
    public SecurityModel parseSecurity(Path projectDir) throws IOException {
        if (projectDir == null || !Files.exists(projectDir)) {
            System.err.println("[SEC] Brak katalogu projektu: " + projectDir);
            return new SecurityModel(List.of(), AuthMechanism.NONE);
        }

        List<SecurityRule> allRules = new ArrayList<>();
        AuthMechanism detectedMechanism = AuthMechanism.NONE;

        List<Path> javaFiles;
        try (Stream<Path> stream = Files.walk(projectDir)) {
            javaFiles = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }

        for (Path file : javaFiles) {
            try {
                ParseResult<CompilationUnit> result = parser.parse(file);
                if (!result.isSuccessful() || result.getResult().isEmpty()) continue;

                CompilationUnit cu = result.getResult().get();

                // Szukamy metod zwracających SecurityFilterChain
                for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
                    String ret = m.getType().asString();
                    if (!ret.contains("SecurityFilterChain")) continue;

                    // Mechanizm auth na podstawie ciała metody
                    AuthMechanism mech = detectAuthMechanism(m);
                    detectedMechanism = pickStronger(detectedMechanism, mech);

                    // Reguły requestMatchers(...).permitAll()/hasRole(...)
                    allRules.addAll(extractRulesFromMethod(m));
                }

            } catch (Exception e) {
                System.err.println("[SEC] Błąd parsowania pliku: " + file + " -> " + e.getMessage());
            }
        }

        System.out.println("[SEC] rules=" + allRules.size() + ", mechanism=" + detectedMechanism);
        return new SecurityModel(Collections.unmodifiableList(allRules), detectedMechanism);
    }

    // ===== WYKRYWANIE MECHANIZMU AUTH =====
    private AuthMechanism detectAuthMechanism(MethodDeclaration m) {
        if (m.getBody().isEmpty()) {
            return AuthMechanism.NONE;
        }
        String body = m.getBody().get().toString();

        // Priorytet: JWT > BASIC > SESSION > OTHER

        // 1) Klasyczny Spring Security: oauth2ResourceServer().jwt()
        if (body.contains(".oauth2ResourceServer") && body.contains(".jwt(")) {
            return AuthMechanism.BEARER_JWT;
        }

        // 2) Własny filtr JWT – heurystyki pod typowe nazwy klas/beanów
        if (body.contains("JwtAuthenticationFilter")
                || body.contains("jwtAuthenticationFilter")
                || body.contains("JwtTokenFilter")
                || body.contains("jwtTokenFilter")
                || body.contains("JwtUtil")
                || body.contains("jwtUtil")) {
            return AuthMechanism.BEARER_JWT;
        }

        // 3) HTTP Basic
        if (body.contains(".httpBasic(")) {
            return AuthMechanism.BASIC;
        }

        // 4) Sesja / form login
        if (body.contains(".formLogin(") || body.contains(".sessionManagement(")) {
            return AuthMechanism.SESSION;
        }

        // 5) Inne custom filtry – coś nietypowego
        if (body.contains(".addFilter") || body.contains(".addFilterBefore") || body.contains(".addFilterAfter")) {
            return AuthMechanism.OTHER;
        }

        return AuthMechanism.NONE;
    }


    private AuthMechanism pickStronger(AuthMechanism current, AuthMechanism candidate) {
        if (candidate == null || candidate == AuthMechanism.NONE) return current;
        if (current == AuthMechanism.NONE) return candidate;
        // Prosta heurystyka: jeśli którykolwiek to BEARER_JWT → wygrywa
        if (candidate == AuthMechanism.BEARER_JWT) return AuthMechanism.BEARER_JWT;
        if (current == AuthMechanism.BEARER_JWT) return AuthMechanism.BEARER_JWT;

        // BASIC > SESSION > OTHER > NONE
        if (candidate == AuthMechanism.BASIC && current != AuthMechanism.BASIC) return AuthMechanism.BASIC;
        if (candidate == AuthMechanism.SESSION && current == AuthMechanism.OTHER) return AuthMechanism.SESSION;

        return current;
    }

    // ===== WYCIĄGANIE REGUŁ authorizeHttpRequests(...) =====
    private static final Set<String> RULE_METHOD_NAMES = Set.of(
            "permitAll",
            "authenticated",
            "hasRole",
            "hasAnyRole",
            "hasAuthority",
            "hasAnyAuthority"
    );

    private List<SecurityRule> extractRulesFromMethod(MethodDeclaration m) {
        List<SecurityRule> rules = new ArrayList<>();
        if (m.getBody().isEmpty()) return rules;

        // Szukamy wywołań permitAll/authenticated/hasRole/...
        for (MethodCallExpr mc : m.findAll(MethodCallExpr.class)) {
            String name = mc.getNameAsString();
            if (!RULE_METHOD_NAMES.contains(name)) continue;

            String ruleType = name;
            List<String> roles = extractRolesFromRuleCall(mc);

            // Scenariusz 1: requestMatchers(...).<rule>()
            mc.getScope().ifPresent(scope -> {
                if (scope.isMethodCallExpr()) {
                    MethodCallExpr rm = scope.asMethodCallExpr();
                    String rmName = rm.getNameAsString();
                    if (rmName.equals("requestMatchers") || rmName.equals("antMatchers") || rmName.equals("mvcMatchers")) {
                        rules.addAll(buildRulesFromRequestMatchers(rm, ruleType, roles));
                    } else if (rmName.equals("anyRequest")) {
                        // anyRequest().authenticated()/permitAll()/hasRole(...)
                        rules.add(new SecurityRule(
                                null,      // wszystkie metody
                                "/**",     // globalny fallback
                                ruleType,
                                roles
                        ));
                    }
                }
            });
        }

        return rules;
    }

    private List<String> extractRolesFromRuleCall(MethodCallExpr mc) {
        List<String> roles = new ArrayList<>();
        String name = mc.getNameAsString();

        if (name.equals("hasRole") || name.equals("hasAuthority")) {
            mc.getArguments().forEach(arg -> {
                if (arg.isStringLiteralExpr()) {
                    roles.add(arg.asStringLiteralExpr().asString());
                }
            });
        } else if (name.equals("hasAnyRole") || name.equals("hasAnyAuthority")) {
            mc.getArguments().forEach(arg -> {
                if (arg.isStringLiteralExpr()) {
                    roles.add(arg.asStringLiteralExpr().asString());
                }
            });
        }
        // authenticated / permitAll → roles=[]
        return roles;
    }

    private List<SecurityRule> buildRulesFromRequestMatchers(MethodCallExpr rm,
                                                             String ruleType,
                                                             List<String> roles) {
        List<SecurityRule> rules = new ArrayList<>();
        List<Expression> args = rm.getArguments();

        String httpMethod = null;
        List<String> patterns = new ArrayList<>();

        if (args.isEmpty()) {
            return rules;
        }

        // Przypadki:
        // requestMatchers("/api/auth/**", "/docs/**")
        // requestMatchers(HttpMethod.GET, "/api/books/**")
        // requestMatchers(HttpMethod.POST, "/api/books/**", "/api/other/**")
        int idx = 0;

        // 1) Jeśli pierwszy argument jest HttpMethod.XYZ -> metoda
        Expression first = args.get(0);
        if (first.isFieldAccessExpr()) {
            FieldAccessExpr fa = first.asFieldAccessExpr();
            // np. HttpMethod.GET -> "GET"
            httpMethod = fa.getNameAsString().toUpperCase(Locale.ROOT);
            idx = 1; // reszta argumentów to ścieżki
        } else if (first.isNameExpr()) {
            // fallback np. GET bez kwalifikacji – rzadkie, ale na wszelki
            NameExpr ne = first.asNameExpr();
            if (ne.getNameAsString().equalsIgnoreCase("GET")
                    || ne.getNameAsString().equalsIgnoreCase("POST")
                    || ne.getNameAsString().equalsIgnoreCase("PUT")
                    || ne.getNameAsString().equalsIgnoreCase("DELETE")
                    || ne.getNameAsString().equalsIgnoreCase("PATCH")) {
                httpMethod = ne.getNameAsString().toUpperCase(Locale.ROOT);
                idx = 1;
            }
        }

        // 2) Reszta stringów traktujemy jako wzorce ścieżek
        for (int i = idx; i < args.size(); i++) {
            Expression e = args.get(i);
            if (e.isStringLiteralExpr()) {
                patterns.add(e.asStringLiteralExpr().asString());
            }
        }

        if (patterns.isEmpty()) {
            // jeśli nie znaleziono żadnej ścieżki – nic nie tworzymy
            return rules;
        }

        for (String p : patterns) {
            rules.add(new SecurityRule(
                    httpMethod,
                    p,
                    ruleType,
                    List.copyOf(roles)
            ));
        }

        return rules;
    }

    // ===== UTILS =====
    private static String nz(String s) {
        return (s == null) ? "" : s;
    }
}
