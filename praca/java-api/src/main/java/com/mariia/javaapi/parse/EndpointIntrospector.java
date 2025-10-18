package com.mariia.javaapi.parse;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Service
public class EndpointIntrospector {

    private static final String OWN_PACKAGE_PREFIX = "com.mariia.";
    private final RequestMappingHandlerMapping mappings;

    @Autowired
    public EndpointIntrospector(RequestMappingHandlerMapping mappings){
        this.mappings=mappings;

    }

    public List<EndpointMeta> listAll(){
        List<EndpointMeta> out = new ArrayList<>();

        Map<RequestMappingInfo, HandlerMethod> handlerMethods =mappings.getHandlerMethods();
        for(var entry: handlerMethods.entrySet()){ //pętla po wszystkich zarejestrowanych endpointach.
            var info=entry.getKey();
            var handler = entry.getValue();
            if (!handler.getBeanType().getName().startsWith(OWN_PACKAGE_PREFIX)) {
                continue;
            }
            //sciezki, zwykle jesdna
            Set<String> paths =
                    (info.getPathPatternsCondition() != null)
                    ? info.getPathPatternsCondition().getPatternValues()
                    : (info.getPatternsCondition() != null
                        ? info.getPatternsCondition().getPatterns()
                        : Set.of());
            if (paths.isEmpty()) continue;


            Set<RequestMethod> methods =
                    (info.getMethodsCondition() != null)
                    ? info.getMethodsCondition().getMethods()
                    : Set.of();

            // jeśli brak metod — przyjmij GET (albo użyj specjalnej wartości "ANY")
            if (methods.isEmpty()) {
                methods = Set.of(RequestMethod.GET);
            }

            // utwórz wpis dla KAŻDEJ ścieżki i metody
            for (String path : paths) {
                for (RequestMethod http : methods) {
                    EndpointMeta em = new EndpointMeta();
                    em.controller = handler.getBeanType().getName();
                    em.methodName = handler.getMethod().getName();
                    em.httpMethod = http.name();
                    em.path = path;
                    em.params = extractParams(handler);
                    em.returns= extractReturn(handler);
                    em.extras= Map.of();
                    out.add(em);
                }
            }
        }
        //sortowanie
        out.sort(Comparator.comparing(e -> e.path + "#" + e.httpMethod));
        return out;
    }
    private List<ParamMeta> extractParams(HandlerMethod handler){
        List<ParamMeta> list = new ArrayList<>();
        for(Parameter p :handler.getMethod().getParameters()){
            ParamMeta pm = new ParamMeta();
            pm.type=p.getType().getName();
            pm.constraints=extractConstraints(p.getAnnotations());

            //rozpoznanie miejsca parametru
            if(p.isAnnotationPresent(PathVariable.class)){
                var ann = p .getAnnotation(PathVariable.class);
                pm.in = "path";
                pm.name=StringUtils.hasText(ann.name())?ann.name():p.getName();
                pm.required = ann.required();
            } else if(p.isAnnotationPresent(RequestParam.class)){
                var ann = p.getAnnotation(RequestParam.class);
                pm.in = "query";
                pm.name = StringUtils.hasText(ann.name())?ann.name():p.getName();
                pm.required=ann.required();

                if (!ValueConstants.DEFAULT_NONE.equals(ann.defaultValue())) {
                    pm.required = false;
                }
            }else if (p.isAnnotationPresent(RequestHeader.class)){
                var ann = p.getAnnotation(RequestHeader.class);
                pm.in="header";
                pm.name=StringUtils.hasText(ann.name())? ann.name() : p.getName();
                pm.required=ann.required();
            }else if(p.isAnnotationPresent(RequestBody.class)){
                pm.in="body";
                pm.name="body";
                pm.required=true;
                pm.constraints.addAll(extractDtoFieldConstraints(p.getType()));

            }else{
                pm.in="unknown";
                pm.name=p.getName();
                pm.required=false;
            }
            list.add(pm);
        }
        return list;
    }

    private ReturnMeta extractReturn(HandlerMethod handler){
        ReturnMeta r= new ReturnMeta();
        var method = handler.getMethod();
        var rt = ResolvableType.forMethodReturnType(method);
        // Jeśli to ResponseEntity<T> → zwróć T, inaczej pełny typ
        var inner = rt.as(org.springframework.http.ResponseEntity.class).getGeneric(0);
        r.type = (inner != ResolvableType.NONE ? inner.toString() : rt.toString());
        return r;
    }
    private List<String> extractConstraints(Annotation[] anns){
        List<String> cs = new ArrayList<>();
        for(Annotation a: anns){
            if(a instanceof NotBlank) cs.add("@NotBlank");
            if(a instanceof NotNull) cs.add("@NotNull");
            if(a instanceof Email) cs.add("@Email");
            if(a instanceof Size s) cs.add("@Size(min=" + s.min() + ",max=" + s.max() + ")");
            if(a instanceof Min m) cs.add("@Min(" + m.value() +")");
            if(a instanceof Max m) cs.add("@Max(" + m.value() +")");
        }
        return cs;
    }
    private List<String> extractDtoFieldConstraints(Class<?> dtoClass) {
        List<String> out = new ArrayList<>();
        // nie wchodzimy w typy JDK
        if (dtoClass.getName().startsWith("java.")) return out;

        for (Field f : dtoClass.getDeclaredFields()) {
            List<String> cs = extractConstraints(f.getAnnotations());
            if (!cs.isEmpty()) {
                // przykład formatu: "body.email: @NotBlank, @Email"
                out.add("body." + f.getName() + ": " + String.join(", ", cs));
            }
        }
        return out;
    }
}
    //# lista endpointów w JSON (to jest „surowy materiał” do NLP)
//curl -s http://localhost:8080/api/tools/endpoints | jq .




    

